package mml.mmlclib.semantic

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import scala.annotation.tailrec

import BindingIds.Allocation

/** Ownership state for a binding */
enum OwnershipState derives CanEqual:
  case Owned // Caller owns the value, must free it
  case Mixed(witness: Ref, nonOwning: Set[NonOwningOrigin])
  case Moved // Value has been moved to another owner
  case Borrowed // Borrowed reference, caller does not own
  case Literal // Literal value, no ownership tracking needed
  case Global // Top-level binding, borrow-only

enum NonOwningOrigin derives CanEqual:
  case Literal, Global, Borrowed, ResourceFree

enum ReturnContract derives CanEqual:

  case Unowned
  case Owned(tpe: Type)

  def ownedType: Option[Type] = this match
    case Owned(tpe) => tpe.some
    case Unowned => none

/** Ownership evidence retains the resolved binding and its destruction contract. */
case class BindingInfo(
  state:              OwnershipState,
  bindingTpe:         Option[Type]   = None,
  bindingId:          Option[String] = None,
  destructorTargetId: Option[String] = None // Resolved closure destructor target
)

case class OwnedBinding(
  name:               String,
  tpe:                Option[Type],
  id:                 Option[String],
  witness:            Option[Ref],
  destructorTargetId: Option[String]
)

/** An aggregate projection is identified by its owner and resolved field identities. */
case class OwnershipPath(ownerId: String, fields: List[String]):
  def overlaps(other: OwnershipPath): Boolean =
    ownerId == other.ownerId &&
      (fields.startsWith(other.fields) || other.fields.startsWith(fields))

