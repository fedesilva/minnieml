package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import scala.annotation.tailrec

/** Ownership state for a binding */
enum OwnershipState derives CanEqual:
  case Owned // Caller owns the value, must free it
  case Moved // Value has been moved to another owner
  case Borrowed // Borrowed reference, caller does not own
  case Literal // Literal value, no ownership tracking needed
  case Global // Top-level binding, borrow-only

/** Binding info: ownership state, type, ID for selecting __free_T, and optional witness boolean */
case class BindingInfo(
  state:              OwnershipState,
  bindingTpe:         Option[Type]   = None,
  bindingId:          Option[String] = None,
  witness:            Option[String] = None, // Name of __owns_<binding> if mixed ownership
  destructorTargetId: Option[String] = None // Resolved closure destructor target
)

case class OwnedBinding(
  name:               String,
  tpe:                Option[Type],
  id:                 Option[String],
  witness:            Option[String],
  destructorTargetId: Option[String]
)

/** An aggregate projection is identified by its owner and resolved field identities. */
case class OwnershipPath(ownerId: String, fields: List[String]):
  def overlaps(other: OwnershipPath): Boolean =
    ownerId == other.ownerId &&
      (fields.startsWith(other.fields) || other.fields.startsWith(fields))

/** Callable flow and return analysis share immutable lambda instances from one module snapshot. */
final class CallableIdentity(val lambda: Lambda):
  override def hashCode(): Int = System.identityHashCode(lambda)
  override def equals(other: Any): Boolean = other match
    case key: CallableIdentity => lambda eq key.lambda
    case _ => false

/** Tracks ownership for bindings within a scope */
case class OwnershipScope(
  bindings:               Map[String, BindingInfo]            = Map.empty,
  movedAt:                Map[String, SourceOrigin]           = Map.empty,
  syntheticOwner:         SyntheticOwner,
  resolvables:            ResolvablesIndex,
  returningOwned:         Map[CallableIdentity, Option[Type]] = Map.empty,
  tempCounter:            Int                                 = 0,
  consumedVia:            Map[String, (Ref, FnParam)]         = Map.empty,
  skipConsumingOwnership: Boolean                             = false,
  borrowedDependencies:   Map[String, List[Ref]]              = Map.empty,
  movedBindingIds:        Map[String, SourceOrigin]           = Map.empty,
  callableValues:         CallableValues                      = CallableValues.empty,
  fieldAliases:           Map[String, OwnershipPath]          = Map.empty,
  consumedFields:         Map[OwnershipPath, SourceOrigin]    = Map.empty
):

  def nextTemp: (String, OwnershipScope) =
    (s"__tmp_$tempCounter", copy(tempCounter = tempCounter + 1))

  def withOwned(
    name:               String,
    tpe:                Option[Type],
    id:                 Option[String] = None,
    destructorTargetId: Option[String] = None
  ): OwnershipScope =
    copy(bindings =
      bindings + (name -> BindingInfo(
        OwnershipState.Owned,
        tpe,
        id,
        destructorTargetId = destructorTargetId
      ))
    )

  def withOwnedClosure(
    name:               String,
    tpe:                Option[Type],
    id:                 Option[String],
    destructorTargetId: String
  ): OwnershipScope =
    copy(bindings =
      bindings + (name -> BindingInfo(
        OwnershipState.Owned,
        tpe,
        id,
        destructorTargetId = Some(destructorTargetId)
      ))
    )

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

  def withMoved(name: String, source: SourceOrigin): OwnershipScope =
    val existing = bindings.get(name)
    copy(
      bindings = bindings + (name -> existing.fold(BindingInfo(OwnershipState.Moved))(
        _.copy(state = OwnershipState.Moved)
      )),
      movedAt         = movedAt + (name -> source),
      movedBindingIds = movedBindingIds ++ existing.flatMap(_.bindingId).map(_ -> source)
    )

  def withBorrowed(name: String): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Borrowed)))

  def withLiteral(name: String): OwnershipScope =
    copy(bindings = bindings + (name -> BindingInfo(OwnershipState.Literal)))

  def getState(name: String): Option[OwnershipState] = bindings.get(name).map(_.state)

  def getInfo(name: String): Option[BindingInfo] = bindings.get(name)

  def getMovedAt(name: String): Option[SourceOrigin] = movedAt.get(name)

  /** Get all owned bindings that need to be freed, with their types, IDs, and witnesses */
  def ownedBindings: List[OwnedBinding] =
    bindings
      .collect:
        case (name, BindingInfo(OwnershipState.Owned, tpe, id, witness, destructorTargetId)) =>
          OwnedBinding(name, tpe, id, witness, destructorTargetId)
      .toList

/** Result of analyzing an expression */
case class ExprResult(
  scope:  OwnershipScope,
  expr:   Expr,
  errors: List[SemanticError] = Nil
)

