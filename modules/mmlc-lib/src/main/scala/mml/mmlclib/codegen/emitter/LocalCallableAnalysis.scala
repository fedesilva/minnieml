package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.collectArgsAndFunction

/** Records uses of one local function binding to decide whether to emit a plain LLVM entry. Calls
  * through aliases count toward the original binding. At least one fully applied direct call makes
  * the binding eligible for the optimization. Proven PAP captures also qualify without inflating
  * these occurrence counts.
  *
  * The analyzer creates this record only for lambdas with no captures.
  *
  * Counts describe occurrences in the final typed module, not runtime invocation counts. An alias
  * declaration alone does not count as a value use.
  *
  * @param lambda
  *   the bound lambda, including its body and parameter ownership annotations
  * @param signature
  *   its resolved parameter and return types, including Unit parameters
  * @param directCalls
  *   fully applied calls whose target is known in the caller's lexical scope
  * @param valueUses
  *   uses requiring a function value, including passing, returning, storing, and capturing it
  */
case class LocalCallableUse(
  lambda:      Lambda,
  signature:   TypeFn,
  directCalls: Int = 0,
  valueUses:   Int = 0
)

/** Stores the use analysis that binding emission needs before choosing a local function's entry. A
  * binding is emitted before its uses, so the emitter cannot make that choice from the binding
  * alone. The analyzer first finds which non-capturing lambdas have fully applied direct calls.
  * Those bindings and proven PAP targets qualify for a plain entry; other value-only bindings keep
  * ordinary closure emission.
  *
  * The analysis identifies bindings by semantic ID. LocalCallableEmitter later allocates LLVM
  * symbols and creates adapters as value uses are emitted; the counts here do not preallocate
  * adapters.
  *
  * @param targets
  *   original lambda binding IDs mapped to their uses, including lambdas with no direct calls
  * @param aliases
  *   alias binding IDs mapped to the original lambda binding ID, with alias chains flattened
  * @param papTargets
  *   bindings whose known entry can replace a callable payload in a PAP environment
  */
case class LocalCallableAnalysis(
  targets:    Map[String, LocalCallableUse] = Map.empty,
  aliases:    Map[String, String]           = Map.empty,
  papTargets: Set[String]                   = Set.empty
):
  /** Finds an original lambda binding eligible for a plain entry. Alias IDs must be resolved first.
    * A proven PAP target also qualifies; other value-only lambdas keep ordinary closure emission.
    */
  def candidate(id: String): Option[LocalCallableUse] =
    targets.get(id).filter(callable => callable.directCalls > 0 || papTargets.contains(id))

