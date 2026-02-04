package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Ownership state for a binding */
enum OwnershipState derives CanEqual:
  case Owned // Caller owns the value, must free it
  case Moved // Value has been moved to another owner
  case Borrowed // Borrowed reference, caller does not own
  case Literal // Literal value, no ownership tracking needed

/** Binding info: ownership state, type, ID for selecting __free_T, and optional sidecar boolean */
case class BindingInfo(
  state:      OwnershipState,
  bindingTpe: Option[Type]   = None,
  bindingId:  Option[String] = None,
  sidecar:    Option[String] = None // Name of __owns_<binding> if mixed ownership
)

/** Tracks ownership for bindings within a scope */
case class OwnershipScope(
  bindings:       Map[String, BindingInfo]  = Map.empty,
  movedAt:        Map[String, SrcSpan]      = Map.empty,
  resolvables:    ResolvablesIndex,
  returningOwned: Map[String, Option[Type]] = Map.empty,
  tempCounter:    Int                       = 0
):
  def nextTemp: (String, OwnershipScope) =
    (s"__tmp_$tempCounter", copy(tempCounter = tempCounter + 1))
  def withOwned(name: String, tpe: Option[Type], id: Option[String] = None): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Owned, tpe, id)))

  def withMixedOwnership(
    name:        String,
    tpe:         Option[Type],
    sidecarName: String,
    id:          Option[String] = None
  ): OwnershipScope =
    copy(bindings =
      bindings + (name -> BindingInfo(OwnershipState.Owned, tpe, id, Some(sidecarName)))
    )

  def getSidecar(name: String): Option[String] =
    bindings.get(name).flatMap(_.sidecar)

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

  /** Get all owned bindings that need to be freed, with their types, IDs, and sidecars */
  def ownedBindings: List[(String, Option[Type], Option[String], Option[String])] =
    bindings
      .collect:
        case (name, BindingInfo(OwnershipState.Owned, tpe, id, sidecar)) =>
          (name, tpe, id, sidecar)
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

  /** Synthetic span for generated AST nodes - won't conflict with semantic tokens */
  private val syntheticSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))

  /** Infer which functions return owned heap values (even if not annotated with MemEffect.Alloc).
    * Uses a fixed-point intramodule analysis so that functions returning the result of other
    * returning functions are marked transitively.
    */
  private object ReturnOwnershipAnalysis:
    private def merge(t1: Option[Type], t2: Option[Type]): Option[Type] = (t1, t2) match
      case (Some(a), Some(b)) if a == b => Some(a)
      case (Some(a), _) => Some(a)
      case (_, Some(b)) => Some(b)
      case _ => None

    private def appReturnsOwned(
      app:            App,
      resolvables:    ResolvablesIndex,
      returningOwned: Map[String, Option[Type]]
    ): Option[Type] =
      getBaseFn(app.fn).flatMap: ref =>
        ref.resolvedId.flatMap: id =>
          returningOwned
            .get(id)
            .flatten
            .orElse:
              resolvables
                .lookup(id)
                .collect { case bnd: Bnd => bnd }
                .filter(bndAllocates)
                .flatMap(b => b.typeAsc.orElse(b.typeSpec))

    private def termReturnsOwned(
      term:           Term,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      returningOwned: Map[String, Option[Type]]
    ): Option[Type] =
      term match
        case ref: Ref => env.get(ref.name).flatten
        case app: App =>
          app.fn match
            case lambda: Lambda =>
              val argOwned  = argReturnsOwned(app.arg, env, resolvables, returningOwned)
              val paramName = lambda.params.headOption.map(_.name)
              val bodyEnv =
                paramName.map(n => env + (n -> argOwned)).getOrElse(env)
              argOwned.orElse(exprReturnsOwned(lambda.body, bodyEnv, resolvables, returningOwned))
            case _ =>
              appReturnsOwned(app, resolvables, returningOwned)
        case cond: Cond =>
          merge(
            exprReturnsOwned(cond.ifTrue, env, resolvables, returningOwned),
            exprReturnsOwned(cond.ifFalse, env, resolvables, returningOwned)
          )
        case TermGroup(_, inner, _) =>
          exprReturnsOwned(inner, env, resolvables, returningOwned)
        case _ => None

    private def argReturnsOwned(
      expr:           Expr,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      returningOwned: Map[String, Option[Type]]
    ): Option[Type] =
      exprReturnsOwned(expr, env, resolvables, returningOwned)

    private def exprReturnsOwned(
      expr:           Expr,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      returningOwned: Map[String, Option[Type]]
    ): Option[Type] =
      expr.terms.lastOption.flatMap(termReturnsOwned(_, env, resolvables, returningOwned))

    def discover(module: Module): Map[String, Option[Type]] =
      val resolvables = module.resolvables
      val functions   = module.members.collect { case b: Bnd => b }.flatMap(b => b.id.map(_ -> b))
      var returningOwned = Map.empty[String, Option[Type]]
      var changed        = true
      var iterations     = 0

      while changed && iterations < 10 do
        iterations += 1
        changed = false
        functions.foreach { case (id, bnd) =>
          val lambdaOpt = bnd.value.terms.collectFirst { case l: Lambda => l }
          lambdaOpt.foreach { lambda =>
            val resultOwned =
              exprReturnsOwned(lambda.body, Map.empty, resolvables, returningOwned)
                .filter(t => getTypeName(t).exists(isHeapType(_, resolvables)))

            val current = returningOwned.get(id).flatten
            if resultOwned.isDefined && current != resultOwned then
              returningOwned = returningOwned.updated(id, resultOwned)
              changed        = true
          }
        }

      returningOwned

  /** Get the type name from a Type */
  def getTypeName(t: Type): Option[String] = t match
    case TypeRef(_, name, _, _) => Some(name)
    case TypeStruct(_, _, _, name, _, _) => Some(name)
    case _ => None

  /** Find a type definition by name, trying common ID patterns */
  private def findTypeByName(
    typeName:    String,
    resolvables: ResolvablesIndex
  ): Option[ResolvableType] =
    // Try stdlib ID format first
    resolvables
      .lookupType(s"stdlib::typedef::$typeName")
      .orElse(resolvables.lookupType(s"stdlib::typealias::$typeName"))
      .orElse:
        // Search through all types by name as fallback (for user-defined types)
        resolvables.resolvableTypes.values.find:
          case td: TypeDef => td.name == typeName
          case ta: TypeAlias => ta.name == typeName
          case ts: TypeStruct => ts.name == typeName

  /** Check if a type is heap-allocated by looking at its NativeType.memEffect */
  def isHeapType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    findTypeByName(typeName, resolvables) match
      case Some(TypeDef(_, _, _, Some(ns: NativeStruct), _, _, _)) =>
        ns.memEffect.contains(MemEffect.Alloc)
      case Some(TypeDef(_, _, _, Some(np: NativePointer), _, _, _)) =>
        np.memEffect.contains(MemEffect.Alloc)
      case Some(TypeDef(_, _, _, Some(prim: NativePrimitive), _, _, _)) =>
        prim.memEffect.contains(MemEffect.Alloc)
      case Some(s: TypeStruct) =>
        hasHeapFields(s, resolvables)
      case _ => false

  /** Check if a user struct has any heap-typed fields */
  def hasHeapFields(struct: TypeStruct, resolvables: ResolvablesIndex): Boolean =
    struct.fields.exists { field =>
      getTypeName(field.typeSpec).exists(isHeapType(_, resolvables))
    }

  /** Get free function name for a type, or None if not heap type */
  def freeFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    if isHeapType(typeName, resolvables) then Some(s"__free_$typeName")
    else None

  /** Get clone function name for a type, or None if not heap type */
  def cloneFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    if isHeapType(typeName, resolvables) then Some(s"__clone_$typeName")
    else None

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
  def appAllocates(
    app:            App,
    resolvables:    ResolvablesIndex,
    returningOwned: Map[String, Option[Type]]
  ): Option[Type] =
    getBaseFn(app.fn).flatMap: ref =>
      ref.resolvedId.flatMap { id =>
        val returned =
          returningOwned
            .get(id)
            .flatten
            .filter(t => getTypeName(t).exists(isHeapType(_, resolvables)))

        returned.orElse:
          resolvables
            .lookup(id)
            .collect { case bnd: Bnd => bnd }
            .filter(bndAllocates)
            .flatMap(_.typeAsc)
            .filter(t => getTypeName(t).exists(isHeapType(_, resolvables)))
      }

  private def mergeAllocTypes(t1: Option[Type], t2: Option[Type]): Option[Type] = (t1, t2) match
    case (Some(a), Some(b)) if a == b => Some(a)
    case (Some(a), Some(_)) => Some(a)
    case (Some(a), None) => Some(a)
    case (None, Some(b)) => Some(b)
    case _ => None

  private def exprAllocates(expr: Expr, scope: OwnershipScope): Option[Type] =
    expr.terms.lastOption.flatMap(termAllocates(_, scope))

  /** Check if a term is an allocating expression */
  def termAllocates(term: Term, scope: OwnershipScope): Option[Type] = term match
    case app: App => appAllocates(app, scope.resolvables, scope.returningOwned)
    case Cond(_, _, ifTrue, ifFalse, _, _) =>
      val trueAlloc  = exprAllocates(ifTrue, scope)
      val falseAlloc = exprAllocates(ifFalse, scope)
      mergeAllocTypes(trueAlloc, falseAlloc)
    case _ => None

  /** Create a free call: App(Ref("__free_T"), Ref(binding)) */
  private def mkFreeCall(
    bindingName: String,
    tpe:         Type,
    span:        SrcSpan,
    bindingId:   Option[String],
    resolvables: ResolvablesIndex
  ): App =
    val typeName = getTypeName(tpe)
    val freeFn   = typeName.flatMap(freeFnFor(_, resolvables)).getOrElse("__free_String")
    // Use stdlib ID format for the free function
    val freeFnId = Some(s"stdlib::bnd::$freeFn")
    val unitType: Option[Type] = Some(TypeRef(span, "Unit", Some("stdlib::typedef::Unit"), Nil))
    // Free function type: T -> Unit
    val fnType  = Some(TypeFn(span, List(tpe), unitType.get))
    val fnRef   = Ref(span, freeFn, resolvedId = freeFnId, typeSpec = fnType)
    val argRef  = Ref(span, bindingName, resolvedId = bindingId, typeSpec = Some(tpe))
    val argExpr = Expr(span, List(argRef), typeSpec = Some(tpe))
    App(span, fnRef, argExpr, typeSpec = unitType)

  /** Check if an expression is a conditional with mixed allocation (one branch allocates, other
    * doesn't). Returns Some((trueAllocates, allocType)) if mixed, None otherwise.
    */
  private def detectMixedConditional(expr: Expr, scope: OwnershipScope): Option[(Boolean, Type)] =
    expr.terms.headOption.flatMap:
      case Cond(_, _, ifTrue, ifFalse, _, _) =>
        val trueAlloc  = exprAllocates(ifTrue, scope)
        val falseAlloc = exprAllocates(ifFalse, scope)
        // XOR: exactly one branch allocates
        (trueAlloc, falseAlloc) match
          case (Some(tpe), None) => Some((true, tpe))
          case (None, Some(tpe)) => Some((false, tpe))
          case _ => None
      case _ => None

  /** Create a sidecar conditional: `if cond then true else false` or `if cond then false else true`
    * depending on which branch allocates.
    */
  private def mkSidecarConditional(
    originalCond:  Cond,
    trueAllocates: Boolean
  ): Cond =
    val boolType = Some(TypeRef(syntheticSpan, "Bool", Some("stdlib::typedef::Bool"), Nil))
    val trueLit  = LiteralBool(syntheticSpan, true)
    val falseLit = LiteralBool(syntheticSpan, false)

    val (ifTrueExpr, ifFalseExpr) =
      if trueAllocates then
        (
          Expr(syntheticSpan, List(trueLit), typeSpec  = boolType),
          Expr(syntheticSpan, List(falseLit), typeSpec = boolType)
        )
      else
        (
          Expr(syntheticSpan, List(falseLit), typeSpec = boolType),
          Expr(syntheticSpan, List(trueLit), typeSpec  = boolType)
        )

    Cond(syntheticSpan, originalCond.cond, ifTrueExpr, ifFalseExpr, boolType, boolType)

  /** Create a conditional free: if __owns_x then __free_T x else () */
  private def mkConditionalFree(
    bindingName: String,
    tpe:         Type,
    sidecarName: String,
    span:        SrcSpan,
    bindingId:   Option[String],
    resolvables: ResolvablesIndex
  ): Cond =
    val boolType = Some(TypeRef(span, "Bool", Some("stdlib::typedef::Bool"), Nil))
    val unitType = Some(TypeRef(span, "Unit", Some("stdlib::typedef::Unit"), Nil))

    // Condition: reference to the sidecar boolean
    val sidecarRef  = Ref(span, sidecarName, typeSpec = boolType)
    val sidecarExpr = Expr(span, List(sidecarRef), typeSpec = boolType)

    // Then branch: __free_T x
    val freeCall     = mkFreeCall(bindingName, tpe, span, bindingId, resolvables)
    val freeCallExpr = Expr(span, List(freeCall), typeSpec = unitType)

    // Else branch: ()
    val unitLit     = LiteralUnit(span)
    val unitLitExpr = Expr(span, List(unitLit), typeSpec = unitType)

    Cond(span, sidecarExpr, freeCallExpr, unitLitExpr, unitType, unitType)

  /** Wrap an expression with CPS-style free calls. Transforms: `expr` into
    * `let __r = expr; let _ = free1; let _ = free2; __r`
    *
    * For bindings with sidecars, generates conditional free: `if __owns_x then __free_T x else ()`
    */
  private def wrapWithFrees(
    expr:        Expr,
    toFree:      List[(String, Option[Type], Option[String], Option[String])],
    span:        SrcSpan,
    resolvables: ResolvablesIndex
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
    // For bindings with sidecars, generate conditional free instead
    val withFrees = toFree.foldRight(innermost): (binding, acc) =>
      val (name, tpeOpt, id, sidecarOpt) = binding
      tpeOpt match
        case Some(tpe) =>
          val freeOrCondFree: Term = sidecarOpt match
            case Some(sidecarName) =>
              // Conditional free: if __owns_x then __free_T x else ()
              mkConditionalFree(name, tpe, sidecarName, span, id, resolvables)
            case None =>
              // Unconditional free
              mkFreeCall(name, tpe, span, id, resolvables)

          val discardParam =
            FnParam(span, "_", typeSpec = unitType, typeAsc = unitType)
          val discardLam =
            Lambda(span, List(discardParam), acc, Nil, typeSpec = resultType)
          val freeAppExpr = Expr(span, List(freeOrCondFree), typeSpec = unitType)
          Expr(span, List(App(span, discardLam, freeAppExpr, typeSpec = resultType)))
        case None => acc

    // Wrap with: let __r = expr; <withFrees>
    val resultLam = Lambda(span, List(resultParam), withFrees, Nil, typeSpec = resultType)
    Expr(span, List(App(span, resultLam, expr, typeSpec = resultType)), typeSpec = resultType)

  /** Wrap an expression with __clone_T call */
  private def wrapWithClone(
    expr: Expr,
    tpe:  Type
  ): Expr =
    val typeName    = getTypeName(tpe).getOrElse("String")
    val cloneFnName = s"__clone_$typeName"
    val cloneFnId   = Some(s"stdlib::bnd::$cloneFnName")
    val cloneFnType = Some(TypeFn(syntheticSpan, List(tpe), tpe))
    val cloneFnRef = Ref(syntheticSpan, cloneFnName, resolvedId = cloneFnId, typeSpec = cloneFnType)
    val cloneApp   = App(syntheticSpan, cloneFnRef, expr, typeSpec = Some(tpe))
    Expr(syntheticSpan, List(cloneApp), typeSpec = Some(tpe))

  /** Promote static branches to heap when function returns heap type.
    *
    * When a function returns a heap type and its body is a conditional where one branch allocates
    * and the other doesn't, wrap the non-allocating branch with __clone_T. This ensures the caller
    * always owns the returned value and can unconditionally free it.
    */
  private def promoteStaticBranchesInReturn(
    expr:       Expr,
    returnType: Option[Type],
    scope:      OwnershipScope
  ): Expr =
    // Only apply to heap return types
    val typeName = returnType.flatMap(getTypeName)
    if !typeName.exists(isHeapType(_, scope.resolvables)) then return expr

    expr.terms.lastOption match
      case Some(Cond(span, condExpr, ifTrue, ifFalse, typeSpec, typeAsc)) =>
        val trueAlloc  = exprAllocates(ifTrue, scope)
        val falseAlloc = exprAllocates(ifFalse, scope)

        (trueAlloc, falseAlloc) match
          case (Some(_), None) =>
            // True allocates, false is static - clone false branch
            val cloned  = wrapWithClone(ifFalse, returnType.getOrElse(ifFalse.typeSpec.get))
            val newCond = Cond(span, condExpr, ifTrue, cloned, typeSpec, typeAsc)
            expr.copy(terms = expr.terms.init :+ newCond)
          case (None, Some(_)) =>
            // False allocates, true is static - clone true branch
            val cloned  = wrapWithClone(ifTrue, returnType.getOrElse(ifTrue.typeSpec.get))
            val newCond = Cond(span, condExpr, cloned, ifFalse, typeSpec, typeAsc)
            expr.copy(terms = expr.terms.init :+ newCond)
          case _ =>
            // Both allocate or neither - no change needed
            expr
      case _ => expr

  /** Names of owned bindings that flow out through the returned expression */
  private def returnedOwnedNames(expr: Expr, scope: OwnershipScope): Set[String] =
    def termReturned(term: Term): Set[String] =
      term match
        case ref: Ref if scope.getState(ref.name).contains(OwnershipState.Owned) => Set(ref.name)
        case Cond(_, _, ifTrue, ifFalse, _, _) =>
          returnedOwnedNames(ifTrue, scope) ++ returnedOwnedNames(ifFalse, scope)
        case TermGroup(_, inner, _) => returnedOwnedNames(inner, scope)
        case _ => Set.empty

    expr.terms.lastOption.map(termReturned).getOrElse(Set.empty)

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
            val allocType = arg.terms.headOption.flatMap(termAllocates(_, scope))

            // Check if arg is a MIXED conditional (one branch allocates, other doesn't)
            val mixedCond = detectMixedConditional(arg, scope)

            // Set up scope for lambda body - handle mixed conditionals specially
            val (bodyScope, sidecarOpt) = params.headOption match
              case Some(param) if mixedCond.isDefined =>
                // Mixed conditional: generate sidecar boolean for compile-time tracking
                val (trueAllocates, allocTpe) = mixedCond.get
                val sidecarName               = s"__owns_${param.name}"
                val originalCond              = arg.terms.head.asInstanceOf[Cond]
                val sidecarCond               = mkSidecarConditional(originalCond, trueAllocates)
                val boolType =
                  Some(TypeRef(syntheticSpan, "Bool", Some("stdlib::typedef::Bool"), Nil))
                val sidecarExpr = Expr(syntheticSpan, List(sidecarCond), typeSpec = boolType)
                val scopeWithSidecar = argResult.scope
                  .withMixedOwnership(param.name, Some(allocTpe), sidecarName, param.id)
                  .withLiteral(sidecarName) // sidecar is bool, no cleanup needed
                (scopeWithSidecar, Some((sidecarName, sidecarExpr, boolType)))
              case Some(param) if allocType.isDefined =>
                val paramTypeName =
                  param.typeSpec
                    .orElse(param.typeAsc)
                    .flatMap(getTypeName)
                val allocHeap =
                  allocType.filter(t => getTypeName(t).exists(isHeapType(_, scope.resolvables)))
                val owns =
                  allocHeap.filter(t => paramTypeName.forall(_ == getTypeName(t).getOrElse("")))
                val newScope = owns
                  .map(t => argResult.scope.withOwned(param.name, Some(t), param.id))
                  .getOrElse(argResult.scope.withBorrowed(param.name))
                (newScope, None)
              case Some(param) =>
                // Non-allocating: check if it's a literal or borrowed
                val newScope = arg.terms.headOption match
                  case Some(_: LiteralString) =>
                    argResult.scope.withLiteral(param.name)
                  case _ =>
                    argResult.scope.withBorrowed(param.name)
                (newScope, None)
              case None =>
                (argResult.scope, None)

            // Analyze the lambda body with the updated scope
            val bodyResult = analyzeExpr(body, bodyScope)

            // Only insert free calls if body doesn't chain to another let-binding.
            // In CPS style, App(Lambda, ...) is a continuation - ownership propagates through.
            val isTerminalBody = !bodyResult.expr.terms.lastOption.exists:
              case App(_, _: Lambda, _, _, _) => true
              case _ => false

            val escaping = returnedOwnedNames(bodyResult.expr, bodyResult.scope)

            // Free all owned bindings at terminal body. Double-free is prevented by:
            // 1. Explicit __free_* calls mark their args as Moved (via consuming param)
            // 2. Temp wrappers mark inherited bindings as Borrowed (see borrowedScope below)
            // 3. Bindings with sidecars are excluded here - handled separately below
            // For bindings with sidecars, generates conditional free at this point
            val sidecarBinding = sidecarOpt.flatMap(_ => params.headOption.map(_.name))
            val bindingsToFree =
              if isTerminalBody then
                bodyResult.scope.ownedBindings.filter:
                  case (name, Some(tpe), _, _) =>
                    !escaping.contains(name) &&
                    !sidecarBinding.contains(name) && // Exclude sidecar binding - handled below
                    getTypeName(tpe).exists(isHeapType(_, scope.resolvables))
                  case _ => false
              else Nil

            // Wrap body in CPS-style free sequence:
            // let result = <body>; let _ = free x; result
            // For bindings with sidecars: if __owns_x then __free_T x else ()
            val bodyWithTerminalFrees =
              if bindingsToFree.isEmpty then bodyResult.expr
              else wrapWithFrees(bodyResult.expr, bindingsToFree, body.span, scope.resolvables)

            // If we have a sidecar, wrap the body (inside the let) with conditional free
            // Structure: let __owns_x = <cond>; let x = <val>; <body>; if __owns_x then free x; result
            val newBody = sidecarOpt match
              case Some((sidecarName, _, _)) =>
                // Get the binding's type from the param
                val bindingName = params.headOption.map(_.name).getOrElse("")
                val bindingType = params.headOption.flatMap(p => p.typeSpec.orElse(p.typeAsc))
                val bindingId   = params.headOption.flatMap(_.id)

                bindingType match
                  case Some(tpe) if getTypeName(tpe).exists(isHeapType(_, scope.resolvables)) =>
                    // Generate: let __r = <body>; if __owns_x then free x else (); __r
                    val toFree = List((bindingName, Some(tpe), bindingId, Some(sidecarName)))
                    wrapWithFrees(bodyWithTerminalFrees, toFree, body.span, scope.resolvables)
                  case _ =>
                    bodyWithTerminalFrees
              case None =>
                bodyWithTerminalFrees

            val newLambda = Lambda(lSpan, params, newBody, captures, lTypeSpec, lTypeAsc, meta)
            val innerApp  = App(span, newLambda, argResult.expr, typeAsc, typeSpec)

            // If we have a sidecar, wrap with: let __owns_x = <sidecar_cond>; <innerApp>
            val finalTerm = sidecarOpt match
              case Some((sidecarName, sidecarExpr, boolType)) =>
                val sidecarParam =
                  FnParam(syntheticSpan, sidecarName, typeSpec = boolType, typeAsc = boolType)
                val innerAppExpr = Expr(syntheticSpan, List(innerApp), typeSpec = typeSpec)
                val sidecarLambda =
                  Lambda(syntheticSpan, List(sidecarParam), innerAppExpr, Nil, typeSpec = typeSpec)
                App(syntheticSpan, sidecarLambda, sidecarExpr, typeSpec = typeSpec)
              case None =>
                innerApp

            TermResult(
              scope, // Lambda application doesn't change outer scope
              finalTerm,
              errors = argResult.errors ++ bodyResult.errors
            )

          // Regular function application - handle entire curried chain at once
          case _ =>
            // Collect all args and base function from curried application chain
            def collectArgsAndBase(
              t:    Ref | App | Lambda,
              args: List[(Expr, SrcSpan, Option[Type], Option[Type])]
            ): (Ref | Lambda, List[(Expr, SrcSpan, Option[Type], Option[Type])]) =
              t match
                case ref:    Ref => (ref, args)
                case lambda: Lambda => (lambda, args)
                case App(s, inner, a, tAsc, tSpec) =>
                  collectArgsAndBase(inner, (a, s, tAsc, tSpec) :: args)

            val (baseFn, allArgsWithMeta) =
              collectArgsAndBase(fn, List((arg, span, typeAsc, typeSpec)))

            // Check which args allocate (in order from first to last)
            val argsWithAlloc = allArgsWithMeta.map { case (argExpr, s, tAsc, tSpec) =>
              val allocType = argExpr.terms.lastOption.flatMap(termAllocates(_, scope))
              (argExpr, s, tAsc, tSpec, allocType)
            }

            val allocatingArgs = argsWithAlloc.filter(_._5.isDefined)

            if allocatingArgs.isEmpty then
              // No allocating args - proceed with normal analysis
              val argResult                 = analyzeExpr(arg, scope)
              val (scopeAfterArg, fnErrors) = handleConsumingParam(fn, arg, argResult.scope)
              val fnResult                  = analyzeTerm(fn, scopeAfterArg)
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
            else
              // Create temps for allocating args, build clean App chain, wrap with let-bindings
              var currentScope = scope
              val tempsAndArgs = argsWithAlloc.map { case (argExpr, s, tAsc, tSpec, allocOpt) =>
                allocOpt match
                  case Some(allocType) =>
                    val (tmpName, newScope) = currentScope.nextTemp
                    currentScope = newScope
                    val tmpRef     = Ref(syntheticSpan, tmpName, typeSpec = Some(allocType))
                    val tmpRefExpr = Expr(syntheticSpan, List(tmpRef), typeSpec = Some(allocType))
                    (tmpRefExpr, s, tAsc, tSpec, Some((tmpName, argExpr, allocType)))
                  case None =>
                    (argExpr, s, tAsc, tSpec, None)
              }

              // Build the clean App chain using temps where needed
              val innerApp = tempsAndArgs.foldLeft(baseFn: Ref | App | Lambda) {
                case (accFn, (argExpr, s, tAsc, tSpec, _)) =>
                  App(s, accFn, argExpr, tAsc, tSpec)
              }

              // Collect allocating bindings (reverse order for proper nesting of let-bindings)
              val allocBindings = tempsAndArgs.flatMap(_._5).reverse

              // Check if this is a struct constructor - if so, clone heap args (struct owns copies)
              val isStructConstructor = baseFn match
                case ref: Ref => ref.name.startsWith("__mk_")
                case _        => false

              // For struct constructors, wrap heap args with clone calls
              val finalInnerApp =
                if isStructConstructor then
                  // Rebuild the App chain with cloned args for heap types
                  tempsAndArgs.foldLeft(baseFn: Ref | App | Lambda) {
                    case (accFn, (argExpr, s, tAsc, tSpec, Some((_, _, allocType)))) =>
                      // This arg allocated - wrap with clone
                      val clonedArg = wrapWithClone(argExpr, allocType)
                      App(s, accFn, clonedArg, tAsc, tSpec)
                    case (accFn, (argExpr, s, tAsc, tSpec, None)) =>
                      // Non-allocating arg - use as-is
                      App(s, accFn, argExpr, tAsc, tSpec)
                  }
                else innerApp

              val finalInnerBody = Expr(syntheticSpan, List(finalInnerApp), typeSpec = typeSpec)

              // Build structure with explicit free calls:
              // let tmp0 = arg0; let tmp1 = arg1; let result = inner; free tmp1; free tmp0; result
              val resultType = typeSpec
              val resultName = "__tmp_result"
              val resultRef  = Ref(syntheticSpan, resultName, typeSpec = resultType)

              // Free calls for all temps (in same order as allocBindings, which frees inner first)
              val freeCalls = allocBindings.map { case (tmpName, _, allocType) =>
                mkFreeCall(tmpName, allocType, syntheticSpan, None, scope.resolvables)
              }

              // Build innermost: result reference
              val innermost = Expr(syntheticSpan, List(resultRef), typeSpec = resultType)

              // Wrap with free calls: let _ = free tmpN; ... let _ = free tmp0; result
              val unitType = Some(
                TypeRef(syntheticSpan, "Unit", Some("stdlib::typedef::Unit"), Nil)
              )
              val withFrees = freeCalls.foldRight(innermost) { (freeCall, acc) =>
                val discardParam = FnParam(syntheticSpan, "_", typeSpec = unitType)
                val discardLam =
                  Lambda(syntheticSpan, List(discardParam), acc, Nil, typeSpec = resultType)
                val freeExpr = Expr(syntheticSpan, List(freeCall), typeSpec = unitType)
                Expr(
                  syntheticSpan,
                  List(App(syntheticSpan, discardLam, freeExpr, typeSpec = resultType))
                )
              }

              // Wrap with result binding: let result = innerBody; <withFrees>
              val resultParam = FnParam(syntheticSpan, resultName, typeSpec = resultType)
              val resultLam =
                Lambda(syntheticSpan, List(resultParam), withFrees, Nil, typeSpec = resultType)
              val withResult = Expr(
                syntheticSpan,
                List(App(syntheticSpan, resultLam, finalInnerBody, typeSpec = resultType)),
                typeSpec = resultType
              )

              // Wrap with temp bindings (cleanup is already in withResult, not scope-end)
              val wrappedExpr = allocBindings.foldLeft(withResult) {
                case (body, (tmpName, argExpr, allocType)) =>
                  val tmpParam = FnParam(syntheticSpan, tmpName, typeSpec = Some(allocType))
                  val wrapperLambda =
                    Lambda(syntheticSpan, List(tmpParam), body, Nil, typeSpec = body.typeSpec)
                  Expr(
                    syntheticSpan,
                    List(App(syntheticSpan, wrapperLambda, argExpr, typeSpec = body.typeSpec))
                  )
              }

              // For analyzing the temp wrapper, mark inherited owned bindings as Borrowed.
              // This prevents them from being freed inside the wrapper - they'll be freed
              // by their owning scope. Temps have explicit frees so they're handled.
              val borrowedScope = scope.ownedBindings.foldLeft(currentScope) {
                case (s, (name, _, _, _)) => s.withBorrowed(name)
              }

              // The wrapped result is an Expr with a single App term
              wrappedExpr.terms match
                case List(wrappedApp: App) =>
                  val result = analyzeTerm(wrappedApp, borrowedScope)
                  // Restore original scope's ownership state for outer context
                  TermResult(scope, result.term, result.errors)
                case _ =>
                  // Fallback - shouldn't happen
                  val result = analyzeExpr(wrappedExpr, borrowedScope)
                  TermResult(scope, wrappedExpr.terms.head, result.errors)

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

        // Apply clone insertion for mixed-allocation returns
        val returnType   = typeAsc.orElse(typeSpec)
        val promotedBody = promoteStaticBranchesInReturn(bodyResult.expr, returnType, paramScope)

        TermResult(
          scope, // Lambda doesn't change outer scope
          Lambda(span, params, promotedBody, captures, typeSpec, typeAsc, meta),
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
    member:         Member,
    resolvables:    ResolvablesIndex,
    returningOwned: Map[String, Option[Type]]
  ): (Member, List[SemanticError]) =
    member match
      case bnd @ Bnd(visibility, span, name, value, typeSpec, typeAsc, docComment, meta, id) =>
        val scope  = OwnershipScope(resolvables = resolvables, returningOwned = returningOwned)
        val result = analyzeExpr(value, scope)
        (bnd.copy(value = result.expr), result.errors)

      case other =>
        (other, Nil)

  /** Main entry point - rewrite module with ownership tracking */
  def rewriteModule(state: CompilerState): CompilerState =
    val module         = state.module
    val returningOwned = ReturnOwnershipAnalysis.discover(module)
    var allErrors      = List.empty[SemanticError]

    val newMembers = module.members.map: member =>
      val (newMember, errors) = analyzeMember(member, module.resolvables, returningOwned)
      allErrors = allErrors ++ errors
      newMember

    val newModule = module.copy(members = newMembers)
    state.withModule(newModule).addErrors(allErrors)
