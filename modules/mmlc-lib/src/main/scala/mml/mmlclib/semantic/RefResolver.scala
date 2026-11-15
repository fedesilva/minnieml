package mml.mmlclib.semantic

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.ValueScope
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

object RefResolver:

  /** Resolve all references and retain each failed occurrence alongside its diagnostic roots. */
  def rewriteModule(state: CompilerState): CompilerState =
    val scope = ValueScope.module(state.module)
    val (diagnostics, members) = state.module.members
      .traverse { member =>
        resolveMember(member, Context(member, state.module, state.protocols, scope))
      }
      .run(state.diagnostics)
      .value

    state.copy(diagnostics = diagnostics).withModule(state.module.copy(members = members))

  private val phaseName = "mml.mmlclib.semantic.RefResolver"

  private type Resolution[A] = State[DiagnosticStore, A]

  /** Reference-resolution environment. genericFunctions maps local function binding IDs to their
    * type-parameter counts for selecting explicit type arguments.
    */
  private case class Context(
    member:           Member,
    module:           Module,
    protocols:        ProtocolRegistry,
    scope:            ValueScope,
    genericFunctions: Map[String, Int] = Map.empty
  ):

    def within(params: List[FnParam]): Context = copy(scope = scope.within(params))

  /** A failed callee stays intact inside the smallest application that can carry its error. */
  private case class ResolvedCallee(value: Callee, causes: Vector[DiagnosticId] = Vector.empty):

    def asTerm: Term =
      if causes.isEmpty then value
      else
        InvalidExpression(
          source       = value.source,
          originalExpr = Expr(value.source, List(value)),
          typeSpec     = value.typeSpec,
          typeAsc      = value.typeAsc,
          causes       = causes
        )

  private def resolveMember(member: Member, context: Context): Resolution[Member] = member match
    case binding: Bnd =>
      resolveExpr(binding.value, context)
        .map(value => binding.copy(value = value))
    case other => other.pure[Resolution]

  private def resolveExpr(expr: Expr, context: Context): Resolution[Expr] =
    resolveTerms(expr.terms, context)
      .map(terms => expr.copy(terms = terms))

  /** Selects leading type names as explicit arguments of a generic callee. Value ascriptions keep
    * constructors in value position; complete type prefixes leave following values intact.
    */
  private def resolveTerms(terms: List[Term], context: Context): Resolution[List[Term]] =
    terms match
      case Nil => Nil.pure[Resolution]
      case head :: tail =>
        resolveTerm(head, context).flatMap { resolved =>
          val generic = referenceWithin(resolved)
            .filter(_.explicitTypeArgs.isEmpty)
            .flatMap(ref => genericArity(ref, context).map(ref -> _))
          generic match
            case Some((ref, arity)) =>
              val (candidates, remaining) = tail.span(candidateType(_, context).nonEmpty)
              val (prefix, values) =
                if candidates.size > arity &&
                  candidates.drop(arity).forall(candidateValue(_, context).nonEmpty)
                then candidates.splitAt(arity)
                else (candidates, Nil)
              val types = prefix.flatMap(candidateType(_, context))
              if types.isEmpty then resolveTerms(tail, context).map(resolved :: _)
              else
                val selected     = ref.copy(explicitTypeArgs = types.some)
                val selectedTerm = replaceReference(resolved, selected)
                resolveTerms(values ++ remaining, context).map(selectedTerm :: _)
            case _ => resolveTerms(tail, context).map(resolved :: _)
        }

  /** Finds a reference through singleton expression groups. Type-argument candidates disallow
    * ascriptions at every level so written value annotations reach expression checking intact.
    */
  private def referenceWithin(term: Term, allowAscriptions: Boolean = true): Option[Ref] =
    if !allowAscriptions && term.typeAsc.nonEmpty then None
    else
      term match
        case ref:   Ref => ref.some
        case group: TermGroup => referenceWithin(group.inner, allowAscriptions)
        case expr:  Expr =>
          expr.terms match
            case inner :: Nil => referenceWithin(inner, allowAscriptions)
            case _ => None
        case _ => None

  private def replaceReference(term: Term, ref: Ref): Term = term match
    case _:     Ref => ref
    case group: TermGroup =>
      group.copy(inner =
        group.inner.copy(
          terms = group.inner.terms.map(replaceReference(_, ref))
        )
      )
    case expr: Expr => expr.copy(terms = expr.terms.map(replaceReference(_, ref)))
    case other => other

  private def genericArity(ref: Ref, context: Context): Option[Int] =
    ref.resolvedId
      .flatMap(context.genericFunctions.get)
      .orElse(
        ref.resolvedId
          .flatMap(context.module.resolvables.lookup)
          .collect { case binding: Bnd =>
            binding.value.terms.collectFirst {
              case lambda: Lambda if lambda.typeParams.nonEmpty => lambda.typeParams.size
            }
          }
          .flatten
      )

  private def candidateType(term: Term, context: Context): Option[Type] =
    referenceWithin(term, allowAscriptions = false).filter(_.qualifier.isEmpty).flatMap { ref =>
      context.module.resolvables.resolvableTypes.values.toList.filter(_.name == ref.name) match
        case declaration :: Nil => TypeRef(ref.source, ref.name, declaration.id).some
        case _ => None
    }

  private def candidateValue(term: Term, context: Context): Option[Expr] =
    referenceWithin(term).filter(_.qualifier.isEmpty).flatMap { ref =>
      context.protocols
        .visibleCandidates(ref.name, context.scope, context.module.resolvables)
        .toOption
        .filter(_.nonEmpty)
        .map { candidates =>
          val resolvedId = candidates match
            case ProtocolCandidate.Declaration(id) :: Nil => id.some
            case _ => None
          val value = ref.copy(resolvedId = resolvedId, candidateIds = candidates.map(_.id))
          Expr(ref.source, List(value))
        }
    }

  private def resolveTerm(term: Term, context: Context): Resolution[Term] = term match

    case ref: Ref =>
      resolveRef(ref, context).map(_.asTerm)

    case app: FnTypeApp =>
      resolveTerm(app.fn, context).flatMap { resolved =>
        referenceWithin(resolved) match
          case Some(ref) =>
            val arity = genericArity(ref, context).getOrElse(app.args.size)
            val trailingValues = app.args.drop(arity).traverse {
              case tpe: TypeRef => candidateValue(Ref(tpe.source, tpe.name), context)
              case _ => None
            }
            val (types, values) = trailingValues match
              case Some(values) if app.args.size > arity => (app.args.take(arity), values)
              case _ => (app.args, Nil)
            val selected = ref.copy(
              source           = app.source,
              explicitTypeArgs = types.some,
              typeAsc          = app.typeAsc,
              typeSpec         = app.typeSpec
            )
            val selectedTerm = replaceReference(resolved, selected)
            val result =
              if values.isEmpty then selectedTerm
              else Expr(app.source, selectedTerm :: values.flatMap(_.terms))
            result.pure[Resolution]

          case None =>
            val payload = Expr(app.source, List(app.copy(fn = resolved)))
            val causes = resolved match
              case invalid: InvalidExpression => invalid.causes
              case _ => Vector.empty
            DiagnosticStore
              .report(
                SemanticError
                  .InvalidExpression(payload, ExpressionProblem.InvalidStructure, phaseName),
                causes
              )
              .map(roots => InvalidExpression(app.source, payload, causes = roots))
      }

    case app: App =>
      resolveApp(app, context).map(_.asTerm)

    case lambda: Lambda =>
      resolveLambda(lambda, context).widen

    case group: TermGroup =>
      resolveExpr(group.inner, context)
        .map(inner => group.copy(inner = inner))

    case expr: Expr =>
      resolveExpr(expr, context).widen

    case tuple: Tuple =>
      tuple.elements
        .traverse(resolveExpr(_, context))
        .map(elements => tuple.copy(elements = elements))

    case cond: Cond =>
      for
        predicate <- resolveExpr(cond.cond, context)
        yes <- resolveExpr(cond.ifTrue, context)
        no <- resolveExpr(cond.ifFalse, context)
      yield cond.copy(cond = predicate, ifTrue = yes, ifFalse = no)

    case other => other.pure[Resolution]

  private def resolveRef(ref: Ref, context: Context): Resolution[ResolvedCallee] =
    ref.qualifier match

      case Some(qualifier) =>
        resolveTerm(qualifier, context)
          .map(updated => ResolvedCallee(ref.copy(qualifier = updated.some)))

      case None =>
        val candidates = context.protocols
          .visibleCandidates(ref.name, context.scope, context.module.resolvables)
        candidates match
          case Left(ProtocolLookupError.RejectedDeclaration(causes)) =>
            ResolvedCallee(ref, causes).pure[Resolution]

          case Left(error) =>
            DiagnosticStore
              .report(
                SemanticError
                  .ProtocolErrorFound(ProtocolError.RegistryFailure(ref, error), phaseName)
              )
              .map(causes => ResolvedCallee(ref, causes))

          case Right(Nil) =>
            DiagnosticStore
              .report(SemanticError.UndefinedRef(ref, context.member, phaseName))
              .map(causes => ResolvedCallee(ref, causes))

          case Right(visible) =>
            val resolvedId = visible match
              case ProtocolCandidate.Declaration(id) :: Nil => id.some
              case _ => None
            val updated = ref.copy(candidateIds = visible.map(_.id), resolvedId = resolvedId)
            ResolvedCallee(updated).pure[Resolution]

  private def resolveLambda(lambda: Lambda, context: Context): Resolution[Lambda] =
    resolveExpr(lambda.body, context.within(lambda.params))
      .map(body => lambda.copy(body = body))

  private def resolveApp(app: App, context: Context): Resolution[ResolvedCallee] =
    val bindingContext = (app.fn, app.arg.terms) match
      case (scope: Lambda, List(value: Lambda)) if scope.params.size == 1 =>
        val generic = scope.params.head.id
          .filter(_ => value.typeParams.nonEmpty)
          .map(_ -> value.typeParams.size)
        context.copy(genericFunctions = context.genericFunctions ++ generic)
      case _ => context

    // A parser-lowered local binding is in scope in its initializer, enabling recursion.
    val argContext = app.fn match
      case lambda: Lambda => bindingContext.within(lambda.params)
      case _ => bindingContext

    for
      fn <- resolveCallee(app.fn, bindingContext)
      arg <- resolveExpr(app.arg, argContext)
    yield ResolvedCallee(app.copy(fn = fn.value, arg = arg), fn.causes)

  private def resolveCallee(callee: Callee, context: Context): Resolution[ResolvedCallee] =
    callee match
      case ref:    Ref => resolveRef(ref, context)
      case app:    App => resolveApp(app, context)
      case lambda: Lambda =>
        resolveLambda(lambda, context).map(ResolvedCallee(_))