object LocalCallableAnalysis:

  /** Finds an unqualified reference through expression wrappers and parentheses. Field access and
    * conditional selection do not establish a simple alias.
    */
  def reference(term: Term): Option[Ref] = term match
    case ref: Ref if ref.qualifier.isEmpty => ref.some
    case Expr(_, List(inner), _, _) => reference(inner)
    case TermGroup(_, inner, _) => reference(inner)
    case _ => none

  /** Analyzes the final typed module after semantic transformations, without changing its AST.
    *
    * Resolved binding IDs keep aliases and shadowed names distinct. A separately emitted lambda
    * sees captured callables as runtime values, so outer direct-call knowledge does not enter its
    * body. Its own recursive binding remains a known target. Capturing a known callable counts as a
    * value use in the enclosing scope.
    */
  def analyze(module: Module, knownTargets: CallableTargetAnalysis): LocalCallableAnalysis =
    type Scope = Map[String, String]

    def target(ref: Ref, scope: Scope): Option[String] =
      ref.resolvedId.filter(_ => ref.qualifier.isEmpty).flatMap(scope.get)

    def use(id: String, direct: Boolean, analysis: LocalCallableAnalysis): LocalCallableAnalysis =
      val current = analysis.targets(id)
      val updated =
        if direct then current.copy(directCalls = current.directCalls + 1)
        else current.copy(valueUses             = current.valueUses + 1)
      analysis.copy(targets = analysis.targets.updated(id, updated))

    def visitLambda(
      lambda:   Lambda,
      scope:    Scope,
      self:     Scope,
      analysis: LocalCallableAnalysis
    ): LocalCallableAnalysis =
      val captures = lambda.captures.foldLeft(analysis) { (current, capture) =>
        target(capture.ref, scope).fold(current)(use(_, false, current))
      }
      // A separately emitted body sees runtime captures, not outer callable targets.
      visit(lambda.body, self, captures)

    def binding(
      param:    FnParam,
      value:    Expr,
      body:     Expr,
      scope:    Scope,
      analysis: LocalCallableAnalysis
    ): LocalCallableAnalysis =
      val candidate = for
        id <- param.id
        lambda <- value.terms match
          case List(lambda: Lambda) if lambda.captures.isEmpty => lambda.some
          case _ => none
        signature <- lambda.typeSpec.flatMap(resolveToTypeFn(_, module.resolvables))
      yield (id, LocalCallableUse(lambda, signature))

      candidate match
        case Some((id, callable)) =>
          val registered = analysis.copy(targets = analysis.targets.updated(id, callable))
          val withBody   = visitLambda(callable.lambda, scope, Map(id -> id), registered)
          visit(body, scope.updated(id, id), withBody)
        case None =>
          val alias = for
            id <- param.id
            ref <- reference(value)
            canonical <- target(ref, scope)
          yield (id, canonical)
          alias match
            case Some((id, canonical)) =>
              // An alias only extends target knowledge; it does not yet require a closure value.
              visit(
                body,
                scope.updated(id, canonical),
                analysis.copy(aliases = analysis.aliases.updated(id, canonical))
              )
            case None => visit(body, scope, visit(value, scope, analysis))

    def visit(term: Term, scope: Scope, analysis: LocalCallableAnalysis): LocalCallableAnalysis =
      term match
        case app: App =>
          val (callee, args) = collectArgsAndFunction(app)
          callee match
            // Let desugaring uses an immediate unary lambda. Its body stays in the caller's scope.
            case lambda: Lambda if lambda.params.size == 1 && args.size == 1 =>
              binding(lambda.params.head, args.head, lambda.body, scope, analysis)
            case ref: Ref =>
              val called = target(ref, scope) match
                case Some(id) =>
                  use(id, args.size == analysis.targets(id).signature.paramTypes.size, analysis)
                case None => ref.qualifier.fold(analysis)(visit(_, scope, analysis))
              args.foldLeft(called)((current, arg) => visit(arg, scope, current))
            case lambda: Lambda =>
              args.foldLeft(visitLambda(lambda, scope, Map.empty, analysis)) { (current, arg) =>
                visit(arg, scope, current)
              }
        case ref: Ref =>
          val used = target(ref, scope).fold(analysis)(use(_, false, analysis))
          ref.qualifier.fold(used)(visit(_, scope, used))
        case lambda: Lambda => visitLambda(lambda, scope, Map.empty, analysis)
        case expr:   Expr => expr.terms.foldLeft(analysis)((current, t) => visit(t, scope, current))
        case group:  TermGroup => visit(group.inner, scope, analysis)
        case cond:   Cond =>
          List(cond.cond, cond.ifTrue, cond.ifFalse)
            .foldLeft(analysis)((current, e) => visit(e, scope, current))
        case destruction: Destruction => visit(destruction.operand, scope, analysis)
        case tuple:       Tuple =>
          tuple.elements.toList.foldLeft(analysis)((current, e) => visit(e, scope, current))
        case _ => analysis

    val lexical = module.members.foldLeft(LocalCallableAnalysis()) {
      case (analysis, bnd: Bnd) => visit(bnd.value, Map.empty, analysis)
      case (analysis, _) => analysis
    }
    val papTargets = module.members
      .collect { case bnd: Bnd => bnd }
      .flatMap(bnd =>
        TermTraversal.collect(bnd.value) {
          case lambda: Lambda if lambda.meta.exists(_.isPartialApplication) => lambda
        }
      )
      .flatMap(_.captures.flatMap(capture => knownTargets.target(capture.ref)))
      .toSet
    lexical.copy(papTargets = papTargets)