/** Result of analyzing a term */
case class TermResult[+T <: Term](
  scope:  OwnershipScope,
  term:   T,
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

  private val syntheticSource = SourceOrigin.Synth

  private val UnitTypeId = "stdlib::typedef::Unit"
  private val BoolTypeId = "stdlib::typedef::Bool"

  private def unitTypeRef(source: SourceOrigin): TypeRef =
    TypeRef(source, "Unit", Some(UnitTypeId), Nil)

  private def boolTypeRef(source: SourceOrigin): TypeRef =
    TypeRef(source, "Bool", Some(BoolTypeId), Nil)

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
      values:         CallableValues,
      returningOwned: Map[CallableIdentity, Option[Type]]
    ): Option[Type] =
      getBaseFn(app.fn).toList
        .flatMap(values.lambdas)
        .flatMap(lambda => returningOwned.get(CallableIdentity(lambda)).flatten)
        .headOption

    private def termReturnsOwned(
      term:           Term,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      values:         CallableValues,
      returningOwned: Map[CallableIdentity, Option[Type]]
    ): Option[Type] =
      term match
        case ref: Ref => env.get(ref.name).flatten
        case app: App =>
          app.fn match
            case lambda: Lambda =>
              val argOwned  = argReturnsOwned(app.arg, env, resolvables, values, returningOwned)
              val paramName = lambda.params.headOption.map(_.name)
              val bodyEnv =
                paramName.map(n => env + (n -> argOwned)).getOrElse(env)
              exprReturnsOwned(lambda.body, bodyEnv, resolvables, values, returningOwned)
            case _ =>
              appReturnsOwned(app, values, returningOwned)
        case cond: Cond =>
          merge(
            exprReturnsOwned(cond.ifTrue, env, resolvables, values, returningOwned),
            exprReturnsOwned(cond.ifFalse, env, resolvables, values, returningOwned)
          )
        case TermGroup(_, inner, _) =>
          exprReturnsOwned(inner, env, resolvables, values, returningOwned)
        case lambda: Lambda
            if lambda.isMove &&
              (lambda.captures.nonEmpty || lambda.meta.exists(_.isPartialApplication)) =>
          lambda.typeSpec
        case native: NativeImpl if native.memEffect.contains(MemEffect.Alloc) => native.typeSpec
        case constructor: DataConstructor =>
          constructor.typeSpec.filter(isOwnedType(_, resolvables))
        case _ => None

    private def argReturnsOwned(
      expr:           Expr,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      values:         CallableValues,
      returningOwned: Map[CallableIdentity, Option[Type]]
    ): Option[Type] =
      exprReturnsOwned(expr, env, resolvables, values, returningOwned)

    private def exprReturnsOwned(
      expr:           Expr,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      values:         CallableValues,
      returningOwned: Map[CallableIdentity, Option[Type]]
    ): Option[Type] =
      expr.terms.lastOption.flatMap(termReturnsOwned(_, env, resolvables, values, returningOwned))

    def discover(module: Module, values: CallableValues): Map[CallableIdentity, Option[Type]] =
      val resolvables = module.resolvables
      val boundLambdas = resolvables.resolvables.toList.flatMap { (id, declaration) =>
        values.lambdas(Ref(SourceOrigin.Synth, declaration.name, resolvedId = id.some))
      }
      val inlineLambdas = module.members.collect { case bnd: Bnd => bnd }.flatMap { bnd =>
        TermTraversal.collect(bnd.value) { case lambda: Lambda => lambda }
      }
      val functions = (boundLambdas ++ inlineLambdas)
        .distinctBy(CallableIdentity(_))
        .map { lambda =>
          val consumingParams = lambda.params.filter(_.consuming).flatMap { param =>
            param.typeSpec.orElse(param.typeAsc).map(param.name -> _.some)
          }
          val transferredCaptures = lambda.captures
            .map(_.ref)
            .filter { ref =>
              ref.resolvedId.exists(id => lambda.meta.exists(_.transferredCaptures.contains(id)))
            }
            .map(ref => ref.name -> ref.typeSpec)
          (lambda, (consumingParams ++ transferredCaptures).toMap)
        }

      @tailrec
      def loop(
        returningOwned: Map[CallableIdentity, Option[Type]]
      ): Map[CallableIdentity, Option[Type]] =
        val (nextReturningOwned, changed) =
          functions.foldLeft((returningOwned, false)):
            case ((currentReturningOwned, changedAcc), (lambda, consumingEnv)) =>
              val resultOwned =
                exprReturnsOwned(
                  lambda.body,
                  consumingEnv,
                  resolvables,
                  values,
                  currentReturningOwned
                )
                  .orElse:
                    val nativeAllocates = lambda.body.terms.exists {
                      case native: NativeImpl => native.memEffect.contains(MemEffect.Alloc)
                      case _ => false
                    }
                    Option
                      .when(nativeAllocates)(lambdaReturnType(lambda.typeAsc, lambda.typeSpec))
                      .flatten
                  .filter(t => isOwnedType(t, resolvables))

              resultOwned match
                case Some(ownedType)
                    if currentReturningOwned.get(CallableIdentity(lambda)).flatten != Some(
                      ownedType
                    ) =>
                  (currentReturningOwned.updated(CallableIdentity(lambda), Some(ownedType)), true)
                case _ =>
                  (currentReturningOwned, changedAcc)

        if changed then loop(nextReturningOwned)
        else nextReturningOwned

      loop(Map.empty)

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

  /** Check if a type is owned (heap type or capturing closure). */
  private def isOwnedType(t: Type, resolvables: ResolvablesIndex): Boolean =
    TypeUtils.requiresDestruction(t, resolvables)

  /** Get the base function Ref from an App chain (e.g., App(App(Ref(f), x), y) -> Ref(f)) */
  private def getBaseFn(term: Ref | App | Lambda): Option[Ref] = term match
    case ref: Ref => Some(ref)
    case App(_, fn, _, _, _) => getBaseFn(fn)
    case _: Lambda => None

  /** Result ownership follows the callable value, including qualified fields and aliases. */
  private def appAllocates(app: App, scope: OwnershipScope): Option[Type] =
    getBaseFn(app.fn).toList
      .flatMap(scope.callableValues.lambdas)
      .flatMap(lambda => scope.returningOwned.get(CallableIdentity(lambda)).flatten)
      .find(isOwnedType(_, scope.resolvables))

  private def mergeAllocTypes(t1: Option[Type], t2: Option[Type]): Option[Type] = (t1, t2) match
    case (Some(a), Some(b)) if a == b => Some(a)
    case (Some(a), Some(_)) => Some(a)
    case (Some(a), None) => Some(a)
    case (None, Some(b)) => Some(b)
    case _ => None

  private def exprAllocates(expr: Expr, scope: OwnershipScope): Option[Type] =
    expr.terms.lastOption.flatMap(termAllocates(_, scope))

  /** Check if a term is an allocating expression */
  def termAllocates(term: Term, scope: OwnershipScope): Option[Type] = unwrapTerm(term) match
    case app: App => appAllocates(app, scope)
    case Cond(_, _, ifTrue, ifFalse, _, _) =>
      val trueAlloc  = exprAllocates(ifTrue, scope)
      val falseAlloc = exprAllocates(ifFalse, scope)
      mergeAllocTypes(trueAlloc, falseAlloc)
    case lambda: Lambda
        if lambda.isMove &&
          (lambda.captures.nonEmpty || lambda.meta.exists(_.isPartialApplication)) =>
      lambda.typeSpec
    case _ => None

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
    bindingName:              String,
    tpe:                      Type,
    span:                     SourceOrigin,
    bindingId:                Option[String],
    resolvables:              ResolvablesIndex,
    destructorTargetOverride: Option[String] = None
  ): Option[Term] =
    val argRef = Ref(
      SourceOrigin.Synth,
      bindingName,
      typeSpec     = tpe.some,
      resolvedId   = bindingId,
      candidateIds = bindingId.toList
    )
    val argExpr  = Expr(span, List(argRef), typeSpec = tpe.some)
    val unitType = unitTypeRef(span).some
    TypeUtils.canonical(tpe, resolvables) match
      case Some(_: TypeFn) =>
        destructorTargetOverride
          .orElse(DestructionTargets.named("__free_closure", resolvables))
          .map(id => DestroyClosure(span, argExpr, id, unitType))
      case _ =>
        DestructionTargets.forType(tpe, resolvables).flatMap { id =>
          resolvables.lookup(id).collect { case b: Bnd =>
            val fnRef = Ref(SourceOrigin.Synth, b.name, resolvedId = id.some, typeSpec = b.typeSpec)
            App(span, fnRef, argExpr, typeSpec = unitType)
          }
        }

  private enum ConditionalOwnership derives CanEqual:
    case AlwaysOwned(tpe: Type)
    case NeverOwned
    case MixedOwned(tpe: Type)

  private def boolLiteralExpr(value: Boolean): Expr =
    val boolType = Some(boolTypeRef(syntheticSource))
    Expr(
      syntheticSource,
      List(LiteralBool(syntheticSource, value, typeSpec = boolType, typeAsc = None)),
      typeSpec = boolType
    )

  private def mkBoolConditionalExpr(
    condExpr:    Expr,
    ifTrueExpr:  Expr,
    ifFalseExpr: Expr
  ): Expr =
    val boolType = Some(boolTypeRef(syntheticSource))
    val condTerm = Cond(syntheticSource, condExpr, ifTrueExpr, ifFalseExpr, boolType, boolType)
    Expr(syntheticSource, List(condTerm), typeSpec = boolType)

  private def classifyConditionalOwnership(
    expr:  Expr,
    scope: OwnershipScope
  ): ConditionalOwnership =
    def classifyExpr(e: Expr): ConditionalOwnership =
      e.terms.lastOption.map(unwrapTerm) match
        case Some(cond: Cond) =>
          classifyCond(cond)
        case _ =>
          exprAllocates(e, scope) match
            case Some(tpe) => ConditionalOwnership.AlwaysOwned(tpe)
            case None => ConditionalOwnership.NeverOwned

    def classifyCond(cond: Cond): ConditionalOwnership =
      import ConditionalOwnership.*
      (classifyExpr(cond.ifTrue), classifyExpr(cond.ifFalse)) match
        case (AlwaysOwned(tpe), AlwaysOwned(_)) => AlwaysOwned(tpe)
        case (NeverOwned, NeverOwned) => NeverOwned
        case (AlwaysOwned(tpe), _) => MixedOwned(tpe)
        case (MixedOwned(tpe), _) => MixedOwned(tpe)
        case (_, AlwaysOwned(tpe)) => MixedOwned(tpe)
        case (_, MixedOwned(tpe)) => MixedOwned(tpe)

    classifyExpr(expr)

  /** Create a conditional free: if __owns_x then __free_T x else () */
  private def mkConditionalFree(
    bindingName:  String,
    tpe:          Type,
    witnessName:  String,
    span:         SourceOrigin,
    bindingId:    Option[String],
    resolvables:  ResolvablesIndex,
    witnessParam: Option[FnParam]
  ): Option[Cond] =
    mkFreeCall(bindingName, tpe, span, bindingId, resolvables).map { freeCall =>
      val boolType = Some(boolTypeRef(span))
      val unitType = Some(unitTypeRef(span))

      val witnessRef =
        witnessParam
          .map(SyntheticLocals.ref(_, typeSpec = boolType))
          .getOrElse(Ref(SourceOrigin.Synth, witnessName, typeSpec = boolType))
      val witnessExpr = Expr(span, List(witnessRef), typeSpec = boolType)

      val freeCallExpr = Expr(span, List(freeCall), typeSpec = unitType)

      val unitLit     = LiteralUnit(span, typeSpec = unitType, typeAsc = None)
      val unitLitExpr = Expr(span, List(unitLit), typeSpec = unitType)

      Cond(span, witnessExpr, freeCallExpr, unitLitExpr, unitType, unitType)
    }

  /** Wrap an expression with CPS-style free calls. Transforms: `expr` into
    * `let __r = expr; let _ = free1; let _ = free2; __r`
    *
    * For bindings with witnesses, generates conditional free: `if __owns_x then __free_T x else ()`
    */
  private def wrapWithFrees(
    expr:            Expr,
    toFree:          List[OwnedBinding],
    span:            SourceOrigin,
    owner:           SyntheticOwner,
    resolvables:     ResolvablesIndex,
    witnessBindings: Map[String, FnParam] = Map.empty
  ): Expr =
    if toFree.isEmpty then return expr

    // Get the result type from the expression
    val resultType = expr.typeSpec

    // Create a unique result binding name with proper type
    val resultName = "__ownership_result"
    val resultLocal =
      SyntheticLocals.local(owner, resultName, typeSpec = resultType, typeAsc = resultType)
    val resultParam = resultLocal.param
    val resultRef   = resultLocal.ref

    // Unit type for free call results
    val unitType = Some(unitTypeRef(span))

    // Build the innermost expression: just the result reference
    val innermost = Expr(span, List(resultRef), typeSpec = resultType)

    // Fold free calls from right to left, building:
    // let _ = freeN; ... let _ = free1; __r
    // For bindings with witnesses, generate conditional free instead
    val withFrees = toFree.foldRight(innermost): (binding, acc) =>
      val freeTermOpt: Option[Term] = binding.tpe.flatMap { tpe =>
        binding.witness match
          case Some(witnessName) =>
            mkConditionalFree(
              binding.name,
              tpe,
              witnessName,
              span,
              binding.id,
              resolvables,
              witnessBindings.get(witnessName)
            )
          case None =>
            mkFreeCall(binding.name, tpe, span, binding.id, resolvables, binding.destructorTargetId)
      }
      freeTermOpt match
        case Some(freeTerm) =>
          val discardParam =
            SyntheticLocals.param(owner, "_", typeSpec = unitType, typeAsc = unitType)
          val discardLam =
            Lambda(span, List(discardParam), acc, Nil, typeSpec = resultType)
          val freeAppExpr = Expr(span, List(freeTerm), typeSpec = unitType)
          Expr(span, List(App(span, discardLam, freeAppExpr, typeSpec = resultType)))
        case None => acc

    // Wrap with: let __r = expr; <withFrees>
    val resultLam = Lambda(span, List(resultParam), withFrees, Nil, typeSpec = resultType)
    Expr(span, List(App(span, resultLam, expr, typeSpec = resultType)), typeSpec = resultType)

  /** Clone only through a registered function for a duplicable type. */
  private def wrapWithClone(
    expr:        Expr,
    tpe:         Type,
    resolvables: ResolvablesIndex
  ): Either[SemanticError, Expr] =
    CloneCalls.build(
      expr,
      tpe,
      resolvables,
      PhaseName,
      (typeName, function) => lookupCloneFnId(function, typeName, resolvables)
    )

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
  ): Either[SemanticError, Expr] =
    if !returnType.exists(isOwnedType(_, scope.resolvables)) then expr.asRight
    else
      expr.terms.lastOption match
        case Some(cond: Cond) =>
          val trueAlloc  = exprAllocates(cond.ifTrue, scope)
          val falseAlloc = exprAllocates(cond.ifFalse, scope)
          (trueAlloc, falseAlloc, returnType) match
            case (Some(_), None, Some(tpe)) =>
              wrapWithClone(cond.ifFalse, tpe, scope.resolvables).map { cloned =>
                expr.copy(terms = expr.terms.init :+ cond.copy(ifFalse = cloned))
              }
            case (None, Some(_), Some(tpe)) =>
              wrapWithClone(cond.ifTrue, tpe, scope.resolvables).map { cloned =>
                expr.copy(terms = expr.terms.init :+ cond.copy(ifTrue = cloned))
              }
            case _ => expr.asRight
        case _ => expr.asRight

  /** Names of owned bindings that flow out through the returned expression */
  private def returnedOwnedNames(expr: Expr, scope: OwnershipScope): Set[String] =
    returnedOrigins(expr, scope.resolvables).collect {
      case ref: Ref if scope.getState(ref.name).contains(OwnershipState.Owned) => ref.name
    }.toSet

  /** References and lambda values that reach the result through argument bindings and branches.
    * Argument origins are keyed by parameter identity; declarations come from the symbol index.
    */
  private def returnedOrigins(expr: Expr, resolvables: ResolvablesIndex): List[Ref | Lambda] =
    type Origins   = List[Ref | Lambda]
    type Arguments = Map[String, Origins]

    def fromExpr(value: Expr, arguments: Arguments, index: ResolvablesIndex): Origins =
      value.terms.lastOption.toList.flatMap(fromTerm(_, arguments, index)).distinct

    def fromTerm(term: Term, arguments: Arguments, index: ResolvablesIndex): Origins =
      term match
        case ref: Ref =>
          ref.resolvedId
            .flatMap(index.lookup)
            .flatMap(_.id)
            .flatMap(arguments.get)
            .getOrElse(List(ref))
        case lambda: Lambda => List(lambda)
        case app:    App =>
          val (callee, args) = collectArgsAndBase(
            app.fn,
            List(CallArgument(app.arg, app.source, app.typeAsc, app.typeSpec))
          )
          callee match
            case lambda: Lambda
                if lambda.params.length == args.length ||
                  (lambda.params.isEmpty && args.length == 1) =>
              val supplied = lambda.params
                .zip(args)
                .flatMap:
                  case (param, argument) =>
                    param.id.map(_ -> fromExpr(argument.value, arguments, index))
              fromExpr(
                lambda.body,
                arguments ++ supplied,
                index.updatedAll(lambda.params)
              )
            case _ => Nil
        case cond: Cond =>
          fromExpr(cond.ifTrue, arguments, index) ++ fromExpr(cond.ifFalse, arguments, index)
        case group:  TermGroup => fromExpr(group.inner, arguments, index)
        case nested: Expr => fromExpr(nested, arguments, index)
        case _ => Nil

    fromExpr(expr, Map.empty, resolvables).distinct

  private def lambdaReturnType(typeAsc: Option[Type], typeSpec: Option[Type]): Option[Type] =
    typeAsc.orElse:
      typeSpec.flatMap:
        case TypeFn(_, _, ret) => Some(ret)
        case other => Some(other)

  /** Check if a binding name is referenced anywhere in an expression */
  private def containsRefInExpr(name: String, expr: Expr): Boolean =
    expr.terms.exists(containsRef(name, _))

  /** Check if a binding name is referenced anywhere in a term */
  private def containsRef(name: String, term: Term): Boolean =
    term match
      case ref: Ref => ref.qualifier.fold(ref.name == name)(containsRef(name, _))
      case App(_, fn, arg, _, _) => containsRef(name, fn) || containsRefInExpr(name, arg)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        containsRefInExpr(name, cond) || containsRefInExpr(name, ifTrue) ||
        containsRefInExpr(name, ifFalse)
      case TermGroup(_, inner, _) => containsRefInExpr(name, inner)
      case Tuple(_, elements, _, _) => elements.exists(containsRefInExpr(name, _))
      case Lambda(_, params, body, _, _, _, _, _) =>
        // Skip if a param shadows the name
        if params.exists(_.name == name) then false
        else containsRefInExpr(name, body)
      case expr: Expr => containsRefInExpr(name, expr)
      case _ => false

  @tailrec
  private def unwrapTerm(term: Term): Term = term match
    case TermGroup(_, inner, _) if inner.terms.size == 1 => unwrapTerm(inner.terms.head)
    case Expr(_, List(single), _, _) => unwrapTerm(single)
    case _ => term

  /** Handle passing a value to a consuming parameter. If consuming, validates and marks the binding
    * as moved. Returns updated scope and any errors.
    */
  private def handleConsumingParam(
    param: Option[FnParam],
    arg:   Expr,
    scope: OwnershipScope
  ): (OwnershipScope, List[SemanticError]) =
    param.filter(_.consuming) match
      case Some(consumingParam) =>
        // Get the ref being passed (if it's a simple ref)
        arg.terms.headOption.map(unwrapTerm) match
          case Some(ref: Ref) =>
            // Check if it's owned - can only move owned values
            val staticFunction = scope.callableValues.lambdas(ref) match
              case Nil => false
              case lambdas =>
                lambdas.forall(l => l.captures.isEmpty && !l.meta.exists(_.isPartialApplication))
            if scope.skipConsumingOwnership || staticFunction then (scope, Nil)
            else if ownershipPath(ref, scope).exists(_.fields.nonEmpty) then
              (
                scope,
                List(
                  SemanticError.BorrowedValuePassedToConsumingParam(
                    consumingParam,
                    ref,
                    PhaseName
                  )
                )
              )
            else
              scope.getState(ref.name) match
                case Some(OwnershipState.Owned) =>
                  // Valid move - mark as moved and record consuming info
                  val newScope = scope
                    .withMoved(ref.name, ref.source)
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
                  (
                    scope,
                    List(
                      SemanticError.BorrowedValuePassedToConsumingParam(
                        consumingParam,
                        ref,
                        PhaseName
                      )
                    )
                  )
                case Some(OwnershipState.Global) =>
                  // Accepted; wrapper ensures clone
                  (scope, Nil)
                case _ =>
                  // Passing a literal or untracked expression to consuming param - allowed
                  (scope, Nil)
          case _ =>
            // Complex expression - can't track ownership
            (scope, Nil)
      case None =>
        (scope, Nil)

  /** Rebinding an owned value transfers its cleanup obligation. Mixed-ownership witnesses retain
    * their separate conditional handling.
    */
  private def isMoveOnRebind(name: String, scope: OwnershipScope): Boolean =
    scope.getInfo(name) match
      case Some(BindingInfo(OwnershipState.Owned, Some(tpe), _, None, _)) =>
        isOwnedType(tpe, scope.resolvables)
      case _ => false

  /** Static strings and global values need owned storage at consuming boundaries. */
  private def argNeedsClone(argExpr: Expr, scope: OwnershipScope): Boolean =
    argExpr.terms.headOption.map(unwrapTerm) match
      case Some(_: LiteralString) => true
      case Some(ref: Ref) =>
        if ref.qualifier.isDefined then false
        else
          scope.getState(ref.name) match
            case Some(OwnershipState.Global | OwnershipState.Literal) => true
            case Some(OwnershipState.Owned) => false // will be moved
            case Some(OwnershipState.Borrowed) => false
            case _ => false
      case _ => false

  /** Check if an argument is a freshly allocating expression */
  private def argAllocates(argExpr: Expr, scope: OwnershipScope): Boolean =
    argExpr.terms.lastOption.flatMap(termAllocates(_, scope)).isDefined

  private def ownershipPath(ref: Ref, scope: OwnershipScope): Option[OwnershipPath] =
    ref.qualifier match
      case Some(owner: Ref) =>
        ownershipPath(owner, scope).map(path => path.copy(fields = path.fields ++ ref.resolvedId))
      case None =>
        ref.resolvedId.map { id =>
          scope.fieldAliases.getOrElse(id, OwnershipPath(id, Nil))
        }
      case _ => None

  private def pathOwner(path: OwnershipPath, scope: OwnershipScope): Option[BindingInfo] =
    scope.bindings.values.find(_.bindingId.contains(path.ownerId))

  /** Check binding moves, consumed fields, and the lifetimes of borrowed projections. */
  private def analyzeRef(ref: Ref, scope: OwnershipScope): TermResult[Ref] =
    val path = ownershipPath(ref, scope)
    val movedAt = path
      .flatMap { path =>
        scope.movedBindingIds
          .get(path.ownerId)
          .orElse(
            scope.consumedFields.collectFirst {
              case (consumed, source) if consumed.overlaps(path) =>
                source
            }
          )
      }
      .orElse(
        scope
          .getMovedAt(ref.name)
          .filter(_ =>
            ref.qualifier.isEmpty &&
              scope.getState(ref.name).contains(OwnershipState.Moved)
          )
      )
    @tailrec
    def qualifiers(current: Ref, acc: List[Ref]): List[Ref] = current.qualifier match
      case Some(parent: Ref) => qualifiers(parent, current :: acc)
      case _ => current :: acc
    val references = qualifiers(ref, Nil)
    val qualifierMoves = references.filter(_.qualifier.isEmpty).flatMap { root =>
      scope
        .getMovedAt(root.name)
        .filter(_ =>
          scope
            .getInfo(root.name)
            .exists(info =>
              info.state == OwnershipState.Moved &&
                (info.bindingId.isEmpty || info.bindingId == root.resolvedId)
            )
        )
    }
    val dependencyErrors = references
      .flatMap(_.resolvedId)
      .flatMap(scope.borrowedDependencies.getOrElse(_, Nil))
      .flatMap { dependency =>
        dependency.resolvedId.flatMap(scope.movedBindingIds.get).map { source =>
          SemanticError.UseAfterMove(dependency, source, PhaseName)
        }
      }
    TermResult(
      scope,
      ref,
      errors =
        ((movedAt.toList ++ qualifierMoves).map(SemanticError.UseAfterMove(ref, _, PhaseName)) ++
          dependencyErrors).distinct
    )

  private def borrowedDependencies(expr: Expr, scope: OwnershipScope): List[Ref] =
    borrowedDependencies(returnedOrigins(expr, scope.resolvables), scope)

  private def borrowedDependencies(origins: List[Ref | Lambda], scope: OwnershipScope): List[Ref] =
    origins
      .flatMap {
        case lambda: Lambda =>
          lambda.captures
            .map(_.ref)
            .filter { ref =>
              (!lambda.isMove || ref.resolvedId
                .exists(id => lambda.meta.exists(_.borrowedCaptures.contains(id)))) &&
              ref.typeSpec.exists(isOwnedType(_, scope.resolvables))
            }
            .flatMap { ref =>
              ref :: ref.resolvedId.toList.flatMap(scope.borrowedDependencies.getOrElse(_, Nil))
            }
        case ref: Ref =>
          val fieldOwners =
            if ref.typeSpec.exists(isOwnedType(_, scope.resolvables)) then
              ref.qualifier.toList.flatMap { qualifier =>
                TermTraversal.collect(qualifier) {
                  case owner: Ref if owner.typeSpec.exists(isOwnedType(_, scope.resolvables)) =>
                    owner
                }
              }
            else Nil
          fieldOwners ++ (ref :: fieldOwners).flatMap { source =>
            source.resolvedId.toList.flatMap(scope.borrowedDependencies.getOrElse(_, Nil))
          }
      }
      .distinctBy(_.resolvedId)

  /** A PAP owning only scalar values has no payload destruction effects. Release its environment
    * before a terminal call when neither that call nor a borrowed alias can use it. This leaves
    * recursive calls in tail position without moving payload destructors across user effects.
    */
  private def releaseFinishedPap(
    expr:    Expr,
    binding: OwnedBinding,
    scope:   OwnershipScope
  ): Option[Expr] =
    val origins = binding.id.toList.flatMap { id =>
      scope.callableValues.lambdas(Ref(SourceOrigin.Synth, binding.name, resolvedId = id.some))
    }
    val trivial = origins.nonEmpty && origins.forall(_.meta.exists { meta =>
      meta.isPartialApplication && meta.transferredCaptures.isEmpty && meta.borrowedCaptures.isEmpty
    })
    val dependentIds = scope.borrowedDependencies.collect {
      case (id, refs) if refs.exists(_.resolvedId == binding.id) => id
    }.toSet ++ binding.id

    def insert(value: Expr, free: Term): Option[Expr] = value.terms match
      case List(app: App) =>
        app.fn match
          case local: Lambda =>
            insert(local.body, free).map { body =>
              value.copy(terms = List(app.copy(fn = local.copy(body = body))))
            }
          case _
              if scope.callableValues.referencedCaptureIds(app).intersect(dependentIds).isEmpty =>
            val unitType = unitTypeRef(value.source).some
            val discard  = SyntheticLocals.param(scope.syntheticOwner, "_", typeSpec = unitType)
            val body = Lambda(value.source, List(discard), value, Nil, typeSpec = value.typeSpec)
            Some(
              value.copy(terms =
                List(
                  App(
                    value.source,
                    body,
                    Expr(value.source, List(free), typeSpec = unitType),
                    typeSpec = value.typeSpec
                  )
                )
              )
            )
          case _ => None
      case List(cond: Cond) =>
        for
          ifTrue <- insert(cond.ifTrue, free)
          ifFalse <- insert(cond.ifFalse, free)
        yield value.copy(terms = List(cond.copy(ifTrue = ifTrue, ifFalse = ifFalse)))
      case _ => None

    Option.when(trivial)(binding).flatMap { binding =>
      binding.tpe.flatMap { tpe =>
        mkFreeCall(
          binding.name,
          tpe,
          expr.source,
          binding.id,
          scope.resolvables,
          binding.destructorTargetId
        ).flatMap(insert(expr, _))
      }
    }

  private def sameBinding(left: OwnedBinding, right: OwnedBinding): Boolean =
    (left.id, right.id) match
      case (Some(leftId), Some(rightId)) => leftId == rightId
      case _ => left.name == right.name

  private def bindingMoved(binding: OwnedBinding, scope: OwnershipScope): Boolean =
    binding.id.fold(scope.getState(binding.name).contains(OwnershipState.Moved))(
      scope.movedBindingIds.contains
    )

  /** Analyze a direct lambda application: App(Lambda(params, body), arg) */
  private def analyzeLambdaApplication(
    span:           SourceOrigin,
    lSource:        SourceOrigin,
    params:         List[FnParam],
    body:           Expr,
    captures:       List[Capture],
    lTypeSpec:      Option[Type],
    lTypeAsc:       Option[Type],
    meta:           Option[LambdaMeta],
    isMove:         Boolean,
    arg:            Expr,
    typeAsc:        Option[Type],
    typeSpec:       Option[Type],
    scope:          OwnershipScope,
    resultConsumer: Option[FnParam] = none
  ): TermResult[Term] =
    val consumingParam = params.headOption.filter(_.consuming)
    val cloned         = prepareConsumingArgument(arg, consumingParam, scope)
    val prepared =
      if consumingParam.isDefined then
        ConditionalArgument(cloned.expr, Vector.empty, none, scope.tempCounter)
      else prepareConditionalArgument(cloned.expr, scope)
    val predicates = analyzeConditions(prepared, scope)
    val argResult  = analyzeArgument(prepared.value, consumingParam, predicates.scope)

    // Forward-scan: check if any newly-moved bindings are still used in the body
    val newlyMoved = argResult.scope.movedAt.keySet -- scope.movedAt.keySet
    val lastUseErrors = newlyMoved.toList.flatMap: name =>
      if !params.exists(_.name == name) && containsRefInExpr(name, body) then
        argResult.scope.consumedVia
          .get(name)
          .map: (ref, param) =>
            SemanticError.ConsumingParamNotLastUse(param, ref, PhaseName)
      else Nil

    // Track bindings that already belonged to the outer scope so we don't free them
    // inside this CPS wrapper. Only bindings created in this let should be freed here.
    val inheritedOwned = scope.ownedBindings

    val allocType = prepared.value.terms.headOption
      .flatMap(termAllocates(_, scope))
      .orElse(returnedOrigins(prepared.value, scope.resolvables).collectFirst {
        case lambda: Lambda
            if lambda.isMove &&
              (lambda.captures.nonEmpty || lambda.meta.exists(_.isPartialApplication)) =>
          lambda.typeSpec
      }.flatten)
    val mixedCond = allocType.zip(prepared.witness)

    // Set up scope for lambda body - handle mixed conditionals specially
    val (bodyScope, witnessOpt) = params.headOption match
      case Some(param) if param.consuming && !scope.skipConsumingOwnership =>
        (
          argResult.scope.withOwned(param.name, param.typeSpec.orElse(param.typeAsc), param.id),
          None
        )
      case Some(param) if mixedCond.isDefined =>
        val (allocTpe, witnessExpr) = mixedCond.get
        val witnessName             = s"__owns_${param.name}"
        val boolType                = Some(boolTypeRef(syntheticSource))
        val witnessParam =
          SyntheticLocals.param(
            scope.syntheticOwner,
            witnessName,
            typeSpec = boolType,
            typeAsc  = boolType
          )
        val scopeWithWitness = argResult.scope
          .withMixedOwnership(param.name, Some(allocTpe), witnessParam.name, param.id)
          .withLiteral(witnessParam.name)
        (scopeWithWitness, Some((witnessParam, witnessExpr)))
      case Some(param) if allocType.isDefined =>
        val paramTypeName =
          param.typeSpec
            .orElse(param.typeAsc)
            .flatMap(TypeUtils.canonical(_, scope.resolvables))
            .flatMap(getTypeName)
        val allocHeap =
          allocType.filter(t => isOwnedType(t, scope.resolvables))
        val owns =
          allocHeap.filter(t =>
            paramTypeName.forall(
              _ == TypeUtils.canonical(t, scope.resolvables).flatMap(getTypeName).getOrElse("")
            )
          )
        // Check if allocating expression is a capturing lambda with env struct name
        val closureFreeFn = arg.terms.headOption.collect {
          case lambda: Lambda if lambda.captures.nonEmpty =>
            lambda.meta
              .flatMap(_.envStructName)
              .flatMap(n => DestructionTargets.named(s"__free_$n", scope.resolvables))
        }.flatten
        val newScope = owns
          .map { t =>
            closureFreeFn match
              case Some(destructorTargetId) =>
                argResult.scope.withOwnedClosure(param.name, Some(t), param.id, destructorTargetId)
              case None =>
                argResult.scope.withOwned(param.name, Some(t), param.id)
          }
          .getOrElse(argResult.scope.withBorrowed(param.name))
        (newScope, None)
      case Some(param) =>
        val newScope = arg.terms.headOption match
          case Some(_: LiteralString) =>
            argResult.scope.withLiteral(param.name)
          case Some(ref: Ref)
              if ref.qualifier.isEmpty && isMoveOnRebind(ref.name, argResult.scope) =>
            val srcInfo = argResult.scope.getInfo(ref.name).get
            argResult.scope
              .withMoved(ref.name, ref.source)
              .withOwned(param.name, srcInfo.bindingTpe, param.id, srcInfo.destructorTargetId)
          case _ =>
            argResult.scope.withBorrowed(param.name)
        (newScope, None)
      case None =>
        (argResult.scope, None)

    val dependencies = borrowedDependencies(argResult.expr, argResult.scope)
    val sinkErrors =
      if consumingParam.isDefined then ownershipSinkErrors(cloned.expr, dependencies, scope)
      else Nil
    val scopeWithDependencies = params.headOption.flatMap(_.id).fold(bodyScope) { id =>
      bodyScope
        .copy(borrowedDependencies = bodyScope.borrowedDependencies.updated(id, dependencies))
    }
    val fieldAlias = for
      param <- params.headOption
      id <- param.id
      ref <- arg.terms.headOption.map(unwrapTerm).collect { case ref: Ref => ref }
      path <- ownershipPath(ref, argResult.scope).filter(_.fields.nonEmpty)
    yield id -> path
    val scopeWithAliases =
      scopeWithDependencies.copy(fieldAliases = scopeWithDependencies.fieldAliases ++ fieldAlias)
    val preparedBody = prepareConsumingArgument(body, resultConsumer, scopeWithAliases)
    val bodyResult   = analyzeArgument(preparedBody.expr, resultConsumer, scopeWithAliases)
    val escaping     = returnedOwnedNames(bodyResult.expr, bodyResult.scope)

    // Free all owned bindings at terminal body
    val witnessBinding = witnessOpt.flatMap(_ => params.headOption.map(_.name))
    val bindingsToFree =
      bodyResult.scope.ownedBindings.filter:
        case binding @ OwnedBinding(_, Some(tpe), _, _, _) =>
          !inheritedOwned.exists(inherited => sameBinding(inherited, binding)) &&
          !escaping.contains(binding.name) &&
          !witnessBinding.contains(binding.name) &&
          isOwnedType(tpe, scope.resolvables)
        case _ => false

    val (bodyWithEarlyFrees, terminalFrees) = bindingsToFree.foldLeft(
      (bodyResult.expr, List.empty[OwnedBinding])
    ) { case ((body, remaining), binding) =>
      releaseFinishedPap(body, binding, scopeWithDependencies).fold(
        (body, remaining :+ binding)
      )(released => (released, remaining))
    }
    val bodyWithTerminalFrees =
      if terminalFrees.isEmpty then bodyWithEarlyFrees
      else
        wrapWithFrees(
          bodyWithEarlyFrees,
          terminalFrees,
          body.source,
          scope.syntheticOwner,
          scope.resolvables
        )

    // If we have a witness, wrap the body with conditional free
    val newBody = witnessOpt match
      case Some((witnessParam, _)) =>
        val bindingName = params.headOption.map(_.name).getOrElse("")
        val bindingType = params.headOption.flatMap(p => p.typeSpec.orElse(p.typeAsc))
        val bindingId   = params.headOption.flatMap(_.id)
        bindingType match
          case Some(tpe) if isOwnedType(tpe, scope.resolvables) =>
            val toFree =
              List(OwnedBinding(bindingName, Some(tpe), bindingId, Some(witnessParam.name), None))
            wrapWithFrees(
              bodyWithTerminalFrees,
              toFree,
              body.source,
              scope.syntheticOwner,
              scope.resolvables,
              witnessBindings = Map(witnessParam.name -> witnessParam)
            )
          case _ =>
            bodyWithTerminalFrees
      case None =>
        bodyWithTerminalFrees

    val newLambda = Lambda(lSource, params, newBody, captures, lTypeSpec, lTypeAsc, meta, isMove)
    val innerApp  = App(span, newLambda, argResult.expr, typeAsc, typeSpec)

    val finalTerm = witnessOpt match
      case Some((witnessParam, witnessExpr)) =>
        val innerAppExpr = Expr(syntheticSource, List(innerApp), typeSpec = typeSpec)
        val witnessLambda =
          Lambda(syntheticSource, List(witnessParam), innerAppExpr, Nil, typeSpec = typeSpec)
        App(syntheticSource, witnessLambda, witnessExpr, typeSpec = typeSpec)
      case None =>
        innerApp

    // Propagate moves of inherited bindings back to the outer scope
    val returnScope = inheritedOwned.foldLeft(scope) { (s, binding) =>
      val name        = binding.name
      val movedInArg  = bindingMoved(binding, argResult.scope)
      val movedInBody = bindingMoved(binding, bodyResult.scope)
      if movedInArg || movedInBody then
        val span = argResult.scope
          .getMovedAt(name)
          .orElse(bodyResult.scope.getMovedAt(name))
          .getOrElse(SourceOrigin.Synth)
        s.withMoved(name, span)
      else s
    }

    val finalExpr = Expr(span, List(finalTerm), typeSpec = typeSpec)
    val wrapped   = predicates.bindings.foldRight(finalExpr)((binding, body) => binding.wrap(body))
    TermResult(
      returnScope.copy(
        tempCounter    = bodyResult.scope.tempCounter,
        consumedFields = bodyResult.scope.consumedFields
      ),
      wrapped.terms.head,
      errors = cloned.errors ++ predicates.errors ++ argResult.errors ++ preparedBody.errors ++
        bodyResult.errors ++ sinkErrors ++ lastUseErrors
    )

  private def ownershipSinkErrors(
    value:        Expr,
    dependencies: List[Ref],
    scope:        OwnershipScope
  ): List[SemanticError] =
    val isFunctionValue = value.typeSpec
      .flatMap(TypeUtils.canonical(_, scope.resolvables))
      .exists(_.isInstanceOf[TypeFn])
    val borrowsEnvironment = scope.callableValues.lambdas(value).exists { lambda =>
      lambda.captures.nonEmpty && !lambda.isMove
    }
    if !scope.skipConsumingOwnership &&
      ((isFunctionValue && borrowsEnvironment) || dependencies.nonEmpty)
    then
      List(
        SemanticError.InvalidExpression(
          value,
          "An ownership sink cannot receive a value with borrowed ownership",
          PhaseName
        )
      )
    else Nil

  /** Static values clone at consuming boundaries; each conditional branch keeps its contract. */
  private def prepareConsumingArgument(
    value:     Expr,
    parameter: Option[FnParam],
    scope:     OwnershipScope
  ): ExprResult =
    if !parameter.exists(_.consuming) then ExprResult(scope, value)
    else
      value.terms.lastOption.map(unwrapTerm) match
        case Some(cond: Cond) =>
          val yes = prepareConsumingArgument(cond.ifTrue, parameter, scope)
          val no  = prepareConsumingArgument(cond.ifFalse, parameter, scope)
          ExprResult(
            scope,
            value.copy(terms = List(cond.copy(ifTrue = yes.expr, ifFalse = no.expr))),
            yes.errors ++ no.errors
          )
        case _ if !argAllocates(value, scope) && argNeedsClone(value, scope) =>
          val tpe = parameter.flatMap(p => p.typeSpec.orElse(p.typeAsc)).orElse(value.typeSpec)
          tpe.fold(ExprResult(scope, value)) { tpe =>
            val cloned = wrapWithClone(value, tpe, scope.resolvables)
            ExprResult(scope, cloned.getOrElse(value), cloned.left.toOption.toList)
          }
        case _ => ExprResult(scope, value)

  private def analyzeArgument(
    value:     Expr,
    parameter: Option[FnParam],
    scope:     OwnershipScope
  ): ExprResult =
    value.terms.lastOption.map(unwrapTerm) match
      case Some(cond: Cond) if parameter.exists(_.consuming) =>
        val result = analyzeCond(
          cond.source,
          cond.cond,
          cond.ifTrue,
          cond.ifFalse,
          cond.typeSpec,
          cond.typeAsc,
          scope,
          parameter
        )
        ExprResult(result.scope, value.copy(terms = List(result.term)), result.errors)
      case Some(app: App) if parameter.exists(_.consuming) =>
        app.fn match
          case lambda: Lambda =>
            // FIXME:QA: Pass lambda directly instead of decomposing it into fields.
            val result = analyzeLambdaApplication(
              app.source,
              lambda.source,
              lambda.params,
              lambda.body,
              lambda.captures,
              lambda.typeSpec,
              lambda.typeAsc,
              lambda.meta,
              lambda.isMove,
              app.arg,
              app.typeAsc,
              app.typeSpec,
              scope,
              parameter
            )
            ExprResult(result.scope, value.copy(terms = List(result.term)), result.errors)
          case _ =>
            val result              = analyzeExpr(value, scope)
            val (nextScope, errors) = handleConsumingParam(parameter, result.expr, result.scope)
            result.copy(scope = nextScope, errors = result.errors ++ errors)
      case _ =>
        val result              = analyzeExpr(value, scope)
        val (nextScope, errors) = handleConsumingParam(parameter, result.expr, result.scope)
        result.copy(scope = nextScope, errors = result.errors ++ errors)

  private case class CallArgument(
    value:    Expr,
    source:   SourceOrigin,
    typeAsc:  Option[Type],
    typeSpec: Option[Type]
  )

  private case class ArgumentOwnership(
    argument:     CallArgument,
    parameter:    Option[FnParam],
    allocation:   Option[Type],
    dependencies: List[Ref],
    borrows:      List[Ref]
  ):
    def consumed: Boolean = parameter.exists(_.consuming)

  /** Collect arguments in source evaluation order, retaining each application's result type. */
  @tailrec
  private def collectArgsAndBase(
    term: Ref | App | Lambda,
    args: List[CallArgument]
  ): (Ref | Lambda, List[CallArgument]) = term match
    case app: App =>
      val argument = CallArgument(app.arg, app.source, app.typeAsc, app.typeSpec)
      collectArgsAndBase(app.fn, argument :: args)
    case callee: (Ref | Lambda) => (callee, args)

  /** Analyze a regular function application (not a let-binding) */
  private def analyzeRegularApp(
    span:     SourceOrigin,
    fn:       Ref | App | Lambda,
    arg:      Expr,
    typeAsc:  Option[Type],
    typeSpec: Option[Type],
    scope:    OwnershipScope
  ): TermResult[Term] =
    val (baseFn, allArgsWithMeta) =
      collectArgsAndBase(fn, List(CallArgument(arg, span, typeAsc, typeSpec)))

    // Resolve base function's param list to detect consuming params
    val baseFnParams = scope.callableValues.parameters(baseFn)

    val cloneResults = allArgsWithMeta.zipWithIndex.map { (argument, position) =>
      val prepared = prepareConsumingArgument(argument.value, baseFnParams.lift(position), scope)
      (argument.copy(value = prepared.expr), prepared.errors)
    }
    val cloneErrors = cloneResults.flatMap(_._2)
    val arguments = cloneResults.zipWithIndex.map { case ((argument, _), position) =>
      val origins      = returnedOrigins(argument.value, scope.resolvables)
      val dependencies = borrowedDependencies(origins, scope)
      val parameter    = baseFnParams.lift(position)
      val borrowedValues =
        if parameter.exists(_.consuming) then Nil
        else
          origins.collect {
            case ref: Ref if ref.typeSpec.exists(isOwnedType(_, scope.resolvables)) => ref
          }
      val allocation = exprAllocates(argument.value, scope)
      ArgumentOwnership(
        argument,
        parameter,
        allocation,
        dependencies,
        (dependencies ++ borrowedValues).distinct
      )
    }
    val result = analyzeCall(baseFn, arguments, typeSpec, scope)

    val isConstructor = baseFn match
      case ref: Ref =>
        ref.resolvedId.flatMap(scope.resolvables.lookup).exists {
          case binding: Bnd => binding.meta.exists(_.origin == BindingOrigin.Constructor)
          case _ => false
        }
      case _: Lambda => false
    val argumentErrors = arguments.flatMap { argument =>
      val value = argument.argument.value
      if argument.consumed || isConstructor then
        ownershipSinkErrors(value, argument.dependencies, scope)
      else if scope.callableValues.consumesOnCall(value) then
        List(
          SemanticError.InvalidExpression(
            value,
            "A call-once function requires a consuming parameter",
            PhaseName
          )
        )
      else Nil
    }
    val argumentLifetimeErrors = arguments.flatMap { argument =>
      argument.borrows.flatMap(ref => analyzeRef(ref, result.scope).errors)
    }
    val fullyApplied = baseFn.typeSpec
      .flatMap(TypeUtils.canonical(_, scope.resolvables))
      .collect { case tpe: TypeFn => allArgsWithMeta.size >= tpe.paramTypes.size }
      .contains(true)
    val resultOwnership = scope.callableValues
      .lambdas(baseFn)
      .map { lambda =>
        scope.returningOwned.get(CallableIdentity(lambda)).flatten.isDefined
      }
      .distinct
    val resultOwnershipErrors =
      if fullyApplied && typeSpec.exists(isOwnedType(_, scope.resolvables)) &&
        resultOwnership.size > 1
      then
        List(
          SemanticError.InvalidExpression(
            Expr(span, List(baseFn)),
            "Callable alternatives must agree on result ownership",
            PhaseName
          )
        )
      else Nil
    val parameterOwnershipErrors =
      if scope.callableValues.hasConsistentParameterOwnership(baseFn) then Nil
      else
        List(
          SemanticError.InvalidExpression(
            Expr(span, List(baseFn)),
            "Callable alternatives must agree on consuming parameters",
            PhaseName
          )
        )
    val checked = result.copy(errors =
      (result.errors ++ cloneErrors ++ argumentErrors ++ argumentLifetimeErrors ++
        resultOwnershipErrors ++ parameterOwnershipErrors).distinct
    )
    // Partial application transfers its captured callee during PAP creation.
    baseFn match
      case ref: Ref if fullyApplied && scope.callableValues.consumesOnCall(ref) =>
        val fieldPath = ownershipPath(ref, result.scope).filter(_.fields.nonEmpty)
        fieldPath match
          case Some(path) =>
            if pathOwner(path, result.scope).exists(_.state == OwnershipState.Owned) then
              checked.copy(scope =
                result.scope.copy(consumedFields = result.scope.consumedFields.updated(path, span))
              )
            else
              checked.copy(errors =
                checked.errors :+ SemanticError.InvalidExpression(
                  Expr(span, List(ref)),
                  "Calling this field consumes its payload and requires an owned aggregate",
                  PhaseName
                )
              )
          case None =>
            result.scope.getInfo(ref.name) match
              case Some(info) if info.state == OwnershipState.Owned =>
                val expression = Expr(span, List(result.term), typeSpec = typeSpec)
                val cleanup = OwnedBinding(
                  ref.name,
                  ref.typeSpec,
                  ref.resolvedId,
                  None,
                  DestructionTargets.named("__free_closure", scope.resolvables)
                )
                val wrapped = wrapWithFrees(
                  expression,
                  List(cleanup),
                  span,
                  scope.syntheticOwner,
                  scope.resolvables
                )
                checked.copy(
                  scope = result.scope.withMoved(ref.name, span),
                  term  = wrapped.terms.head
                )
              case Some(info) if info.state == OwnershipState.Moved => checked
              case _ =>
                checked.copy(errors =
                  checked.errors :+ SemanticError.InvalidExpression(
                    Expr(span, List(ref)),
                    "Calling this function consumes its environment and requires ownership",
                    PhaseName
                  )
                )
      case _ => checked

  private case class ArgumentBinding(local: SyntheticLocals.Local, value: Expr):
    def reference: Expr =
      Expr(syntheticSource, List(local.ref), typeSpec = local.param.typeSpec)

    def wrap(body: Expr): Expr =
      val lambda = Lambda(syntheticSource, List(local.param), body, Nil, typeSpec = body.typeSpec)
      Expr(
        syntheticSource,
        List(App(syntheticSource, lambda, value, typeSpec = body.typeSpec)),
        typeSpec = body.typeSpec
      )

  private case class ConditionalArgument(
    value:    Expr,
    bindings: Vector[ArgumentBinding],
    witness:  Option[Expr],
    nextTemp: Int
  )

  /** Bind each branch decision once. Nested predicates run only on their selected path. */
  private def prepareConditionalArgument(expr: Expr, scope: OwnershipScope): ConditionalArgument =
    def prepare(value: Expr, counter: Int): ConditionalArgument =
      value.terms.lastOption.map(unwrapTerm) match
        case Some(cond: Cond) =>
          val local = SyntheticLocals.local(
            scope.syntheticOwner,
            s"__condition_$counter",
            typeSpec = boolTypeRef(syntheticSource).some
          )
          val binding  = ArgumentBinding(local, cond.cond)
          val yes      = prepare(cond.ifTrue, counter + 1)
          val no       = prepare(cond.ifFalse, yes.nextTemp)
          val decision = binding.reference
          val yesBindings = yes.bindings.map { binding =>
            binding.copy(value =
              mkBoolConditionalExpr(decision, binding.value, boolLiteralExpr(false))
            )
          }
          val noBindings = no.bindings.map { binding =>
            binding.copy(value =
              mkBoolConditionalExpr(decision, boolLiteralExpr(false), binding.value)
            )
          }
          val rewritten = cond.copy(cond = decision, ifTrue = yes.value, ifFalse = no.value)
          val witness = mkBoolConditionalExpr(
            decision,
            yes.witness.getOrElse(boolLiteralExpr(false)),
            no.witness.getOrElse(boolLiteralExpr(false))
          )
          ConditionalArgument(
            value.copy(terms = List(rewritten)),
            Vector(binding) ++ yesBindings ++ noBindings,
            witness.some,
            no.nextTemp
          )
        case _ =>
          ConditionalArgument(
            value,
            Vector.empty,
            boolLiteralExpr(exprAllocates(value, scope).isDefined).some,
            counter
          )

    classifyConditionalOwnership(expr, scope) match
      case ConditionalOwnership.MixedOwned(_) => prepare(expr, scope.tempCounter)
      case _ => ConditionalArgument(expr, Vector.empty, none, scope.tempCounter)

  private case class AnalyzedConditions(
    scope:    OwnershipScope,
    bindings: Vector[ArgumentBinding] = Vector.empty,
    errors:   List[SemanticError]     = Nil
  )

  private def analyzeConditions(
    prepared: ConditionalArgument,
    scope:    OwnershipScope
  ): AnalyzedConditions =
    val initial = AnalyzedConditions(scope.copy(tempCounter = prepared.nextTemp))
    prepared.bindings.foldLeft(initial) { (current, binding) =>
      val result = analyzeExpr(binding.value, current.scope)
      current.copy(
        scope    = result.scope,
        bindings = current.bindings :+ binding.copy(value = result.expr),
        errors   = current.errors ++ result.errors
      )
    }

  private case class AnalyzedArguments(
    scope:     OwnershipScope,
    arguments: Vector[CallArgument]    = Vector.empty,
    bindings:  Vector[ArgumentBinding] = Vector.empty,
    cleanup:   List[OwnedBinding]      = Nil,
    witnesses: Map[String, FnParam]    = Map.empty,
    errors:    List[SemanticError]     = Nil
  )

  /** Operands run before the callee is used; all moves are visible at the call boundary. */
  private def analyzeCall(
    baseFn:    Ref | Lambda,
    arguments: List[ArgumentOwnership],
    typeSpec:  Option[Type],
    scope:     OwnershipScope
  ): TermResult[Term] =
    val needsBindings = arguments.exists(_.allocation.isDefined)
    val analyzed = arguments.foldLeft(AnalyzedArguments(scope)) { (call, argument) =>
      val prepared =
        if argument.consumed then
          ConditionalArgument(argument.argument.value, Vector.empty, none, call.scope.tempCounter)
        else prepareConditionalArgument(argument.argument.value, call.scope)
      val predicates = analyzeConditions(prepared, call.scope)
      val result     = analyzeArgument(prepared.value, argument.parameter, predicates.scope)
      val next = call.copy(
        scope    = result.scope,
        bindings = call.bindings ++ predicates.bindings,
        errors   = call.errors ++ predicates.errors ++ result.errors
      )
      if needsBindings then
        val (name, nextScope) = next.scope.nextTemp
        val local = SyntheticLocals.local(
          scope.syntheticOwner,
          name,
          typeSpec = argument.allocation.orElse(argument.argument.value.typeSpec)
        )
        val binding = ArgumentBinding(local, result.expr)
        val witness = prepared.witness.map { value =>
          val local = SyntheticLocals.local(
            scope.syntheticOwner,
            s"__owns_$name",
            typeSpec = boolTypeRef(syntheticSource).some
          )
          ArgumentBinding(local, value)
        }
        val cleanup = argument.allocation.filterNot(_ => argument.consumed).map { _ =>
          OwnedBinding(
            local.param.name,
            local.param.typeSpec,
            local.param.id,
            witness.map(_.local.param.name),
            none
          )
        }
        next.copy(
          scope     = nextScope,
          arguments = next.arguments :+ argument.argument.copy(value = binding.reference),
          bindings  = next.bindings ++ Vector(binding) ++ witness,
          cleanup   = cleanup.toList ++ next.cleanup,
          witnesses = next.witnesses ++ witness.map(w => w.local.param.name -> w.local.param)
        )
      else next.copy(arguments = next.arguments :+ argument.argument.copy(value = result.expr))
    }
    val fnResult = analyzeCallee(baseFn, analyzed.scope)
    val applied = analyzed.arguments.foldLeft[Ref | App | Lambda](fnResult.term) {
      (callee, argument) =>
        App(argument.source, callee, argument.value, argument.typeAsc, argument.typeSpec)
    }
    val callExpr = Expr(syntheticSource, List(applied), typeSpec = typeSpec)
    val withCleanup = wrapWithFrees(
      callExpr,
      analyzed.cleanup,
      syntheticSource,
      scope.syntheticOwner,
      scope.resolvables,
      analyzed.witnesses
    )
    val wrapped = analyzed.bindings.foldRight(withCleanup)((binding, body) => binding.wrap(body))
    TermResult(fnResult.scope, wrapped.terms.head, analyzed.errors ++ fnResult.errors)

  /** Analyze a conditional expression */
  private def analyzeCond(
    span:           SourceOrigin,
    condExpr:       Expr,
    ifTrue:         Expr,
    ifFalse:        Expr,
    typeSpec:       Option[Type],
    typeAsc:        Option[Type],
    scope:          OwnershipScope,
    consumingParam: Option[FnParam] = none
  ): TermResult[Term] =
    val condResult  = analyzeExpr(condExpr, scope)
    val trueResult  = analyzeArgument(ifTrue, consumingParam, condResult.scope)
    val falseResult = analyzeArgument(ifFalse, consumingParam, condResult.scope)

    val outerOwnedBindings = condResult.scope.bindings.collect {
      case (name, info @ BindingInfo(OwnershipState.Owned, _, _, _, _)) => (name, info)
    }

    val freesInTrueBranch = outerOwnedBindings.toList.flatMap { case (name, info) =>
      val trueState  = trueResult.scope.getState(name).getOrElse(info.state)
      val falseState = falseResult.scope.getState(name).getOrElse(info.state)
      val isOwned    = info.bindingTpe.exists(isOwnedType(_, scope.resolvables))
      (trueState, falseState) match
        case (OwnershipState.Owned, OwnershipState.Moved) if isOwned =>
          OwnedBinding(name, info.bindingTpe, info.bindingId, None, info.destructorTargetId).some
        case _ =>
          none
    }

    val freesInFalseBranch = outerOwnedBindings.toList.flatMap { case (name, info) =>
      val trueState  = trueResult.scope.getState(name).getOrElse(info.state)
      val falseState = falseResult.scope.getState(name).getOrElse(info.state)
      val isOwned    = info.bindingTpe.exists(isOwnedType(_, scope.resolvables))
      (trueState, falseState) match
        case (OwnershipState.Moved, OwnershipState.Owned) if isOwned =>
          OwnedBinding(name, info.bindingTpe, info.bindingId, None, info.destructorTargetId).some
        case _ =>
          none
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
              .getOrElse(SourceOrigin.Synth)
            acc.withMoved(name, movedAt)
          case (OwnershipState.Moved, OwnershipState.Owned) =>
            val movedAt = trueResult.scope.getMovedAt(name).getOrElse(SourceOrigin.Synth)
            acc.withMoved(name, movedAt)
          case (OwnershipState.Owned, OwnershipState.Moved) =>
            val movedAt = falseResult.scope.getMovedAt(name).getOrElse(SourceOrigin.Synth)
            acc.withMoved(name, movedAt)
          case _ =>
            acc
    }

    val mergedTrueExpr =
      if freesInTrueBranch.isEmpty then trueResult.expr
      else
        wrapWithFrees(
          trueResult.expr,
          freesInTrueBranch,
          ifTrue.source,
          scope.syntheticOwner,
          scope.resolvables
        )

    val mergedFalseExpr =
      if freesInFalseBranch.isEmpty then falseResult.expr
      else
        wrapWithFrees(
          falseResult.expr,
          freesInFalseBranch,
          ifFalse.source,
          scope.syntheticOwner,
          scope.resolvables
        )

    TermResult(
      mergedScope.copy(consumedFields =
        trueResult.scope.consumedFields ++
          falseResult.scope.consumedFields
      ),
      Cond(span, condResult.expr, mergedTrueExpr, mergedFalseExpr, typeSpec, typeAsc),
      errors = condResult.errors ++ trueResult.errors ++ falseResult.errors
    )

  /** Analyze a standalone lambda definition */
  private def analyzeLambda(
    span:     SourceOrigin,
    params:   List[FnParam],
    body:     Expr,
    captures: List[Capture],
    typeSpec: Option[Type],
    typeAsc:  Option[Type],
    meta:     Option[LambdaMeta],
    isMove:   Boolean,
    scope:    OwnershipScope
  ): TermResult[Lambda] =
    // Consuming params are Owned so they get freed at body end,
    // unless skipConsumingOwnership is set (for destructor/constructor functions)
    val paramScope = params.foldLeft(scope): (s, p) =>
      if p.consuming && !scope.skipConsumingOwnership then
        s.withOwned(p.name, p.typeSpec.orElse(p.typeAsc), p.id)
      else if p.consuming then s
      else s.withBorrowed(p.name)

    // Capturing a field alias borrows its aggregate; the lambda cannot consume that owner.
    val captureScope = captures.foldLeft(paramScope): (s, cap) =>
      val ref = cap.ref
      val borrowedOwner = ownershipPath(ref, s).filter(_.fields.nonEmpty).fold(s) { path =>
        s.copy(bindings = s.bindings.map { (name, info) =>
          val binding =
            if info.bindingId.contains(path.ownerId) then info.copy(state = OwnershipState.Borrowed)
            else info
          name -> binding
        })
      }
      val isTransferred =
        ref.resolvedId.exists(id => meta.exists(_.transferredCaptures.contains(id)))
      if isTransferred then borrowedOwner.withOwned(ref.name, ref.typeSpec, ref.resolvedId)
      else if ref.typeSpec.exists(isOwnedType(_, s.resolvables)) then
        borrowedOwner.withBorrowed(ref.name)
      else borrowedOwner

    val bodyResult = analyzeExpr(body, captureScope)

    val returnType   = lambdaReturnType(typeAsc, typeSpec)
    val promotion    = promoteStaticBranchesInReturn(bodyResult.expr, returnType, captureScope)
    val promotedBody = promotion.getOrElse(bodyResult.expr)

    // Insert frees for consuming params that are still Owned (not returned, not moved)
    val escaping = returnedOwnedNames(promotedBody, bodyResult.scope)
    val consumedCaptureParams = captures
      .map(_.ref)
      .filter(ref => ref.resolvedId.exists(id => meta.exists(_.transferredCaptures.contains(id))))
      .map(ref =>
        FnParam(
          ref.source,
          Name.synth(ref.name),
          typeSpec  = ref.typeSpec,
          id        = ref.resolvedId,
          consuming = true
        )
      )
    val consumingToFree = (params.filter(_.consuming) ++ consumedCaptureParams).flatMap { p =>
      val pType = p.typeSpec.orElse(p.typeAsc)
      if !scope.skipConsumingOwnership &&
        pType.exists(isOwnedType(_, scope.resolvables)) &&
        !escaping.contains(p.name) &&
        !bodyResult.scope.getState(p.name).contains(OwnershipState.Moved)
      then OwnedBinding(p.name, pType, p.id, None, None).some
      else none
    }
    val finalBody =
      if consumingToFree.isEmpty then promotedBody
      else
        wrapWithFrees(
          promotedBody,
          consumingToFree,
          body.source,
          scope.syntheticOwner,
          scope.resolvables
        )

    // Validate the typed source flow before cleanup and return-promotion rewrites.
    val origins = returnedOrigins(body, scope.resolvables)
    val borrowedCaptureIds = captures.flatMap: capture =>
      capture.ref.resolvedId.filter: id =>
        capture.ref.typeSpec.exists(isOwnedType(_, scope.resolvables)) &&
          !meta.exists(_.transferredCaptures.contains(id))

    // Parameters borrow unless consuming; captured heap values borrow from their environment.
    def isBorrowedReturn(ref: Ref): Boolean =
      ref.resolvedId.exists: id =>
        borrowedCaptureIds.contains(id) || scope.resolvables
          .lookup(id)
          .exists:
            case param: FnParam => !param.consuming
            case _ => false

    val returnTypeIsOwned = returnType.exists(t => isOwnedType(t, scope.resolvables))
    val borrowEscapeErrors =
      if returnTypeIsOwned then
        origins.collect:
          case ref: Ref if isBorrowedReturn(ref) || ref.qualifier.isDefined =>
            SemanticError.BorrowEscapeViaReturn(ref, PhaseName)
      else Nil

    val borrowClosureEscapeErrors = origins.collect:
      case lambda: Lambda
          if lambda.captures.nonEmpty && (!lambda.isMove ||
            lambda.captures.exists(cap =>
              cap.ref.resolvedId.exists(id => lambda.meta.exists(_.borrowedCaptures.contains(id)))
            )) =>
        SemanticError.BorrowClosureEscapeViaReturn(lambda, PhaseName)

    // Capture ownership: move lambdas move heap captures; borrow lambdas leave them in place.
    val (returnScope, captureErrors, updatedCaptures) =
      captures.foldLeft((scope, List.empty[SemanticError], List.empty[Capture])): (acc, cap) =>
        val (s, errs, caps) = acc
        val ref             = cap.ref
        val isOwnedCapture  = ref.typeSpec.exists(isOwnedType(_, s.resolvables))
        val isTransferred =
          ref.resolvedId.exists(id => meta.exists(_.transferredCaptures.contains(id)))
        val isBorrowed = ref.resolvedId.exists(id => meta.exists(_.borrowedCaptures.contains(id)))
        val sourceLambdas    = scope.callableValues.lambdas(ref)
        val isStaticFunction = sourceLambdas.nonEmpty && sourceLambdas.forall(_.captures.isEmpty)
        if !isOwnedCapture then (s, errs, caps :+ cap)
        else if isBorrowed || isStaticFunction then
          val errors = ref.resolvedId.flatMap(s.movedBindingIds.get).toList.map { movedAt =>
            SemanticError.UseAfterMove(ref, movedAt, PhaseName)
          }
          (s, errs ++ errors, caps :+ Capture.BorrowedRef(ref))
        else if isMove then
          // Move lambda: transfer ownership into env
          s.getState(ref.name) match
            case Some(OwnershipState.Owned) =>
              val ownedCap = ref.typeSpec.flatMap(TypeUtils.canonical(_, s.resolvables)) match
                case Some(_: TypeFn) =>
                  s.getInfo(ref.name)
                    .flatMap(_.destructorTargetId)
                    .orElse(DestructionTargets.named("__free_closure", s.resolvables))
                    .fold(cap)(id => Capture.OwnedClosure(ref, id))
                case _ => cap
              (s.withMoved(ref.name, span), errs, caps :+ ownedCap)
            case Some(OwnershipState.Moved) =>
              val movedAt = s.getMovedAt(ref.name).getOrElse(SourceOrigin.Synth)
              (
                s,
                errs :+ SemanticError.CapturedMovedHeapBinding(ref, movedAt, PhaseName),
                caps :+ cap
              )
            case Some(OwnershipState.Literal | OwnershipState.Global) if isTransferred =>
              (s, errs :+ SemanticError.CapturedBorrowedHeapBinding(ref, PhaseName), caps :+ cap)
            case Some(OwnershipState.Literal) =>
              val cloneId = for
                tpe <- ref.typeSpec.flatMap(TypeUtils.canonical(_, s.resolvables))
                typeName <- getTypeName(tpe)
                function <- cloneFnFor(typeName, s.resolvables)
                id <- lookupCloneFnId(function, typeName, s.resolvables)
              yield id
              cloneId match
                case Some(id) => (s, errs, caps :+ Capture.CapturedLiteral(ref, id))
                case None =>
                  (
                    s,
                    errs :+ SemanticError.InvalidExpression(
                      Expr(ref.source, List(ref)),
                      "This type cannot be cloned",
                      PhaseName
                    ),
                    caps :+ cap
                  )
            case Some(OwnershipState.Borrowed) =>
              (s, errs :+ SemanticError.CapturedBorrowedHeapBinding(ref, PhaseName), caps :+ cap)
            case _ => (s, errs, caps :+ cap)
        else
          // Borrow lambda: outer scope keeps ownership
          s.getState(ref.name) match
            case Some(OwnershipState.Moved) =>
              val movedAt = s.getMovedAt(ref.name).getOrElse(SourceOrigin.Synth)
              (s, errs :+ SemanticError.UseAfterMove(ref, movedAt, PhaseName), caps :+ cap)
            case _ =>
              // Owned, Borrowed, Literal, Global — all fine to borrow
              (s, errs, caps :+ cap)

    TermResult(
      returnScope,
      Lambda(span, params, finalBody, updatedCaptures, typeSpec, typeAsc, meta, isMove),
      errors = bodyResult.errors ++ promotion.left.toOption.toList ++ borrowEscapeErrors ++
        borrowClosureEscapeErrors ++ captureErrors
    )

  /** Analyze a tuple expression */
  private def analyzeTuple(
    span:     SourceOrigin,
    elements: cats.data.NonEmptyList[Expr],
    typeAsc:  Option[Type],
    typeSpec: Option[Type],
    scope:    OwnershipScope
  ): TermResult[Term] =
    val (finalScope, errors, newElements) =
      elements.toList.foldLeft((scope, List.empty[SemanticError], Vector.empty[Expr])) {
        case ((curScope, errs, acc), elem) =>
          val result = analyzeExpr(elem, curScope)
          (result.scope, errs ++ result.errors, acc :+ result.expr)
      }
    val nel = cats.data.NonEmptyList(newElements.head, newElements.tail.toList)
    TermResult(finalScope, Tuple(span, nel, typeAsc, typeSpec), errors = errors)

  private def analyzeCallee(callee: Ref | Lambda, scope: OwnershipScope): TermResult[Ref | Lambda] =
    callee match
      case ref:    Ref => analyzeRef(ref, scope)
      case lambda: Lambda =>
        analyzeLambda(
          lambda.source,
          lambda.params,
          lambda.body,
          lambda.captures,
          lambda.typeSpec,
          lambda.typeAsc,
          lambda.meta,
          lambda.isMove,
          scope
        )

  /** Analyze a term and track ownership changes */
  private def analyzeTerm(
    term:  Term,
    scope: OwnershipScope
  ): TermResult[Term] =
    term match
      case ref: Ref =>
        analyzeRef(ref, scope)

      case App(span, fn: Lambda, arg, typeAsc, typeSpec) =>
        // FIXME:QA: so many fn fields. just pass fn.
        analyzeLambdaApplication(
          span,
          fn.source,
          fn.params,
          fn.body,
          fn.captures,
          fn.typeSpec,
          fn.typeAsc,
          fn.meta,
          fn.isMove,
          arg,
          typeAsc,
          typeSpec,
          scope
        )

      case App(span, fn, arg, typeAsc, typeSpec) =>
        analyzeRegularApp(span, fn, arg, typeAsc, typeSpec, scope)

      case Cond(span, condExpr, ifTrue, ifFalse, typeSpec, typeAsc) =>
        analyzeCond(span, condExpr, ifTrue, ifFalse, typeSpec, typeAsc, scope)

      case Lambda(span, params, body, captures, typeSpec, typeAsc, meta, isMove) =>
        analyzeLambda(span, params, body, captures, typeSpec, typeAsc, meta, isMove, scope)

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
    moduleName:     String,
    resolvables:    ResolvablesIndex,
    returningOwned: Map[CallableIdentity, Option[Type]],
    globals:        Map[String, BindingInfo],
    callableValues: CallableValues
  ): (Member, List[SemanticError]) =
    member match
      case bnd @ Bnd(_, _, _, value, _, _, _, meta, _) =>
        val hasNativeBody = value.terms.exists:
          case l: Lambda => l.body.terms.exists(_.isInstanceOf[NativeImpl])
          case _ => false
        val origin        = meta.map(_.origin)
        val isDestructor  = origin.contains(BindingOrigin.Destructor)
        val isConstructor = origin.contains(BindingOrigin.Constructor)
        val skipConsuming = isDestructor || isConstructor || hasNativeBody
        val scope = OwnershipScope(
          bindings               = globals,
          syntheticOwner         = SyntheticOwner.binding(moduleName, bnd.name),
          resolvables            = resolvables,
          returningOwned         = returningOwned,
          skipConsumingOwnership = skipConsuming,
          callableValues         = callableValues
        )
        val result = analyzeExpr(value, scope)

        // Final cleanup: free any owned bindings that remain in scope and do not escape.
        // This covers cases where nested CPS wrappers skipped frees for inherited bindings.
        val escapingFinal = returnedOwnedNames(result.expr, result.scope)
        val finalToFree = result.scope.ownedBindings.collect {
          case binding @ OwnedBinding(_, Some(tpe), _, _, _)
              if !escapingFinal.contains(binding.name) &&
                isOwnedType(tpe, resolvables) =>
            binding
        }

        val cleanedValue =
          if finalToFree.isEmpty then result.expr
          else
            wrapWithFrees(
              result.expr,
              finalToFree,
              value.source,
              scope.syntheticOwner,
              resolvables
            )

        (bnd.copy(value = cleanedValue), result.errors)

      case other =>
        (other, Nil)

  /** Main entry point - rewrite module with ownership tracking */
  def rewriteModule(state: CompilerState): CompilerState =
    val module         = state.module
    val callableValues = CallableValues.fromModule(module)
    val returningOwned = ReturnOwnershipAnalysis.discover(module, callableValues)

    val globals = module.members.collect {
      case bnd: Bnd
          if bnd.typeSpec.exists(t => getTypeName(t).exists(isHeapType(_, module.resolvables))) =>
        bnd.name -> BindingInfo(OwnershipState.Global, bnd.typeSpec, bnd.id)
    }.toMap

    val (newMembersRev, allErrorsRev) =
      module.members.foldLeft((List.empty[Member], List.empty[SemanticError])):
        case ((membersAcc, errorsAcc), member) =>
          val (newMember, errors) =
            analyzeMember(
              member,
              module.name,
              module.resolvables,
              returningOwned,
              globals,
              callableValues
            )
          (newMember :: membersAcc, errors.reverse_:::(errorsAcc))

    val newModule = module.copy(members = newMembersRev.reverse)
    state.withModule(newModule).addErrors(allErrorsRev.reverse)
