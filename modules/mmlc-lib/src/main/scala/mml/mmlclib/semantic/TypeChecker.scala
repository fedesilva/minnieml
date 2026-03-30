package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.implicits.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import scala.annotation.tailrec
import scala.collection.mutable

object TypeChecker:
  private val phaseName          = "mml.mmlclib.semantic.TypeChecker"
  private val statementParamName = "__stmt"

  private case class CheckResult[+A](value: A, errors: Vector[TypeError]):
    def map[B](f: A => B): CheckResult[B] =
      CheckResult(f(value), errors)

    def flatMap[B](f: A => CheckResult[B]): CheckResult[B] =
      val next = f(value)
      CheckResult(next.value, errors ++ next.errors)

    def addErrors(more: Vector[TypeError]): CheckResult[A] =
      if more.isEmpty then this else CheckResult(value, errors ++ more)

  private object CheckResult:
    def ok[A](value: A): CheckResult[A] = CheckResult(value, Vector.empty)

  private case class LambdaInferenceState(
    inferred:   Map[String, Type],
    aliases:    Map[String, String],
    conflicted: Set[String],
    errors:     Vector[TypeError]
  )

  private case class LambdaInferenceResult(
    inferred:     Map[String, Type],
    failedParams: Set[String],
    errors:       Vector[TypeError]
  )

  private def bindingDisplayName(bnd: Bnd): String =
    bnd.meta.map(_.originalName).getOrElse(bnd.name)

  private def normalizeBindingName(name: String): String =
    if name == statementParamName then "statement" else name

  @tailrec
  private def resolveAlias(name: String, aliases: Map[String, String]): String =
    aliases.get(name) match
      case Some(next) if next != name => resolveAlias(next, aliases)
      case _ => name

  private def collectAppChain(app: App): (Ref | Lambda, List[Expr]) =
    @tailrec
    def loop(current: App, args: List[Expr]): (Ref | Lambda, List[Expr]) =
      current.fn match
        case inner:  App => loop(inner, current.arg :: args)
        case ref:    Ref => (ref, current.arg :: args)
        case lambda: Lambda => (lambda, current.arg :: args)

    loop(app, Nil)

  private def bareRef(expr: Expr): Option[Ref] =
    expr.terms match
      case List(ref: Ref) => Some(ref)
      case List(group: TermGroup) => bareRef(group.inner)
      case List(inner: Expr) => bareRef(inner)
      case _ => None

  private def trackedParamName(
    ref:          Ref,
    aliases:      Map[String, String],
    trackedNames: Set[String]
  ): Option[String] =
    val resolved = resolveAlias(ref.name, aliases)
    Option.when(trackedNames.contains(resolved))(resolved)

  private def recordLambdaParamInference(
    paramName:    String,
    inferredType: Type,
    state:        LambdaInferenceState,
    tracked:      Map[String, FnParam],
    module:       Module
  ): LambdaInferenceState =
    if state.conflicted.contains(paramName) then state
    else
      state.inferred.get(paramName) match
        case None =>
          state.copy(inferred = state.inferred + (paramName -> inferredType))
        case Some(existingType) if areTypesCompatible(existingType, inferredType, module) =>
          state
        case Some(existingType) =>
          val error =
            TypeError.ConflictingLambdaParamInference(
              tracked(paramName),
              existingType,
              inferredType,
              phaseName
            )
          state.copy(
            conflicted = state.conflicted + paramName,
            errors     = state.errors :+ error
          )

  private def recordInferenceFromExpr(
    expr:    Expr,
    argType: Type,
    state:   LambdaInferenceState,
    tracked: Map[String, FnParam],
    module:  Module
  ): LambdaInferenceState =
    bareRef(expr)
      .flatMap(trackedParamName(_, state.aliases, tracked.keySet))
      .map(recordLambdaParamInference(_, argType, state, tracked, module))
      .getOrElse(state)

  private def isSimpleLetBinding(lambda: Lambda, app: App): Boolean =
    lambda.source == app.source &&
      (lambda.params match
        case List(param) =>
          param.name != statementParamName &&
          param.typeAsc.isEmpty &&
          param.typeSpec.isEmpty
        case _ => false)

  private def inferParamTypesFromBody(
    params: List[FnParam],
    body:   Expr,
    module: Module
  ): LambdaInferenceResult =
    val tracked = params.map(param => param.name -> param).toMap

    def walkAppFn(
      fn:    Ref | App | Lambda,
      state: LambdaInferenceState
    ): LambdaInferenceState =
      fn match
        case _:   Ref => state
        case app: App => walkApp(app, state)
        case _:   Lambda => state

    def walkExpr(expr: Expr, state: LambdaInferenceState): LambdaInferenceState =
      expr.terms.foldLeft(state)(walkTerm)

    def walkTerm(state: LambdaInferenceState, term: Term): LambdaInferenceState =
      term match
        case app:  App => walkApp(app, state)
        case cond: Cond =>
          walkExpr(cond.ifFalse, walkExpr(cond.ifTrue, walkExpr(cond.cond, state)))
        case group: TermGroup =>
          walkExpr(group.inner, state)
        case tuple: Tuple =>
          tuple.elements.toList.foldLeft(state) { (acc, elem) =>
            walkExpr(elem, acc)
          }
        case ref: Ref =>
          ref.qualifier match
            case Some(qualifier) => walkTerm(state, qualifier)
            case None => state
        case _: Lambda =>
          state
        case nested: Expr =>
          walkExpr(nested, state)
        case _ =>
          state

    def walkApp(app: App, state: LambdaInferenceState): LambdaInferenceState =
      val inferredFromCall =
        collectAppChain(app) match
          case (ref: Ref, args) =>
            extractTypeFnFromRef(ref, module) match
              case Some(fnType) =>
                args
                  .zip(fnType.paramTypes.toList)
                  .foldLeft(state) { case (acc, (argExpr, argType)) =>
                    recordInferenceFromExpr(argExpr, argType, acc, tracked, module)
                  }
              case None =>
                state
          case _ =>
            state

      app.fn match
        case lambda: Lambda if isSimpleLetBinding(lambda, app) =>
          val afterArg = walkExpr(app.arg, inferredFromCall)
          val aliasesWithBinding =
            bareRef(app.arg)
              .flatMap(trackedParamName(_, afterArg.aliases, tracked.keySet))
              .map(target => afterArg.aliases + (lambda.params.head.name -> target))
              .getOrElse(afterArg.aliases)
          walkExpr(lambda.body, afterArg.copy(aliases = aliasesWithBinding))
        case _ =>
          walkExpr(app.arg, walkAppFn(app.fn, inferredFromCall))

    val initialState = LambdaInferenceState(Map.empty, Map.empty, Set.empty, Vector.empty)
    val finalState   = walkExpr(body, initialState)
    val uninferred   = tracked.keySet -- finalState.inferred.keySet -- finalState.conflicted
    val missingErrors =
      uninferred.toVector.map(name => TypeError.UninferrableLambdaParam(tracked(name), phaseName))

    LambdaInferenceResult(
      finalState.inferred,
      finalState.conflicted ++ uninferred,
      finalState.errors ++ missingErrors
    )

  /** Collect IDs of top-level members that this member's body references. */
  private def collectMemberDeps(member: Member, topLevelIds: Set[String]): Set[String] =
    member match
      case bnd: Bnd =>
        val refs = mutable.Set.empty[String]
        def walkExpr(expr: Expr): Unit = expr.terms.foreach(walkTerm)
        def walkTerm(term: Term): Unit = term match
          case ref: Ref =>
            ref.resolvedId.foreach(id => if topLevelIds.contains(id) then refs += id)
            ref.qualifier.foreach(walkTerm)
          case app: App =>
            walkAppFn(app.fn); walkExpr(app.arg)
          case cond: Cond =>
            walkExpr(cond.cond); walkExpr(cond.ifTrue); walkExpr(cond.ifFalse)
          case group:  TermGroup => walkExpr(group.inner)
          case tuple:  Tuple => tuple.elements.toList.foreach(walkExpr)
          case lambda: Lambda => walkExpr(lambda.body)
          case e:      Expr => walkExpr(e)
          case _ => ()
        def walkAppFn(fn: Ref | App | Lambda): Unit = fn match
          case ref: Ref => ref.resolvedId.foreach(id => if topLevelIds.contains(id) then refs += id)
          case app: App => walkAppFn(app.fn); walkExpr(app.arg)
          case lambda: Lambda => walkExpr(lambda.body)
        bnd.value.terms match
          case (lambda: Lambda) :: _ => walkExpr(lambda.body)
          case _ => walkExpr(bnd.value)
        bnd.id.foreach(refs -= _) // exclude self-references
        refs.toSet
      case _ => Set.empty

  /** Topologically sort members so callees are checked before callers. Members in cycles are
    * appended in source order (they will produce errors requiring type annotations).
    */
  private def topologicalOrder(
    members:      List[Member],
    deps:         Map[String, Set[String]],
    needsReorder: Set[String],
    untyped:      Set[String]
  ): List[Member] =
    // Only edges to untyped members matter — typed members' types are already known
    val filteredDeps = deps.view.mapValues(_.intersect(untyped)).toMap
    val adjOut       = mutable.Map.empty[String, mutable.Set[String]]
    val inDeg        = mutable.Map.from(needsReorder.map(_ -> 0))
    filteredDeps.foreach { case (memberId, depIds) =>
      if needsReorder.contains(memberId) then
        depIds.foreach { depId =>
          adjOut.getOrElseUpdate(depId, mutable.Set.empty) += memberId
          inDeg(memberId) = inDeg.getOrElse(memberId, 0) + 1
        }
    }
    val queue  = mutable.Queue.from(needsReorder.filter(inDeg.getOrElse(_, 0) == 0))
    val sorted = mutable.ListBuffer.empty[String]
    while queue.nonEmpty do
      val id = queue.dequeue()
      sorted += id
      adjOut.getOrElse(id, mutable.Set.empty).foreach { dep =>
        inDeg(dep) = inDeg(dep) - 1
        if inDeg(dep) == 0 then queue.enqueue(dep)
      }
    // Cycle members: not in sorted, append in source order
    val sortedSet = sorted.toSet
    val cycleMembers = members.collect {
      case b: Bnd if b.id.exists(id => needsReorder(id) && !sortedSet(id)) => b.id.get
    }
    val topoOrder = sorted.toList ++ cycleMembers
    // Collect source positions of reorderable members, place topo-sorted members into those slots
    val reorderSlots = members.zipWithIndex.collect {
      case (bnd: Bnd, idx) if bnd.id.exists(needsReorder) => idx
    }
    val memberById = members.collect {
      case bnd: Bnd if bnd.id.isDefined => bnd.id.get -> bnd
    }.toMap
    val result = members.toArray
    topoOrder.zip(reorderSlots).foreach { case (id, slot) =>
      memberById.get(id).foreach(result(slot) = _)
    }
    result.toList

  /** Main entry point - process module and accumulate errors */
  def rewriteModule(state: CompilerState): CompilerState =
    // First pass: lower type ascriptions to specs for all functions and operators
    val (signatureErrors, membersWithSignatures, resolvablesAfterSignatures) =
      lowerAscriptionsToSpecs(state.module)

    // Create the module with lowered type specs and updated resolvables
    val moduleWithSignatures = state.module.copy(
      members     = membersWithSignatures,
      resolvables = resolvablesAfterSignatures
    )

    // Topological sort: check callees before callers so forward references resolve
    val topLevelIds = membersWithSignatures.collect {
      case bnd: Bnd if bnd.id.isDefined => bnd.id.get
    }.toSet
    val deps = membersWithSignatures.collect {
      case bnd: Bnd if bnd.id.isDefined =>
        bnd.id.get -> collectMemberDeps(bnd, topLevelIds)
    }.toMap
    // All Bnds participate in reordering — a member WITH a typeSpec can still
    // depend on one WITHOUT, so limiting to typeSpec-less members is insufficient.
    val needsReorder = topLevelIds
    // Only edges to untyped members create real ordering constraints
    val untyped = membersWithSignatures.collect {
      case bnd: Bnd if bnd.id.isDefined && bnd.typeSpec.isEmpty => bnd.id.get
    }.toSet
    val sortedMembers = topologicalOrder(membersWithSignatures, deps, needsReorder, untyped)

    // Second pass: check all members in topological order
    val initialState =
      (Vector.empty[TypeError], Map.empty[String, Member], moduleWithSignatures.resolvables)
    val (checkErrors, checkedById, finalResolvables) =
      sortedMembers.foldLeft(initialState) { case ((accErrors, accChecked, resolvables), member) =>
        // Build module with checked versions of already-processed members
        val currentMembers = membersWithSignatures.map {
          case bnd: Bnd if bnd.id.isDefined => accChecked.getOrElse(bnd.id.get, bnd)
          case other => other
        }
        val currentModule =
          moduleWithSignatures.copy(members = currentMembers, resolvables = resolvables)
        val result = checkMember(member, currentModule)
        val newResolvables = result.value match
          case bnd: Bnd => resolvables.updated(bnd)
          case _ => resolvables
        val newChecked = result.value match
          case bnd: Bnd if bnd.id.isDefined => accChecked + (bnd.id.get -> result.value)
          case _ => accChecked
        (accErrors ++ result.errors, newChecked, newResolvables)
      }

    // Restore source order
    val checkedMembers = membersWithSignatures.map {
      case bnd: Bnd if bnd.id.isDefined => checkedById.getOrElse(bnd.id.get, bnd)
      case other => other
    }

    val allErrors = (signatureErrors ++ checkErrors).map(SemanticError.TypeCheckingError.apply)
    state
      .addErrors(allErrors.toList)
      .withModule(
        moduleWithSignatures.copy(members = checkedMembers, resolvables = finalResolvables)
      )

  /** First pass: lower mandatory type ascriptions to type specs */
  private def lowerAscriptionsToSpecs(
    module: Module
  ): (Vector[TypeError], List[Member], ResolvablesIndex) =
    val (errors, members, resolvables) =
      module.members.foldLeft(
        (Vector.empty[TypeError], Vector.empty[Member], module.resolvables)
      ) { case ((accErrors, accMembers, resolvables), member) =>
        member match
          // Handle Bnd with Lambda (functions and operators with meta)
          case bnd: Bnd if bnd.meta.isDefined =>
            bnd.value.terms match
              case (lambda: Lambda) :: rest =>
                validateMandatoryAscriptions(bnd) match
                  case Nil =>
                    // Lower param type ascriptions to specs
                    val updatedParams = lambda.params.map(p => p.copy(typeSpec = p.typeAsc))
                    val returnType    = lambda.typeAsc.orElse(bnd.typeAsc)
                    val loweredTypeSpec =
                      returnType.flatMap(buildFunctionTypeSpec(bnd.source, updatedParams, _))
                    val updatedLambda = lambda.copy(params = updatedParams)
                    val updatedValue  = bnd.value.copy(terms = updatedLambda :: rest)
                    val updatedBnd    = bnd.copy(value = updatedValue, typeSpec = loweredTypeSpec)
                    (accErrors, accMembers :+ updatedBnd, resolvables.updated(updatedBnd))
                  case errs => (accErrors ++ errs, accMembers :+ bnd, resolvables)
              case _ =>
                // Bnd with meta but no Lambda - shouldn't happen, pass through
                (accErrors, accMembers :+ bnd, resolvables)

          case other =>
            // Other members don't need ascription lowering
            (accErrors, accMembers :+ other, resolvables)
      }
    (errors, members.toList, resolvables)

  /** Check if an expression contains a NativeImpl */
  private def hasNativeImpl(expr: Expr): Boolean =
    expr.terms.exists {
      case _:          NativeImpl => true
      case nestedExpr: Expr => hasNativeImpl(nestedExpr)
      case _ => false
    }

  /** Validate and compute types for a member */
  private def checkMember(member: Member, module: Module): CheckResult[Member] =
    member match
      case bnd: Bnd =>
        // Handle Bnd with Lambda (functions/operators)
        bnd.value.terms match
          case (lambda: Lambda) :: rest if bnd.meta.isDefined =>
            // Build param context from lambda params
            val paramContext = lambda.params.map(p => p.name -> p).toMap
            val returnType   = lambda.typeAsc.orElse(bnd.typeAsc)
            val bindingName  = bindingDisplayName(bnd)
            val checkedBody =
              checkExprWithContext(lambda.body, module, paramContext, returnType, bindingName)
            val transformedErrors =
              if returnType.isDefined then checkedBody.errors
              else
                checkedBody.errors.map {
                  case TypeError.UnresolvableType(
                        _,
                        Some(UnresolvableTypeContext.NamedValue(name)),
                        _
                      ) if name == bnd.name =>
                    TypeError.RecursiveFunctionMissingReturnType(bnd, phaseName)
                  case other => other
                }

            val (finalReturnType, returnErrors) = returnType match
              case Some(explicitReturn) =>
                if hasNativeImpl(lambda.body) then (Some(explicitReturn), Vector.empty)
                else
                  checkedBody.value.typeSpec match
                    case Some(actualType)
                        if areTypesCompatible(explicitReturn, actualType, module) =>
                      (Some(explicitReturn), Vector.empty)
                    case Some(actualType) =>
                      val displayName = bnd.meta.map(_.originalName).getOrElse(bnd.name)
                      (
                        Some(explicitReturn),
                        Vector(
                          TypeError.TypeMismatch(
                            bnd,
                            explicitReturn,
                            actualType,
                            phaseName,
                            Some(displayName)
                          )
                        )
                      )
                    case None =>
                      val errors =
                        if checkedBody.errors.isEmpty then
                          Vector(TypeError.UnresolvableType(bnd, None, phaseName))
                        else Vector.empty
                      (Some(explicitReturn), errors)
              case None =>
                checkedBody.value.typeSpec match
                  case Some(inferredType) => (Some(inferredType), Vector.empty)
                  case None =>
                    val errors =
                      if checkedBody.errors.isEmpty then
                        Vector(TypeError.UnresolvableType(bnd, None, phaseName))
                      else Vector.empty
                    (None, errors)

            val updatedLambda = lambda.copy(body = checkedBody.value)
            val updatedValue  = bnd.value.copy(terms = updatedLambda :: rest)
            val updatedBnd = bnd.copy(
              value = updatedValue,
              typeSpec = finalReturnType.flatMap(
                buildFunctionTypeSpec(bnd.source, lambda.params, _)
              )
            )
            CheckResult(updatedBnd, transformedErrors ++ returnErrors)

          case _ =>
            // Regular Bnd (no meta/lambda) - simple value binding
            val checkedValue = checkExpr(bnd.value, module, bnd.typeAsc, bindingDisplayName(bnd))
            val ascriptionErrors = validateTypeAscription(
              bnd.copy(typeSpec = checkedValue.value.typeSpec),
              module
            ).toVector
            CheckResult(
              bnd.copy(value = checkedValue.value, typeSpec = checkedValue.value.typeSpec),
              checkedValue.errors ++ ascriptionErrors
            )

      case _: TypeDef | _: TypeAlias =>
        // Type definitions and aliases are handled by TypeResolver, no checks needed here
        CheckResult.ok(member)

      case other =>
        // Other member types do not require type checking at this stage
        CheckResult.ok(other)

  /** Validate mandatory ascriptions for functions/operators
    *
    * Eventually, this will not be required; we will just generate type params, for the next type
    * checker phase.
    */
  private def validateMandatoryAscriptions(member: Member): List[TypeError] = member match
    // Handle Bnd with Lambda (functions and operators)
    case bnd: Bnd =>
      (bnd.meta, bnd.value.terms) match
        case (Some(meta), (lambda: Lambda) :: _) =>
          val returnTypeAsc = lambda.typeAsc.orElse(bnd.typeAsc)
          val isNative      = hasNativeImpl(lambda.body)

          meta.origin match
            case BindingOrigin.Function | BindingOrigin.Constructor | BindingOrigin.Destructor =>
              val paramErrors = lambda.params.collect {
                case param if param.typeAsc.isEmpty =>
                  TypeError.MissingParameterType(param, bnd, phaseName)
              }
              val returnError =
                if isNative && returnTypeAsc.isEmpty then
                  List(TypeError.MissingReturnType(bnd, phaseName))
                else Nil
              paramErrors ++ returnError

            case BindingOrigin.Operator =>
              val paramErrors = lambda.params.collect {
                case param if param.typeAsc.isEmpty =>
                  TypeError.MissingOperatorParameterType(param, bnd, phaseName)
              }
              val returnError =
                if isNative && returnTypeAsc.isEmpty then
                  List(TypeError.MissingOperatorReturnType(bnd, phaseName))
                else Nil
              paramErrors ++ returnError
        case _ => Nil

    case _ =>
      Nil

  /** Type check expressions using forward propagation with parameter context */
  private def checkExprWithContext(
    expr:         Expr,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[Type],
    bindingName:  String
  ): CheckResult[Expr] =
    expr.terms match
      case List(singleTerm) =>
        val checkedTerm =
          checkTermWithContext(singleTerm, module, paramContext, expectedType, bindingName)
        val finalTerm     = normalizeCheckedTerm(checkedTerm.value)
        val termAscErrors = validateTypeAscription(finalTerm, module).toVector
        CheckResult(
          expr.copy(terms = List(finalTerm), typeSpec = finalTerm.typeSpec),
          checkedTerm.errors ++ termAscErrors
        )
      case terms =>
        // Check all terms in the expression
        val lastIndex = terms.size - 1
        val (errors, checkedTerms) =
          terms.foldLeft((Vector.empty[TypeError], Vector.empty[Term])) {
            case ((accErrors, accTerms), term) =>
              val termIndex = accTerms.size
              val termExpectedType =
                if termIndex == lastIndex then expectedType else None
              val checkedTerm = term match
                case e: Expr =>
                  checkExprWithContext(e, module, paramContext, termExpectedType, bindingName)
                case t =>
                  checkTermWithContext(t, module, paramContext, termExpectedType, bindingName)
              val finalTerm     = normalizeCheckedTerm(checkedTerm.value)
              val termAscErrors = validateTypeAscription(finalTerm, module).toVector
              (accErrors ++ checkedTerm.errors ++ termAscErrors, accTerms :+ finalTerm)
          }
        // The type of the expression is the type of the last term
        val exprType = checkedTerms.lastOption.flatMap { case t: Typeable =>
          t.typeSpec
        }
        CheckResult(expr.copy(terms = checkedTerms.toList, typeSpec = exprType), errors)

  /** Type check expressions using forward propagation */
  private def checkExpr(
    expr:         Expr,
    module:       Module,
    expectedType: Option[Type],
    bindingName:  String
  ): CheckResult[Expr] =
    checkExprWithContext(expr, module, Map.empty, expectedType, bindingName)

  /** Type check individual terms with parameter context */
  private def checkTermWithContext(
    term:         Term,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[Type],
    bindingName:  String
  ): CheckResult[Term] =
    term match
      case ref: Ref if ref.qualifier.isDefined =>
        checkSelectionRef(ref, module, paramContext, bindingName)
      case ref: Ref =>
        // First check parameter context, then fall back to normal resolution
        paramContext.get(ref.name) match
          case Some(param) =>
            param.typeSpec match
              case Some(t) => CheckResult.ok(ref.copy(typeSpec = Some(t)))
              case None =>
                CheckResult(
                  ref,
                  Vector(
                    TypeError.UnresolvableType(
                      ref,
                      Some(UnresolvableTypeContext.NamedValue(ref.name)),
                      phaseName
                    )
                  )
                )
          case None =>
            checkRef(ref, module)

      case app: App =>
        checkApplicationWithContext(app, module, paramContext, bindingName)

      case cond: Cond =>
        checkConditionalWithContext(cond, module, paramContext, expectedType, bindingName)

      case group: TermGroup =>
        checkExprWithContext(group.inner, module, paramContext, expectedType, bindingName)
          .map(checkedInner => group.copy(inner = checkedInner))

      case lambda: Lambda =>
        // Handle Lambda terms (e.g., from eta-expansion of partial applications)
        checkLambdaWithContext(lambda, module, paramContext, bindingName, expectedType)

      case other =>
        // For other terms, use the regular checkTerm
        checkTerm(other, module, expectedType, bindingName)

  /** Type check individual terms */
  private def checkTerm(
    term:         Term,
    module:       Module,
    expectedType: Option[Type],
    bindingName:  String
  ): CheckResult[Term] =
    term match
      case lit: LiteralValue =>
        // Literals have their types defined directly, so they are already "checked"
        CheckResult.ok(lit)

      case ref: Ref if ref.qualifier.isDefined =>
        checkSelectionRef(ref, module, Map.empty, bindingName)

      case ref: Ref =>
        checkRef(ref, module)

      case app: App =>
        checkApplication(app, module, bindingName)

      case cond: Cond =>
        checkConditional(cond, module, expectedType, bindingName)

      case group: TermGroup =>
        checkExpr(group.inner, module, expectedType, bindingName).map(checkedInner =>
          group.copy(inner = checkedInner)
        )

      case hole: Hole =>
        expectedType match
          case Some(t) => CheckResult.ok(hole.copy(typeSpec = Some(t)))
          case None =>
            val displayBindingName = normalizeBindingName(bindingName)
            val errors = hole.spanOpt match
              case Some(span) =>
                Vector(TypeError.UntypedHoleInBinding(displayBindingName, span, phaseName))
              case None =>
                Vector(TypeError.UnresolvableType(hole, None, phaseName))
            CheckResult(hole, errors)

      case other =>
        // Other term types do not require type checking at this stage
        CheckResult.ok(other)

  private def checkSelectionRef(
    ref:          Ref,
    module:       Module,
    paramContext: Map[String, FnParam],
    bindingName:  String
  ): CheckResult[Ref] =
    ref.qualifier match
      case None =>
        checkRef(ref, module)
      case Some(qualifier) =>
        val checkedQualifier =
          checkTermWithContext(qualifier, module, paramContext, None, bindingName)
        val baseType = checkedQualifier.value.typeSpec
        val baseErrors =
          if baseType.isEmpty && checkedQualifier.errors.isEmpty then
            Vector(TypeError.UnresolvableType(checkedQualifier.value, None, phaseName))
          else Vector.empty

        val (fieldType, selectionErrors) = baseType match
          case Some(resolvedBase) =>
            resolveStructType(resolvedBase, module) match
              case Some(struct) =>
                struct.fields.find(_.name == ref.name) match
                  case Some(field) => (Some(field.typeSpec), Vector.empty)
                  case None =>
                    (None, Vector(TypeError.UnknownField(ref, struct, phaseName)))
              case None =>
                (None, Vector(TypeError.InvalidSelection(ref, resolvedBase, phaseName)))
          case None =>
            (None, Vector.empty)

        CheckResult(
          ref.copy(qualifier = Some(checkedQualifier.value), typeSpec = fieldType),
          checkedQualifier.errors ++ baseErrors ++ selectionErrors
        )

  /** Resolve a Ref's type from module resolvables (best-effort, no errors). */
  private def resolveRefType(ref: Ref, module: Module): Option[Type] =
    ref.resolvedId.flatMap(module.resolvables.lookup).flatMap {
      case param: FnParam => param.typeSpec.orElse(param.typeAsc)
      case decl:  Decl => decl.typeSpec
      case _ => None
    }

  /** Check ref using module lookups */
  private def checkRef(ref: Ref, module: Module): CheckResult[Ref] =
    // Look up the declaration in the current module to get the computed typeSpec
    ref.resolvedId.flatMap(module.resolvables.lookup) match
      case Some(param: FnParam) =>
        // For parameters, use their type spec (lowered from ascription)
        param.typeSpec match
          case Some(t) =>
            CheckResult.ok(ref.copy(typeSpec = Some(t)))
          case None =>
            CheckResult(
              ref,
              Vector(
                TypeError.UnresolvableType(
                  ref,
                  Some(UnresolvableTypeContext.NamedValue(ref.name)),
                  phaseName
                )
              )
            )
      case Some(decl: Decl) =>
        // Look up the declaration in the current module (which has lowered typeSpecs)
        val updatedDecl = module.members.find {
          case candidate: Bnd if decl.isInstanceOf[Bnd] =>
            candidate.name == decl.name
          case _ => false
        }

        updatedDecl match
          case Some(d: Decl) if d.typeSpec.isDefined =>
            CheckResult.ok(ref.copy(typeSpec = d.typeSpec))
          case _ =>
            // Fallback to the resolved declaration's typeSpec if available
            decl.typeSpec match
              case Some(t) => CheckResult.ok(ref.copy(typeSpec = Some(t)))
              case None =>
                CheckResult(
                  ref,
                  Vector(
                    TypeError.UnresolvableType(
                      ref,
                      Some(UnresolvableTypeContext.NamedValue(ref.name)),
                      phaseName
                    )
                  )
                )
      case _ =>
        CheckResult(
          ref,
          Vector(
            TypeError.UnresolvableType(
              ref,
              Some(UnresolvableTypeContext.NamedValue(ref.name)),
              phaseName
            )
          )
        )

  /** Check function applications with parameter context */
  private def checkApplicationWithContext(
    app:          App,
    module:       Module,
    paramContext: Map[String, FnParam],
    bindingName:  String
  ): CheckResult[App] =
    // Special case: immediately-applied lambda (from let-expression desugaring)
    // Must check arg first to infer lambda param type
    app.fn match
      case lambda: Lambda if lambda.params.nonEmpty =>
        checkImmediatelyAppliedLambda(app, lambda, module, paramContext, bindingName)
      case _ =>
        checkNormalApplication(app, module, paramContext, bindingName)

  /** Check immediately-applied lambda: App(Lambda([param], body), arg)
    *
    * For let-expressions like `let a = 1; a + 1`, the parser desugars to
    * `App(Lambda([a], a + 1), 1)`. The param has no type annotation, so we must check the arg first
    * to infer the param type, then check the body.
    */
  private def checkImmediatelyAppliedLambda(
    app:          App,
    lambda:       Lambda,
    module:       Module,
    paramContext: Map[String, FnParam],
    bindingName:  String
  ): CheckResult[App] =
    // Step 1: Check arg first to get its type
    val param          = lambda.params.head
    val argBindingName = normalizeBindingName(param.name)
    // For recursive let bindings: if the arg is a lambda with a return type
    // ascription, pre-seed the binding's type so the lambda body can
    // reference the binding (same as fn return type annotations).
    // Also handle when the let binding itself has a type ascription
    // (e.g., `let loop: ForeverFn = { ... loop() ... }`).
    val argContextWithSelf = app.arg.terms match
      case List(argLambda: Lambda) if argLambda.typeAsc.isDefined =>
        val preType = buildFunctionTypeSpec(
          argLambda.source,
          argLambda.params,
          argLambda.typeAsc.get
        )
        preType match
          case Some(fnType) =>
            val preParam = param.copy(typeSpec = Some(fnType))
            paramContext + (param.name -> preParam)
          case None => paramContext
      case List(_: Lambda) if param.typeAsc.isDefined =>
        val preParam = param.copy(typeSpec = param.typeAsc)
        paramContext + (param.name -> preParam)
      case _ => paramContext
    val checkedArg =
      checkExprWithContext(app.arg, module, argContextWithSelf, param.typeAsc, argBindingName)
    val argType = checkedArg.value.typeSpec

    // Step 2: Assign inferred type to param (use typeAsc if present, else inferred)
    val paramErrors =
      (param.typeAsc, argType) match
        case (Some(expectedType), Some(actualType))
            if !areTypesCompatible(expectedType, actualType, module) =>
          val expectedBy =
            if param.name == statementParamName then Some("statement") else Some(param.name)
          Vector(TypeError.TypeMismatch(app.arg, expectedType, actualType, phaseName, expectedBy))
        case _ => Vector.empty

    val typedParamType = param.typeAsc.orElse(argType)
    val typedParam     = param.copy(typeSpec = typedParamType)

    // Step 3: Build param context with typed param
    val lambdaParamContext = paramContext + (typedParam.name -> typedParam)

    // Step 4: Check lambda body with typed param
    val checkedBody =
      checkExprWithContext(lambda.body, module, lambdaParamContext, None, bindingName)
    val bodyType = checkedBody.value.typeSpec
    val bodyErrors =
      if bodyType.isEmpty && checkedBody.errors.isEmpty then
        Vector(TypeError.UnresolvableType(lambda.body, None, phaseName))
      else Vector.empty

    // Step 5: Build result
    val lambdaTypeSpec = for
      paramType <- typedParam.typeSpec
      returnType <- bodyType
    yield TypeFn(lambda.source, NonEmptyList.one(paramType), returnType)

    val checkedLambda = lambda.copy(
      params   = List(typedParam),
      body     = checkedBody.value,
      typeSpec = lambdaTypeSpec
    )

    val argErrors =
      if argType.isEmpty && checkedArg.errors.isEmpty then
        Vector(
          TypeError.UnresolvableType(
            app.arg,
            Some(UnresolvableTypeContext.Argument),
            phaseName
          )
        )
      else Vector.empty

    CheckResult(
      app.copy(fn = checkedLambda, arg = checkedArg.value, typeSpec = bodyType),
      checkedArg.errors ++ paramErrors ++ checkedBody.errors ++ argErrors ++ bodyErrors
    )

  /** Check normal application (non-lambda function) */
  private def checkNormalApplication(
    app:          App,
    module:       Module,
    paramContext: Map[String, FnParam],
    bindingName:  String
  ): CheckResult[App] =
    // Phase 1: Recursively check and type all sub-nodes
    val checkedFnEither = app.fn match
      case innerApp: App => checkApplicationWithContext(innerApp, module, paramContext, bindingName)
      case other => checkTermWithContext(other, module, paramContext, None, bindingName)

    val expectedArgType = expectedArgumentType(checkedFnEither.value, module)
    val checkedArgEither =
      checkExprWithContext(app.arg, module, paramContext, expectedArgType, bindingName)

    // Phase 2: Validate function shape, determine application type, and build result
    val checkedFn  = checkedFnEither.value
    val checkedArg = checkedArgEither.value
    val normalizedFn: Ref | App | Lambda = checkedFn match
      case ref:      Ref => ref
      case innerApp: App => innerApp
      case lambda:   Lambda => lambda
      case _ => app.fn

    val fnErrors =
      checkedFn match
        case _: Ref | _: App | _: Lambda => Vector.empty
        case _ =>
          Vector(
            TypeError.InvalidApplication(
              app,
              TypeRef(app.source, "invalid-fn"),
              TypeRef(app.source, "unknown-arg"),
              phaseName
            )
          )

    val appTypeResult =
      if fnErrors.isEmpty then
        determineApplicationType(
          app.copy(fn = normalizedFn, arg = checkedArg),
          normalizedFn,
          module
        )
      else CheckResult.ok(None)

    val typeErrors =
      filterDuplicateUnresolvable(
        appTypeResult.errors,
        checkedFnEither.errors,
        checkedArgEither.errors
      )

    CheckResult(
      app.copy(fn = normalizedFn, arg = checkedArg, typeSpec = appTypeResult.value),
      checkedFnEither.errors ++ checkedArgEither.errors ++ fnErrors ++ typeErrors
    )

  /** Determine the type of an application based on its function and argument with validation */
  private def determineApplicationType(
    app:       App,
    checkedFn: Term,
    module:    Module
  ): CheckResult[Option[Type]] =
    checkedFn match
      case ref: Ref =>
        determineRefApplicationType(app, ref, module)

      case innerApp: App if innerApp.typeSpec.isDefined =>
        innerApp.typeSpec match
          case Some(fnType: TypeFn) =>
            applyTypeFnToArgument(app, fnType, app.arg.typeSpec, module, None)
          case Some(other) =>
            app.arg.typeSpec match
              case Some(argType) =>
                CheckResult(
                  None,
                  Vector(TypeError.InvalidApplication(app, other, argType, phaseName))
                )
              case None =>
                CheckResult(
                  None,
                  Vector(
                    TypeError.UnresolvableType(
                      app.arg,
                      Some(UnresolvableTypeContext.Argument),
                      phaseName
                    )
                  )
                )
          case None =>
            CheckResult(
              None,
              Vector(
                TypeError.UnresolvableType(
                  app,
                  Some(UnresolvableTypeContext.Function),
                  phaseName
                )
              )
            )

      case _ =>
        val fnType  = checkedFn.typeSpec
        val argType = app.arg.typeSpec
        (fnType, argType) match
          case (Some(actualFnType), Some(actualArgType)) =>
            CheckResult(
              None,
              Vector(TypeError.InvalidApplication(app, actualFnType, actualArgType, phaseName))
            )
          case (Some(_), None) =>
            CheckResult(
              None,
              Vector(
                TypeError.UnresolvableType(
                  app.arg,
                  Some(UnresolvableTypeContext.Argument),
                  phaseName
                )
              )
            )
          case (None, _) =>
            CheckResult(
              None,
              Vector(
                TypeError.UnresolvableType(
                  app,
                  Some(UnresolvableTypeContext.Function),
                  phaseName
                )
              )
            )

  private def determineRefApplicationType(
    app:    App,
    ref:    Ref,
    module: Module
  ): CheckResult[Option[Type]] =
    if ref.qualifier.isDefined then
      extractTypeFnFromRef(ref, module) match
        case Some(fnType) =>
          applyTypeFnToArgument(app, fnType, app.arg.typeSpec, module, Some(ref.name))
        case None =>
          val refType = ref.typeSpec
          app.arg.typeSpec match
            case Some(argType) =>
              refType match
                case Some(actualRefType) =>
                  CheckResult(
                    None,
                    Vector(TypeError.InvalidApplication(app, actualRefType, argType, phaseName))
                  )
                case None =>
                  CheckResult(
                    None,
                    Vector(
                      TypeError.UnresolvableType(
                        ref,
                        Some(UnresolvableTypeContext.NamedValue(ref.name)),
                        phaseName
                      )
                    )
                  )
            case None =>
              CheckResult(
                None,
                Vector(
                  TypeError.UnresolvableType(
                    app.arg,
                    Some(UnresolvableTypeContext.Argument),
                    phaseName
                  )
                )
              )
    else
      ref.resolvedId match
        case None =>
          CheckResult(
            None,
            Vector(
              TypeError.UnresolvableType(
                ref,
                Some(UnresolvableTypeContext.NamedValue(ref.name)),
                phaseName
              )
            )
          )
        case Some(_) =>
          extractTypeFnFromRef(ref, module) match
            case Some(fnType) =>
              applyTypeFnToArgument(app, fnType, app.arg.typeSpec, module, Some(ref.name))
            case None =>
              val refType = ref.typeSpec
              app.arg.typeSpec match
                case Some(argType) =>
                  refType match
                    case Some(actualRefType) =>
                      CheckResult(
                        None,
                        Vector(TypeError.InvalidApplication(app, actualRefType, argType, phaseName))
                      )
                    case None =>
                      CheckResult(
                        None,
                        Vector(
                          TypeError.UnresolvableType(
                            ref,
                            Some(UnresolvableTypeContext.NamedValue(ref.name)),
                            phaseName
                          )
                        )
                      )
                case None =>
                  CheckResult(
                    None,
                    Vector(
                      TypeError.UnresolvableType(
                        app.arg,
                        Some(UnresolvableTypeContext.Argument),
                        phaseName
                      )
                    )
                  )

  private def extractTypeFnFromRef(ref: Ref, module: Module): Option[TypeFn] =
    val directType = ref.typeSpec.flatMap(extractTypeFn(_, module))
    directType.orElse {
      ref.resolvedId.flatMap(module.resolvables.lookup).flatMap {
        case decl:  Decl => findTypeFnForDecl(decl, module)
        case param: FnParam => param.typeSpec.flatMap(extractTypeFn(_, module))
        case _ => None
      }
    }

  private def expectedArgumentType(checkedFn: Term, module: Module): Option[Type] =
    checkedFn match
      case ref: Ref =>
        extractTypeFnFromRef(ref, module).map(_.paramTypes.head)
      case app: App =>
        app.typeSpec.flatMap(extractTypeFn(_, module)).map(_.paramTypes.head)
      case lambda: Lambda =>
        lambda.typeSpec.flatMap(extractTypeFn(_, module)).map(_.paramTypes.head)
      case _ =>
        None

  private def extractTypeFn(typeSpec: Type, module: Module): Option[TypeFn] =
    unwrapTypeGroup(typeSpec) match
      case tf: TypeFn => Some(tf)
      case TypeScheme(_, _, bodyType) => extractTypeFn(bodyType, module)
      case tr: TypeRef =>
        val resolved = resolveAliasChain(tr, module)
        if resolved == tr then None else extractTypeFn(resolved, module)
      case _ => None

  private def findTypeFnForDecl(decl: Decl, module: Module): Option[TypeFn] =
    val updatedDecl = module.members.collectFirst {
      case candidate: Bnd if decl.isInstanceOf[Bnd] && candidate.name == decl.name => candidate
    }

    updatedDecl
      .collect { case d: Decl => d }
      .orElse(Some(decl))
      .flatMap(_.typeSpec.flatMap(extractTypeFn(_, module)))

  private def applyTypeFnToArgument(
    app:     App,
    fnType:  TypeFn,
    argType: Option[Type],
    module:  Module,
    fnLabel: Option[String]
  ): CheckResult[Option[Type]] =
    argType match
      case Some(actualArgType) =>
        fnType.paramTypes match
          case NonEmptyList(headParam, tailParams) =>
            val returnType =
              buildRemainingFunctionTypeFromTypes(tailParams, fnType.returnType, app.source)
            if areTypesCompatible(headParam, actualArgType, module) then
              CheckResult.ok(Some(returnType))
            else
              CheckResult(
                Some(returnType),
                Vector(
                  TypeError.TypeMismatch(
                    app.arg,
                    headParam,
                    actualArgType,
                    phaseName,
                    fnLabel
                  )
                )
              )
      case None =>
        CheckResult(
          None,
          Vector(
            TypeError.UnresolvableType(
              app.arg,
              Some(UnresolvableTypeContext.Argument),
              phaseName
            )
          )
        )

  /** Check function applications by collecting all arguments in a chain */
  private def checkApplication(
    app:         App,
    module:      Module,
    bindingName: String
  ): CheckResult[App] =
    checkApplicationWithContext(app, module, Map.empty, bindingName)

  private def buildFunctionTypeSpec(
    source:     SourceOrigin,
    params:     List[FnParam],
    returnType: Type
  ): Option[Type] =
    params
      .traverse(_.typeSpec)
      .map(paramTypes =>
        TypeFn(source, canonicalCallableParamTypes(source, paramTypes), returnType)
      )

  private def buildRemainingFunctionTypeFromTypes(
    remainingTypes: List[Type],
    returnType:     Type,
    source:         SourceOrigin
  ): Type =
    remainingTypes match
      case head :: tail => TypeFn(source, NonEmptyList(head, tail), returnType)
      case Nil => returnType

  /** Validate type ascription against computed type */
  private def validateTypeAscription(node: Typeable, module: Module): List[TypeError] =
    // Lambda typeAsc is the return type, not the full function type
    val (ascribed, computed) = (node, node.typeAsc, node.typeSpec) match
      case (_: Lambda, Some(asc), Some(TypeFn(_, _, ret))) => (Some(asc), Some(ret))
      case (_, asc, comp) => (asc, comp)
    (ascribed, computed) match
      case (Some(asc), Some(comp)) =>
        if areTypesCompatible(asc, comp, module) then Nil
        else List(TypeError.TypeMismatch(node, asc, comp, phaseName, None))
      case _ => Nil

  /** Check type compatibility (handles aliases, etc.) */
  private def areTypesCompatible(t1: Type, t2: Type, module: Module): Boolean =
    val resolved1 = unwrapTypeGroup(resolveAliasChain(t1, module))
    val resolved2 = unwrapTypeGroup(resolveAliasChain(t2, module))
    (resolved1, resolved2) match
      case (NativePrimitive(_, n1, _, _), NativePrimitive(_, n2, _, _)) => n1 == n2
      case (TypeRef(_, name1, _, _), TypeRef(_, name2, _, _)) => name1 == name2
      case (ts1: TypeStruct, ts2: TypeStruct) =>
        ts1.name == ts2.name
      case (ts: TypeStruct, TypeRef(_, actualName, _, _)) =>
        ts.name == actualName
      case (TypeRef(_, expectedName, _, _), ts: TypeStruct) =>
        expectedName == ts.name
      case (TypeFn(_, p1, r1), TypeFn(_, p2, r2)) =>
        p1.length == p2.length &&
        p1.zip(p2).forall { case (pt1, pt2) => areTypesCompatible(pt1, pt2, module) } &&
        areTypesCompatible(r1, r2, module)
      case (TypeTuple(_, e1), TypeTuple(_, e2)) =>
        e1.length == e2.length &&
        e1.zip(e2).forall { case (et1, et2) => areTypesCompatible(et1, et2, module) }
      case (TypeUnit(_), TypeUnit(_)) => true
      case (TypeUnit(_), TypeRef(_, "Unit", _, _)) => true
      case (TypeRef(_, "Unit", _, _), TypeUnit(_)) => true
      case _ => false

  private def resolveStructType(typeSpec: Type, module: Module): Option[TypeStruct] =
    unwrapTypeGroup(resolveAliasChain(typeSpec, module)) match
      case ts: TypeStruct => Some(ts)
      case tr: TypeRef =>
        val resolved = tr.resolvedId
          .flatMap(module.resolvables.lookupType)
          .orElse(module.members.collectFirst { case ts: TypeStruct if ts.name == tr.name => ts })
        resolved match
          case Some(ts: TypeStruct) => Some(ts)
          case Some(td: TypeDef) =>
            td.typeSpec.collect { case ns: NativeStruct =>
              val fields = ns.fields.map { case (name, t) =>
                Field(td.source, Name.synth(name), t)
              }.toVector
              TypeStruct(td.source, None, td.visibility, td.nameNode, fields, td.id)
            }
          case _ => None
      case _ => None

  private def canonicalCallableParamTypes(
    source:     SourceOrigin,
    paramTypes: List[Type]
  ): NonEmptyList[Type] =
    NonEmptyList
      .fromList(paramTypes)
      .getOrElse(NonEmptyList.one(TypeRef(source, "Unit", Some("stdlib::typedef::Unit"), Nil)))

  private def unwrapTypeGroup(typeSpec: Type): Type =
    typeSpec match
      case TypeGroup(_, types) if types.size == 1 => unwrapTypeGroup(types.head)
      case other => other

  /** Follow alias chain to concrete type and update typeSpec along the way */
  private def resolveAliasChain(typeSpec: Type, module: Module): Type = typeSpec match
    case tr: TypeRef =>
      // Look up the resolved type by ID, or fall back to name lookup in module
      val resolved = tr.resolvedId
        .flatMap(module.resolvables.lookupType)
        .orElse(module.members.collectFirst {
          case ta: TypeAlias if ta.name == tr.name => ta
          case td: TypeDef if td.name == tr.name => td
          case ts: TypeStruct if ts.name == tr.name => ts
        })
      resolved match
        case Some(ta: TypeAlias) =>
          ta.typeSpec match
            case Some(resolvedSpec) => resolvedSpec
            case None => resolveAliasChain(ta.typeRef, module)
        case Some(td: TypeDef) =>
          // Return TypeRef to the TypeDef, not its native typeSpec
          TypeRef(tr.source, td.name, td.id)
        case Some(ts: TypeStruct) =>
          TypeRef(tr.source, ts.name, ts.id)
        case _ => tr
    case other => other

  /** Check Lambda terms (e.g., from eta-expansion of partial applications) */
  private def checkLambdaWithContext(
    lambda:       Lambda,
    module:       Module,
    paramContext: Map[String, FnParam],
    bindingName:  String,
    expectedType: Option[Type]
  ): CheckResult[Lambda] =
    // Infer param types from expected function type when not annotated
    val expectedFn = expectedType.flatMap(extractTypeFn(_, module))
    val topDownParams = lambda.params.zipWithIndex.map { (p, i) =>
      if p.typeSpec.isDefined then p
      else if p.typeAsc.isDefined then p.copy(typeSpec = p.typeAsc)
      else p.copy(typeSpec = expectedFn.flatMap(_.paramTypes.toList.lift(i)))
    }
    val stillUntyped = topDownParams.filter(_.typeSpec.isEmpty)
    val inferenceResult =
      if stillUntyped.isEmpty then LambdaInferenceResult(Map.empty, Set.empty, Vector.empty)
      else inferParamTypesFromBody(stillUntyped, lambda.body, module)
    val paramsWithSpecs = topDownParams.map { param =>
      param.typeSpec match
        case Some(_) => param
        case None => param.copy(typeSpec = inferenceResult.inferred.get(param.name))
    }
    // Build param context from lambda params (merged with outer context)
    val lambdaParamContext = paramContext ++ paramsWithSpecs.map(p => p.name -> p).toMap
    // Use lambda's return type ascription (}: Type) as expected type for body
    val checkedBodyRaw =
      checkExprWithContext(lambda.body, module, lambdaParamContext, lambda.typeAsc, bindingName)
    val checkedBody =
      checkedBodyRaw.copy(
        errors = checkedBodyRaw.errors.filterNot {
          case TypeError.UnresolvableType(_, Some(UnresolvableTypeContext.NamedValue(name)), _) =>
            inferenceResult.failedParams.contains(name)
          case _ => false
        }
      )
    val bodyType = checkedBody.value.typeSpec
    val bodyErrors =
      if bodyType.isEmpty && checkedBody.errors.isEmpty && inferenceResult.errors.isEmpty then
        Vector(TypeError.UnresolvableType(lambda, None, phaseName))
      else Vector.empty
    val lambdaTypeSpec = bodyType.flatMap(buildFunctionTypeSpec(lambda.source, paramsWithSpecs, _))
    // Update capture Refs with types resolved from param context or module
    val typedCaptures = lambda.captures.map { cap =>
      val ref = cap.ref
      if ref.typeSpec.isDefined then cap
      else
        val captureType = lambdaParamContext
          .get(ref.name)
          .flatMap(p => p.typeSpec.orElse(p.typeAsc))
          .orElse(resolveRefType(ref, module))
        captureType match
          case Some(t) =>
            val typedRef = ref.copy(typeSpec = Some(t))
            cap match
              case Capture.CapturedRef(_) => Capture.CapturedRef(typedRef)
              case Capture.CapturedLiteral(_, id) => Capture.CapturedLiteral(typedRef, id)
          case None => cap
    }
    CheckResult(
      lambda.copy(
        params   = paramsWithSpecs,
        body     = checkedBody.value,
        captures = typedCaptures,
        typeSpec = lambdaTypeSpec
      ),
      inferenceResult.errors ++ checkedBody.errors ++ bodyErrors
    )

  /** Check conditional with parameter context */
  private def checkConditionalWithContext(
    cond:         Cond,
    module:       Module,
    paramContext: Map[String, FnParam],
    expectedType: Option[Type],
    bindingName:  String
  ): CheckResult[Cond] =
    val checkedCond = checkExprWithContext(cond.cond, module, paramContext, None, bindingName)
    val initialTrue =
      checkExprWithContext(cond.ifTrue, module, paramContext, expectedType, bindingName)
    val initialFalse =
      checkExprWithContext(cond.ifFalse, module, paramContext, expectedType, bindingName)

    val (checkedTrue, checkedFalse) =
      if expectedType.isDefined then (initialTrue, initialFalse)
      else
        (initialTrue.value.typeSpec, initialFalse.value.typeSpec) match
          case (Some(trueType), None) =>
            val recheckedFalse =
              checkExprWithContext(cond.ifFalse, module, paramContext, Some(trueType), bindingName)
            (initialTrue, recheckedFalse)
          case (None, Some(falseType)) =>
            val recheckedTrue =
              checkExprWithContext(cond.ifTrue, module, paramContext, Some(falseType), bindingName)
            (recheckedTrue, initialFalse)
          case _ =>
            (initialTrue, initialFalse)

    val condErrors =
      checkedCond.value.typeSpec match
        case Some(TypeRef(_, "Bool", _, _)) => Vector.empty
        case Some(other) if checkedCond.errors.isEmpty =>
          Vector(
            TypeError.TypeMismatch(
              checkedCond.value,
              TypeRef(cond.source, "Bool"),
              other,
              phaseName,
              None
            )
          )
        case None if checkedCond.errors.isEmpty =>
          Vector(
            TypeError.UnresolvableType(checkedCond.value, None, phaseName)
          )
        case _ => Vector.empty

    val trueType  = checkedTrue.value.typeSpec
    val falseType = checkedFalse.value.typeSpec

    val branchErrors =
      (trueType, falseType) match
        case (Some(t), Some(f)) if !areTypesCompatible(t, f, module) =>
          Vector(TypeError.ConditionalBranchTypeMismatch(cond, t, f, phaseName))
        case (None, _) if checkedTrue.errors.isEmpty =>
          Vector(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
        case (_, None) if checkedFalse.errors.isEmpty =>
          Vector(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
        case _ => Vector.empty

    val condTypeSpec =
      (trueType, falseType) match
        case (Some(t), Some(f)) if areTypesCompatible(t, f, module) => Some(t)
        case _ => expectedType.orElse(trueType).orElse(falseType)

    CheckResult(
      cond.copy(
        cond     = checkedCond.value,
        ifTrue   = checkedTrue.value,
        ifFalse  = checkedFalse.value,
        typeSpec = condTypeSpec
      ),
      checkedCond.errors ++ checkedTrue.errors ++ checkedFalse.errors ++ condErrors ++ branchErrors
    )

  /** Check conditional expressions (both branches must match) */
  private def checkConditional(
    cond:         Cond,
    module:       Module,
    expectedType: Option[Type],
    bindingName:  String
  ): CheckResult[Cond] =
    checkConditionalWithContext(cond, module, Map.empty, expectedType, bindingName)

  private def normalizeCheckedTerm(term: Term): Term =
    term match
      case app: App if app.typeSpec.isEmpty && app.typeAsc.isDefined =>
        // This is the error condition. If we are here, it means checkApplicationWithContext
        // is returning a malformed App node. We correct it immediately.
        app.copy(typeSpec = app.typeAsc, typeAsc = None)
      case other => other

  private def filterDuplicateUnresolvable(
    errors:           Vector[TypeError],
    checkedFnErrors:  Vector[TypeError],
    checkedArgErrors: Vector[TypeError]
  ): Vector[TypeError] =
    errors.filterNot {
      case TypeError.UnresolvableType(_, Some(UnresolvableTypeContext.Argument), _) =>
        checkedArgErrors.nonEmpty
      case TypeError.UnresolvableType(_, Some(UnresolvableTypeContext.Function), _) =>
        checkedFnErrors.nonEmpty
      case TypeError.UnresolvableType(_, Some(UnresolvableTypeContext.NamedValue(_)), _) =>
        checkedFnErrors.nonEmpty
      case _ => false
    }
