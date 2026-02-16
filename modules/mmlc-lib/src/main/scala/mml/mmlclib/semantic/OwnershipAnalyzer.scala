package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Ownership state for a binding */
enum OwnershipState derives CanEqual:
  case Owned // Caller owns the value, must free it
  case Moved // Value has been moved to another owner
  case Borrowed // Borrowed reference, caller does not own
  case Literal // Literal value, no ownership tracking needed

/** Binding info: ownership state, type, ID for selecting __free_T, and optional witness boolean */
case class BindingInfo(
  state:      OwnershipState,
  bindingTpe: Option[Type]   = None,
  bindingId:  Option[String] = None,
  witness:    Option[String] = None // Name of __owns_<binding> if mixed ownership
)

/** Tracks ownership for bindings within a scope */
case class OwnershipScope(
  bindings:               Map[String, BindingInfo]    = Map.empty,
  movedAt:                Map[String, SrcSpan]        = Map.empty,
  resolvables:            ResolvablesIndex,
  returningOwned:         Map[String, Option[Type]]   = Map.empty,
  tempCounter:            Int                         = 0,
  insideTempWrapper:      Boolean                     = false,
  consumedVia:            Map[String, (Ref, FnParam)] = Map.empty,
  skipConsumingOwnership: Boolean                     = false
):

  def nextTemp: (String, OwnershipScope) =
    (s"__tmp_$tempCounter", copy(tempCounter = tempCounter + 1))

  def withOwned(name: String, tpe: Option[Type], id: Option[String] = None): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Owned, tpe, id)))

  def withMixedOwnership(
    name:        String,
    tpe:         Option[Type],
    witnessName: String,
    id:          Option[String] = None
  ): OwnershipScope =
    copy(bindings =
      bindings + (name -> BindingInfo(OwnershipState.Owned, tpe, id, Some(witnessName)))
    )

  def getWitness(name: String): Option[String] =
    bindings.get(name).flatMap(_.witness)

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

  /** Get all owned bindings that need to be freed, with their types, IDs, and witnesses */
  def ownedBindings: List[(String, Option[Type], Option[String], Option[String])] =
    bindings
      .collect:
        case (name, BindingInfo(OwnershipState.Owned, tpe, id, witness)) =>
          (name, tpe, id, witness)
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

  private val UnitTypeId = "stdlib::typedef::Unit"
  private val BoolTypeId = "stdlib::typedef::Bool"

  private def unitTypeRef(span: SrcSpan): TypeRef =
    TypeRef(span, "Unit", Some(UnitTypeId), Nil)

  private def boolTypeRef(span: SrcSpan): TypeRef =
    TypeRef(span, "Bool", Some(BoolTypeId), Nil)

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
                .filter(bndAllocates(_, resolvables))
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
              exprReturnsOwned(lambda.body, bodyEnv, resolvables, returningOwned).orElse(argOwned)
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
            val consumingEnv: Map[String, Option[Type]] = lambda.params
              .filter(_.consuming)
              .flatMap(p => p.typeSpec.orElse(p.typeAsc).map(p.name -> Some(_)))
              .toMap
            val resultOwned =
              exprReturnsOwned(lambda.body, consumingEnv, resolvables, returningOwned)
                .filter(t => getTypeName(t).exists(isHeapType(_, resolvables)))

            val current = returningOwned.get(id).flatten
            if resultOwned.isDefined && current != resultOwned then
              returningOwned = returningOwned.updated(id, resultOwned)
              changed        = true
          }
        }

      returningOwned

  // Delegate to TypeUtils for type queries
  def getTypeName(t: Type): Option[String] = TypeUtils.getTypeName(t)
  def isHeapType(typeName: String, resolvables: ResolvablesIndex): Boolean =
    TypeUtils.isHeapType(typeName, resolvables)
  def hasHeapFields(struct: TypeStruct, resolvables: ResolvablesIndex): Boolean =
    TypeUtils.hasHeapFields(struct, resolvables)
  def freeFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    TypeUtils.freeFnFor(typeName, resolvables)
  def cloneFnFor(typeName: String, resolvables: ResolvablesIndex): Option[String] =
    TypeUtils.cloneFnFor(typeName, resolvables)

  /** Check if a Bnd has memory effect Alloc (native allocator or struct constructor with heap
    * fields)
    */
  def bndAllocates(bnd: Bnd, resolvables: ResolvablesIndex): Boolean =
    bnd.value.terms
      .collectFirst:
        case lambda: Lambda =>
          lambda.body.terms.exists:
            case NativeImpl(_, _, _, _, Some(MemEffect.Alloc)) => true
            case DataConstructor(_, Some(returnType)) =>
              // Struct with heap fields needs cleanup
              // isHeapType for TypeStruct delegates to hasHeapFields
              getTypeName(returnType).exists(isHeapType(_, resolvables))
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
            .filter(bndAllocates(_, resolvables))
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

  /** Look up the resolved ID for a free function by name */
  private def lookupFreeFnId(freeFn: String, resolvables: ResolvablesIndex): Option[String] =
    // Try stdlib first, then search resolvables for user-defined free functions
    val stdlibId = s"stdlib::bnd::$freeFn"
    if resolvables.lookup(stdlibId).isDefined then Some(stdlibId)
    else
      // Search for a binding with this name in resolvables
      resolvables.resolvables.collectFirst:
        case (id, bnd: Bnd) if bnd.name == freeFn => id

  /** Look up the resolved ID for a clone function by name.
    *
    * For user-defined structs, prefer the generated module-local clone function. For native/stdlib
    * types, keep preferring stdlib clone symbols.
    */
  private def lookupCloneFnId(
    cloneFn:     String,
    typeName:    String,
    resolvables: ResolvablesIndex
  ): Option[String] =
    val stdlibId = s"stdlib::bnd::$cloneFn"
    val userCloneId = resolvables.resolvables.collectFirst:
      case (id, bnd: Bnd) if bnd.name == cloneFn && !id.startsWith("stdlib::") => id

    if TypeUtils.isStructWithHeapFields(typeName, resolvables) then
      userCloneId.orElse:
        if resolvables.lookup(stdlibId).isDefined then Some(stdlibId) else None
    else if resolvables.lookup(stdlibId).isDefined then Some(stdlibId)
    else userCloneId

  /** Create a free call: App(Ref("__free_T"), Ref(binding)). Returns None when no free function
    * exists for the type.
    */
  private def mkFreeCall(
    bindingName: String,
    tpe:         Type,
    span:        SrcSpan,
    bindingId:   Option[String],
    resolvables: ResolvablesIndex
  ): Option[App] =
    val typeName = getTypeName(tpe)
    typeName.flatMap(freeFnFor(_, resolvables)).map { freeFn =>
      val freeFnId = lookupFreeFnId(freeFn, resolvables)
      val unitType: Option[Type] = Some(unitTypeRef(span))
      val fnType  = Some(TypeFn(span, List(tpe), unitType.get))
      val fnRef   = Ref(span, freeFn, resolvedId = freeFnId, typeSpec = fnType)
      val argRef  = Ref(span, bindingName, resolvedId = bindingId, typeSpec = Some(tpe))
      val argExpr = Expr(span, List(argRef), typeSpec = Some(tpe))
      App(span, fnRef, argExpr, typeSpec = unitType)
    }

  private enum ConditionalOwnership derives CanEqual:
    case AlwaysOwned(tpe: Type)
    case NeverOwned
    case MixedOwned(tpe: Type, witnessExpr: Expr)

  private def boolLiteralExpr(value: Boolean): Expr =
    val boolType = Some(boolTypeRef(syntheticSpan))
    Expr(syntheticSpan, List(LiteralBool(syntheticSpan, value)), typeSpec = boolType)

  private def mkBoolConditionalExpr(
    condExpr:    Expr,
    ifTrueExpr:  Expr,
    ifFalseExpr: Expr
  ): Expr =
    val boolType = Some(boolTypeRef(syntheticSpan))
    val condTerm = Cond(syntheticSpan, condExpr, ifTrueExpr, ifFalseExpr, boolType, boolType)
    Expr(syntheticSpan, List(condTerm), typeSpec = boolType)

  private def classifyConditionalOwnership(
    expr:  Expr,
    scope: OwnershipScope
  ): ConditionalOwnership =
    def classifyExpr(e: Expr): ConditionalOwnership =
      e.terms.lastOption match
        case Some(cond: Cond) =>
          classifyCond(cond)
        case _ =>
          exprAllocates(e, scope) match
            case Some(tpe) => ConditionalOwnership.AlwaysOwned(tpe)
            case None => ConditionalOwnership.NeverOwned

    def classifyCond(cond: Cond): ConditionalOwnership =
      val trueOwnership  = classifyExpr(cond.ifTrue)
      val falseOwnership = classifyExpr(cond.ifFalse)
      val boolTrue       = boolLiteralExpr(true)
      val boolFalse      = boolLiteralExpr(false)

      (trueOwnership, falseOwnership) match
        case (ConditionalOwnership.AlwaysOwned(t1), ConditionalOwnership.AlwaysOwned(t2)) =>
          ConditionalOwnership.AlwaysOwned(mergeAllocTypes(Some(t1), Some(t2)).getOrElse(t1))
        case (ConditionalOwnership.NeverOwned, ConditionalOwnership.NeverOwned) =>
          ConditionalOwnership.NeverOwned
        case (ConditionalOwnership.AlwaysOwned(tpe), ConditionalOwnership.NeverOwned) =>
          ConditionalOwnership.MixedOwned(
            tpe,
            mkBoolConditionalExpr(cond.cond, boolTrue, boolFalse)
          )
        case (ConditionalOwnership.NeverOwned, ConditionalOwnership.AlwaysOwned(tpe)) =>
          ConditionalOwnership.MixedOwned(
            tpe,
            mkBoolConditionalExpr(cond.cond, boolFalse, boolTrue)
          )
        case (ConditionalOwnership.MixedOwned(tpe, witness), ConditionalOwnership.NeverOwned) =>
          ConditionalOwnership.MixedOwned(
            tpe,
            mkBoolConditionalExpr(cond.cond, witness, boolFalse)
          )
        case (ConditionalOwnership.NeverOwned, ConditionalOwnership.MixedOwned(tpe, witness)) =>
          ConditionalOwnership.MixedOwned(
            tpe,
            mkBoolConditionalExpr(cond.cond, boolFalse, witness)
          )
        case (ConditionalOwnership.AlwaysOwned(t1), ConditionalOwnership.MixedOwned(t2, witness)) =>
          ConditionalOwnership.MixedOwned(
            mergeAllocTypes(Some(t1), Some(t2)).getOrElse(t1),
            mkBoolConditionalExpr(cond.cond, boolTrue, witness)
          )
        case (ConditionalOwnership.MixedOwned(t1, witness), ConditionalOwnership.AlwaysOwned(t2)) =>
          ConditionalOwnership.MixedOwned(
            mergeAllocTypes(Some(t1), Some(t2)).getOrElse(t1),
            mkBoolConditionalExpr(cond.cond, witness, boolTrue)
          )
        case (
              ConditionalOwnership.MixedOwned(t1, witnessTrue),
              ConditionalOwnership.MixedOwned(t2, witnessFalse)
            ) =>
          ConditionalOwnership.MixedOwned(
            mergeAllocTypes(Some(t1), Some(t2)).getOrElse(t1),
            mkBoolConditionalExpr(cond.cond, witnessTrue, witnessFalse)
          )

    classifyExpr(expr)

  /** Check if an expression has mixed ownership and return allocation type + witness expression. */
  private def detectMixedConditional(
    expr:  Expr,
    scope: OwnershipScope
  ): Option[(Type, Expr)] =
    classifyConditionalOwnership(expr, scope) match
      case ConditionalOwnership.MixedOwned(tpe, witnessExpr) => Some((tpe, witnessExpr))
      case _ => None

  /** Create a conditional free: if __owns_x then __free_T x else () */
  private def mkConditionalFree(
    bindingName: String,
    tpe:         Type,
    witnessName: String,
    span:        SrcSpan,
    bindingId:   Option[String],
    resolvables: ResolvablesIndex
  ): Option[Cond] =
    mkFreeCall(bindingName, tpe, span, bindingId, resolvables).map { freeCall =>
      val boolType = Some(boolTypeRef(span))
      val unitType = Some(unitTypeRef(span))

      val witnessRef  = Ref(span, witnessName, typeSpec = boolType)
      val witnessExpr = Expr(span, List(witnessRef), typeSpec = boolType)

      val freeCallExpr = Expr(span, List(freeCall), typeSpec = unitType)

      val unitLit     = LiteralUnit(span)
      val unitLitExpr = Expr(span, List(unitLit), typeSpec = unitType)

      Cond(span, witnessExpr, freeCallExpr, unitLitExpr, unitType, unitType)
    }

  /** Wrap an expression with CPS-style free calls. Transforms: `expr` into
    * `let __r = expr; let _ = free1; let _ = free2; __r`
    *
    * For bindings with witnesses, generates conditional free: `if __owns_x then __free_T x else ()`
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
    val unitType = Some(unitTypeRef(span))

    // Build the innermost expression: just the result reference
    val innermost = Expr(span, List(resultRef), typeSpec = resultType)

    // Fold free calls from right to left, building:
    // let _ = freeN; ... let _ = free1; __r
    // For bindings with witnesses, generate conditional free instead
    val withFrees = toFree.foldRight(innermost): (binding, acc) =>
      val (name, tpeOpt, id, witnessOpt) = binding
      val freeTermOpt: Option[Term] = tpeOpt.flatMap { tpe =>
        witnessOpt match
          case Some(witnessName) =>
            mkConditionalFree(name, tpe, witnessName, span, id, resolvables)
          case None =>
            mkFreeCall(name, tpe, span, id, resolvables)
      }
      freeTermOpt match
        case Some(freeTerm) =>
          val discardParam =
            FnParam(span, "_", typeSpec = unitType, typeAsc = unitType)
          val discardLam =
            Lambda(span, List(discardParam), acc, Nil, typeSpec = resultType)
          val freeAppExpr = Expr(span, List(freeTerm), typeSpec = unitType)
          Expr(span, List(App(span, discardLam, freeAppExpr, typeSpec = resultType)))
        case None => acc

    // Wrap with: let __r = expr; <withFrees>
    val resultLam = Lambda(span, List(resultParam), withFrees, Nil, typeSpec = resultType)
    Expr(span, List(App(span, resultLam, expr, typeSpec = resultType)), typeSpec = resultType)

  /** Wrap an expression with __clone_T call */
  private def wrapWithClone(
    expr:        Expr,
    tpe:         Type,
    resolvables: ResolvablesIndex
  ): Expr =
    val typeName    = getTypeName(tpe).getOrElse("String")
    val cloneFnName = cloneFnFor(typeName, resolvables).getOrElse(s"__clone_$typeName")
    val cloneFnId   = lookupCloneFnId(cloneFnName, typeName, resolvables)
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
            val cloned =
              wrapWithClone(ifFalse, returnType.getOrElse(ifFalse.typeSpec.get), scope.resolvables)
            val newCond = Cond(span, condExpr, ifTrue, cloned, typeSpec, typeAsc)
            expr.copy(terms = expr.terms.init :+ newCond)
          case (None, Some(_)) =>
            // False allocates, true is static - clone true branch
            val cloned =
              wrapWithClone(ifTrue, returnType.getOrElse(ifTrue.typeSpec.get), scope.resolvables)
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

  /** Refs of borrowed bindings that flow out through the returned expression */
  private def returnedBorrowedRefs(expr: Expr, scope: OwnershipScope): List[Ref] =
    def termReturned(term: Term): List[Ref] =
      term match
        case ref: Ref if scope.getState(ref.name).contains(OwnershipState.Borrowed) =>
          List(ref)
        case Cond(_, _, ifTrue, ifFalse, _, _) =>
          returnedBorrowedRefs(ifTrue, scope) ++ returnedBorrowedRefs(ifFalse, scope)
        case TermGroup(_, inner, _) => returnedBorrowedRefs(inner, scope)
        case _ => List.empty
    expr.terms.lastOption.map(termReturned).getOrElse(List.empty)

  /** Check if a binding name is referenced anywhere in an expression */
  private def containsRefInExpr(name: String, expr: Expr): Boolean =
    expr.terms.exists(containsRef(name, _))

  /** Check if a binding name is referenced anywhere in a term */
  private def containsRef(name: String, term: Term): Boolean =
    term match
      case ref: Ref => ref.name == name
      case App(_, fn, arg, _, _) => containsRef(name, fn) || containsRefInExpr(name, arg)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        containsRefInExpr(name, cond) || containsRefInExpr(name, ifTrue) ||
        containsRefInExpr(name, ifFalse)
      case TermGroup(_, inner, _) => containsRefInExpr(name, inner)
      case Tuple(_, elements, _, _) => elements.exists(containsRefInExpr(name, _))
      case Lambda(_, params, body, _, _, _, _) =>
        // Skip if a param shadows the name
        if params.exists(_.name == name) then false
        else containsRefInExpr(name, body)
      case expr: Expr => containsRefInExpr(name, expr)
      case _ => false

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
      case Some(consumingParam) =>
        // Get the ref being passed (if it's a simple ref)
        arg.terms.headOption match
          case Some(ref: Ref) =>
            // Check if it's owned - can only move owned values
            scope.getState(ref.name) match
              case Some(OwnershipState.Owned) =>
                // Valid move - mark as moved and record consuming info
                val newScope = scope
                  .withMoved(ref.name, ref.span)
                  .copy(consumedVia = scope.consumedVia + (ref.name -> (ref, consumingParam)))
                (newScope, Nil)
              case Some(OwnershipState.Moved) =>
                // Already moved - use after move error
                val errors = scope.getMovedAt(ref.name) match
                  case Some(movedAt) =>
                    List(SemanticError.UseAfterMove(ref, movedAt, PhaseName))
                  case None => Nil
                (scope, errors)
              case Some(OwnershipState.Borrowed) =>
                // Borrowed refs cannot satisfy consuming params.
                if scope.insideTempWrapper then (scope, Nil)
                else
                  (
                    scope,
                    List(SemanticError.ConsumingParamNotLastUse(consumingParam, ref, PhaseName))
                  )
              case _ =>
                // Passing a literal or untracked expression to consuming param - allowed
                (scope, Nil)
          case _ =>
            // Complex expression - can't track ownership
            (scope, Nil)
      case None =>
        (scope, Nil)

  /** Check if rebinding a name should be a move (not a borrow). True when the source binding is
    * Owned, has a resolved type, no witness (skip mixed-ownership), and the type is a user-defined
    * struct with heap fields.
    */
  private def isMoveOnRebind(name: String, scope: OwnershipScope): Boolean =
    scope.getInfo(name) match
      case Some(BindingInfo(OwnershipState.Owned, Some(tpe), _, None)) =>
        TypeUtils.getTypeName(tpe).exists(TypeUtils.isStructWithHeapFields(_, scope.resolvables))
      case _ => false

  /** Check if a function call resolves to a struct constructor */
  private def isConstructorCall(
    fn:          Ref | App | Lambda,
    resolvables: ResolvablesIndex
  ): Boolean =
    getBaseFn(fn)
      .flatMap(_.resolvedId)
      .flatMap(resolvables.lookup)
      .collect { case bnd: Bnd => bnd }
      .flatMap(_.value.terms.collectFirst { case l: Lambda => l })
      .exists(_.body.terms.exists(_.isInstanceOf[DataConstructor]))

  /** Check if an argument expression needs auto-cloning for a consuming constructor param. Returns
    * true for literal string values.
    */
  private def argNeedsClone(argExpr: Expr, scope: OwnershipScope): Boolean =
    argExpr.terms.headOption match
      case Some(_: LiteralString) => true
      case Some(ref: Ref) =>
        if ref.qualifier.isDefined then false
        else
          scope.getState(ref.name) match
            case Some(OwnershipState.Owned) => false // will be moved
            case Some(OwnershipState.Borrowed) | Some(OwnershipState.Literal) => false
            case _ => false
      case _ => false

  /** Check if an argument is a freshly allocating expression */
  private def argAllocates(argExpr: Expr, scope: OwnershipScope): Boolean =
    argExpr.terms.lastOption.flatMap(termAllocates(_, scope)).isDefined

  /** Check for use-after-move on a Ref */
  private def analyzeRef(ref: Ref, scope: OwnershipScope): TermResult =
    val nameToCheck = ref.qualifier match
      case Some(q: Ref) => q.name
      case _ => ref.name
    scope.getState(nameToCheck) match
      case Some(OwnershipState.Moved) =>
        scope.getMovedAt(nameToCheck) match
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

  /** Analyze a let-binding: App(Lambda(params, body), arg) */
  private def analyzeLetBinding(
    span:      SrcSpan,
    lSpan:     SrcSpan,
    params:    List[FnParam],
    body:      Expr,
    captures:  List[Ref],
    lTypeSpec: Option[Type],
    lTypeAsc:  Option[Type],
    meta:      Option[LambdaMeta],
    arg:       Expr,
    typeAsc:   Option[Type],
    typeSpec:  Option[Type],
    scope:     OwnershipScope
  ): TermResult =
    val argResult = analyzeExpr(arg, scope)

    // Forward-scan: check if any newly-moved bindings are still used in the body
    val newlyMoved = argResult.scope.movedAt.keySet -- scope.movedAt.keySet
    val lastUseErrors = newlyMoved.toList.flatMap: name =>
      if containsRefInExpr(name, body) then
        argResult.scope.consumedVia
          .get(name)
          .map: (ref, param) =>
            SemanticError.ConsumingParamNotLastUse(param, ref, PhaseName)
      else Nil

    // Track bindings that already belonged to the outer scope so we don't free them
    // inside this CPS wrapper. Only bindings created in this let should be freed here.
    val inheritedOwned: Set[String] =
      scope.ownedBindings.map(_._1).toSet

    val allocType = arg.terms.headOption.flatMap(termAllocates(_, scope))
    val mixedCond = detectMixedConditional(arg, scope)

    // Set up scope for lambda body - handle mixed conditionals specially
    val (bodyScope, witnessOpt) = params.headOption match
      case Some(param) if mixedCond.isDefined =>
        val (allocTpe, witnessExpr) = mixedCond.get
        val witnessName             = s"__owns_${param.name}"
        val boolType                = Some(boolTypeRef(syntheticSpan))
        val scopeWithWitness = argResult.scope
          .withMixedOwnership(param.name, Some(allocTpe), witnessName, param.id)
          .withLiteral(witnessName)
        (scopeWithWitness, Some((witnessName, witnessExpr, boolType)))
      case Some(param) if allocType.isDefined =>
        val paramTypeName =
          param.typeSpec.orElse(param.typeAsc).flatMap(getTypeName)
        val allocHeap =
          allocType.filter(t => getTypeName(t).exists(isHeapType(_, scope.resolvables)))
        val owns =
          allocHeap.filter(t => paramTypeName.forall(_ == getTypeName(t).getOrElse("")))
        val newScope = owns
          .map(t => argResult.scope.withOwned(param.name, Some(t), param.id))
          .getOrElse(argResult.scope.withBorrowed(param.name))
        (newScope, None)
      case Some(param) =>
        val newScope = arg.terms.headOption match
          case Some(_: LiteralString) =>
            argResult.scope.withLiteral(param.name)
          case Some(ref: Ref) if isMoveOnRebind(ref.name, argResult.scope) =>
            val srcInfo = argResult.scope.getInfo(ref.name).get
            argResult.scope
              .withMoved(ref.name, ref.span)
              .withOwned(param.name, srcInfo.bindingTpe, param.id)
          case _ =>
            argResult.scope.withBorrowed(param.name)
        (newScope, None)
      case None =>
        (argResult.scope, None)

    val bodyResult = analyzeExpr(body, bodyScope)
    val escaping   = returnedOwnedNames(bodyResult.expr, bodyResult.scope)

    // Free all owned bindings at terminal body
    val witnessBinding = witnessOpt.flatMap(_ => params.headOption.map(_.name))
    val bindingsToFree =
      bodyResult.scope.ownedBindings.filter:
        case (name, Some(tpe), _, _) =>
          !inheritedOwned.contains(name) &&
          !scope.insideTempWrapper &&
          !escaping.contains(name) &&
          !witnessBinding.contains(name) &&
          getTypeName(tpe).exists(isHeapType(_, scope.resolvables))
        case _ => false

    val bodyWithTerminalFrees =
      if bindingsToFree.isEmpty then bodyResult.expr
      else wrapWithFrees(bodyResult.expr, bindingsToFree, body.span, scope.resolvables)

    // If we have a witness, wrap the body with conditional free
    val newBody = witnessOpt match
      case Some((witnessName, _, _)) =>
        val bindingName = params.headOption.map(_.name).getOrElse("")
        val bindingType = params.headOption.flatMap(p => p.typeSpec.orElse(p.typeAsc))
        val bindingId   = params.headOption.flatMap(_.id)
        bindingType match
          case Some(tpe) if getTypeName(tpe).exists(isHeapType(_, scope.resolvables)) =>
            val toFree = List((bindingName, Some(tpe), bindingId, Some(witnessName)))
            wrapWithFrees(bodyWithTerminalFrees, toFree, body.span, scope.resolvables)
          case _ =>
            bodyWithTerminalFrees
      case None =>
        bodyWithTerminalFrees

    val newLambda = Lambda(lSpan, params, newBody, captures, lTypeSpec, lTypeAsc, meta)
    val innerApp  = App(span, newLambda, argResult.expr, typeAsc, typeSpec)

    val finalTerm = witnessOpt match
      case Some((witnessName, witnessExpr, boolType)) =>
        val witnessParam =
          FnParam(syntheticSpan, witnessName, typeSpec = boolType, typeAsc = boolType)
        val innerAppExpr = Expr(syntheticSpan, List(innerApp), typeSpec = typeSpec)
        val witnessLambda =
          Lambda(syntheticSpan, List(witnessParam), innerAppExpr, Nil, typeSpec = typeSpec)
        App(syntheticSpan, witnessLambda, witnessExpr, typeSpec = typeSpec)
      case None =>
        innerApp

    // Propagate moves of inherited bindings back to the outer scope
    val returnScope = inheritedOwned.foldLeft(scope) { (s, name) =>
      val movedInArg  = argResult.scope.getState(name).contains(OwnershipState.Moved)
      val movedInBody = bodyResult.scope.getState(name).contains(OwnershipState.Moved)
      if movedInArg || movedInBody then
        val span = argResult.scope
          .getMovedAt(name)
          .orElse(bodyResult.scope.getMovedAt(name))
          .getOrElse(syntheticSpan)
        s.withMoved(name, span)
      else s
    }

    TermResult(
      returnScope,
      finalTerm,
      errors = argResult.errors ++ bodyResult.errors ++ lastUseErrors
    )

  /** Collect all args and base function from a curried App chain */
  private def collectArgsAndBase(
    t:    Ref | App | Lambda,
    args: List[(Expr, SrcSpan, Option[Type], Option[Type])]
  ): (Ref | Lambda, List[(Expr, SrcSpan, Option[Type], Option[Type])]) =
    t match
      case ref:    Ref => (ref, args)
      case lambda: Lambda => (lambda, args)
      case App(s, inner, a, tAsc, tSpec) =>
        collectArgsAndBase(inner, (a, s, tAsc, tSpec) :: args)

  /** Analyze a regular function application (not a let-binding) */
  private def analyzeRegularApp(
    span:     SrcSpan,
    fn:       Ref | App | Lambda,
    arg:      Expr,
    typeAsc:  Option[Type],
    typeSpec: Option[Type],
    scope:    OwnershipScope
  ): TermResult =
    val (baseFn, allArgsWithMeta) =
      collectArgsAndBase(fn, List((arg, span, typeAsc, typeSpec)))

    // Resolve base function's param list to detect consuming params
    val baseFnParams: List[FnParam] = getBaseFn(baseFn)
      .flatMap(_.resolvedId)
      .flatMap(scope.resolvables.lookup)
      .collect { case bnd: Bnd => bnd }
      .flatMap(_.value.terms.collectFirst { case l: Lambda => l })
      .map(_.params)
      .getOrElse(Nil)

    // Auto-clone non-owned args passed to consuming constructor params
    val processedArgs =
      if isConstructorCall(baseFn, scope.resolvables) then
        allArgsWithMeta.zipWithIndex.map { case ((argExpr, s, tAsc, tSpec), idx) =>
          val param = baseFnParams.lift(idx)
          val needsClone = param.exists(_.consuming) &&
            !argAllocates(argExpr, scope) &&
            argNeedsClone(argExpr, scope)
          if needsClone then
            val paramType =
              param.flatMap(_.typeAsc).getOrElse(argExpr.typeSpec.get)
            (wrapWithClone(argExpr, paramType, scope.resolvables), s, tAsc, tSpec)
          else (argExpr, s, tAsc, tSpec)
        }
      else allArgsWithMeta

    // Check which args allocate (in order from first to last)
    val argsWithAlloc = processedArgs.zipWithIndex.map { case ((argExpr, s, tAsc, tSpec), idx) =>
      val allocType  = argExpr.terms.lastOption.flatMap(termAllocates(_, scope))
      val isConsumed = baseFnParams.lift(idx).exists(_.consuming)
      (argExpr, s, tAsc, tSpec, allocType, isConsumed)
    }

    val allocatingArgs = argsWithAlloc.filter(_._5.isDefined)

    if allocatingArgs.isEmpty || scope.insideTempWrapper then
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
    else analyzeAllocatingApp(baseFn, argsWithAlloc, typeSpec, scope)

  /** Handle App with allocating args: create temp bindings and explicit free calls */
  private def analyzeAllocatingApp(
    baseFn:        Ref | Lambda,
    argsWithAlloc: List[(Expr, SrcSpan, Option[Type], Option[Type], Option[Type], Boolean)],
    typeSpec:      Option[Type],
    scope:         OwnershipScope
  ): TermResult =
    // Analyze allocating args BEFORE wrapping to prevent infinite recursion
    type TempInfo = (String, Expr, Type, Boolean)
    type ArgEntry =
      (Expr, SrcSpan, Option[Type], Option[Type], Option[TempInfo])

    val (finalScope, argErrors, tempsAndArgs) =
      argsWithAlloc.foldLeft(
        (scope, List.empty[SemanticError], Vector.empty[ArgEntry])
      ) { case ((curScope, errs, acc), (argExpr, s, tAsc, tSpec, allocOpt, consumed)) =>
        allocOpt match
          case Some(allocType) =>
            val argResult           = analyzeExpr(argExpr, curScope)
            val (tmpName, newScope) = curScope.nextTemp
            val tmpRef              = Ref(syntheticSpan, tmpName, typeSpec = Some(allocType))
            val tmpRefExpr =
              Expr(syntheticSpan, List(tmpRef), typeSpec = Some(allocType))
            (
              newScope,
              errs ++ argResult.errors,
              acc :+ (
                tmpRefExpr,
                s,
                tAsc,
                tSpec,
                Some((tmpName, argResult.expr, allocType, consumed))
              )
            )
          case None =>
            (curScope, errs, acc :+ (argExpr, s, tAsc, tSpec, None))
      }

    // Build the clean App chain using temps where needed
    val innerApp = tempsAndArgs.foldLeft(baseFn: Ref | App | Lambda) {
      case (accFn, (argExpr, s, tAsc, tSpec, _)) =>
        App(s, accFn, argExpr, tAsc, tSpec)
    }

    // Collect allocating bindings (reverse order for proper nesting of let-bindings)
    val allBindings   = tempsAndArgs.flatMap(_._5).reverse
    val allocBindings = allBindings.map(b => (b._1, b._2, b._3))

    val finalInnerBody = Expr(syntheticSpan, List(innerApp), typeSpec = typeSpec)

    // Build structure with explicit free calls:
    // let tmp0 = arg0; let tmp1 = arg1; let result = inner; free tmp1; free tmp0; result
    val resultType = typeSpec
    val resultName = "__tmp_result"
    val resultRef  = Ref(syntheticSpan, resultName, typeSpec = resultType)

    // Free calls for temps not consumed by the callee
    val nonConsumedBindings = allBindings.filterNot(_._4)
    val freeCalls = nonConsumedBindings.flatMap { case (tmpName, _, allocType, _) =>
      mkFreeCall(tmpName, allocType, syntheticSpan, None, scope.resolvables)
    }

    val innermost = Expr(syntheticSpan, List(resultRef), typeSpec = resultType)

    val unitType = Some(unitTypeRef(syntheticSpan))
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

    val resultParam = FnParam(syntheticSpan, resultName, typeSpec = resultType)
    val resultLam =
      Lambda(syntheticSpan, List(resultParam), withFrees, Nil, typeSpec = resultType)
    val withResult = Expr(
      syntheticSpan,
      List(App(syntheticSpan, resultLam, finalInnerBody, typeSpec = resultType)),
      typeSpec = resultType
    )

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

    // Mark inherited owned bindings as Borrowed inside the temp wrapper
    val borrowedScope = scope.ownedBindings
      .foldLeft(finalScope) { case (s, (name, _, _, _)) => s.withBorrowed(name) }
      .copy(insideTempWrapper = true)

    // Non-allocating args consumed by constructor params must be marked as Moved
    // in the outer scope. The temp wrapper marks them Borrowed internally, so
    // handleConsumingParam won't see them as owned during re-analysis.
    val consumedMoves: List[(String, SrcSpan)] = argsWithAlloc
      .collect:
        case (argExpr, _, _, _, None, true) =>
          argExpr.terms.headOption.collect:
            case ref: Ref if scope.getState(ref.name).contains(OwnershipState.Owned) =>
              (ref.name, ref.span)
      .flatten

    val returnScope = consumedMoves.foldLeft(scope) { case (s, (name, span)) =>
      s.withMoved(name, span)
    }

    wrappedExpr.terms match
      case List(wrappedApp: App) =>
        val result = analyzeTerm(wrappedApp, borrowedScope)
        TermResult(returnScope, result.term, argErrors ++ result.errors)
      case _ =>
        val result = analyzeExpr(wrappedExpr, borrowedScope)
        TermResult(returnScope, wrappedExpr.terms.head, argErrors ++ result.errors)

  /** Analyze a conditional expression */
  private def analyzeCond(
    span:     SrcSpan,
    condExpr: Expr,
    ifTrue:   Expr,
    ifFalse:  Expr,
    typeSpec: Option[Type],
    typeAsc:  Option[Type],
    scope:    OwnershipScope
  ): TermResult =
    val condResult  = analyzeExpr(condExpr, scope)
    val trueResult  = analyzeExpr(ifTrue, condResult.scope)
    val falseResult = analyzeExpr(ifFalse, condResult.scope)

    val outerOwnedBindings = condResult.scope.bindings.collect {
      case (name, info @ BindingInfo(OwnershipState.Owned, _, _, _)) => (name, info)
    }

    val freesInTrueBranch = outerOwnedBindings.toList.flatMap { case (name, info) =>
      val trueState  = trueResult.scope.getState(name).getOrElse(info.state)
      val falseState = falseResult.scope.getState(name).getOrElse(info.state)
      val isHeap = info.bindingTpe
        .flatMap(getTypeName)
        .exists(isHeapType(_, scope.resolvables))
      (trueState, falseState) match
        case (OwnershipState.Owned, OwnershipState.Moved) if isHeap =>
          Some((name, info.bindingTpe, info.bindingId, None: Option[String]))
        case _ =>
          None
    }

    val freesInFalseBranch = outerOwnedBindings.toList.flatMap { case (name, info) =>
      val trueState  = trueResult.scope.getState(name).getOrElse(info.state)
      val falseState = falseResult.scope.getState(name).getOrElse(info.state)
      val isHeap = info.bindingTpe
        .flatMap(getTypeName)
        .exists(isHeapType(_, scope.resolvables))
      (trueState, falseState) match
        case (OwnershipState.Moved, OwnershipState.Owned) if isHeap =>
          Some((name, info.bindingTpe, info.bindingId, None: Option[String]))
        case _ =>
          None
    }

    val mergedScope = outerOwnedBindings.toList.foldLeft(condResult.scope) {
      case (acc, (name, info)) =>
        val trueState  = trueResult.scope.getState(name).getOrElse(info.state)
        val falseState = falseResult.scope.getState(name).getOrElse(info.state)
        (trueState, falseState) match
          case (OwnershipState.Moved, OwnershipState.Moved) =>
            val movedAt = trueResult.scope
              .getMovedAt(name)
              .orElse(falseResult.scope.getMovedAt(name))
              .getOrElse(syntheticSpan)
            acc.withMoved(name, movedAt)
          case (OwnershipState.Moved, OwnershipState.Owned) =>
            val movedAt = trueResult.scope.getMovedAt(name).getOrElse(syntheticSpan)
            acc.withMoved(name, movedAt)
          case (OwnershipState.Owned, OwnershipState.Moved) =>
            val movedAt = falseResult.scope.getMovedAt(name).getOrElse(syntheticSpan)
            acc.withMoved(name, movedAt)
          case _ =>
            acc
    }

    val mergedTrueExpr =
      if freesInTrueBranch.isEmpty then trueResult.expr
      else wrapWithFrees(trueResult.expr, freesInTrueBranch, ifTrue.span, scope.resolvables)

    val mergedFalseExpr =
      if freesInFalseBranch.isEmpty then falseResult.expr
      else wrapWithFrees(falseResult.expr, freesInFalseBranch, ifFalse.span, scope.resolvables)

    TermResult(
      mergedScope,
      Cond(span, condResult.expr, mergedTrueExpr, mergedFalseExpr, typeSpec, typeAsc),
      errors = condResult.errors ++ trueResult.errors ++ falseResult.errors
    )

  /** Analyze a standalone lambda definition */
  private def analyzeLambda(
    span:     SrcSpan,
    params:   List[FnParam],
    body:     Expr,
    captures: List[Ref],
    typeSpec: Option[Type],
    typeAsc:  Option[Type],
    meta:     Option[LambdaMeta],
    scope:    OwnershipScope
  ): TermResult =
    // Consuming params are Owned so they get freed at body end,
    // unless skipConsumingOwnership is set (for destructor/constructor functions)
    val paramScope = params.foldLeft(scope): (s, p) =>
      if p.consuming && !scope.skipConsumingOwnership then
        s.withOwned(p.name, p.typeSpec.orElse(p.typeAsc), p.id)
      else if p.consuming then s
      else s.withBorrowed(p.name)

    val bodyResult = analyzeExpr(body, paramScope)

    val returnType   = typeAsc.orElse(typeSpec)
    val promotedBody = promoteStaticBranchesInReturn(bodyResult.expr, returnType, paramScope)

    // Insert frees for consuming params that are still Owned (not returned, not moved)
    val escaping = returnedOwnedNames(promotedBody, bodyResult.scope)
    val consumingToFree = params.filter(_.consuming).flatMap { p =>
      val pType = p.typeSpec.orElse(p.typeAsc)
      if !scope.skipConsumingOwnership &&
        pType.flatMap(getTypeName).exists(isHeapType(_, scope.resolvables)) &&
        !escaping.contains(p.name) &&
        !bodyResult.scope.getState(p.name).contains(OwnershipState.Moved)
      then Some((p.name, pType, p.id, None: Option[String]))
      else None
    }
    val finalBody =
      if consumingToFree.isEmpty then promotedBody
      else wrapWithFrees(promotedBody, consumingToFree, body.span, scope.resolvables)

    val borrowEscapeErrors = returnType
      .flatMap(getTypeName)
      .filter(isHeapType(_, scope.resolvables))
      .map(_ => returnedBorrowedRefs(finalBody, bodyResult.scope))
      .getOrElse(Nil)
      .map(ref => SemanticError.BorrowEscapeViaReturn(ref, PhaseName))

    TermResult(
      scope,
      Lambda(span, params, finalBody, captures, typeSpec, typeAsc, meta),
      errors = bodyResult.errors ++ borrowEscapeErrors
    )

  /** Analyze a tuple expression */
  private def analyzeTuple(
    span:     SrcSpan,
    elements: cats.data.NonEmptyList[Expr],
    typeAsc:  Option[Type],
    typeSpec: Option[Type],
    scope:    OwnershipScope
  ): TermResult =
    val (finalScope, errors, newElements) =
      elements.toList.foldLeft((scope, List.empty[SemanticError], Vector.empty[Expr])) {
        case ((curScope, errs, acc), elem) =>
          val result = analyzeExpr(elem, curScope)
          (result.scope, errs ++ result.errors, acc :+ result.expr)
      }
    val nel = cats.data.NonEmptyList.fromListUnsafe(newElements.toList)
    TermResult(finalScope, Tuple(span, nel, typeAsc, typeSpec), errors = errors)

  /** Analyze a term and track ownership changes */
  private def analyzeTerm(
    term:  Term,
    scope: OwnershipScope
  ): TermResult =
    term match
      case ref: Ref =>
        analyzeRef(ref, scope)

      case App(span, fn: Lambda, arg, typeAsc, typeSpec) =>
        analyzeLetBinding(
          span,
          fn.span,
          fn.params,
          fn.body,
          fn.captures,
          fn.typeSpec,
          fn.typeAsc,
          fn.meta,
          arg,
          typeAsc,
          typeSpec,
          scope
        )

      case App(span, fn, arg, typeAsc, typeSpec) =>
        analyzeRegularApp(span, fn, arg, typeAsc, typeSpec, scope)

      case Cond(span, condExpr, ifTrue, ifFalse, typeSpec, typeAsc) =>
        analyzeCond(span, condExpr, ifTrue, ifFalse, typeSpec, typeAsc, scope)

      case Lambda(span, params, body, captures, typeSpec, typeAsc, meta) =>
        analyzeLambda(span, params, body, captures, typeSpec, typeAsc, meta, scope)

      case expr: Expr =>
        val result = analyzeExpr(expr, scope)
        TermResult(result.scope, result.expr, result.errors)

      case TermGroup(span, inner, typeAsc) =>
        val result = analyzeExpr(inner, scope)
        TermResult(result.scope, TermGroup(span, result.expr, typeAsc), errors = result.errors)

      case Tuple(span, elements, typeAsc, typeSpec) =>
        analyzeTuple(span, elements, typeAsc, typeSpec, scope)

      case lit: LiteralValue =>
        TermResult(scope, lit)

      case other =>
        TermResult(scope, other)

  /** Analyze an expression */
  private def analyzeExpr(
    expr:  Expr,
    scope: OwnershipScope
  ): ExprResult =
    val (finalScope, revTerms, errors) =
      expr.terms.foldLeft((scope, List.empty[Term], List.empty[SemanticError])):
        case ((s, ts, errs), term) =>
          val result = analyzeTerm(term, s)
          (result.scope, result.term :: ts, errs ++ result.errors)

    ExprResult(
      finalScope,
      expr.copy(terms = revTerms.reverse),
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
        val hasNativeBody = value.terms.exists:
          case l: Lambda => l.body.terms.exists(_.isInstanceOf[NativeImpl])
          case _ => false
        val origin        = meta.map(_.origin)
        val isDestructor  = origin.contains(BindingOrigin.Destructor)
        val isConstructor = origin.contains(BindingOrigin.Constructor)
        val skipConsuming = isDestructor || isConstructor || hasNativeBody
        val scope = OwnershipScope(
          resolvables            = resolvables,
          returningOwned         = returningOwned,
          skipConsumingOwnership = skipConsuming
        )
        val result = analyzeExpr(value, scope)

        // Final cleanup: free any owned bindings that remain in scope and do not escape.
        // This covers cases where nested CPS wrappers skipped frees for inherited bindings.
        val escapingFinal = returnedOwnedNames(result.expr, result.scope)
        val finalToFree = result.scope.ownedBindings.collect {
          case (name, Some(tpe), id, witness)
              if !escapingFinal.contains(name) &&
                getTypeName(tpe).exists(isHeapType(_, resolvables)) =>
            (name, Some(tpe), id, witness)
        }

        val cleanedValue =
          if finalToFree.isEmpty then result.expr
          else wrapWithFrees(result.expr, finalToFree, value.span, resolvables)

        (bnd.copy(value = cleanedValue), result.errors)

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