/** Tracks ownership for bindings within a scope */
case class OwnershipScope(
  bindings:               Map[String, BindingInfo]              = Map.empty,
  movedAt:                Map[String, SourceOrigin]             = Map.empty,
  bindingOwner:           BindingOwner,
  resolvables:            ResolvablesIndex,
  bindingIds:             BindingIdSupply                       = BindingIdSupply(),
  returningOwned:         Map[CallableIdentity, ReturnContract] = Map.empty,
  tempCounter:            Int                                   = 0,
  consumedVia:            Map[String, (Ref, FnParam)]           = Map.empty,
  skipConsumingOwnership: Boolean                               = false,
  borrowedDependencies:   Map[String, List[Ref]]                = Map.empty,
  movedBindingIds:        Map[String, SourceOrigin]             = Map.empty,
  callableValues:         CallableValues                        = CallableValues.empty,
  fieldAliases:           Map[String, OwnershipPath]            = Map.empty,
  consumedFields:         Map[OwnershipPath, SourceOrigin]      = Map.empty,
  preparedArguments:      Set[String]                           = Set.empty
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

  def getState(name: String): Option[OwnershipState] = bindings.get(name).map(_.state)

  def getInfo(name: String): Option[BindingInfo] = bindings.get(name)

  def getInfo(ref: Ref): Option[BindingInfo] =
    ref.resolvedId
      .flatMap(id => bindings.values.find(_.bindingId.contains(id)))
      .orElse(
        bindings
          .get(ref.name)
          .filter(info => info.bindingId.isEmpty || info.bindingId == ref.resolvedId)
      )

  def getMovedAt(name: String): Option[SourceOrigin] = movedAt.get(name)

  /** Get all owned bindings that need to be freed, with their types, IDs, and witnesses */
  def ownedBindings: List[OwnedBinding] =
    bindings
      .flatMap:
        case (name, info) =>
          info.state match

            case OwnershipState.Owned =>
              OwnedBinding(
                name,
                info.bindingTpe,
                info.bindingId,
                none,
                info.destructorTargetId
              ).some

            case OwnershipState.Mixed(witness, _) =>
              OwnedBinding(
                name,
                info.bindingTpe,
                info.bindingId,
                witness.some,
                info.destructorTargetId
              ).some

            case _ => none
      .toList

case class ResultOwnership(
  owns:               Boolean              = false,
  nonOwning:          Set[NonOwningOrigin] = Set.empty,
  dependencies:       List[Ref]            = Nil,
  destructorTargetId: Option[String]       = None
):

  def mixed: Boolean = owns && nonOwning.nonEmpty

  def merge(other: ResultOwnership): ResultOwnership = ResultOwnership(
    owns || other.owns,
    nonOwning ++ other.nonOwning,
    (dependencies ++ other.dependencies).distinctBy(_.resolvedId),
    Option.when(destructorTargetId == other.destructorTargetId)(destructorTargetId).flatten
  )

/** Result of analyzing an expression */
case class ExprResult(
  scope:     OwnershipScope,
  expr:      Expr,
  errors:    List[SemanticError] = Nil,
  ownership: ResultOwnership     = ResultOwnership()
)

/** Result of analyzing a term */
case class TermResult[+T <: Term](
  scope:     OwnershipScope,
  term:      T,
  errors:    List[SemanticError] = Nil,
  ownership: ResultOwnership     = ResultOwnership()
)

/** Ownership analyzer phase. Tracks ownership of heap-allocated values and inserts __free_* calls.
  *
  * Key responsibilities:
  *   - Track ownership state for each binding
  *   - At App nodes: check callee's memEffect and consuming params
  *   - Validate last-use constraints for ~ parameters
  *   - Insert App(Ref("__free_T"), Ref(binding)) at scope end
  *   - Transport conditional ownership and reconcile branch-local transfers
  */
object OwnershipAnalyzer:
  private val PhaseName = "ownership-analyzer"

  private val syntheticSource = SourceOrigin.Synth

  private val UnitTypeId = "stdlib::typedef::Unit"
  private val BoolTypeId = "stdlib::typedef::Bool"

  private enum OwnershipBoundary derives CanEqual:
    case Argument(parameter: FnParam)
    case Return

  private enum ResultRequirement derives CanEqual:
    case Preserve
    case Rebind(retainedOwners: Set[String], borrowReference: Boolean)
    case Owned(boundary: OwnershipBoundary, tpe: Type)

    def relaysOwnership: Boolean = this match
      case Rebind(_, _) => true
      case _ => false

    def inBranch: ResultRequirement = this match
      case Rebind(owners, _) => Rebind(owners, borrowReference = true)
      case _ => this

  private def inheritedOwners(scope: OwnershipScope): Set[String] =
    scope.ownedBindings.flatMap(_.id).toSet

  private def ownsStorage(state: OwnershipState): Boolean = state match
    case OwnershipState.Owned | OwnershipState.Mixed(_, _) => true
    case _ => false

  private def enterScope(params: List[FnParam], scope: OwnershipScope): OwnershipScope =
    val allocation    = BindingIds.within(params, scope.bindingOwner)
    val (ids, nested) = allocation.run(scope.bindingIds).value
    scope.copy(bindingOwner = nested, bindingIds = ids)

  private def unitTypeRef(source: SourceOrigin): TypeRef =
    // TODO: QA: Use a canonical type reference for Unit instead of creating a new TypeRef each time.
    TypeRef(source, "Unit", Some(UnitTypeId), Nil)

  private def boolTypeRef(source: SourceOrigin): TypeRef =
    // TODO: QA: Use a canonical type reference for Bool instead of creating a new TypeRef each time.
    TypeRef(source, "Bool", Some(BoolTypeId), Nil)

  private def resultBindingKey(name: String, id: Option[String]): String =
    id.getOrElse(name)

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
      returningOwned: Map[CallableIdentity, ReturnContract]
    ): Option[Type] =
      val contracts = values
        .invokedLambdas(app)
        .map: lambda =>
          returningOwned.getOrElse(CallableIdentity(lambda), ReturnContract.Unowned).ownedType

      Option.when(contracts.nonEmpty && contracts.forall(_.isDefined))(contracts.head).flatten

    private def termReturnsOwned(
      term:           Term,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      values:         CallableValues,
      returningOwned: Map[CallableIdentity, ReturnContract]
    ): Option[Type] =
      term match
        case ref: Ref => env.get(resultBindingKey(ref.name, ref.resolvedId)).flatten
        case app: App =>
          values.stages(app).lastOption.filter(_.complete).flatMap { stage =>
            stage.callee match
              case lambda: Lambda =>
                val supplied = lambda.params.zip(stage.arguments).map { (param, argument) =>
                  resultBindingKey(param.name, param.id) ->
                    (if lambda.borrowsArgument then none
                     else argReturnsOwned(argument, env, resolvables, values, returningOwned))
                }
                exprReturnsOwned(lambda.body, env ++ supplied, resolvables, values, returningOwned)
              case _ => appReturnsOwned(app, values, returningOwned)
          }
        case cond: Cond =>
          merge(
            exprReturnsOwned(cond.ifTrue, env, resolvables, values, returningOwned),
            exprReturnsOwned(cond.ifFalse, env, resolvables, values, returningOwned)
          )
        case TermGroup(_, inner, _) =>
          exprReturnsOwned(inner, env, resolvables, values, returningOwned)
        case expr: Expr =>
          exprReturnsOwned(expr, env, resolvables, values, returningOwned)
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
      returningOwned: Map[CallableIdentity, ReturnContract]
    ): Option[Type] =
      exprReturnsOwned(expr, env, resolvables, values, returningOwned)

    private def exprReturnsOwned(
      expr:           Expr,
      env:            Map[String, Option[Type]],
      resolvables:    ResolvablesIndex,
      values:         CallableValues,
      returningOwned: Map[CallableIdentity, ReturnContract]
    ): Option[Type] =
      if values.recovery.isAvailable(expr) then
        singleTerm(expr).flatMap(termReturnsOwned(_, env, resolvables, values, returningOwned))
      else none

    /** Result flow follows the current tree, including rewritten scoped lambdas. */
    def resultOwned(
      term:  Term,
      env:   Map[String, Option[Type]],
      scope: OwnershipScope
    ): Option[Type] =
      if scope.callableValues.recovery.isAvailable(term) then
        termReturnsOwned(term, env, scope.resolvables, scope.callableValues, scope.returningOwned)
      else none

    def discover(module: Module, values: CallableValues): Map[CallableIdentity, ReturnContract] =
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
            param.typeSpec.orElse(param.typeAsc).map { tpe =>
              resultBindingKey(param.name, param.id) -> tpe.some
            }
          }
          val transferredCaptures = lambda.captures
            .map(_.ref)
            .filter { ref =>
              ref.resolvedId.exists(id => CaptureTransfers.ownedByInvocation(lambda).contains(id))
            }
            .map(ref => resultBindingKey(ref.name, ref.resolvedId) -> ref.typeSpec)
          (lambda, (consumingParams ++ transferredCaptures).toMap)
        }

      @tailrec
      def loop(
        returningOwned: Map[CallableIdentity, ReturnContract]
      ): Map[CallableIdentity, ReturnContract] =
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
                      .when(nativeAllocates)(lambdaReturnType(lambda))
                      .flatten
                  .filter(t => isOwnedType(t, resolvables))

              resultOwned match
                case Some(ownedType)
                    if currentReturningOwned
                      .get(CallableIdentity(lambda))
                      .flatMap(_.ownedType) != Some(
                      ownedType
                    ) =>
                  (
                    currentReturningOwned.updated(
                      CallableIdentity(lambda),
                      ReturnContract.Owned(ownedType)
                    ),
                    true
                  )
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

  /** Result ownership follows the final invocation, including returned callable values. */
  private def appAllocates(app: App, scope: OwnershipScope): Option[Type] =
    ReturnOwnershipAnalysis
      .resultOwned(app, ownedResultEnvironment(scope), scope)
      .filter(isOwnedType(_, scope.resolvables))

  private def ownedResultEnvironment(scope: OwnershipScope): Map[String, Option[Type]] =
    scope.bindings.collect {
      case (name, info) if ownsStorage(info.state) =>
        resultBindingKey(name, info.bindingId) -> info.bindingTpe
    }

  private def mergeAllocTypes(t1: Option[Type], t2: Option[Type]): Option[Type] = (t1, t2) match
    case (Some(a), Some(b)) if a == b => Some(a)
    case (Some(a), Some(_)) => Some(a)
    case (Some(a), None) => Some(a)
    case (None, Some(b)) => Some(b)
    case _ => None

  private def exprAllocates(expr: Expr, scope: OwnershipScope): Option[Type] =
    if scope.callableValues.recovery.isAvailable(expr) then
      singleTerm(expr).flatMap(termAllocates(_, scope))
    else none

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
    destructorTargetOverride: Option[String]
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

  private def boolLiteralExpr(value: Boolean): Expr =
    val boolType = Some(boolTypeRef(syntheticSource))
    Expr(
      syntheticSource,
      List(LiteralBool(syntheticSource, value, typeSpec = boolType, typeAsc = None)),
      typeSpec = boolType
    )

  private def cleanupTerm(
    binding:     OwnedBinding,
    span:        SourceOrigin,
    resolvables: ResolvablesIndex
  ): Option[Term] =
    binding.tpe
      .flatMap { tpe =>
        mkFreeCall(
          binding.name,
          tpe,
          span,
          binding.id,
          resolvables,
          binding.destructorTargetId
        )
      }
      .map { free =>
        binding.witness.fold(free) { witness =>
          val unitType = unitTypeRef(span).some
          Cond(
            span,
            Expr(span, List(witness), typeSpec                     = witness.typeSpec),
            Expr(span, List(free), typeSpec                        = unitType),
            Expr(span, List(LiteralUnit(span, unitType)), typeSpec = unitType),
            unitType,
            unitType
          )
        }
      }

  /** Cleanup follows the complete obligation, including a resolved conditional witness. */
  private def wrapWithFrees(
    expr:            Expr,
    toFree:          List[OwnedBinding],
    span:            SourceOrigin,
    owner:           BindingOwner,
    resolvables:     ResolvablesIndex,
    ownershipResult: Boolean = false
  ): Allocation[Expr] =

    def withCleanup(result: Expr): Allocation[Expr] =
      toFree.foldRight(result.pure[Allocation]): (binding, rest) =>
        rest.flatMap: body =>
          val free = cleanupTerm(binding, span, resolvables)

          free.fold(body.pure[Allocation]): term =>
            LocalBindings.sequence(term, body, owner, unitTypeRef(span), span)

    if toFree.isEmpty then expr.pure[Allocation]
    else
      for
        result <- LocalBindings.local(
          owner,
          "__ownership_result",
          typeSpec = expr.typeSpec,
          source   = expr.source
        )
        body <-
          if ownershipResult then
            for
              witness <- LocalBindings.local(
                owner,
                "__ownership_witness",
                typeSpec = boolTypeRef(span).some
              )
              yielded = Expr(
                span,
                List(YieldOwnership(span, result.expression, witness.expression, expr.typeSpec)),
                typeSpec = expr.typeSpec
              )
              cleaned <- withCleanup(yielded)
            yield expr.copy(terms =
              List(BindOwnership(span, result.param, witness.param, expr, cleaned, expr.typeSpec))
            )
          else
            withCleanup(result.expression).map: cleaned =>
              expr.copy(terms = List(LocalBindings.bind(result.param, expr, cleaned, span)))
      yield body

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

  private def resourceFree(value: Term, scope: OwnershipScope): Boolean =
    val lambdas = scope.callableValues.lambdas(value)
    lambdas.nonEmpty && lambdas.forall(lambda =>
      lambda.captures.isEmpty && !lambda.meta.exists(_.isPartialApplication)
    )

  /** Static return provenance requires proof of every result path, independently of Unowned. */
  private def staticResultOrigins(
    value: Expr,
    scope: OwnershipScope
  ): Option[Set[NonOwningOrigin]] =
    type Origins = Set[NonOwningOrigin]
    type Locals  = Map[String, Option[Origins]]

    def fromTerm(
      term:   Term,
      locals: Locals,
      active: Set[CallableIdentity]
    ): Option[Origins] =
      if !scope.callableValues.recovery.isAvailable(term) then none
      else
        unwrapTerm(term) match

          case _: LiteralString => Set(NonOwningOrigin.Literal).some

          case ref: Ref if ref.qualifier.isEmpty =>
            ref.resolvedId.flatMap(locals.get).flatten

          case cond: Cond =>
            (fromTerm(cond.ifTrue, locals, active), fromTerm(cond.ifFalse, locals, active))
              .mapN(_ ++ _)

          case app: App =>
            scope.callableValues.stages(app).lastOption.filter(_.complete).flatMap { stage =>
              stage.callee match

                case lambda: Lambda =>
                  val supplied = lambda.params
                    .zip(stage.arguments)
                    .flatMap: (param, argument) =>
                      param.id.map(
                        _ ->
                          (if lambda.borrowsArgument then none
                           else fromTerm(argument, locals, active))
                      )
                  fromTerm(lambda.body, locals ++ supplied, active)

                case _ =>
                  scope.callableValues
                    .provenInvokedLambdas(app)
                    .flatMap: targets =>
                      targets
                        .traverse { lambda =>
                          val identity = CallableIdentity(lambda)
                          if scope.returningOwned.get(identity).flatMap(_.ownedType).nonEmpty then
                            none
                          // Recursive edges add no terminal result; every exit still needs proof.
                          else if active.contains(identity) then Set.empty[NonOwningOrigin].some
                          else fromTerm(lambda.body, Map.empty, active + identity)
                        }
                        .map(_.flatten.toSet)
            }

          case _ => none

    fromTerm(value, Map.empty, Set.empty).filter(_.nonEmpty)

  /** Result evidence describes the selected value, independently of a callable's return promise. */
  private def resultOwnership(value: Expr, scope: OwnershipScope): ResultOwnership =

    if !scope.callableValues.recovery.isAvailable(value) then ResultOwnership()
    else
      val dependencies = borrowedDependencies(value, scope)
      val evidence = singleTerm(value).map(unwrapTerm) match

        case Some(term) if resourceFree(term, scope) =>
          ResultOwnership(nonOwning = Set(NonOwningOrigin.ResourceFree))

        case Some(ref: Ref) if ownershipPath(ref, scope).exists(_.fields.nonEmpty) =>
          ResultOwnership(nonOwning = Set(NonOwningOrigin.Borrowed), dependencies = dependencies)

        case Some(ref: Ref) =>
          scope
            .getInfo(ref)
            .fold(ResultOwnership(nonOwning = Set(NonOwningOrigin.Borrowed))): info =>
              val result = info.state match

                case OwnershipState.Owned => ResultOwnership(owns = true)
                case OwnershipState.Mixed(_, origins) => ResultOwnership(true, origins)

                case OwnershipState.Literal =>
                  ResultOwnership(nonOwning = Set(NonOwningOrigin.Literal))

                case OwnershipState.Global =>
                  ResultOwnership(nonOwning = Set(NonOwningOrigin.Global))

                case OwnershipState.Borrowed =>
                  ResultOwnership(
                    nonOwning    = Set(NonOwningOrigin.Borrowed),
                    dependencies = List(ref)
                  )

                case OwnershipState.Moved => ResultOwnership()

              result.copy(destructorTargetId = info.destructorTargetId)

        case Some(_: LiteralString) =>
          ResultOwnership(nonOwning = Set(NonOwningOrigin.Literal))

        case Some(lambda: Lambda)
            if lambda.isMove &&
              (lambda.captures.nonEmpty || lambda.meta.exists(_.isPartialApplication)) =>
          ResultOwnership(
            owns = true,
            destructorTargetId = lambda.meta
              .flatMap(_.envLayoutId)
              .flatMap(DestructionTargets.forEnvironment(_, scope.resolvables))
          )

        case Some(_: Lambda) =>
          ResultOwnership(nonOwning = Set(NonOwningOrigin.Borrowed))

        case _ if exprAllocates(value, scope).isDefined => ResultOwnership(owns = true)
        case _ =>
          ResultOwnership(nonOwning =
            staticResultOrigins(value, scope).getOrElse(Set(NonOwningOrigin.Borrowed))
          )

      evidence.copy(dependencies = (evidence.dependencies ++ dependencies).distinctBy(_.resolvedId))

  private def moveResult(value: Expr, scope: OwnershipScope): OwnershipScope =
    singleTerm(value).map(unwrapTerm) match

      case Some(ref: Ref) if ref.qualifier.isEmpty =>
        scope
          .getInfo(ref)
          .filter(info => ownsStorage(info.state))
          .fold(scope): _ =>
            scope.withMoved(ref.name, ref.source)

      case _ => scope

  private def resultWitness(value: Expr, facts: ResultOwnership, scope: OwnershipScope): Expr =
    val witness = singleTerm(value)
      .map(unwrapTerm)
      .collect { case ref: Ref => ref }
      .flatMap(scope.getInfo)
      .flatMap(_.state match
        case OwnershipState.Mixed(witness, _) => witness.some
        case _ => none)

    witness.fold(boolLiteralExpr(facts.owns))(ref =>
      Expr(value.source, List(ref), typeSpec = ref.typeSpec)
    )

  private def owningError(
    value:    Expr,
    boundary: OwnershipBoundary,
    facts:    ResultOwnership,
    scope:    OwnershipScope
  ): SemanticError =
    singleTerm(value).map(unwrapTerm) match

      case Some(ref: Ref) =>
        boundary match

          case OwnershipBoundary.Argument(parameter) =>
            SemanticError.BorrowedValuePassedToConsumingParam(parameter, ref, PhaseName)

          case OwnershipBoundary.Return =>
            val source = facts.dependencies.find(dependency =>
              dependency.resolvedId.forall(id =>
                scope.borrowedDependencies.getOrElse(id, Nil).isEmpty
              )
            )
            SemanticError.BorrowEscapeViaReturn(source.getOrElse(ref), PhaseName)

      case _ =>
        SemanticError.InvalidExpression(
          value,
          "An ownership sink cannot receive a value with borrowed ownership",
          PhaseName
        )

  /** Establish ownership using the boundary's clone policy and the already evaluated value. */
  private def establishOwnership(
    value:    Expr,
    facts:    ResultOwnership,
    scope:    OwnershipScope,
    boundary: OwnershipBoundary,
    tpe:      Type
  ): ExprResult =

    if scope.skipConsumingOwnership || !isOwnedType(tpe, scope.resolvables) then
      ExprResult(scope, value, ownership = facts)
    else if facts.nonOwning.contains(NonOwningOrigin.Borrowed) || facts.dependencies.nonEmpty then
      ExprResult(scope, value, List(owningError(value, boundary, facts, scope)), facts)
    else
      val needsClone = facts.nonOwning.exists(origin =>
        origin == NonOwningOrigin.Literal || origin == NonOwningOrigin.Global
      )
      val promoted =
        if needsClone then
          wrapWithClone(value, tpe, scope.resolvables).map: cloned =>
            if facts.owns then
              val cond = Cond(
                value.source,
                resultWitness(value, facts, scope),
                value,
                cloned,
                value.typeSpec,
                value.typeAsc
              )
              value.copy(terms = List(cond))
            else cloned
        else value.asRight[SemanticError]

      val moved = promoted.fold(_ => scope, _ => moveResult(value, scope))
      val consumed = boundary match

        case OwnershipBoundary.Argument(parameter) =>
          singleTerm(value).map(unwrapTerm) match

            case Some(ref: Ref) if moved.movedBindingIds != scope.movedBindingIds =>
              moved.copy(consumedVia =
                moved.consumedVia ++ ref.resolvedId.map(_ -> (ref, parameter))
              )

            case _ => moved

        case OwnershipBoundary.Return => moved

      ExprResult(
        consumed,
        promoted.getOrElse(value),
        promoted.left.toOption.toList,
        promoted.fold(
          _ => facts,
          _ => ResultOwnership(owns = true, destructorTargetId = facts.destructorTargetId)
        )
      )

  /** Analyze a result while its lexical ownership evidence is available. */
  private def analyzeResult(
    value:       Expr,
    scope:       OwnershipScope,
    requirement: ResultRequirement
  ): ExprResult =

    if !scope.callableValues.recovery.isAvailable(value) then analyzeExpr(value, scope)
    else
      singleTerm(value).map(unwrapTerm) match

        case Some(cond: Cond) =>
          val result = analyzeCond(cond, scope, requirement)
          ExprResult(
            result.scope,
            value.copy(terms = List(result.term)),
            result.errors,
            result.ownership
          )

        case Some(app @ App(_, lambda: Lambda, _, _, _)) =>
          val result = analyzeLambdaApplication(app, lambda, scope, requirement)
          ExprResult(
            result.scope,
            value.copy(terms = List(result.term)),
            result.errors,
            result.ownership
          )

        case _ =>
          val facts    = resultOwnership(value, scope)
          val analyzed = analyzeExpr(value, scope)
          requirement match

            case ResultRequirement.Preserve => analyzed.copy(ownership = facts)

            case ResultRequirement.Rebind(retainedOwners, borrowReference) =>
              val borrowed = singleTerm(value)
                .map(unwrapTerm)
                .collect:
                  case ref: Ref
                      if borrowReference && ref.resolvedId.exists(retainedOwners.contains) =>
                    ref

              val outgoing = borrowed.fold(facts): ref =>
                ResultOwnership(
                  nonOwning    = Set(NonOwningOrigin.Borrowed),
                  dependencies = (ref :: facts.dependencies).distinctBy(_.resolvedId)
                )

              val witness =
                if borrowed.nonEmpty then boolLiteralExpr(false)
                else resultWitness(value, facts, scope)
              val yielded = YieldOwnership(value.source, analyzed.expr, witness, value.typeSpec)
              analyzed.copy(
                scope =
                  if borrowed.nonEmpty then analyzed.scope else moveResult(value, analyzed.scope),
                expr      = value.copy(terms = List(yielded)),
                ownership = outgoing
              )

            case ResultRequirement.Owned(boundary, tpe) =>
              val result = establishOwnership(analyzed.expr, facts, analyzed.scope, boundary, tpe)
              result.copy(errors = analyzed.errors ++ result.errors)

  /** Homogeneous results need only their ordinary value after ownership transfer is recorded. */
  private def ordinaryResult(value: Expr): Expr =
    singleTerm(value) match

      case Some(yielded: YieldOwnership) => yielded.value

      case Some(binding: BindOwnership) =>
        value.copy(terms = List(binding.copy(body = ordinaryResult(binding.body))))

      case Some(app: App) =>
        app.fn match

          case lambda: Lambda =>
            value.copy(terms = List(app.copy(fn = lambda.copy(body = ordinaryResult(lambda.body)))))

          case _ => value

      case Some(cond: Cond) =>
        value.copy(terms =
          List(
            cond.copy(ifTrue = ordinaryResult(cond.ifTrue), ifFalse = ordinaryResult(cond.ifFalse))
          )
        )

      case _ => value

  /** References and lambda values that reach the result through argument bindings and branches.
    * Argument origins are keyed by parameter identity; declarations come from the symbol index.
    */
  private def returnedOrigins(expr: Expr, resolvables: ResolvablesIndex): List[Ref | Lambda] =
    type Origins   = List[Ref | Lambda]
    type Arguments = Map[String, Origins]

    def fromExpr(value: Expr, arguments: Arguments, index: ResolvablesIndex): Origins =
      singleTerm(value).toList.flatMap(fromTerm(_, arguments, index)).distinct

    def fromTerm(term: Term, arguments: Arguments, index: ResolvablesIndex): Origins =
      term match

        case yielded: YieldOwnership => fromExpr(yielded.value, arguments, index)

        case binding: BindOwnership =>
          val supplied =
            binding.valueParam.id.map(_ -> fromExpr(binding.initializer, arguments, index))
          fromExpr(
            binding.body,
            arguments ++ supplied,
            index.updatedAll(List(binding.valueParam, binding.witnessParam))
          )

        case ref: Ref =>
          ref.resolvedId
            .flatMap(index.lookup)
            .flatMap(_.id)
            .flatMap(arguments.get)
            .getOrElse(List(ref))

        case lambda: Lambda => List(lambda)
        case app:    App =>
          CallStages(app, index, ValueAvailability.empty).lastOption
            .filter(_.complete)
            .toList
            .flatMap { stage =>
              stage.callee match

                case lambda: Lambda =>
                  val supplied = lambda.params
                    .zip(stage.arguments)
                    .filterNot(_ => lambda.borrowsArgument)
                    .flatMap: (param, argument) =>
                      param.id.map(_ -> fromExpr(argument, arguments, index))
                  fromExpr(
                    lambda.body,
                    arguments ++ supplied,
                    index.updatedAll(lambda.params)
                  )

                case _ => Nil
            }

        case cond: Cond =>
          fromExpr(cond.ifTrue, arguments, index) ++ fromExpr(cond.ifFalse, arguments, index)
        case group:  TermGroup => fromExpr(group.inner, arguments, index)
        case nested: Expr => fromExpr(nested, arguments, index)
        case _ => Nil

    fromExpr(expr, Map.empty, resolvables).distinct

  private def lambdaReturnType(lambda: Lambda): Option[Type] =
    lambda.typeAsc.orElse:
      lambda.typeSpec.flatMap:
        case TypeFn(_, _, ret) => Some(ret)
        case other => Some(other)

  /** A rejected return explains dependent capture failures without hiding independent borrows. */
  private def withoutBorrowedReturnCascades(
    errors: List[SemanticError],
    values: CallableValues,
    module: Module
  ): List[SemanticError] =
    val rejectedRefs = errors.collect { case SemanticError.BorrowEscapeViaReturn(ref, _) =>
      ref
    }.toSet
    val rejectedClosures = errors.collect {
      case SemanticError.BorrowClosureEscapeViaReturn(lambda, _) => CallableIdentity(lambda)
    }.toSet

    if rejectedRefs.isEmpty && rejectedClosures.isEmpty then errors
    else
      val borrowedParameters = module.members.flatMap {
        case binding: Bnd =>
          TermTraversal
            .collect(binding.value) {
              case lambda: Lambda if lambda.borrowsArgument =>
                lambda.params.flatMap(_.id)
            }
            .flatten
        case _ => Nil
      }.toSet

      def rejectedReturn(lambda: Lambda): Boolean =
        returnedOrigins(lambda.body, values.index).exists {
          case ref:     Ref => rejectedRefs.contains(ref)
          case closure: Lambda => rejectedClosures.contains(CallableIdentity(closure))
        }

      def rejectedResult(term: Term, seen: Set[String]): Boolean = term match

        case ref: Ref if ref.qualifier.isEmpty =>
          ref.resolvedId
            .filterNot(id => seen.contains(id) || borrowedParameters.contains(id))
            .exists { id =>
              val sources = values.lexicalSources(ref)
              sources.nonEmpty && sources.forall(rejectedResult(_, seen + id))
            }

        case app: App =>
          values.stages(app).lastOption.filter(_.complete).exists { stage =>
            stage.callee match
              case lambda: Lambda =>
                rejectedReturn(lambda) || rejectedResult(lambda.body, seen)
              case _ => values.provenInvokedLambdas(app).exists(_.forall(rejectedReturn))
          }

        case expr:  Expr => singleTerm(expr).exists(rejectedResult(_, seen))
        case group: TermGroup => rejectedResult(group.inner, seen)
        case cond:  Cond =>
          rejectedResult(cond.ifTrue, seen) && rejectedResult(cond.ifFalse, seen)
        case _ => false

      errors.filterNot {
        case SemanticError.CapturedBorrowedHeapBinding(ref, _) => rejectedResult(ref, Set.empty)
        case _ => false
      }

  /** References retain their definition identity across nested scopes and shadowing. */
  private def containsRef(bindingId: String, term: Term): Boolean =
    term match
      case ref: Ref if ref.resolvedId.contains(bindingId) => true
      case _ => TermTraversal.children(term).exists(containsRef(bindingId, _))

  /** Only normalized expressions have a result term; retained syntax has no result contract. */
  private def singleTerm(expr: Expr): Option[Term] = expr.terms match
    case List(term) => term.some
    case _ => none

  private val ExpressionShapeMessage = "Ownership analysis requires a single-term expression"

  private def isExpressionShapeError(error: SemanticError): Boolean = error match
    case SemanticError.InvalidExpression(_, ExpressionShapeMessage, PhaseName) => true
    case _ => false

  @tailrec
  private def unwrapTerm(term: Term): Term = term match
    case TermGroup(_, Expr(_, List(single), _, _), _) => unwrapTerm(single)
    case Expr(_, List(single), _, _) => unwrapTerm(single)
    case _ => term

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

  /** Qualifier expressions retain their ownership effects and diagnostic context. */
  private def analyzeQualifier(ref: Ref, scope: OwnershipScope): TermResult[Ref] =
    val qualifierResult = ref.qualifier.collect {
      case parent: Ref => analyzeQualifier(parent, scope)
      case value => analyzeTerm(value, scope)
    }
    qualifierResult.fold(TermResult(scope, ref)) { result =>
      TermResult(result.scope, ref.copy(qualifier = result.term.some), result.errors)
    }

  private def analyzeRef(ref: Ref, scope: OwnershipScope): TermResult[Ref] =
    val qualifier = analyzeQualifier(ref, scope)
    val checked   = checkRef(qualifier.term, qualifier.scope, ref.source)
    checked.copy(errors = qualifier.errors ++ checked.errors)

  private case class MovedOwner(
    id:         String,
    movedAt:    SourceOrigin,
    dependency: Option[Ref] = None
  ):

    def at(ref: Ref, source: SourceOrigin): SemanticError.UseAfterMove =
      SemanticError.UseAfterMove(
        ref,
        movedAt,
        PhaseName,
        ownerId    = id.some,
        source     = source,
        dependency = dependency
      )

  /** Resolve moved storage independently of the expression that uses it. */
  private def movedOwners(ref: Ref, scope: OwnershipScope): List[MovedOwner] =

    val pathMoves = ownershipPath(ref, scope).toList.flatMap { path =>
      scope.movedBindingIds
        .get(path.ownerId)
        .orElse(scope.consumedFields.collectFirst {
          case (consumed, source) if consumed.overlaps(path) => source
        })
        .map(MovedOwner(path.ownerId, _))
    }

    @tailrec
    def qualifiers(current: Ref, acc: List[Ref]): List[Ref] = current.qualifier match
      case Some(parent: Ref) => qualifiers(parent, current :: acc)
      case _ => current :: acc

    val references = qualifiers(ref, Nil)
    val dependencies = references.flatMap { reference =>
      reference.resolvedId.toList.flatMap(scope.borrowedDependencies.getOrElse(_, Nil))
    }
    val movedBindings = (references.filter(_.qualifier.isEmpty) ++ dependencies)
      .flatMap(_.resolvedId)
      .flatMap(id => scope.movedBindingIds.get(id).map(MovedOwner(id, _)))

    (pathMoves ++ movedBindings).distinct.map { owner =>
      owner.copy(dependency = dependencies.find(_.resolvedId.contains(owner.id)))
    }

  /** Every diagnostic retains both the current use and the identity of the moved owner. */
  private def checkRef(
    ref:    Ref,
    scope:  OwnershipScope,
    source: SourceOrigin
  ): TermResult[Ref] =
    val errors = movedOwners(ref, scope).map(_.at(ref, source))
    TermResult(scope, ref, errors)

  private def borrowedUseErrors(
    references: List[Ref],
    use:        Expr,
    scope:      OwnershipScope
  ): List[SemanticError] =
    val useRef = singleTerm(use).map(unwrapTerm).collect { case ref: Ref => ref }
    references.flatMap { ref =>
      movedOwners(ref, scope).map(_.at(useRef.getOrElse(ref), use.source))
    }

  private case class MoveSite(ownerId: String, source: SrcSpan, movedAt: SourceOrigin)

  /** Invalid dependencies retain their primary error; independent uses keep separate diagnostics.
    */
  private def normalizeMoveErrors(errors: List[SemanticError]): List[SemanticError] =

    def site(error: SemanticError.UseAfterMove): Option[MoveSite] =
      for
        id <- error.ownerId
        span <- error.source.spanOpt
      yield MoveSite(id, span, error.movedAt)

    val reported = errors
      .collect { case error: SemanticError.UseAfterMove => error }
      .flatMap(site)
      .toSet
    val independent = errors.filterNot {
      case error: SemanticError.UseAfterMove =>
        error.dependency.exists { dependency =>
          val origin = for
            id <- dependency.resolvedId
            span <- dependency.spanOpt
            if !error.source.spanOpt.contains(span)
          yield MoveSite(id, span, error.movedAt)

          origin.exists(reported.contains)
        }
      case _ => false
    }

    // Lowered uses and source uses share a diagnostic site; retain the written reference identity.
    val writtenUses = independent
      .collect {
        case error: SemanticError.UseAfterMove if error.ref.source.isFromSource => error
      }
      .flatMap(error => site(error).map(_ -> error))
      .toMap
    val located = independent.map {
      case error: SemanticError.UseAfterMove =>
        site(error).flatMap(writtenUses.get).getOrElse(error)
      case error => error
    }

    located.distinctBy {
      case error: SemanticError.UseAfterMove => site(error).toRight(error: SemanticError)
      case error => error.asLeft[MoveSite]
    }

  private def borrowedDependencies(expr: Expr, scope: OwnershipScope): List[Ref] =
    if scope.callableValues.recovery.isAvailable(expr) then
      borrowedDependencies(returnedOrigins(expr, scope.resolvables), scope)
    else Nil

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

  /** Storage-only destruction may precede a terminal call after its arguments have run. Every other
    * path keeps scope-end cleanup, including calls retaining the owner through a borrow.
    */
  private def placeCleanup(
    expr:            Expr,
    bindings:        List[OwnedBinding],
    scope:           OwnershipScope,
    ownershipResult: Boolean = false
  ): Allocation[Expr] =

    def late(value: Expr, binding: OwnedBinding): Allocation[Expr] =
      wrapWithFrees(
        value,
        List(binding),
        value.source,
        scope.bindingOwner,
        scope.resolvables,
        ownershipResult
      )

    def storageIndependent(value: Term, current: OwnershipScope): Boolean =
      val native = value.typeSpec
        .flatMap(TypeUtils.canonical(_, current.resolvables))
        .flatMap {
          case ref: TypeRef =>
            ref.resolvedId
              .flatMap(current.resolvables.lookupType)
              .collect { case definition: TypeDef =>
                definition
              }
              .flatMap(_.typeSpec)
          case _ => none[Type]
        }
      native.exists {
        case primitive: NativePrimitive =>
          !TypeUtils.isPointerNativeType(primitive) &&
          primitive.memEffect.isEmpty && primitive.freeFn.isEmpty
        case _ => false
      }

    def extend(current: OwnershipScope, param: FnParam, value: Expr): OwnershipScope =
      val retained =
        if storageIndependent(value, current) then Nil
        else TermTraversal.collect(value) { case ref: Ref => ref }
      val dependencies = param.id.map { id =>
        id -> (current.borrowedDependencies.getOrElse(id, Nil) ++
          borrowedDependencies(value, current) ++ retained).distinctBy(_.resolvedId)
      }
      current.copy(
        callableValues       = current.callableValues.withBindings(List(param -> value)),
        borrowedDependencies = current.borrowedDependencies ++ dependencies,
        resolvables          = current.resolvables.updated(param)
      )

    def retains(term: Term, binding: OwnedBinding, current: OwnershipScope): Boolean =

      def references(value: Term): Set[String] =
        if storageIndependent(value, current) then Set.empty
        else
          value match
            case lambda: Lambda => lambda.captures.flatMap(c => references(c.ref)).toSet
            case ref:    Ref => ref.resolvedId.toSet ++ ref.qualifier.toList.flatMap(references)
            case other => TermTraversal.children(other).flatMap(references).toSet

      @tailrec
      def dependencies(pending: List[String], seen: Set[String]): Set[String] = pending match
        case Nil => seen
        case id :: rest if seen.contains(id) => dependencies(rest, seen)
        case id :: rest =>
          val borrowed = current.borrowedDependencies.getOrElse(id, Nil).flatMap(_.resolvedId)
          val owner    = current.fieldAliases.get(id).map(_.ownerId).toList
          val sources = current.callableValues.bindings
            .getOrElse(id, Nil)
            .flatMap(references)
          dependencies(borrowed ++ owner ++ sources ++ rest, seen + id)

      def ownedStorage(value: Ref, seen: Set[String]): Set[String] =
        if value.resolvedId.exists(seen.contains) ||
          !value.typeSpec.exists(isOwnedType(_, current.resolvables))
        then Set.empty
        else
          val aliases = current.callableValues.resultReferences(value).flatMap(references).toSet
          value.typeSpec.flatMap(TypeUtils.canonical(_, current.resolvables)) match
            case Some(_: TypeFn) =>
              current.callableValues.provenLambdas(value) match
                case Some(origins) if origins.forall(_.captures.isEmpty) => Set.empty
                case origins =>
                  val captures = origins.toList.flatten
                    .flatMap(StorageOnlyDestruction.ownedCaptures)
                  aliases ++ captures.flatMap(ownedStorage(_, seen ++ aliases))

            case _ => dependencies(aliases.toList, Set.empty)

      val owner = Ref(
        syntheticSource,
        binding.name,
        typeSpec   = binding.tpe,
        resolvedId = binding.id
      )
      val retained = dependencies(references(term).toList, Set.empty)
      val released = ownedStorage(owner, Set.empty)
      // Lexical values identify storage; module functions and shared field declarations do not.
      retained.intersect(released).exists { id =>
        binding.id.contains(id) || current.resolvables.lookup(id).exists {
          case _: FnParam => true
          case _ => false
        }
      }

    case class CleanupPlacement(expr: Expr, earlyOnEveryPath: Boolean)

    def insert(
      value:   Expr,
      binding: OwnedBinding,
      free:    Term,
      current: OwnershipScope
    ): Allocation[CleanupPlacement] = value.terms match

      case List(app: App) =>
        app.fn match

          case local: Lambda =>
            val nested = local.params.headOption.fold(current)(extend(current, _, app.arg))
            insert(local.body, binding, free, nested).map { placed =>
              CleanupPlacement(
                value.copy(terms = List(app.copy(fn = local.copy(body = placed.expr)))),
                placed.earlyOnEveryPath
              )
            }

          case _ =>
            val (callee, arguments) = CallableValues.application(app)
            val retained = retains(callee, binding, current) || arguments.exists { argument =>
              !storageIndependent(argument, current) &&
              retains(argument, binding, current)
            }
            if retained then late(value, binding).map(CleanupPlacement(_, false))
            else
              for
                locals <- arguments.traverse { argument =>
                  for
                    next <- State.inspect[BindingIdSupply, Long](_.next)
                    local <- LocalBindings.local(
                      current.bindingOwner,
                      s"__cleanup_arg_$next",
                      typeSpec = argument.typeSpec,
                      purpose  = "cleanup-argument",
                      source   = argument.source
                    )
                  yield local
                }
                // Preserve the typed application spine while substituting evaluated operands.
                replaced = replaceCallArguments(app, locals.map(_.expression))
                cleaned <- LocalBindings.sequence(
                  free,
                  value.copy(terms = List(replaced)),
                  current.bindingOwner,
                  unitTypeRef(value.source),
                  value.source
                )
              yield CleanupPlacement(
                arguments.zip(locals).foldRight(cleaned) { case ((argument, local), body) =>
                  body.copy(terms = List(LocalBindings.bind(local.param, argument, body)))
                },
                true
              )

      case List(bound: BindOwnership) =>
        val nested = extend(current, bound.valueParam, bound.initializer)
        insert(bound.body, binding, free, nested).map { placed =>
          CleanupPlacement(
            value.copy(terms = List(bound.copy(body = placed.expr))),
            placed.earlyOnEveryPath
          )
        }

      case List(cond: Cond) =>
        for
          yes <- insert(cond.ifTrue, binding, free, current)
          no <- insert(cond.ifFalse, binding, free, current)
        yield CleanupPlacement(
          value.copy(terms = List(cond.copy(ifTrue = yes.expr, ifFalse = no.expr))),
          yes.earlyOnEveryPath && no.earlyOnEveryPath
        )

      case _ => late(value, binding).map(CleanupPlacement(_, false))

    val (early, remaining) = bindings.partition { binding =>
      !ownershipResult &&
      StorageOnlyDestruction.closure(
        Ref(syntheticSource, binding.name, typeSpec = binding.tpe, resolvedId = binding.id),
        scope.callableValues
      ) && cleanupTerm(binding, expr.source, scope.resolvables)
        .exists(free =>
          insert(expr, binding, free, scope).runA(scope.bindingIds).value.earlyOnEveryPath
        )
    }

    (early ++ remaining).foldLeftM(expr) { (value, binding) =>
      val ref = Ref(
        syntheticSource,
        binding.name,
        typeSpec   = binding.tpe,
        resolvedId = binding.id
      )
      val free = Option
        .when(
          !ownershipResult && StorageOnlyDestruction.closure(
            ref,
            scope.callableValues
          )
        )(cleanupTerm(binding, value.source, scope.resolvables))
        .flatten
      free.fold(late(value, binding))(term => insert(value, binding, term, scope).map(_.expr))
    }

  private def replaceCallArguments(app: App, arguments: List[Expr]): App =
    def replace(current: App, remaining: List[Expr]): (App, List[Expr]) =
      val (fn, rest) = current.fn match
        case inner: App => replace(inner, remaining)
        case other => (other, remaining)
      rest match
        case arg :: tail => (current.copy(fn = fn, arg = arg), tail)
        case Nil => (current.copy(fn = fn), Nil)

    replace(app, arguments)._1

  private def sameBinding(left: OwnedBinding, right: OwnedBinding): Boolean =
    (left.id, right.id) match
      case (Some(leftId), Some(rightId)) => leftId == rightId
      case _ => left.name == right.name

  private def bindingMoved(binding: OwnedBinding, scope: OwnershipScope): Boolean =
    binding.id.fold(scope.getState(binding.name).contains(OwnershipState.Moved))(
      scope.movedBindingIds.contains
    )

  /** Local bindings transfer their result obligation before the initializer scope is destroyed. */
  private def analyzeLambdaApplication(
    app:         App,
    lambda:      Lambda,
    scope:       OwnershipScope,
    requirement: ResultRequirement = ResultRequirement.Preserve
  ): TermResult[Term] =

    val parameter = lambda.params.headOption
    val valueType = parameter.flatMap(p => p.typeSpec.orElse(p.typeAsc)).orElse(app.arg.typeSpec)
    val heap      = valueType.exists(isOwnedType(_, scope.resolvables))
    val initializerRequirement = parameter
      .filter(_.consuming)
      .flatMap: param =>
        valueType.map(ResultRequirement.Owned(OwnershipBoundary.Argument(param), _))
      .getOrElse(
        if heap then
          ResultRequirement.Rebind(inheritedOwners(scope), borrowReference = lambda.borrowsArgument)
        else ResultRequirement.Preserve
      )

    val argument     = analyzeResult(app.arg, scope, initializerRequirement)
    val facts        = argument.ownership
    val needsWitness = heap && facts.mixed && !parameter.exists(_.consuming)
    val (witnessIds, witness) =
      if needsWitness then
        LocalBindings
          .local(
            scope.bindingOwner,
            "__owns_" + parameter.fold("result")(_.name),
            typeSpec = boolTypeRef(app.source).some
          )
          .map(_.some)
          .run(argument.scope.bindingIds)
          .value
      else (argument.scope.bindingIds, none[LocalBindings.Local])

    val state = witness match
      case _ if lambda.borrowsArgument => OwnershipState.Borrowed
      case Some(local) => OwnershipState.Mixed(local.ref, facts.nonOwning)
      case None if heap && facts.owns => OwnershipState.Owned
      case None if facts.nonOwning == Set(NonOwningOrigin.Literal) => OwnershipState.Literal
      case None if facts.nonOwning == Set(NonOwningOrigin.Global) => OwnershipState.Global
      case _ => OwnershipState.Borrowed

    val dependencies = facts.dependencies
    val bound = parameter.fold(argument.scope): param =>
      argument.scope.copy(
        bindings = argument.scope.bindings
          .updated(param.name, BindingInfo(state, valueType, param.id, facts.destructorTargetId)),
        borrowedDependencies =
          argument.scope.borrowedDependencies ++ param.id.map(_ -> dependencies)
      )

    val fieldAlias = for
      param <- parameter
      id <- param.id
      ref <- singleTerm(app.arg).map(unwrapTerm).collect { case ref: Ref => ref }
      if heap
      path <- ownershipPath(ref, scope).filter(_.fields.nonEmpty)
    yield id -> path

    val bodyScope = enterScope(
      lambda.params,
      bound.copy(bindingIds = witnessIds, fieldAliases = bound.fieldAliases ++ fieldAlias)
    )
    val body      = analyzeResult(lambda.body, bodyScope, requirement)
    val inherited = scope.ownedBindings
    val bodyCleanup = body.scope.ownedBindings.filter(binding =>
      !inherited.exists(sameBinding(_, binding)) &&
        binding.tpe.exists(isOwnedType(_, scope.resolvables))
    )
    // The caller retains fresh argument storage while the source parameter only borrows it.
    val argumentCleanup = parameter
      .filter(_ => lambda.borrowsArgument && heap && facts.owns)
      .map(param =>
        OwnedBinding(param.name, valueType, param.id, witness.map(_.ref), facts.destructorTargetId)
      )
      .toList
    val toFree = bodyCleanup ++ argumentCleanup
    val localIds = toFree.flatMap(_.id).toSet ++
      parameter.filter(_ => lambda.borrowsArgument && heap).flatMap(_.id)
    val escapeErrors = body.ownership.dependencies
      .filter(_.resolvedId.exists(localIds.contains))
      .map(SemanticError.BorrowEscapeViaReturn(_, PhaseName))
    val lastUseErrors = argument.scope.consumedVia.toList.flatMap:
      case (bindingId, (ref, param)) =>
        if !scope.movedBindingIds.contains(bindingId) &&
          argument.scope.movedBindingIds.contains(bindingId) &&
          containsRef(bindingId, lambda.body)
        then List(SemanticError.ConsumingParamNotLastUse(param, ref, PhaseName))
        else Nil

    val borrowedArguments =
      if lambda.borrowsArgument && heap then facts.dependencies else Nil
    val argumentLifetimeErrors = borrowedUseErrors(borrowedArguments, app.arg, body.scope)

    val relaysOwnership = requirement.relaysOwnership
    val (cleanupIds, cleaned) = placeCleanup(
      body.expr,
      toFree,
      bodyScope.copy(borrowedDependencies = body.scope.borrowedDependencies),
      relaysOwnership
    ).run(body.scope.bindingIds).value

    val initializer =
      if initializerRequirement.relaysOwnership && !needsWitness then ordinaryResult(argument.expr)
      else argument.expr
    val term = witness
      .zip(parameter)
      .fold[Term](
        app.copy(
          fn  = lambda.copy(body = cleaned),
          arg = initializer
        )
      ): (witness, param) =>
        BindOwnership(
          app.source,
          param,
          witness.param,
          initializer,
          cleaned,
          app.typeSpec,
          app.typeAsc
        )

    val inheritedScope = inherited.foldLeft(scope): (current, binding) =>
      if bindingMoved(binding, argument.scope) || bindingMoved(binding, body.scope) then
        current.withMoved(
          binding.name,
          argument.scope
            .getMovedAt(binding.name)
            .orElse(body.scope.getMovedAt(binding.name))
            .getOrElse(app.source)
        )
      else current

    TermResult(
      inheritedScope.copy(
        bindingIds           = cleanupIds,
        tempCounter          = body.scope.tempCounter,
        consumedFields       = body.scope.consumedFields,
        movedBindingIds      = inheritedScope.movedBindingIds ++ body.scope.movedBindingIds,
        borrowedDependencies = body.scope.borrowedDependencies
      ),
      term,
      (argument.errors ++ body.errors ++ escapeErrors ++ lastUseErrors ++
        argumentLifetimeErrors).distinct,
      body.ownership
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

  private def analyzeArgument(
    value:     Expr,
    parameter: Option[FnParam],
    scope:     OwnershipScope
  ): ExprResult =
    if preparedArgument(value, scope) then
      val facts   = resultOwnership(value, scope)
      val checked = analyzeExpr(value, scope)
      checked.copy(
        scope =
          if parameter.exists(_.consuming) then moveResult(value, checked.scope)
          else checked.scope,
        ownership = facts
      )
    else analyzeUnpreparedArgument(value, parameter, scope)

  private def preparedArgument(value: Expr, scope: OwnershipScope): Boolean =
    singleTerm(value).map(unwrapTerm).exists {
      case ref: Ref => ref.resolvedId.exists(scope.preparedArguments.contains)
      case _ => false
    }

  private def analyzeUnpreparedArgument(
    value:     Expr,
    parameter: Option[FnParam],
    scope:     OwnershipScope
  ): ExprResult =
    val requirement = parameter
      .filter(_.consuming)
      .flatMap { param =>
        param.typeSpec
          .orElse(param.typeAsc)
          .orElse(value.typeSpec)
          .map(ResultRequirement.Owned(OwnershipBoundary.Argument(param), _))
      }
      .getOrElse(ResultRequirement.Preserve)
    analyzeResult(value, scope, requirement)

  private case class ArgumentOwnership(
    application:  App,
    parameter:    Option[FnParam],
    allocation:   Option[Type],
    dependencies: List[Ref],
    borrows:      List[Ref]
  ):
    def consumed: Boolean = parameter.exists(_.consuming)

  private def describeArguments(
    stage: CallStages.Stage,
    scope: OwnershipScope
  ): List[ArgumentOwnership] =
    val parameters = scope.callableValues.parameters(stage.callee)
    stage.applications.zipWithIndex.map { case (argument, position) =>
      val origins =
        if scope.callableValues.recovery.isAvailable(argument.arg) then
          returnedOrigins(argument.arg, scope.resolvables)
        else Nil
      val dependencies = borrowedDependencies(origins, scope)
      val parameter    = parameters.lift(position)
      val borrowedValues =
        if parameter.exists(_.consuming) then Nil
        else
          origins.collect {
            case ref: Ref if ref.typeSpec.exists(isOwnedType(_, scope.resolvables)) => ref
          }
      val allocation = exprAllocates(argument.arg, scope)
      ArgumentOwnership(
        argument,
        parameter,
        allocation,
        dependencies,
        (dependencies ++ borrowedValues).distinct
      )
    }

  /** Analyze a regular function application (not a let-binding) */
  private def analyzeRegularApp(
    app:   App,
    scope: OwnershipScope
  ): TermResult[Term] =

    val stages = scope.callableValues.stages(app)
    if stages.size > 1 then analyzeStagedCall(app, stages, scope)
    else analyzeSingleStage(app, scope)

  private case class StagedExpression(value: Expr, aliases: List[(FnParam, Expr)])

  private case class AnalyzedCallStage(stage: CallStages.Stage, arguments: AnalyzedArguments)

  /** Supplied operands precede evaluated callees; intermediate results keep their own contracts. */
  private def analyzeStagedCall(
    app:    App,
    stages: List[CallStages.Stage],
    scope:  OwnershipScope
  ): TermResult[Term] =

    val preparedStages = stages.foldLeft(Vector.empty[AnalyzedCallStage]) { (prepared, stage) =>
      val currentScope = prepared.lastOption.fold(scope)(_.arguments.scope)
      val arguments = analyzeArguments(
        describeArguments(stage, scope),
        currentScope,
        forceBindings = true
      )
      prepared :+ AnalyzedCallStage(stage, arguments)
    }
    val bindings  = preparedStages.flatMap(_.arguments.bindings)
    val arguments = bindings.map(_.argument).toList
    val argumentErrors = arguments.flatMap { argument =>
      val value = argument.application.arg
      if argument.consumed then ownershipSinkErrors(value, argument.dependencies, scope)
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
    val preparedScope = preparedStages.lastOption.fold(scope)(_.arguments.scope)
    val argumentScope = bindings.foldLeft(preparedScope) { (current, binding) =>
      val argument = binding.argument
      val owns     = argument.consumed || argument.allocation.isDefined
      val facts    = binding.ownership
      val state =
        if owns && facts.owns then
          binding.witness.fold[OwnershipState](OwnershipState.Owned)(witness =>
            OwnershipState.Mixed(witness.ref, facts.nonOwning)
          )
        else if facts.nonOwning == Set(NonOwningOrigin.Literal) then OwnershipState.Literal
        else if facts.nonOwning == Set(NonOwningOrigin.Global) then OwnershipState.Global
        else OwnershipState.Borrowed
      val dependencies = if owns then facts.dependencies else argument.borrows
      current.copy(
        bindings = current.bindings.updated(
          binding.local.param.name,
          BindingInfo(
            state,
            binding.local.param.typeSpec,
            binding.local.param.id,
            facts.destructorTargetId
          )
        ),
        borrowedDependencies = current.borrowedDependencies ++
          binding.local.param.id.map(_ -> dependencies)
      )
    }

    def lower(
      remaining: List[AnalyzedCallStage],
      callee:    Ref | Lambda,
      ordinal:   Int
    ): Allocation[StagedExpression] = remaining match

      case AnalyzedCallStage(stage, prepared) :: rest =>
        val applied = prepared.arguments.foldLeft[Callee](callee) { (fn, argument) =>
          argument.copy(fn = fn)
        }
        val value = Expr(stage.result.source, List(applied), typeSpec = stage.resultType)
        if rest.isEmpty then StagedExpression(value, Nil).pure[Allocation]
        else
          for
            local <- LocalBindings.local(
              scope.bindingOwner,
              s"__callee_${argumentScope.tempCounter + ordinal}",
              typeSpec = stage.resultType,
              source   = stage.result.source
            )
            continuation <- lower(rest, local.ref, ordinal + 1)
          yield StagedExpression(
            continuation.value.copy(terms =
              List(
                LocalBindings.bind(local.param, value, continuation.value)
              )
            ),
            (local.param -> Expr(
              stage.result.source,
              List(stage.result),
              typeSpec = stage.resultType
            )) :: continuation.aliases
          )

      case Nil =>
        StagedExpression(Expr(app.source, List(callee), typeSpec = app.typeSpec), Nil)
          .pure[Allocation]

    val (base, _) = CallableValues.application(app)
    val (ids, lowered) =
      lower(preparedStages.toList, base, 0).run(argumentScope.bindingIds).value
    val aliases = bindings.map { binding =>
      binding.local.param -> binding.argument.application.arg
    }.toList ++ lowered.aliases
    val stagedScope = argumentScope.copy(
      bindingIds     = ids,
      tempCounter    = argumentScope.tempCounter + lowered.aliases.size,
      callableValues = argumentScope.callableValues.withBindings(aliases),
      resolvables    = argumentScope.resolvables.updatedAll(aliases.map(_._1)),
      preparedArguments = argumentScope.preparedArguments ++
        bindings.flatMap(_.local.param.id)
    )
    val result = analyzeExpr(lowered.value, stagedScope)
    val lifetimeErrors = arguments.flatMap(argument =>
      borrowedUseErrors(argument.borrows, argument.application.arg, result.scope)
    )
    val retainedCleanup = preparedStages.reverse.toList
      .flatMap(_.arguments.cleanup)
      .filterNot(bindingMoved(_, result.scope))
    val (cleanupIds, cleaned) = wrapWithFrees(
      result.expr,
      retainedCleanup,
      app.source,
      scope.bindingOwner,
      stagedScope.resolvables
    ).run(result.scope.bindingIds).value
    val wrapped           = bindings.foldRight(cleaned)((binding, body) => binding.wrap(body))
    val localNames        = bindings.map(_.local.param.name).toSet
    val preparationErrors = preparedStages.toList.flatMap(_.arguments.errors)
    TermResult(
      result.scope.copy(
        bindings          = result.scope.bindings -- localNames,
        preparedArguments = scope.preparedArguments,
        bindingIds        = cleanupIds
      ),
      wrapped.terms.head,
      (preparationErrors ++ argumentErrors ++ result.errors ++ lifetimeErrors).distinct
    )

  private def analyzeSingleStage(app: App, scope: OwnershipScope): TermResult[Term] =
    val (baseFn, _) = CallableValues.application(app)
    val arguments   = scope.callableValues.stages(app).flatMap(describeArguments(_, scope))
    val result      = analyzeCall(baseFn, arguments, app.typeSpec, scope)

    val isConstructor = baseFn match
      case ref: Ref =>
        ref.resolvedId.flatMap(scope.resolvables.lookup).exists {
          case binding: Bnd => binding.meta.exists(_.origin == BindingOrigin.Constructor)
          case _ => false
        }
      case _: Lambda => false
    val argumentErrors = arguments.flatMap { argument =>
      val value = argument.application.arg
      if preparedArgument(value, scope) then Nil
      else if argument.consumed || isConstructor then
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
      borrowedUseErrors(argument.borrows, argument.application.arg, result.scope)
    }
    val fullyApplied = scope.callableValues.stages(app).lastOption.exists(_.complete)
    val resultOwnership = scope.callableValues
      .invokedLambdas(app)
      .map { lambda =>
        scope.returningOwned.get(CallableIdentity(lambda)).flatMap(_.ownedType).isDefined
      }
      .distinct
    val resultOwnershipErrors =
      if fullyApplied && app.typeSpec.exists(isOwnedType(_, scope.resolvables)) &&
        resultOwnership.size > 1
      then
        List(
          SemanticError.InvalidExpression(
            Expr(app.source, List(baseFn)),
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
            Expr(app.source, List(baseFn)),
            "Callable alternatives must agree on consuming parameters",
            PhaseName
          )
        )
    val checked = result.copy(errors =
      (result.errors ++ argumentErrors ++ argumentLifetimeErrors ++
        resultOwnershipErrors ++ parameterOwnershipErrors).distinct
    )
    // Partial application transfers its captured callee during PAP creation.
    baseFn match
      case ref: Ref if fullyApplied && scope.callableValues.consumesOnCall(ref) =>
        val fieldPath = ownershipPath(ref, result.scope).filter(_.fields.nonEmpty)
        fieldPath match
          case Some(path) =>
            if pathOwner(path, result.scope).exists(info => info.state == OwnershipState.Owned)
            then
              checked.copy(scope =
                result.scope
                  .copy(consumedFields = result.scope.consumedFields.updated(path, app.source))
              )
            else
              checked.copy(errors =
                checked.errors :+ SemanticError.InvalidExpression(
                  Expr(app.source, List(ref)),
                  "Calling this field consumes its payload and requires an owned aggregate",
                  PhaseName
                )
              )
          case None =>
            result.scope.getInfo(ref) match
              case Some(info)
                  if info.state == OwnershipState.Owned ||
                    canConsumeResourceFreeAlternative(ref, result.scope) =>
                val expression = Expr(app.source, List(result.term), typeSpec = app.typeSpec)
                val cleanup = OwnedBinding(
                  ref.name,
                  ref.typeSpec,
                  ref.resolvedId,
                  None,
                  DestructionTargets.named("__free_closure", scope.resolvables)
                )
                val (cleanupIds, wrapped) = wrapWithFrees(
                  expression,
                  List(cleanup),
                  app.source,
                  scope.bindingOwner,
                  scope.resolvables
                ).run(result.scope.bindingIds).value
                checked.copy(
                  scope =
                    result.scope.withMoved(ref.name, app.source).copy(bindingIds = cleanupIds),
                  term = wrapped.terms.head
                )
              case Some(info) if info.state == OwnershipState.Moved => checked
              case _ =>
                checked.copy(errors =
                  checked.errors :+ SemanticError.InvalidExpression(
                    Expr(app.source, List(ref)),
                    "Calling this function consumes its environment and requires ownership",
                    PhaseName
                  )
                )
      case _ => checked

  private def canConsumeResourceFreeAlternative(ref: Ref, scope: OwnershipScope): Boolean =
    val facts = resultOwnership(Expr(ref.source, List(ref), typeSpec = ref.typeSpec), scope)
    facts.owns && facts.nonOwning == Set(NonOwningOrigin.ResourceFree) &&
    facts.dependencies.isEmpty

  private case class ArgumentBinding(
    argument:  ArgumentOwnership,
    local:     LocalBindings.Local,
    value:     Expr,
    witness:   Option[LocalBindings.Local] = None,
    ownership: ResultOwnership             = ResultOwnership()
  ):

    def reference: Expr = local.expression

    def wrap(body: Expr): Expr =
      val term = witness.fold[Term](LocalBindings.bind(local.param, value, body)): owns =>
        BindOwnership(body.source, local.param, owns.param, value, body, body.typeSpec)

      body.copy(terms = List(term))

  private case class AnalyzedArguments(
    scope:     OwnershipScope,
    arguments: Vector[App]             = Vector.empty,
    bindings:  Vector[ArgumentBinding] = Vector.empty,
    cleanup:   List[OwnedBinding]      = Nil,
    errors:    List[SemanticError]     = Nil
  )

  private def analyzeArguments(
    arguments:     List[ArgumentOwnership],
    scope:         OwnershipScope,
    forceBindings: Boolean = false
  ): AnalyzedArguments =
    val needsBindings = forceBindings || arguments.exists(_.allocation.isDefined)
    arguments.foldLeft(AnalyzedArguments(scope)): (call, argument) =>
      val ownsTemporary = !argument.consumed && argument.allocation.isDefined
      val result =
        if ownsTemporary then
          analyzeResult(
            argument.application.arg,
            call.scope,
            ResultRequirement.Rebind(inheritedOwners(call.scope), borrowReference = true)
          )
        else analyzeArgument(argument.application.arg, argument.parameter, call.scope)
      val next = call.copy(scope = result.scope, errors = call.errors ++ result.errors)

      if needsBindings then
        val (name, nextScope) = next.scope.nextTemp
        val bindings = for
          local <- LocalBindings.local(
            scope.bindingOwner,
            name,
            typeSpec = argument.application.arg.typeSpec.orElse(argument.allocation),
            source   = argument.application.arg.source
          )
          witness <-
            if ownsTemporary && result.ownership.mixed then
              LocalBindings
                .local(
                  scope.bindingOwner,
                  s"__owns_$name",
                  typeSpec = boolTypeRef(syntheticSource).some
                )
                .map(_.some)
            else none[LocalBindings.Local].pure[Allocation]
        yield (local, witness)

        val (ids, (local, witness)) = bindings.run(nextScope.bindingIds).value
        val value =
          if ownsTemporary && witness.isEmpty then ordinaryResult(result.expr)
          else result.expr
        val binding = ArgumentBinding(argument, local, value, witness, result.ownership)
        val cleanup = Option.when(ownsTemporary && result.ownership.owns)(
          OwnedBinding(
            local.param.name,
            local.param.typeSpec,
            local.param.id,
            witness.map(_.ref),
            result.ownership.destructorTargetId
          )
        )

        next.copy(
          scope     = nextScope.copy(bindingIds = ids),
          arguments = next.arguments :+ argument.application.copy(arg = binding.reference),
          bindings  = next.bindings :+ binding,
          cleanup   = cleanup.toList ++ next.cleanup
        )
      else next.copy(arguments = next.arguments :+ argument.application.copy(arg = result.expr))

  /** Operands run before the callee is used; all moves are visible at the call boundary. */
  private def analyzeCall(
    baseFn:    Ref | Lambda,
    arguments: List[ArgumentOwnership],
    typeSpec:  Option[Type],
    scope:     OwnershipScope
  ): TermResult[Term] =

    val analyzed = analyzeArguments(arguments, scope)

    val fnResult = analyzeCallee(baseFn, analyzed.scope)
    val applied = analyzed.arguments.foldLeft[Callee](fnResult.term): (callee, argument) =>
      argument.copy(fn = callee)

    val callExpr = Expr(syntheticSource, List(applied), typeSpec = typeSpec)
    val (cleanupIds, withCleanup) = wrapWithFrees(
      callExpr,
      analyzed.cleanup,
      syntheticSource,
      scope.bindingOwner,
      scope.resolvables
    ).run(fnResult.scope.bindingIds).value
    val wrapped = analyzed.bindings.foldRight(withCleanup)((binding, body) => binding.wrap(body))
    TermResult(
      fnResult.scope.copy(bindingIds = cleanupIds),
      wrapped.terms.head,
      analyzed.errors ++ fnResult.errors
    )

  /** Each branch transfers or retains the original obligation before scopes are joined. */
  private def analyzeCond(
    cond:        Cond,
    scope:       OwnershipScope,
    requirement: ResultRequirement = ResultRequirement.Preserve
  ): TermResult[Term] =

    val predicate = analyzeExpr(cond.cond, scope)
    val yes       = analyzeResult(cond.ifTrue, predicate.scope, requirement.inBranch)
    val no = analyzeResult(
      cond.ifFalse,
      predicate.scope.copy(bindingIds = yes.scope.bindingIds, tempCounter = yes.scope.tempCounter),
      requirement.inBranch
    )

    val obligations = predicate.scope.ownedBindings
    val freeYes = obligations.filter(binding =>
      !bindingMoved(binding, yes.scope) && bindingMoved(binding, no.scope)
    )
    val freeNo = obligations.filter(binding =>
      bindingMoved(binding, yes.scope) && !bindingMoved(binding, no.scope)
    )
    val merged = obligations.foldLeft(predicate.scope): (current, binding) =>
      if bindingMoved(binding, yes.scope) || bindingMoved(binding, no.scope) then
        current.withMoved(
          binding.name,
          yes.scope
            .getMovedAt(binding.name)
            .orElse(no.scope.getMovedAt(binding.name))
            .getOrElse(cond.source)
        )
      else current

    val relaysOwnership = requirement.relaysOwnership
    val (yesIds, cleanedYes) = placeCleanup(
      yes.expr,
      freeYes,
      yes.scope,
      relaysOwnership
    ).run(no.scope.bindingIds).value
    val (noIds, cleanedNo) = placeCleanup(
      no.expr,
      freeNo,
      no.scope,
      relaysOwnership
    ).run(yesIds).value

    val dependencies = (yes.scope.borrowedDependencies |+| no.scope.borrowedDependencies)
      .map:
        case (id, refs) => id -> refs.distinctBy(_.resolvedId)

    TermResult(
      merged.copy(
        bindingIds           = noIds,
        tempCounter          = no.scope.tempCounter,
        borrowedDependencies = dependencies,
        movedBindingIds =
          merged.movedBindingIds ++ yes.scope.movedBindingIds ++ no.scope.movedBindingIds,
        consumedFields = yes.scope.consumedFields ++ no.scope.consumedFields
      ),
      cond.copy(cond = predicate.expr, ifTrue = cleanedYes, ifFalse = cleanedNo),
      predicate.errors ++ yes.errors ++ no.errors,
      yes.ownership.merge(no.ownership)
    )

  /** Analyze a standalone lambda definition */
  private def analyzeLambda(
    lambda: Lambda,
    scope:  OwnershipScope
  ): TermResult[Lambda] =

    // Consuming params are Owned so they get freed at body end,
    // unless skipConsumingOwnership is set (for destructor/constructor functions)
    val paramScope = lambda.params.foldLeft(enterScope(lambda.params, scope)): (s, p) =>
      if p.consuming && !scope.skipConsumingOwnership then
        s.withOwned(p.name, p.typeSpec.orElse(p.typeAsc), p.id)
      else if p.consuming then s
      else s.withBorrowed(p.name)

    val invocationCaptures = CaptureTransfers.ownedByInvocation(lambda)

    // Capturing a field alias borrows its aggregate; the lambda cannot consume that owner.
    val captureScope = lambda.captures.foldLeft(paramScope): (s, cap) =>
      val ref = cap.ref
      val borrowedOwner = ownershipPath(ref, s).filter(_.fields.nonEmpty).fold(s) { path =>
        s.copy(bindings = s.bindings.map { (name, info) =>
          val binding =
            if info.bindingId.contains(path.ownerId) then info.copy(state = OwnershipState.Borrowed)
            else info
          name -> binding
        })
      }
      val ownsCapture = ref.resolvedId.exists(invocationCaptures.contains)
      if ownsCapture then borrowedOwner.withOwned(ref.name, ref.typeSpec, ref.resolvedId)
      else if ref.typeSpec.exists(isOwnedType(_, s.resolvables)) then
        borrowedOwner.withBorrowed(ref.name)
      else borrowedOwner

    val returnType = lambdaReturnType(lambda)
    val requirement = scope.returningOwned
      .get(CallableIdentity(lambda))
      .flatMap(_.ownedType)
      .fold[ResultRequirement](ResultRequirement.Preserve) { tpe =>
        ResultRequirement.Owned(OwnershipBoundary.Return, tpe)
      }
    val bodyResult = analyzeResult(lambda.body, captureScope, requirement)

    // Insert frees for consuming params that are still Owned (not returned, not moved)
    val consumedCaptureParams = lambda.captures
      .map(_.ref)
      .filter(ref => ref.resolvedId.exists(invocationCaptures.contains))
      .map(ref =>
        FnParam(
          ref.source,
          Name.synth(ref.name),
          typeSpec  = ref.typeSpec,
          id        = ref.resolvedId,
          consuming = true
        )
      )
    val consumingToFree = (lambda.params.filter(_.consuming) ++ consumedCaptureParams).flatMap {
      p =>
        val pType = p.typeSpec.orElse(p.typeAsc)
        if !scope.skipConsumingOwnership &&
          pType.exists(isOwnedType(_, scope.resolvables)) &&
          !bodyResult.scope.getState(p.name).contains(OwnershipState.Moved)
        then OwnedBinding(p.name, pType, p.id, None, None).some
        else none
    }
    val (cleanupIds, finalBody) = placeCleanup(
      bodyResult.expr,
      consumingToFree,
      captureScope.copy(borrowedDependencies = bodyResult.scope.borrowedDependencies)
    ).run(bodyResult.scope.bindingIds).value

    // Validate the typed source flow before cleanup and return-promotion rewrites.
    val origins = returnedOrigins(lambda.body, scope.resolvables)
      .filter(scope.callableValues.recovery.isAvailable)
    val borrowedCaptureIds = lambda.captures.flatMap: capture =>
      capture.ref.resolvedId.filter: id =>
        capture.ref.typeSpec.exists(isOwnedType(_, scope.resolvables)) &&
          !invocationCaptures.contains(id)

    // Call-once entries own their captures; other captures and non-consuming parameters borrow.
    def isBorrowedReturn(ref: Ref): Boolean =
      ref.resolvedId.exists: id =>
        !invocationCaptures.contains(id) && (borrowedCaptureIds.contains(id) || scope.resolvables
          .lookup(id)
          .exists:
            case param: FnParam => !param.consuming
            case _ => false)

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
      lambda.captures.foldLeft((scope, List.empty[SemanticError], List.empty[Capture])):
        (acc, cap) =>
          val (s, errs, caps) = acc
          val ref             = cap.ref
          val isOwnedCapture  = ref.typeSpec.exists(isOwnedType(_, s.resolvables))
          val isTransferred =
            ref.resolvedId.exists(id => lambda.meta.exists(_.transferredCaptures.contains(id)))
          val isBorrowed =
            ref.resolvedId.exists(id => lambda.meta.exists(_.borrowedCaptures.contains(id)))
          val sourceLambdas    = scope.callableValues.lambdas(ref)
          val isStaticFunction = sourceLambdas.nonEmpty && sourceLambdas.forall(_.captures.isEmpty)
          if !isOwnedCapture || !scope.callableValues.recovery.isAvailable(ref) then
            (s, errs, caps :+ cap)
          else if isBorrowed || isStaticFunction then
            val errors = ref.resolvedId.flatMap(s.movedBindingIds.get).toList.map { movedAt =>
              SemanticError.UseAfterMove(ref, movedAt, PhaseName, ref.resolvedId, ref.source)
            }
            (s, errs ++ errors, caps :+ Capture.BorrowedRef(ref))
          else if lambda.isMove then
            // Move lambda: transfer ownership into env
            s.getState(ref.name) match

              case Some(OwnershipState.Mixed(witness, origins)) =>
                val dependencies =
                  ref.resolvedId.toList.flatMap(s.borrowedDependencies.getOrElse(_, Nil))
                val payload = Expr(ref.source, List(ref), typeSpec = ref.typeSpec)

                if origins.contains(NonOwningOrigin.Borrowed) || dependencies.nonEmpty then
                  (
                    s,
                    errs :+ SemanticError.CapturedBorrowedHeapBinding(ref, PhaseName),
                    caps :+ cap
                  )
                else if origins == Set(NonOwningOrigin.ResourceFree) then
                  val owned = DestructionTargets
                    .named("__free_closure", s.resolvables)
                    .fold(cap)(id => Capture.OwnedClosure(ref, id))
                  (s.withMoved(ref.name, lambda.source), errs, caps :+ owned)
                else if isTransferred && lambda.meta.exists(_.isPartialApplication) then
                  (
                    s,
                    errs :+ SemanticError.InvalidExpression(
                      payload,
                      "A stored consuming argument requires ownership on every path; " +
                        "implicit cloning is not allowed",
                      PhaseName
                    ),
                    caps :+ cap
                  )
                else if origins != Set(NonOwningOrigin.Literal) then
                  (
                    s,
                    errs :+ SemanticError.InvalidExpression(
                      payload,
                      "This capture cannot establish ownership for its non-owning alternatives",
                      PhaseName
                    ),
                    caps :+ cap
                  )
                else
                  val cloneId = for
                    tpe <- ref.typeSpec.flatMap(TypeUtils.canonical(_, s.resolvables))
                    typeName <- getTypeName(tpe)
                    function <- cloneFnFor(typeName, s.resolvables)
                    id <- lookupCloneFnId(function, typeName, s.resolvables)
                  yield id

                  cloneId match

                    case Some(id) =>
                      (
                        s.withMoved(ref.name, lambda.source),
                        errs,
                        caps :+ Capture.CapturedConditional(ref, witness, id)
                      )

                    case None =>
                      (
                        s,
                        errs :+ SemanticError.InvalidExpression(
                          payload,
                          "This type cannot be cloned",
                          PhaseName
                        ),
                        caps :+ cap
                      )

              case Some(OwnershipState.Owned) =>
                val ownedCap = ref.typeSpec.flatMap(TypeUtils.canonical(_, s.resolvables)) match
                  case Some(_: TypeFn) =>
                    s.getInfo(ref.name)
                      .flatMap(_.destructorTargetId)
                      .orElse(DestructionTargets.named("__free_closure", s.resolvables))
                      .fold(cap)(id => Capture.OwnedClosure(ref, id))
                  case _ => cap
                (s.withMoved(ref.name, lambda.source), errs, caps :+ ownedCap)
              case Some(OwnershipState.Moved) =>
                val movedAt = s.getMovedAt(ref.name).getOrElse(SourceOrigin.Synth)
                (
                  s,
                  errs :+ SemanticError.CapturedMovedHeapBinding(ref, movedAt, PhaseName),
                  caps :+ cap
                )
              case Some(OwnershipState.Literal | OwnershipState.Global)
                  if isTransferred && lambda.meta.exists(_.isPartialApplication) =>
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
                (
                  s,
                  errs :+ SemanticError.UseAfterMove(
                    ref,
                    movedAt,
                    PhaseName,
                    ref.resolvedId,
                    ref.source
                  ),
                  caps :+ cap
                )
              case _ =>
                // Owned, Borrowed, Literal, Global — all fine to borrow
                (s, errs, caps :+ cap)

    TermResult(
      returnScope.copy(bindingIds = cleanupIds, tempCounter = bodyResult.scope.tempCounter),
      lambda.copy(body            = finalBody, captures     = updatedCaptures),
      errors = (bodyResult.errors ++ borrowEscapeErrors ++
        borrowClosureEscapeErrors ++ captureErrors).distinct
    )

  /** Analyze a tuple expression */
  private def analyzeTuple(
    tuple: Tuple,
    scope: OwnershipScope
  ): TermResult[Term] =

    val (finalScope, errors, newElements) =
      tuple.elements.toList.foldLeft((scope, List.empty[SemanticError], Vector.empty[Expr])) {
        case ((curScope, errs, acc), elem) =>
          val result = analyzeExpr(elem, curScope)
          (result.scope, errs ++ result.errors, acc :+ result.expr)
      }
    val nel = cats.data.NonEmptyList(newElements.head, newElements.tail.toList)
    TermResult(finalScope, tuple.copy(elements = nel), errors = errors)

  private def analyzeCallee(callee: Ref | Lambda, scope: OwnershipScope): TermResult[Ref | Lambda] =
    callee match
      case ref:    Ref => analyzeRef(ref, scope)
      case lambda: Lambda =>
        analyzeLambda(lambda, scope)

  /** Analyze a term and track ownership changes */
  private def analyzeTerm(
    term:  Term,
    scope: OwnershipScope
  ): TermResult[Term] =
    term match
      case invalid: InvalidExpression =>
        val result = analyzeExpr(invalid.originalExpr, scope)
        TermResult(
          result.scope,
          invalid.copy(originalExpr = result.expr),
          result.errors.filterNot(isExpressionShapeError)
        )

      case ref: Ref =>
        analyzeRef(ref, scope)

      case app: App =>
        app.fn match
          case lambda: Lambda => analyzeLambdaApplication(app, lambda, scope)
          case _ if !scope.callableValues.recovery.isAvailable(app) =>
            val callee   = analyzeTerm(app.fn, scope)
            val argument = analyzeExpr(app.arg, callee.scope)
            TermResult(
              argument.scope,
              app.copy(arg = argument.expr),
              callee.errors ++ argument.errors
            )
          case _ => analyzeRegularApp(app, scope)

      case cond: Cond =>
        analyzeCond(cond, scope)

      case lambda: Lambda =>
        analyzeLambda(lambda, scope)

      case expr: Expr =>
        val result = analyzeExpr(expr, scope)
        TermResult(result.scope, result.expr, result.errors)

      case TermGroup(span, inner, typeAsc) =>
        val result = analyzeExpr(inner, scope)
        TermResult(result.scope, TermGroup(span, result.expr, typeAsc), errors = result.errors)

      case tuple: Tuple =>
        analyzeTuple(tuple, scope)

      case lit: LiteralValue =>
        TermResult(scope, lit)

      case other =>
        TermResult(scope, other)

  /** Analyze an expression */
  private def analyzeExpr(
    expr:  Expr,
    scope: OwnershipScope
  ): ExprResult =
    val shapeErrors = Option
      .when(singleTerm(expr).isEmpty)(
        SemanticError.InvalidExpression(expr, ExpressionShapeMessage, PhaseName)
      )
      .toList
    val (finalScope, revTerms, errors) =
      expr.terms.foldLeft((scope, List.empty[Term], List.empty[SemanticError])):
        case ((s, ts, errs), term) =>
          val result = analyzeTerm(term, s)
          (result.scope, result.term :: ts, errs ++ result.errors)

    ExprResult(
      finalScope,
      expr.copy(terms = revTerms.reverse),
      errors = shapeErrors ++ errors
    )

  /** Analyze a member and insert free calls */
  private def analyzeMember(
    member:         Member,
    moduleName:     String,
    resolvables:    ResolvablesIndex,
    returningOwned: Map[CallableIdentity, ReturnContract],
    globals:        Map[String, BindingInfo],
    callableValues: CallableValues,
    bindingIds:     BindingIdSupply
  ): (Member, List[SemanticError], BindingIdSupply) =
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
          bindingOwner           = BindingOwner.binding(moduleName, bnd.name),
          resolvables            = resolvables,
          bindingIds             = bindingIds,
          returningOwned         = returningOwned,
          skipConsumingOwnership = skipConsuming,
          callableValues         = callableValues
        )
        val result = analyzeExpr(value, scope)

        // Final cleanup: free any owned bindings that remain in scope and do not escape.
        // This covers cases where nested CPS wrappers skipped frees for inherited bindings.
        val finalToFree = result.scope.ownedBindings.collect {
          case binding @ OwnedBinding(_, Some(tpe), _, _, _) if isOwnedType(tpe, resolvables) =>
            binding
        }

        val (finalIds, cleanedValue) = wrapWithFrees(
          result.expr,
          finalToFree,
          value.source,
          scope.bindingOwner,
          resolvables
        ).run(result.scope.bindingIds).value

        (bnd.copy(value = cleanedValue), result.errors, finalIds)

      case other =>
        (other, Nil, bindingIds)

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

    val (newMembersRev, allErrorsRev, finalIds) =
      module.members.foldLeft(
        (List.empty[Member], List.empty[SemanticError], state.bindingIds.include(module))
      ):
        case ((membersAcc, errorsAcc, ids), member) =>
          val (newMember, errors, nextIds) =
            analyzeMember(
              member,
              module.name,
              module.resolvables,
              returningOwned,
              globals,
              callableValues,
              ids
            )
          (newMember :: membersAcc, errors.reverse_:::(errorsAcc), nextIds)

    val newModule = module.copy(members = newMembersRev.reverse)
    val errors    = withoutBorrowedReturnCascades(allErrorsRev.reverse, callableValues, module)
    state.copy(bindingIds = finalIds).withModule(newModule).addErrors(normalizeMoveErrors(errors))
