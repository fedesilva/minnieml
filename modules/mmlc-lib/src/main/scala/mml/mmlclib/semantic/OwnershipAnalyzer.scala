package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Ownership state for a binding */
enum OwnershipState derives CanEqual:
  case Owned // Caller owns the value, must free it
  case Moved // Value has been moved to another owner
  case Borrowed // Borrowed reference, caller does not own
  case Literal // Literal value, no ownership tracking needed

/** Binding info: ownership state, type, and ID for selecting __free_T */
case class BindingInfo(
  state:      OwnershipState,
  bindingTpe: Option[Type]   = None,
  bindingId:  Option[String] = None
)

/** Tracks ownership for bindings within a scope */
case class OwnershipScope(
  bindings:    Map[String, BindingInfo] = Map.empty,
  movedAt:     Map[String, SrcSpan]     = Map.empty,
  resolvables: ResolvablesIndex
):
  def withOwned(name: String, tpe: Option[Type], id: Option[String] = None): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Owned, tpe, id)))

  def withMoved(name: String, span: SrcSpan): OwnershipScope =
    val existing = bindings.get(name)
    copy(
      bindings =
        bindings + (name -> BindingInfo(OwnershipState.Moved, existing.flatMap(_.bindingTpe))),
      movedAt = movedAt + (name -> span)
    )

  def withBorrowed(name: String): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Borrowed)))

  def withLiteral(name: String): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Literal)))

  def getState(name: String): Option[OwnershipState] = bindings.get(name).map(_.state)

  def getInfo(name: String): Option[BindingInfo] = bindings.get(name)

  def getMovedAt(name: String): Option[SrcSpan] = movedAt.get(name)

  /** Get all owned bindings that need to be freed, with their types and IDs */
  def ownedBindings: List[(String, Option[Type], Option[String])] =
    bindings
      .collect:
        case (name, BindingInfo(OwnershipState.Owned, tpe, id)) => (name, tpe, id)
      .toList

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

  /** Check if a Bnd has memory effect Alloc */
  def bndAllocates(bnd: Bnd): Boolean =
    bnd.value.terms
      .collectFirst:
        case lambda: Lambda =>
          lambda.body.terms.exists:
            case NativeImpl(_, _, _, _, Some(MemEffect.Alloc)) => true
            case _ => false
      .getOrElse(false)

  /** Get the base function Ref from an App chain (e.g., App(App(Ref(f), x), y) -> Ref(f)) */
  private def getBaseFn(term: Ref | App | Lambda): Option[Ref] = term match
    case ref: Ref => Some(ref)
    case App(_, fn, _, _, _) => getBaseFn(fn)
    case _: Lambda => None

  /** Check if an App calls an allocating function, returning the return type if so */
  def appAllocates(app: App, resolvables: ResolvablesIndex): Option[Type] =
    getBaseFn(app.fn).flatMap: ref =>
      ref.resolvedId
        .flatMap(resolvables.lookup)
        .collect { case bnd: Bnd => bnd }
        .filter(bndAllocates)
        .flatMap(_.typeAsc)

  private def mergeAllocTypes(t1: Option[Type], t2: Option[Type]): Option[Type] = (t1, t2) match
    case (Some(a), Some(b)) if a == b => Some(a)
    case (Some(a), Some(_)) => Some(a)
    case (Some(a), None) => Some(a)
    case (None, Some(b)) => Some(b)
    case _ => None

  private def exprAllocates(expr: Expr, resolvables: ResolvablesIndex): Option[Type] =
    expr.terms.lastOption.flatMap(termAllocates(_, resolvables))

  /** Check if a term is an allocating expression */
  def termAllocates(term: Term, resolvables: ResolvablesIndex): Option[Type] = term match
    case app: App => appAllocates(app, resolvables)
    case Cond(_, _, ifTrue, ifFalse, _, _) =>
      val trueAlloc  = exprAllocates(ifTrue, resolvables)
      val falseAlloc = exprAllocates(ifFalse, resolvables)
      mergeAllocTypes(trueAlloc, falseAlloc)
    case _ => None

  /** Create a free call: App(Ref("__free_T"), Ref(binding)) */
  private def mkFreeCall(
    bindingName: String,
    tpe:         Type,
    span:        SrcSpan,
    bindingId:   Option[String]
  ): App =
    val typeName = getTypeName(tpe)
    val freeFn   = typeName.flatMap(freeFnFor).getOrElse("__free_String")
    // Use stdlib ID format for the free function
    val freeFnId = Some(s"stdlib::bnd::$freeFn")
    val unitType: Option[Type] = Some(TypeRef(span, "Unit", Some("stdlib::typedef::Unit"), Nil))
    // Free function type: T -> Unit
    val fnType  = Some(TypeFn(span, List(tpe), unitType.get))
    val fnRef   = Ref(span, freeFn, resolvedId = freeFnId, typeSpec = fnType)
    val argRef  = Ref(span, bindingName, resolvedId = bindingId, typeSpec = Some(tpe))
    val argExpr = Expr(span, List(argRef), typeSpec = Some(tpe))
    App(span, fnRef, argExpr, typeSpec = unitType)

  /** Wrap an expression with CPS-style free calls. Transforms: `expr` into
    * `let __r = expr; let _ = free1; let _ = free2; __r`
    */
  private def wrapWithFrees(
    expr:   Expr,
    toFree: List[(String, Option[Type], Option[String])],
    span:   SrcSpan
  ): Expr =
    if toFree.isEmpty then return expr

    // Get the result type from the expression
    val resultType = expr.typeSpec

    // Create a unique result binding name with proper type
    val resultName  = "__ownership_result"
    val resultParam = FnParam(span, resultName, typeSpec = resultType, typeAsc = resultType)
    val resultRef   = Ref(span, resultName, typeSpec = resultType)

    // Unit type for free call results
    val unitType = Some(TypeRef(span, "Unit", Some("stdlib::typedef::Unit"), Nil))

    // Build the innermost expression: just the result reference
    val innermost = Expr(span, List(resultRef), typeSpec = resultType)

    // Fold free calls from right to left, building:
    // let _ = freeN; ... let _ = free1; __r
    val withFrees = toFree.foldRight(innermost): (binding, acc) =>
      val (name, tpeOpt, id) = binding
      tpeOpt match
        case Some(tpe) =>
          val freeCall = mkFreeCall(name, tpe, span, id)
          val discardParam =
            FnParam(span, "_", typeSpec = unitType, typeAsc = unitType)
          val discardLam =
            Lambda(span, List(discardParam), acc, Nil, typeSpec = resultType)
          val freeAppExpr = Expr(span, List(freeCall), typeSpec = unitType)
          Expr(span, List(App(span, discardLam, freeAppExpr, typeSpec = resultType)))
        case None => acc

    // Wrap with: let __r = expr; <withFrees>
    val resultLam = Lambda(span, List(resultParam), withFrees, Nil, typeSpec = resultType)
    Expr(span, List(App(span, resultLam, expr, typeSpec = resultType)), typeSpec = resultType)

  /** Get the consuming parameter for a given argument position in an App chain. Returns the FnParam
    * if it's consuming, None otherwise.
    */
  private def getConsumingParam(
    fn:          Ref | App | Lambda,
    resolvables: ResolvablesIndex
  ): Option[FnParam] =
    // For an App chain like App(App(Ref(f), x), y), we need to figure out
    // which parameter position 'y' corresponds to.
    // The base function tells us the params, and the App depth tells us position.
    def countAppDepth(t: Ref | App | Lambda): Int = t match
      case App(_, inner, _, _, _) => 1 + countAppDepth(inner)
      case _ => 0

    val depth = countAppDepth(fn)
    getBaseFn(fn).flatMap: ref =>
      ref.resolvedId
        .flatMap(resolvables.lookup)
        .collect { case bnd: Bnd => bnd }
        .flatMap: bnd =>
          // Get lambda from bnd.value
          bnd.value.terms.collectFirst { case l: Lambda => l }
        .flatMap: lambda =>
          // depth=0 means we're applying to first param, depth=1 to second, etc.
          lambda.params.lift(depth).filter(_.consuming)

  /** Handle passing a value to a consuming parameter. If consuming, validates and marks the binding
    * as moved. Returns updated scope and any errors.
    */
  private def handleConsumingParam(
    fn:    Ref | App | Lambda,
    arg:   Expr,
    scope: OwnershipScope
  ): (OwnershipScope, List[SemanticError]) =
    getConsumingParam(fn, scope.resolvables) match
      case Some(_) =>
        // Get the ref being passed (if it's a simple ref)
        arg.terms.headOption match
          case Some(ref: Ref) =>
            // Check if it's owned - can only move owned values
            scope.getState(ref.name) match
              case Some(OwnershipState.Owned) =>
                // Valid move - mark as moved
                (scope.withMoved(ref.name, ref.span), Nil)
              case Some(OwnershipState.Moved) =>
                // Already moved - use after move error
                val errors = scope.getMovedAt(ref.name) match
                  case Some(movedAt) =>
                    List(SemanticError.UseAfterMove(ref, movedAt, PhaseName))
                  case None => Nil
                (scope, errors)
              case _ =>
                // Passing a borrowed or literal to consuming param - allowed
                (scope, Nil)
          case _ =>
            // Complex expression - can't track ownership
            (scope, Nil)
      case None =>
        (scope, Nil)

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
        fn match
          // Pattern: App(Lambda(...), arg) - this is a let binding
          case Lambda(lSpan, params, body, captures, lTypeSpec, lTypeAsc, meta) =>
            // Analyze the argument first
            val argResult = analyzeExpr(arg, scope)

            // Check if arg is an allocating expression
            val allocType = arg.terms.headOption.flatMap(termAllocates(_, scope.resolvables))

            // Set up scope for lambda body - first param gets ownership if allocating
            val bodyScope = params.headOption match
              case Some(param) if allocType.isDefined =>
                argResult.scope.withOwned(param.name, allocType, param.id)
              case Some(param) =>
                // Non-allocating: check if it's a literal or borrowed
                arg.terms.headOption match
                  case Some(_: LiteralString) =>
                    argResult.scope.withLiteral(param.name)
                  case _ =>
                    argResult.scope.withBorrowed(param.name)
              case None =>
                argResult.scope

            // Analyze the lambda body with the updated scope
            val bodyResult = analyzeExpr(body, bodyScope)

            // Only insert free calls if body doesn't chain to another let-binding.
            // In CPS style, App(Lambda, ...) is a continuation - ownership propagates through.
            val isTerminalBody = !bodyResult.expr.terms.lastOption.exists:
              case App(_, _: Lambda, _, _, _) => true
              case _ => false

            val bindingsToFree =
              if isTerminalBody then
                bodyResult.scope.ownedBindings.filter:
                  case (_, Some(tpe), _) =>
                    heapTypes.contains(getTypeName(tpe).getOrElse(""))
                  case _ => false
              else Nil

            // Wrap body in CPS-style free sequence:
            // let result = <body>; let _ = free x; result
            val newBody =
              if bindingsToFree.isEmpty then bodyResult.expr
              else wrapWithFrees(bodyResult.expr, bindingsToFree, body.span)

            val newLambda = Lambda(lSpan, params, newBody, captures, lTypeSpec, lTypeAsc, meta)

            TermResult(
              scope, // Lambda application doesn't change outer scope
              App(span, newLambda, argResult.expr, typeAsc, typeSpec),
              errors = argResult.errors ++ bodyResult.errors
            )

          // Regular function application
          case _ =>
            // First analyze the argument
            val argResult = analyzeExpr(arg, scope)

            // Check if we're passing to a consuming parameter and update scope
            val (scopeAfterArg, fnErrors) =
              handleConsumingParam(fn, arg, argResult.scope)

            // Then analyze the function part
            val fnResult = analyzeTerm(fn, scopeAfterArg)

            TermResult(
              fnResult.scope,
              App(
                span,
                fnResult.term.asInstanceOf[Ref | App | Lambda],
                argResult.expr,
                typeAsc,
                typeSpec
              ),
              errors = argResult.errors ++ fnResult.errors ++ fnErrors
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
