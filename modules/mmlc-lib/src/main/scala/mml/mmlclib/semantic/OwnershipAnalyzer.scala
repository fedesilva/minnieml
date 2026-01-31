package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Ownership state for a binding */
enum OwnershipState derives CanEqual:
  case Owned // Caller owns the value, must free it
  case Moved // Value has been moved to another owner
  case Borrowed // Borrowed reference, caller does not own
  case Literal // Literal value, no ownership tracking needed

/** Tracks ownership for bindings within a scope */
case class OwnershipScope(
  bindings:    Map[String, OwnershipState] = Map.empty,
  movedAt:     Map[String, SrcSpan]        = Map.empty,
  resolvables: ResolvablesIndex
):
  def withOwned(name: String): OwnershipScope =
    copy(bindings = bindings + (name -> OwnershipState.Owned))

  def withMoved(name: String, span: SrcSpan): OwnershipScope =
    copy(
      bindings = bindings + (name -> OwnershipState.Moved),
      movedAt  = movedAt + (name -> span)
    )

  def withBorrowed(name: String): OwnershipScope =
    copy(bindings = bindings + (name -> OwnershipState.Borrowed))

  def getState(name: String): Option[OwnershipState] = bindings.get(name)

  def getMovedAt(name: String): Option[SrcSpan] = movedAt.get(name)

  /** Get all owned bindings that need to be freed */
  def ownedBindings: List[String] =
    bindings.collect { case (name, OwnershipState.Owned) => name }.toList

/** Result of analyzing an expression */
case class ExprResult(
  scope:  OwnershipScope,
  expr:   Expr,
  errors: List[SemanticError] = Nil
)

/** Result of analyzing a term */
case class TermResult(
  scope:  OwnershipScope,
  term:   Term,
  errors: List[SemanticError] = Nil
)

/** Ownership analyzer phase. Tracks ownership of heap-allocated values and inserts __free_* calls.
  *
  * Key responsibilities:
  *   - Track ownership state for each binding
  *   - At App nodes: check callee's memEffect and consuming params
  *   - Validate last-use constraints for ~ parameters
  *   - Insert App(Ref("__free_T"), Ref(binding)) at scope end
  *   - Handle conditional branches (both must have same ownership)
  */
object OwnershipAnalyzer:
  private val PhaseName = "ownership-analyzer"

  /** Types that need to be freed - will be used when inserting free calls */
  val heapTypes: Set[String] = Set("String", "Buffer", "IntArray", "StringArray")

  /** Get the free function name for a type - will be used when inserting free calls */
  def freeFnFor(tName: String): Option[String] =
    if heapTypes.contains(tName) then Some(s"__free_$tName")
    else None

  /** Get the type name from a Type - will be used when inserting free calls */
  def getTypeName(t: Type): Option[String] = t match
    case TypeRef(_, name, _, _) => Some(name)
    case _ => None

  /** Check if a Bnd has memory effect Alloc - will be used for tracking allocations */
  def bndAllocates(bnd: Bnd): Boolean =
    bnd.value.terms
      .collectFirst:
        case lambda: Lambda =>
          lambda.body.terms.exists:
            case NativeImpl(_, _, _, _, Some(MemEffect.Alloc)) => true
            case _ => false
      .getOrElse(false)

  /** Analyze a term and track ownership changes */
  private def analyzeTerm(
    term:  Term,
    scope: OwnershipScope
  ): TermResult =
    term match
      case ref: Ref =>
        // Check if this is a use of a moved binding
        scope.getState(ref.name) match
          case Some(OwnershipState.Moved) =>
            scope.getMovedAt(ref.name) match
              case Some(movedAt) =>
                TermResult(
                  scope,
                  ref,
                  errors = List(SemanticError.UseAfterMove(ref, movedAt, PhaseName))
                )
              case None =>
                TermResult(scope, ref)
          case _ =>
            TermResult(scope, ref)

      case App(span, fn, arg, typeAsc, typeSpec) =>
        // First analyze the argument
        val argResult = analyzeExpr(arg, scope)

        // Then analyze the function part
        val fnResult = analyzeTerm(fn, argResult.scope)

        // Check if this application returns an owned value
        val newScope = fnResult.scope

        TermResult(
          newScope,
          App(
            span,
            fnResult.term.asInstanceOf[Ref | App | Lambda],
            argResult.expr,
            typeAsc,
            typeSpec
          ),
          errors = argResult.errors ++ fnResult.errors
        )

      case Cond(span, condExpr, ifTrue, ifFalse, typeSpec, typeAsc) =>
        // Analyze condition
        val condResult = analyzeExpr(condExpr, scope)

        // Analyze both branches starting from the same scope
        val trueResult  = analyzeExpr(ifTrue, condResult.scope)
        val falseResult = analyzeExpr(ifFalse, condResult.scope)

        // Branches should have compatible ownership states
        // For now, just use the true branch's scope
        TermResult(
          trueResult.scope,
          Cond(span, condResult.expr, trueResult.expr, falseResult.expr, typeSpec, typeAsc),
          errors = condResult.errors ++ trueResult.errors ++ falseResult.errors
        )

      case Lambda(span, params, body, captures, typeSpec, typeAsc, meta) =>
        // Create new scope for lambda body with params as borrowed (by default)
        val paramScope = params.foldLeft(scope): (s, p) =>
          if p.consuming then s // Consuming params are handled at call site
          else s.withBorrowed(p.name)

        val bodyResult = analyzeExpr(body, paramScope)

        TermResult(
          scope, // Lambda doesn't change outer scope
          Lambda(span, params, bodyResult.expr, captures, typeSpec, typeAsc, meta),
          errors = bodyResult.errors
        )

      case expr: Expr =>
        val result = analyzeExpr(expr, scope)
        TermResult(result.scope, result.expr, result.errors)

      case TermGroup(span, inner, typeAsc) =>
        val result = analyzeExpr(inner, scope)
        TermResult(
          result.scope,
          TermGroup(span, result.expr, typeAsc),
          errors = result.errors
        )

      case Tuple(span, elements, typeAsc, typeSpec) =>
        var currentScope = scope
        var errors       = List.empty[SemanticError]
        val newElements = elements.map: elem =>
          val result = analyzeExpr(elem, currentScope)
          currentScope = result.scope
          errors       = errors ++ result.errors
          result.expr

        TermResult(
          currentScope,
          Tuple(span, newElements, typeAsc, typeSpec),
          errors = errors
        )

      // Literals don't affect ownership
      case lit: LiteralValue =>
        TermResult(scope, lit)

      // Other terms pass through unchanged
      case other =>
        TermResult(scope, other)

  /** Analyze an expression */
  private def analyzeExpr(
    expr:  Expr,
    scope: OwnershipScope
  ): ExprResult =
    var currentScope = scope
    var errors       = List.empty[SemanticError]
    val newTerms = expr.terms.map: term =>
      val result = analyzeTerm(term, currentScope)
      currentScope = result.scope
      errors       = errors ++ result.errors
      result.term

    ExprResult(
      currentScope,
      expr.copy(terms = newTerms),
      errors = errors
    )

  /** Analyze a member and insert free calls */
  private def analyzeMember(
    member:      Member,
    resolvables: ResolvablesIndex
  ): (Member, List[SemanticError]) =
    member match
      case bnd @ Bnd(visibility, span, name, value, typeSpec, typeAsc, docComment, meta, id) =>
        val scope  = OwnershipScope(resolvables = resolvables)
        val result = analyzeExpr(value, scope)
        (bnd.copy(value = result.expr), result.errors)

      case other =>
        (other, Nil)

  /** Main entry point - rewrite module with ownership tracking */
  def rewriteModule(state: CompilerState): CompilerState =
    val module    = state.module
    var allErrors = List.empty[SemanticError]

    val newMembers = module.members.map: member =>
      val (newMember, errors) = analyzeMember(member, module.resolvables)
      allErrors = allErrors ++ errors
      newMember

    val newModule = module.copy(members = newMembers)
    state.withModule(newModule).addErrors(allErrors)
