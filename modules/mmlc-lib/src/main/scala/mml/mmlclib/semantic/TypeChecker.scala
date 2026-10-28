package mml.mmlclib.semantic

import cats.data.{NonEmptyList, State}
import cats.implicits.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{CompilationError, DiagnosticId, DiagnosticRelation, DiagnosticStore}

import scala.annotation.tailrec

object TypeChecker:

  /** Check definitions in dependency order while retaining diagnostics in emission order. */
  def rewriteModule(state: CompilerState): CompilerState =
    val checking = for
      signatures <- state.module.members.traverse(lowerSignature)
      members           = signatures.map(_._1)
      parameterFailures = signatures.flatMap(_._2).toMap
      module = state.module.copy(
        members = members,
        resolvables = members.foldLeft(state.module.resolvables) {
          case (index, binding: Bnd) => index.updated(binding)
          case (index, _) => index
        }
      )
      topLevelIds = members.collect { case binding: Bnd => binding.id }.flatten.toSet
      dependencies = members.collect {
        case binding: Bnd if binding.id.isDefined =>
          binding.id.get -> collectMemberDeps(
            binding,
            topLevelIds,
            state.protocols,
            module.resolvables
          )
      }.toMap
      ordered = topologicalOrder(members, dependencies, topLevelIds)
      initial = ModuleCheckingState(
        module,
        Set.empty,
        DiagnosticCauses.fromModule(module),
        ValueAvailability.fromModule(module.copy(members = Nil))
      )
      checked <- ordered.foldM(initial) { (current, member) =>
        member match
          case binding: Bnd =>
            val context = CheckContext(
              current.module,
              state.protocols,
              Map.empty,
              current.causes,
              current.availability,
              bindingDisplayName(binding)
            )
            checkBinding(binding, context, parameterFailures).map(current.withChecked)
          case _ => current.pure[Check]
      }
    yield checked.module

    val (diagnostics, module) = checking.run(state.diagnostics).value
    val checked               = state.copy(diagnostics = diagnostics).withModule(module)
    validateIntegerRanges(validateResolvedReferences(checked))

  private val phaseName          = "mml.mmlclib.semantic.TypeChecker"
  private val statementParamName = "__stmt"
  private type Check[A] = State[DiagnosticStore, A]

  /** Callee failures are wrapped only where the enclosing syntax accepts an arbitrary term. */
  private case class Checked[+A](value: A, roots: Vector[DiagnosticId] = Vector.empty)

  private case class CheckContext(
    module:           Module,
    protocols:        ProtocolRegistry,
    params:           Map[String, FnParam],
    causes:           DiagnosticCauses,
    availability:     ValueAvailability,
    bindingName:      String,
    recursiveBinding: Option[Bnd]          = None,
    enclosingFailure: Vector[DiagnosticId] = Vector.empty
  ):

    def knownRootsOf(term: Term): Vector[DiagnosticId] = causes.knownRootsOf(term)

    def provenRootsOf(term: Term): Vector[DiagnosticId] = causes.provenRootsOf(term)

    def independent: CheckContext = copy(enclosingFailure = Vector.empty)

    def withBinding(param: FnParam, value: Expr): CheckContext =
      copy(
        params       = params + (param.name -> param),
        causes       = causes.withBinding(param, value),
        availability = availability.withBinding(param, value)
      )

  private case class ModuleCheckingState(
    module:       Module,
    checkedIds:   Set[String],
    causes:       DiagnosticCauses,
    availability: ValueAvailability
  ):

    def withChecked(binding: Bnd): ModuleCheckingState =
      val updated = module.copy(
        members = module.members.map {
          case original: Bnd if original.id == binding.id => binding
          case other => other
        },
        resolvables = module.resolvables.updated(binding)
      )
      val ids = checkedIds ++ binding.id
      val checkedModule = updated.copy(members = updated.members.filter {
        case member: Bnd => member.id.exists(ids)
        case _ => false
      })
      copy(
        module       = updated,
        checkedIds   = ids,
        causes       = causes.updatedModule(updated, binding),
        availability = availability.updatedModule(checkedModule, binding)
      )

  private def report(
    error: TypeError,
    roots: Vector[DiagnosticId] = Vector.empty
  ): Check[Vector[DiagnosticId]] =
    DiagnosticStore.report(SemanticError.TypeCheckingError(error), roots)

  private def reportAll(errors: List[TypeError]): Check[Vector[DiagnosticId]] =
    errors.traverse(report(_)).map(_.flatten.toVector.distinct)

  /** The diagnostic payload stays intact while the failed result cannot supply a value. */
  private def invalidResult(term: Term, roots: Vector[DiagnosticId]): Term =
    if roots.isEmpty then term
    else
      term match
        case invalid: InvalidExpression =>
          invalid.copy(causes = (invalid.causes ++ roots).distinct)
        case other =>
          InvalidExpression(
            source       = other.source,
            originalExpr = Expr(other.source, List(other), typeSpec = other.typeSpec),
            typeSpec     = other.typeSpec,
            typeAsc      = other.typeAsc,
            causes       = roots.distinct
          )

  private def invalidExpr(expr: Expr, roots: Vector[DiagnosticId]): Expr =
    if roots.isEmpty then expr
    else
      expr.terms match
        case List(invalid: InvalidExpression) =>
          expr.copy(terms = List(invalidResult(invalid, roots)))
        case _ =>
          expr.copy(terms =
            List(
              InvalidExpression(
                expr.source,
                expr,
                typeSpec = expr.typeSpec,
                typeAsc  = expr.typeAsc,
                causes   = roots.distinct
              )
            )
          )

  private def failedType(tpe: Type, context: CheckContext): Boolean =
    context.causes.knownRootsOfType(tpe).nonEmpty ||
      context.availability.containsInvalidType(tpe)

  private def resultRoots(
    checked: Checked[Term],
    context: CheckContext
  ): Vector[DiagnosticId] =
    if checked.roots.nonEmpty then checked.roots else context.provenRootsOf(checked.value)

  /** Constraints and lexical aliases are keyed by resolved binding identity. */
  private case class LambdaInferenceState(
    inferred:   Map[String, Type],
    aliases:    Map[String, String],
    conflicted: Set[String],
    errors:     Vector[TypeError]
  )

  private case class LambdaInferenceResult(
    inferred: Map[String, Type],
    errors:   Vector[TypeError]
  )

  private def bindingDisplayName(bnd: Bnd): String =
    bnd.meta.map(_.originalName).getOrElse(bnd.name)

  private def normalizeBindingName(name: String): String =
    if name == statementParamName then "statement" else name

  @tailrec
  private def resolveAlias(id: String, aliases: Map[String, String]): String =
    aliases.get(id) match
      case Some(next) if next != id => resolveAlias(next, aliases)
      case _ => id

  private def bareRef(expr: Expr): Option[Ref] =
    expr.terms match
      case List(ref: Ref) => Some(ref)
      case List(group: TermGroup) => bareRef(group.inner)
      case List(inner: Expr) => bareRef(inner)
      case _ => None

  private def trackedParamId(
    ref:        Ref,
    aliases:    Map[String, String],
    trackedIds: Set[String]
  ): Option[String] =
    ref.resolvedId.map(resolveAlias(_, aliases)).filter(trackedIds.contains)

  private def recordLambdaParamInference(
    paramId:      String,
    inferredType: Type,
    state:        LambdaInferenceState,
    tracked:      Map[String, FnParam],
    module:       Module
  ): LambdaInferenceState =
    if state.conflicted.contains(paramId) then state
    else
      state.inferred.get(paramId) match
        case None =>
          state.copy(inferred = state.inferred + (paramId -> inferredType))
        case Some(existingType) if areTypesCompatible(existingType, inferredType, module) =>
          state
        case Some(existingType) =>
          val error =
            TypeError.ConflictingLambdaParamInference(
              tracked(paramId),
              existingType,
              inferredType,
              phaseName
            )
          state.copy(
            conflicted = state.conflicted + paramId,
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
      .flatMap(trackedParamId(_, state.aliases, tracked.keySet))
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
    params:  List[FnParam],
    body:    Expr,
    context: CheckContext
  ): LambdaInferenceResult =
    val module  = context.module
    val tracked = params.flatMap(param => param.id.map(_ -> param)).toMap

    def referenceType(ref: Ref, state: LambdaInferenceState): Option[Type] =
      trackedParamId(ref, state.aliases, tracked.keySet)
        .flatMap(state.inferred.get)
        .orElse(
          context.params
            .get(ref.name)
            .filter(_.id == ref.resolvedId)
            .flatMap(param => param.typeSpec.orElse(param.typeAsc))
        )
        .orElse(resolveRefType(ref, module))
        .orElse(ref.typeSpec)

    def argumentType(argument: Expr, state: LambdaInferenceState): Option[Type] =
      val direct = argument.typeAsc.orElse(argument.typeSpec).orElse {
        argument.terms match
          case List(lambda: Lambda) => lambda.typeSpec
          case List(term) => term.typeAsc.orElse(term.typeSpec)
          case _ => None
      }
      bareRef(argument)
        .flatMap(referenceType(_, state))
        .orElse(direct)
        .filterNot(failedType(_, context))

    // A known operand, such as 1.0 in value + 1.0, constrains the protocol parameter. The
    // registration set alone supplies no evidence, and unresolved alternatives remain unresolved.
    def callSignatures(
      ref:          Ref,
      applications: List[App],
      state:        LambdaInferenceState
    ): List[TypeFn] =
      if !needsProtocolSelection(ref, module.resolvables) then
        referenceType(ref, state).flatMap(extractTypeFn(_, module)).toList
      else
        val known = applications.map(application => argumentType(application.arg, state))
        ProtocolSelection
          .candidates(ref, context.protocols, module.resolvables)
          .toOption
          .toList
          .flatMap { choices =>
            val evidence = ProtocolSelection.matchSignatures(
              choices,
              applications.zip(known).map { (application, tpe) =>
                ProtocolSelection.ApplicationEvidence(
                  tpe,
                  calleeAscriptions(application).filterNot(failedType(_, context))
                )
              },
              None,
              module.resolvables
            )
            if evidence.exists(_.evidence == ProtocolSelection.SignatureEvidence.Unknown) then Nil
            else
              (choices.declarationIds ++ evidence.flatMap(_.implementationIds)).distinct
                .flatMap { id =>
                  module.resolvables
                    .lookup(id)
                    .collect { case value: Typeable => value }
                    .flatMap(value => value.typeSpec.orElse(value.typeAsc))
                    .flatMap(extractTypeFn(_, module))
                }
                .filter { function =>
                  known.zip(function.paramTypes.toList).forall { (actual, expected) =>
                    actual.forall(areTypesCompatible(expected, _, module))
                  }
                }
          }

    def walkAppFn(
      fn:    Callee,
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
        case invalid: InvalidExpression =>
          walkExpr(invalid.originalExpr, state)
        case _ =>
          state

    def walkApp(app: App, state: LambdaInferenceState): LambdaInferenceState =
      val inferredFromCall = CallableValues.applicationNodes(app) match
        case (ref: Ref, applications) =>
          val signatures = callSignatures(ref, applications, state)
          applications.zipWithIndex.foldLeft(state) { case (current, (application, position)) =>
            val possible = signatures.flatMap(_.paramTypes.toList.lift(position))
            possible.headOption
              .filter { expected =>
                possible.size == signatures.size &&
                possible.forall(areTypesCompatible(expected, _, module))
              }
              .fold(current) { expected =>
                recordInferenceFromExpr(application.arg, expected, current, tracked, module)
              }
          }
        case _ => state

      app.fn match
        case lambda: Lambda if isSimpleLetBinding(lambda, app) =>
          val afterArg = walkExpr(app.arg, inferredFromCall)
          val alias = for
            bindingId <- lambda.params.head.id
            targetId <- bareRef(app.arg)
              .flatMap(trackedParamId(_, afterArg.aliases, tracked.keySet))
          yield bindingId -> targetId

          val afterBody = walkExpr(lambda.body, afterArg.copy(aliases = afterArg.aliases ++ alias))
          afterBody.copy(aliases = afterArg.aliases)

        case _ =>
          walkExpr(app.arg, walkAppFn(app.fn, inferredFromCall))

    val initialState = LambdaInferenceState(Map.empty, Map.empty, Set.empty, Vector.empty)
    val finalState   = walkExpr(body, initialState)
    val missingErrors =
      params
        .filterNot { param =>
          param.id.exists { id =>
            finalState.inferred.contains(id) || finalState.conflicted.contains(id)
          }
        }
        .toVector
        .map(param => TypeError.UninferrableLambdaParam(param, phaseName))

    LambdaInferenceResult(
      finalState.inferred,
      finalState.errors ++ missingErrors
    )

  /** Include references retained inside failed payloads in dependency ordering. */
  private def collectMemberDeps(
    member:      Member,
    topLevelIds: Set[String],
    protocols:   ProtocolRegistry,
    index:       ResolvablesIndex
  ): Set[String] =
    member match
      case binding: Bnd =>
        val referenced = TermTraversal
          .collect(binding.value) { case ref: Ref =>
            val ids = (ref.resolvedId.toList ++ ref.candidateIds).distinct
            ids.flatMap { id =>
              index.lookup(id) match
                case Some(_: ProtocolSignature) =>
                  protocols
                    .signatureCandidates(id, index)
                    .toOption
                    .toList
                    .flatMap(_.implementations.flatMap(_.binding.id))
                case _ => List(id)
            }
          }
          .flatten
          .toSet
        (referenced intersect topLevelIds) -- binding.id
      case _ => Set.empty

  /** Check callees before callers; unresolved cycles retain their source order. */
  private def topologicalOrder(
    members:      List[Member],
    deps:         Map[String, Set[String]],
    needsReorder: Set[String]
  ): List[Member] =
    val ids = members.collect {
      case binding: Bnd if binding.id.exists(needsReorder) => binding.id.get
    }
    val allDependencies =
      ids.map(id => id -> deps.getOrElse(id, Set.empty).intersect(needsReorder)).toMap
    val annotated = members
      .collect {
        case binding: Bnd if binding.typeSpec.isDefined => binding.id
      }
      .flatten
      .toSet

    def reaches(from: String, target: String): Boolean =
      @tailrec
      def visit(pending: List[String], visited: Set[String]): Boolean = pending match
        case Nil => false
        case id :: _ if id == target => true
        case id :: rest if visited.contains(id) => visit(rest, visited)
        case id :: rest =>
          visit(allDependencies.getOrElse(id, Set.empty).toList ++ rest, visited + id)
      visit(List(from), Set.empty)

    // A declared callable signature breaks an inference cycle, but not an acyclic dependency.
    val dependencies = allDependencies.map { (id, targets) =>
      id -> targets.filterNot(target => annotated.contains(target) && reaches(target, id))
    }
    val dependants = ids.foldLeft(Map.empty[String, List[String]]) { (current, id) =>
      dependencies(id).foldLeft(current) { (index, dependency) =>
        index.updated(dependency, index.getOrElse(dependency, Nil) :+ id)
      }
    }
    val initialCounts = dependencies.view.mapValues(_.size).toMap

    @tailrec
    def order(
      ready:  List[String],
      counts: Map[String, Int],
      sorted: Vector[String]
    ): Vector[String] = ready match
      case Nil => sorted
      case id :: rest =>
        val (remaining, newlyReady) = dependants
          .getOrElse(id, Nil)
          .foldLeft(
            (counts, List.empty[String])
          ) { case ((current, next), dependant) =>
            val count = current(dependant) - 1
            (current.updated(dependant, count), if count == 0 then next :+ dependant else next)
          }
        order(rest ++ newlyReady, remaining, sorted :+ id)

    val sorted = order(ids.filter(initialCounts(_) == 0), initialCounts, Vector.empty)
    val allIds = sorted.toList ++ ids.filterNot(sorted.toSet)
    val byId   = members.collect { case binding: Bnd => binding.id.map(_ -> binding) }.flatten.toMap
    members
      .foldLeft((allIds, List.empty[Member])) { case ((remaining, result), member) =>
        member match
          case binding: Bnd if binding.id.exists(needsReorder) =>
            remaining match
              case id :: rest => (rest, result :+ byId(id))
              case Nil => (Nil, result :+ member)
          case _ => (remaining, result :+ member)
      }
      ._2

  /** Successful executable definitions contain only concrete selections before callable lowering.
    */
  private def validateResolvedReferences(state: CompilerState): CompilerState =
    if state.hasErrors then state
    else
      val index = state.module.resolvables
      val unresolved =
        state.module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
          TermTraversal.collect(binding.value) {
            case ref: Ref if needsProtocolSelection(ref, index) =>
              SemanticError.ProtocolErrorFound(
                ProtocolError.InsufficientTypeInformation(ref),
                phaseName
              )
          }
        }
      state.addErrors(unresolved)

  /** The parser retains MinInt's positive magnitude until the concrete negation is selected. */
  private def finalizeIntegerLiteral(checked: Checked[Callee]): Term =
    @tailrec
    def magnitude(term: Term): Option[LiteralInt] = term match
      case literal:    LiteralInt if literal.value == 2147483648L => literal.some
      case expression: Expr =>
        expression.terms match
          case List(inner) => magnitude(inner)
          case _ => None
      case _ => None

    checked.value match
      case application: App if checked.roots.isEmpty =>
        application.fn match
          case ref: Ref if ref.resolvedId.contains(standardIntNegationId) =>
            magnitude(application.arg).fold[Term](application) { literal =>
              literal.copy(
                source   = application.source,
                value    = Int.MinValue.toLong,
                typeSpec = application.typeSpec,
                typeAsc  = application.typeAsc.orElse(literal.typeAsc)
              )
            }
          case _ => application
      case other => other

  /** Every remaining magnitude must fit Int32, including operands retained in failed expressions.
    */
  private def validateIntegerRanges(state: CompilerState): CompilerState =
    val errors = state.module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
      TermTraversal.collect(binding.value) {
        case literal: LiteralInt if literal.value < Int.MinValue || literal.value > Int.MaxValue =>
          SemanticError.InvalidExpression(
            Expr(literal.source, List(literal)),
            "Integer literal is outside the Int32 range",
            phaseName
          )
      }
    }
    state.addErrors(errors)

  private def lowerSignature(
    member: Member
  ): Check[(Member, List[(String, Vector[DiagnosticId])])] = member match
    case binding: Bnd if binding.meta.isDefined =>
      val errors = validateMandatoryAscriptions(binding)
      for failures <- errors.traverse { error =>
          report(error).map { roots =>
            val parameter = error match
              case TypeError.MissingParameterType(param, _, _) => param.id
              case TypeError.MissingOperatorParameterType(param, _, _) => param.id
              case _ => None
            parameter.map(_ -> roots)
          }
        }
      yield
        val lowered = binding.value.terms match
          case (lambda: Lambda) :: rest =>
            val params     = lambda.params.map(param => param.copy(typeSpec = param.typeAsc))
            val returnType = lambda.typeAsc.orElse(binding.typeAsc)
            val signature  = returnType.flatMap(buildFunctionTypeSpec(binding.source, params, _))
            binding.copy(
              value = binding.value
                .copy(terms = lambda.copy(params = params, typeSpec = signature) :: rest),
              typeSpec = signature
            )
          case _ => binding
        (lowered, failures.flatten)
    case other => (other, Nil).pure[Check]

  private def withParameterFailure(
    context:   CheckContext,
    parameter: FnParam,
    roots:     Vector[DiagnosticId]
  ): CheckContext =
    val payload = Expr(parameter.source, Nil, typeSpec = parameter.typeSpec)
    context.withBinding(parameter, invalidExpr(payload, roots))

  private def hasNativeImpl(expr: Expr): Boolean =
    expr.terms.exists {
      case _:      NativeImpl => true
      case nested: Expr => hasNativeImpl(nested)
      case _ => false
    }

  private def checkBinding(
    binding:           Bnd,
    context:           CheckContext,
    parameterFailures: Map[String, Vector[DiagnosticId]]
  ): Check[Bnd] =
    binding.value.terms match
      case (lambda: Lambda) :: rest if binding.meta.isDefined =>
        val declaredReturn = lambda.typeAsc.orElse(binding.typeAsc)
        val parameterContext = context.copy(
          params           = lambda.params.map(param => param.name -> param).toMap,
          recursiveBinding = Option.when(declaredReturn.isEmpty)(binding)
        )
        val bodyContext = lambda.params.foldLeft(parameterContext) { (current, param) =>
          param.id.flatMap(parameterFailures.get) match
            case Some(roots) => withParameterFailure(current, param, roots)
            case None => current
        }
        for
          body <- checkExpr(lambda.body, bodyContext, declaredReturn)
          checkedBody <-
            if hasNativeImpl(lambda.body) then body.pure[Check]
            else
              validateExpectedResult(
                body,
                declaredReturn,
                binding,
                bodyContext,
                context.bindingName.some
              )
        yield
          val returnType = declaredReturn.orElse(checkedBody.typeSpec)
          val signature = returnType.flatMap(
            buildFunctionTypeSpec(binding.source, lambda.params, _)
          )
          val updatedLambda = lambda.copy(body = checkedBody, typeSpec = signature)
          binding.copy(
            value    = binding.value.copy(terms = updatedLambda :: rest),
            typeSpec = signature
          )
      case _ =>
        for
          value <- checkExpr(binding.value, context, binding.typeAsc)
          checked <- validateExpectedResult(value, binding.typeAsc, binding, context)
        yield binding.copy(value = checked, typeSpec = checked.typeSpec)

  /** Ascriptions consume a usable result; retained partial types cannot prove a mismatch. */
  private def validateExpectedResult(
    value:    Expr,
    expected: Option[Type],
    owner:    Typeable,
    context:  CheckContext,
    label:    Option[String] = None
  ): Check[Expr] =
    if !context.availability.isAvailable(value) && !hasNativeImpl(value) then
      report(TypeError.UnresolvableType(owner, None, phaseName), context.provenRootsOf(value))
        .map(invalidExpr(value, _))
    else
      (expected, value.typeSpec) match
        case (Some(wanted), Some(actual))
            if !failedType(wanted, context) &&
              !areTypesCompatible(wanted, actual, context.module) =>
          report(
            TypeError.TypeMismatch(owner, wanted, actual, phaseName, label)
          ).map(invalidExpr(value, _))
        case (Some(wanted), _) if failedType(wanted, context) =>
          report(
            TypeError.UnresolvableType(owner, None, phaseName),
            context.causes.provenRootsOfType(wanted)
          ).map(invalidExpr(value, _))
        case _ => value.pure[Check]

  /** Retain typed operands and the local wrappers that identify failed results. */
  private def checkExpr(
    expr:         Expr,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Expr] =
    expr.terms.zipWithIndex
      .traverse { (term, index) =>
        val expected =
          if index == expr.terms.size - 1 then expr.typeAsc.orElse(expectedType) else None
        for
          checked <- checkTerm(term, context, expected)
          validated <- validateTermAscription(checked, context)
        yield validated
      }
      .map { terms =>
        expr.copy(terms = terms, typeSpec = terms.lastOption.flatMap(_.typeSpec))
      }

  private def validateTermAscription(term: Term, context: CheckContext): Check[Term] =
    term.typeAsc match
      case None => term.pure[Check]
      case Some(ascribed) if failedType(ascribed, context) =>
        report(
          TypeError.UnresolvableType(term, None, phaseName),
          context.causes.provenRootsOfType(ascribed)
        ).map(invalidResult(term, _))
      case Some(_) if !context.availability.isAvailable(term) => term.pure[Check]
      case Some(_) =>
        reportAll(validateTypeAscription(term, context.module)).map(invalidResult(term, _))

  private def checkTerm(
    term:         Term,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Term] = term match

    case callee: Callee =>
      val expected = callee match
        case _: Lambda => expectedType
        case _ => callee.typeAsc.orElse(expectedType)
      checkCallee(callee, context, expected)
        .map(checked => invalidResult(finalizeIntegerLiteral(checked), checked.roots))

    case cond: Cond => checkConditional(cond, context, expectedType)

    case group: TermGroup =>
      checkExpr(group.inner, context, expectedType).map(inner => group.copy(inner = inner))

    case nested: Expr => checkExpr(nested, context, expectedType).widen

    case tuple: Tuple =>
      val expectedElements = expectedType
        .flatMap(TypeUtils.canonical(_, context.module.resolvables))
        .collect { case expected: TypeTuple => expected.elements }
        .getOrElse(Nil)
      tuple.elements.zipWithIndex
        .traverse { (element, index) =>
          checkExpr(element, context.independent, expectedElements.lift(index))
        }
        .map { elements =>
          val updated = tuple.copy(
            elements = elements,
            typeSpec = elements.toList.traverse(_.typeSpec).map(TypeTuple(tuple.source, _))
          )
          invalidResult(updated, context.provenRootsOf(updated))
        }

    case invalid: InvalidExpression =>
      for
        payload <- checkExpr(
          invalid.originalExpr,
          context.copy(enclosingFailure = invalid.causes),
          expectedType
        )
        roots <-
          if invalid.causes.nonEmpty then invalid.causes.pure[Check]
          else report(TypeError.UnresolvableType(invalid, None, phaseName))
      yield invalid.copy(
        originalExpr = payload,
        typeSpec     = invalid.typeSpec.orElse(payload.typeSpec),
        causes       = (roots ++ context.knownRootsOf(payload)).distinct
      )

    case hole: Hole =>
      expectedType match
        case Some(tpe) => hole.copy(typeSpec = tpe.some).pure[Check]
        case None =>
          val error = hole.spanOpt match
            case Some(span) =>
              TypeError.UntypedHoleInBinding(
                normalizeBindingName(context.bindingName),
                span,
                phaseName
              )
            case None => TypeError.UnresolvableType(hole, None, phaseName)
          report(error).map(invalidResult(hole, _))

    case other => other.pure[Check]

  private def checkCallee(
    callee:       Callee,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Checked[Callee]] = callee match

    case ref: Ref if ref.qualifier.isDefined => checkSelection(ref, context).widen

    case ref: Ref if needsProtocolSelection(ref, context.module.resolvables) =>
      selectCallable(ref, Nil, context, expectedType)

    case ref: Ref => checkRef(ref, context).widen

    case app: App =>
      val (root, applications) = CallableValues.applicationNodes(app)
      root match

        case ref: Ref if needsProtocolSelection(ref, context.module.resolvables) =>
          selectCallable(ref, applications, context, expectedType)

        case lambda: Lambda
            if lambda.params.nonEmpty && applications.head.calleeAscriptions.nonEmpty =>
          checkAscribedLambdaApplication(lambda, applications, context)

        case _ =>
          app.fn match
            case lambda: Lambda if lambda.params.nonEmpty =>
              checkImmediateApplication(app, lambda, context, expectedType).widen
            case _ => checkNormalApplication(app, context, expectedType).widen

    case lambda: Lambda => checkLambda(lambda, context, expectedType).widen

  private def needsProtocolSelection(ref: Ref, index: ResolvablesIndex): Boolean =
    val selected = ref.resolvedId.flatMap(index.lookup).exists(_.isInstanceOf[Bnd])
    !selected && (ref.candidateIds ++ ref.resolvedId).exists { id =>
      index.lookup(id).exists(_.isInstanceOf[ProtocolSignature])
    }

  private case class ArgumentProbe(
    original:          Expr,
    value:             Expr,
    independentErrors: List[CompilationError],
    knownType:         Option[Type],
    reusable:          Boolean
  )

  private case class SelectionTrial(
    checked:     Checked[Callee],
    diagnostics: DiagnosticStore,
    applicable:  Boolean
  )

  private def hasPrimarySince(diagnostics: DiagnosticStore, checkpoint: DiagnosticStore): Boolean =
    diagnostics.records
      .drop(checkpoint.records.size)
      .exists(_.relation == DiagnosticRelation.Primary)

  /** A probe supplies evidence only when the argument checks without an expected type. Its
    * diagnostics and inferred lambda parameters remain local until a final choice is made. A
    * concrete result with no emitted diagnostics can be reused by every candidate: its type still
    * constrains each candidate, while contextual lambdas and holes are checked afresh. Reuse also
    * requires a compatible expected type; selection with unknown alternatives fails before a probe
    * can become reusable. Independent errors, such as the mixed operands in `1.0 + 2`, rule out
    * every enclosing candidate. Retaining those errors avoids checking that failed subtree again.
    */
  private def probeArgument(
    argument:   Expr,
    context:    CheckContext,
    checkpoint: DiagnosticStore
  ): ArgumentProbe =
    val (diagnostics, value) = checkExpr(argument, context.independent, None).run(checkpoint).value
    val hasNewFailure        = hasPrimarySince(diagnostics, checkpoint)
    val independentErrors = diagnostics.records
      .drop(checkpoint.records.size)
      .collect {
        case record
            if record.relation == DiagnosticRelation.Primary &&
              independentProbeError(record.error) =>
          record.error
      }
      .toList
    val known = Option
      .when(!hasNewFailure && context.availability.isAvailable(value))(
        value.typeSpec
      )
      .flatten
    val reusable = known.isDefined && diagnostics.records.size == checkpoint.records.size
    ArgumentProbe(argument, value, independentErrors, known, reusable)

  private def calleeAscriptions(application: App): List[Type] =
    val own = application.fn match
      case _: Lambda => Nil
      case other => other.typeAsc.toList
    own ++ application.calleeAscriptions

  /** Every written constraint is checked; an inner annotation cannot be replaced by an outer one.
    */
  private def validateCalleeAscriptions(
    checked:     Checked[Callee],
    annotations: List[Type],
    context:     CheckContext
  ): Check[Checked[Callee]] =
    annotations
      .traverse { expected =>
        if failedType(expected, context) then
          report(
            TypeError.UnresolvableType(checked.value, None, phaseName),
            context.causes.provenRootsOfType(expected)
          )
        else
          checked.value.typeSpec match
            case Some(actual)
                if !failedType(actual, context) &&
                  !areTypesCompatible(expected, actual, context.module) =>
              report(TypeError.TypeMismatch(checked.value, expected, actual, phaseName, None))
            case _ => Vector.empty[DiagnosticId].pure[Check]
      }
      .map(roots => checked.copy(roots = (checked.roots ++ roots.flatten).distinct))

  /** Check the source lambda's complete signature before lowering its parameter scopes. */
  private def checkAscribedLambdaApplication(
    lambda:       Lambda,
    applications: List[App],
    context:      CheckContext
  ): Check[Checked[Callee]] =
    val expected = calleeAscriptions(applications.head).find(tpe => !failedType(tpe, context))
    for
      function <- checkLambda(lambda, context, expected)
      checked <- checkApplicationChain(
        Checked[Callee](function.value, function.roots),
        applications,
        context
      )
    yield checked.value match
      case application: App =>
        val (invocation, later) =
          CallableValues.applicationNodes(application)._2.splitAt(lambda.params.size)
        val lowered = DirectLambdaApplication.lower(
          function.value,
          invocation.map(_.arg),
          applications.head.source
        )
        val result = later.foldLeft(lowered) { (callee, original) => original.copy(fn = callee) }
        Checked(result, checked.roots)
      case _ => checked

  /** Check every supplied argument before deciding whether a concrete signature applies. A returned
    * callable starts another invocation naturally when validateApplication consumes the previous
    * signature's last parameter.
    */
  private def checkApplicationChain(
    root:             Checked[Callee],
    applications:     List[App],
    context:          CheckContext,
    checkedArguments: List[Option[Expr]] = Nil
  ): Check[Checked[Callee]] =
    applications.zipWithIndex.foldLeftM(root) { case (function, (original, position)) =>
      val expected = expectedArgumentType(function.value, context.module)
      for
        constrained <- validateCalleeAscriptions(function, calleeAscriptions(original), context)
        argument <- checkedArguments
          .lift(position)
          .flatten
          .filter { value =>
            expected
              .forall(tpe => value.typeSpec.exists(areTypesCompatible(tpe, _, context.module)))
          }
          .fold(checkExpr(original.arg, context.independent, expected))(_.pure[Check])
        checked <- validateApplication(
          original.copy(fn = constrained.value, arg = argument),
          constrained,
          context
        )
      yield Checked[Callee](checked.value, checked.roots)
    }

  private def trySelection(
    ref:          Ref,
    bindingId:    String,
    applications: List[App],
    probes:       List[ArgumentProbe],
    context:      CheckContext,
    expectedType: Option[Type],
    checkpoint:   DiagnosticStore
  ): SelectionTrial =
    val selected =
      ref.copy(resolvedId = bindingId.some, candidateIds = List(bindingId), typeSpec = None)
    val checking = for
      root <- checkRef(selected, context)
      result <- checkApplicationChain(
        Checked[Callee](root.value, root.roots),
        applications,
        context,
        probes.map(probe => Option.when(probe.reusable)(probe.value))
      )
    yield result
    val (diagnostics, checked) = checking.run(checkpoint).value
    val newFailure             = hasPrimarySince(diagnostics, checkpoint)
    val expectedMatches = expectedType.forall { expected =>
      checked.value.typeSpec.exists(areTypesCompatible(expected, _, context.module))
    }
    // A failed implementation still has a signature. Consequences of its recorded body error
    // cannot favor another implementation with the same signature.
    SelectionTrial(checked, diagnostics, !newFailure && expectedMatches)

  private def selectCallable(
    ref:          Ref,
    applications: List[App],
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Checked[Callee]] =
    import ProtocolSelection.SignatureEvidence

    State.get[DiagnosticStore].flatMap { checkpoint =>
      // Failed annotations supply no type evidence; independently known arguments remain usable.
      val selectionExpected = expectedType.filterNot(failedType(_, context))
      val probes            = applications.map(app => probeArgument(app.arg, context, checkpoint))
      val candidates = ProtocolSelection.candidates(
        ref,
        context.protocols,
        context.module.resolvables
      )
      candidates match
        case Left(error) =>
          failedSelection(
            ref,
            applications,
            probes,
            ProtocolError.RegistryFailure(ref, error),
            expectedType,
            context
          )

        case Right(choices) =>
          val evidence = ProtocolSelection.matchSignatures(
            choices,
            applications.zip(probes).map { (application, probe) =>
              ProtocolSelection.ApplicationEvidence(
                probe.knownType,
                calleeAscriptions(application).filterNot(failedType(_, context))
              )
            },
            selectionExpected,
            context.module.resolvables
          )
          val matching = evidence.flatMap(_.implementationIds)
          // An expected parameter type cannot repair an independent operand error. Trying each
          // candidate would only repeat the same failed subtree, once at every enclosing call.
          val trials =
            if probes.exists(_.independentErrors.nonEmpty) then Nil
            else
              (choices.declarationIds ++ matching).distinct.map { id =>
                trySelection(ref, id, applications, probes, context, selectionExpected, checkpoint)
              }
          val applicable    = trials.filter(_.applicable)
          val needsEvidence = evidence.exists(_.evidence == SignatureEvidence.Unknown)
          applicable match
            case winner :: Nil if !needsEvidence => State.set(winner.diagnostics).as(winner.checked)

            case _ =>
              val missing = evidence.collectFirst {
                case ProtocolSelection.SignatureMatch(
                      registered,
                      SignatureEvidence.Concrete(tpe),
                      Nil
                    ) =>
                  ProtocolError.MissingInstance(ref, registered.protocol, tpe)
              }
              val error =
                if applicable.size > 1 then ProtocolError.AmbiguousSelection(ref)
                else if needsEvidence then ProtocolError.InsufficientTypeInformation(ref)
                else missing.getOrElse(ProtocolError.IncompatibleArguments(ref))
              failedSelection(ref, applications, probes, error, expectedType, context)
    }

  /** Context-dependent probe errors are provisional: an expected lambda or hole type may resolve
    * them. Independent argument errors are emitted once when no selection can be committed.
    */
  private def independentProbeError(error: CompilationError): Boolean = error match
    case SemanticError.TypeCheckingError(
          _: TypeError.UninferrableLambdaParam | _: TypeError.UntypedHoleInBinding |
          _: TypeError.UnresolvableType | _: TypeError.ConditionalBranchTypeUnknown
        ) =>
      false
    case SemanticError.ProtocolErrorFound(
          _: ProtocolError.InsufficientTypeInformation | _: ProtocolError.AmbiguousSelection,
          _
        ) =>
      false
    case _ => true

  private def failedSelection(
    ref:          Ref,
    applications: List[App],
    probes:       List[ArgumentProbe],
    error:        ProtocolError,
    expectedType: Option[Type],
    context:      CheckContext
  ): Check[Checked[Callee]] =
    val independentErrors = probes.flatMap(_.independentErrors)
    // A failed annotation explains missing evidence, but not a concrete argument mismatch.
    val expectedRoots = error match
      case _: ProtocolError.InsufficientTypeInformation | _: ProtocolError.AmbiguousSelection =>
        (expectedType.toList ++ applications.flatMap(calleeAscriptions))
          .flatMap(context.causes.knownRootsOfType)
          .toVector
      case _ => Vector.empty
    val instanceRoots = error match
      case ProtocolError.MissingInstance(_, protocol, concreteType) =>
        protocol.id.toVector.flatMap { id =>
          context.protocols.instanceFailureCauses(id, concreteType, context.module.resolvables)
        }
      case _ => Vector.empty
    for
      argumentRoots <- independentErrors.traverse(DiagnosticStore.report(_))
      knownRoots = probes.flatMap(probe => context.knownRootsOf(probe.original)).toVector
      selectionRoots =
        argumentRoots.flatten ++ knownRoots ++ expectedRoots ++ instanceRoots ++
          context.enclosingFailure
      roots <- DiagnosticStore.report(
        SemanticError.ProtocolErrorFound(error, phaseName),
        selectionRoots.distinct.toVector
      )
    yield
      val unresolved = ref.copy(resolvedId = None, typeSpec = None)
      val result = applications.zip(probes).foldLeft[Callee](unresolved) {
        case (function, (original, probe)) =>
          val argument = if probe.reusable then probe.value else original.arg
          original.copy(fn = function, arg = argument, typeSpec = None)
      }
      Checked(result, roots)

  private def checkRef(ref: Ref, context: CheckContext): Check[Checked[Ref]] =
    val inferred = context.params
      .get(ref.name)
      .filter(_.id == ref.resolvedId)
      .flatMap(_.typeSpec)
      .orElse(resolveRefType(ref, context.module))
      .orElse(ref.typeSpec)
    val updated = ref.copy(typeSpec = inferred)
    val roots   = context.provenRootsOf(updated)
    inferred match
      case Some(tpe) if !failedType(tpe, context) => Checked(updated, roots).pure[Check]
      case _ =>
        val original = context.recursiveBinding.filter(binding => ref.resolvedId == binding.id)
        val error = original match
          case Some(binding) => TypeError.RecursiveFunctionMissingReturnType(binding, phaseName)
          case None =>
            TypeError.UnresolvableType(
              updated,
              UnresolvableTypeContext.NamedValue(ref.name).some,
              phaseName
            )
        val prerequisiteRoots =
          if ref.resolvedId.isEmpty then (roots ++ context.enclosingFailure).distinct
          else roots
        report(error, prerequisiteRoots).map(Checked(updated, _))

  private def checkSelection(ref: Ref, context: CheckContext): Check[Checked[Ref]] =

    ref.qualifier match

      case None => checkRef(ref, context)

      case Some(qualifier) =>
        for
          checked <- checkTerm(qualifier, context.independent, None)
          selected <-
            if !context.availability.isAvailable(checked) then
              report(
                TypeError.UnresolvableType(checked, None, phaseName),
                context.provenRootsOf(checked)
              ).map(roots => Checked(ref.copy(qualifier = checked.some), roots))
            else
              checked.typeSpec match

                case Some(baseType) =>
                  resolveStructType(baseType, context.module) match

                    case Some(struct) =>
                      struct.fields.find(_.name == ref.name) match

                        case Some(field) =>
                          Checked(
                            ref.copy(
                              qualifier  = checked.some,
                              typeSpec   = field.typeSpec.some,
                              resolvedId = field.id
                            )
                          ).pure[Check]

                        case None =>
                          report(TypeError.UnknownField(ref, struct, phaseName))
                            .map(roots => Checked(ref.copy(qualifier = checked.some), roots))

                    case None =>
                      report(TypeError.InvalidSelection(ref, baseType, phaseName))
                        .map(roots => Checked(ref.copy(qualifier = checked.some), roots))

                case None =>
                  report(TypeError.UnresolvableType(checked, None, phaseName))
                    .map(roots => Checked(ref.copy(qualifier = checked.some), roots))
        yield selected

  private def resolveRefType(ref: Ref, module: Module): Option[Type] =
    ref.resolvedId.flatMap(module.resolvables.lookup).flatMap {
      case param: FnParam => param.typeSpec.orElse(param.typeAsc)
      case value: Typeable => value.typeSpec
      case _ => None
    }

  /** A local initializer supplies its binding, while the continuation supplies the result. */
  private def checkImmediateApplication(
    app:          App,
    lambda:       Lambda,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Checked[App]] =
    val parameter = lambda.params.head
    val initializerContext = context.independent.copy(
      bindingName = normalizeBindingName(parameter.name)
    )
    val seededContext = app.arg.terms match
      case List(value: Lambda) if value.typeAsc.isDefined =>
        val recursiveType = value.typeAsc.flatMap(
          buildFunctionTypeSpec(value.source, value.params, _)
        )
        initializerContext.copy(params =
          initializerContext.params +
            (parameter.name -> parameter.copy(typeSpec = recursiveType))
        )
      case List(_: Lambda) if parameter.typeAsc.isDefined =>
        initializerContext.copy(params =
          initializerContext.params +
            (parameter.name -> parameter.copy(typeSpec = parameter.typeAsc))
        )
      case _ => initializerContext

    for
      initialArg <- checkExpr(app.arg, seededContext, parameter.typeAsc)
      arg <- validateExpectedResult(
        initialArg,
        parameter.typeAsc,
        app.arg,
        seededContext,
        seededContext.bindingName.some
      )
      typedParameter = parameter.copy(typeSpec = parameter.typeAsc.orElse(arg.typeSpec))
      bodyContext    = context.independent.withBinding(typedParameter, arg)
      initialBody <- checkExpr(lambda.body, bodyContext, expectedType)
      body <- validateExpectedResult(initialBody, expectedType, lambda.body, bodyContext)
    yield
      val lambdaType = for
        paramType <- typedParameter.typeSpec
        resultType <- body.typeSpec
      yield TypeFn(lambda.source, NonEmptyList.one(paramType), resultType)
      val typedLambda = lambda.copy(
        params   = List(typedParameter),
        body     = body,
        typeSpec = lambdaType
      )
      Checked(
        app.copy(fn = typedLambda, arg = arg, typeSpec = body.typeSpec),
        bodyContext.knownRootsOf(body)
      )

  private def checkNormalApplication(
    app:          App,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Checked[App]] =
    for
      checkpoint <- State.get[DiagnosticStore]
      explicit = calleeAscriptions(app).find(tpe => !failedType(tpe, context))
      fn <- app.fn match

        case _ if explicit.isDefined => checkCallee(app.fn, context, explicit)

        case inner: App
            if CallableValues.applicationNodes(inner)._1.isInstanceOf[Lambda] &&
              expectedType.isDefined =>
          val (diagnostics, inferred) = checkCallee(inner, context, None).run(checkpoint).value
          val hasType =
            inferred.value.typeSpec.isDefined && !hasPrimarySince(diagnostics, checkpoint)
          // A known inner signature constrains the outer argument. Context only supplies missing
          // information; an incompatible outer argument cannot invalidate that inner value.
          if hasType then State.set(diagnostics).as(inferred)
          else
            val expectedCallee = for
              result <- expectedType
              argument <- probeArgument(app.arg, context, checkpoint).knownType
            yield TypeFn(app.source, NonEmptyList.one(argument), result)
            expectedCallee match
              case Some(expected) => checkCallee(inner, context, expected.some)
              case None => State.set(diagnostics).as(inferred)

        case _ => checkCallee(app.fn, context, None)
      constrained <- validateCalleeAscriptions(fn, calleeAscriptions(app), context)
      expected = expectedArgumentType(constrained.value, context.module)
      arg <- checkExpr(app.arg, context.independent, expected)
      checked <- validateApplication(
        app.copy(fn = constrained.value, arg = arg),
        constrained,
        context
      )
    yield checked

  private def validateApplication(
    app:     App,
    fn:      Checked[Callee],
    context: CheckContext
  ): Check[Checked[App]] =

    val fnRoots      = resultRoots(fn, context)
    val fnAvailable  = fnRoots.isEmpty && context.availability.isAvailable(fn.value)
    val argAvailable = context.availability.isAvailable(app.arg)
    val fnType       = extractTypeFnFromCallee(fn.value, context.module)
    val partialType = fnType.map { tpe =>
      buildRemainingFunctionTypeFromTypes(tpe.paramTypes.tail, tpe.returnType, app.source)
    }
    val typedApp = app.copy(typeSpec = partialType)

    def usableType(tpe: Type): Boolean =

      val evidence = context.causes.evidenceOfType(tpe)
      evidence.isComplete && evidence.knownRoots.isEmpty

    for
      missingFn <-
        if fnAvailable then Vector.empty[DiagnosticId].pure[Check]
        else
          val error = fn.value match
            case ref: Ref =>
              TypeError.UnresolvableType(
                ref,
                UnresolvableTypeContext.NamedValue(ref.name).some,
                phaseName
              )
            case _ =>
              TypeError.UnresolvableType(
                typedApp,
                UnresolvableTypeContext.Function.some,
                phaseName
              )
          report(error, fnRoots)
      missingArg <-
        if argAvailable then Vector.empty[DiagnosticId].pure[Check]
        else
          report(
            TypeError.UnresolvableType(app.arg, UnresolvableTypeContext.Argument.some, phaseName),
            context.provenRootsOf(app.arg)
          )
      constraintErrors <-
        if !argAvailable then Vector.empty[DiagnosticId].pure[Check]
        else
          (fnType, app.arg.typeSpec) match
            // A declared parameter constrains an argument even when the function body failed.
            case (Some(callable), Some(actual))
                if usableType(callable.paramTypes.head) && usableType(actual) =>
              val wanted = callable.paramTypes.head
              if areTypesCompatible(wanted, actual, context.module) then
                Vector.empty[DiagnosticId].pure[Check]
              else
                val label = fn.value match
                  case ref: Ref => ref.name.some
                  case _ => None
                report(TypeError.TypeMismatch(app.arg, wanted, actual, phaseName, label))

            case (Some(callable), _) if fnAvailable && !usableType(callable.paramTypes.head) =>
              report(
                TypeError.UnresolvableType(app, UnresolvableTypeContext.Function.some, phaseName),
                context.causes.provenRootsOfType(callable.paramTypes.head)
              )

            case (None, Some(actual)) if fnAvailable =>
              fn.value.typeSpec match
                case Some(calleeType) =>
                  report(TypeError.InvalidApplication(app, calleeType, actual, phaseName))
                case None =>
                  report(
                    TypeError.UnresolvableType(
                      app,
                      UnresolvableTypeContext.Function.some,
                      phaseName
                    )
                  )

            case _ if fnAvailable =>
              report(
                TypeError.UnresolvableType(
                  app.arg,
                  UnresolvableTypeContext.Argument.some,
                  phaseName
                )
              )

            case _ => Vector.empty[DiagnosticId].pure[Check]
    yield Checked(typedApp, (missingFn ++ missingArg ++ constraintErrors).distinct)

  private def checkLambda(
    lambda:       Lambda,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Checked[Lambda]] =
    val expectedFn = expectedType.flatMap(extractTypeFn(_, context.module))
    val topDownParams = lambda.params.zipWithIndex.map { (parameter, index) =>
      if parameter.typeSpec.isDefined then parameter
      else if parameter.typeAsc.isDefined then parameter.copy(typeSpec = parameter.typeAsc)
      else parameter.copy(typeSpec = expectedFn.flatMap(_.paramTypes.toList.lift(index)))
    }
    val untyped = topDownParams.filter(_.typeSpec.isEmpty)
    val inferred =
      if untyped.isEmpty then LambdaInferenceResult(Map.empty, Vector.empty)
      else
        inferParamTypesFromBody(
          untyped,
          lambda.body,
          context.copy(params = context.params ++ topDownParams.map(param => param.name -> param))
        )
    val parameters = topDownParams.map { parameter =>
      parameter.copy(typeSpec =
        parameter.typeSpec.orElse(parameter.id.flatMap(inferred.inferred.get))
      )
    }
    val parameterContext = context.independent.copy(
      params = context.params ++ parameters.map(parameter => parameter.name -> parameter)
    )

    for
      failures <- inferred.errors.toList.traverse { error =>
        report(error).map { roots =>
          val parameter = error match
            case TypeError.ConflictingLambdaParamInference(param, _, _, _) => param.some
            case TypeError.UninferrableLambdaParam(param, _) => param.some
            case _ => None
          parameter.map(_ -> roots)
        }
      }
      bodyContext = failures.flatten.foldLeft(parameterContext) {
        case (current, (parameter, roots)) => withParameterFailure(current, parameter, roots)
      }
      expectedReturn = lambda.typeAsc.orElse(expectedFn.map(_.returnType))
      initialBody <- checkExpr(lambda.body, bodyContext, expectedReturn)
      body <- validateExpectedResult(initialBody, expectedReturn, lambda, bodyContext)
    yield
      val lambdaType = body.typeSpec.flatMap(buildFunctionTypeSpec(lambda.source, parameters, _))
      val captures = lambda.captures.map { capture =>
        val ref = capture.ref
        val captureType = ref.typeSpec
          .orElse(
            bodyContext.params
              .get(ref.name)
              .filter(_.id == ref.resolvedId)
              .flatMap(p => p.typeSpec.orElse(p.typeAsc))
          )
          .orElse(resolveRefType(ref, context.module))
        val typedRef = ref.copy(typeSpec = captureType)
        capture match
          case Capture.CapturedRef(_) => Capture.CapturedRef(typedRef)
          case Capture.BorrowedRef(_) => Capture.BorrowedRef(typedRef)
          case Capture.OwnedClosure(_, id) => Capture.OwnedClosure(typedRef, id)
          case Capture.CapturedLiteral(_, id) => Capture.CapturedLiteral(typedRef, id)
          case Capture.CapturedConditional(_, witness, id) =>
            Capture.CapturedConditional(typedRef, witness, id)
      }
      val updated = lambda.copy(
        params   = parameters,
        body     = body,
        captures = captures,
        typeSpec = lambdaType
      )
      Checked(
        updated,
        (failures.flatten.flatMap(_._2) ++ bodyContext.knownRootsOf(body)).toVector.distinct
      )

  /** Contextual branch inference is provisional until both branches choose their final types. */
  private def checkBranches(
    cond:         Cond,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[(Expr, Expr)] =
    for
      checkpoint <- State.get[DiagnosticStore]
      yes <- checkExpr(cond.ifTrue, context, expectedType)
      no <- checkExpr(cond.ifFalse, context, expectedType)
      checked <-
        if expectedType.isDefined then (yes, no).pure[Check]
        else
          (yes.typeSpec, no.typeSpec) match
            case (Some(tpe), None) if context.availability.isAvailable(yes) =>
              State.set(checkpoint) *> (
                checkExpr(cond.ifTrue, context, None),
                checkExpr(cond.ifFalse, context, tpe.some)
              ).tupled
            case (None, Some(tpe)) if context.availability.isAvailable(no) =>
              State.set(checkpoint) *> (
                checkExpr(cond.ifTrue, context, tpe.some),
                checkExpr(cond.ifFalse, context, None)
              ).tupled
            case _ => (yes, no).pure[Check]
    yield checked

  private def checkConditional(
    cond:         Cond,
    context:      CheckContext,
    expectedType: Option[Type]
  ): Check[Term] =
    val childContext = context.independent
    for
      predicate <- checkExpr(cond.cond, childContext, None)
      branches <- checkBranches(cond, childContext, expectedType)
      (yes, no) = branches
      predicateRoots <-
        if !context.availability.isAvailable(predicate) then
          report(
            TypeError.UnresolvableType(predicate, None, phaseName),
            context.provenRootsOf(predicate)
          )
        else
          predicate.typeSpec match
            case Some(tpe)
                if areTypesCompatible(
                  tpe,
                  TypeRef(cond.source, "Bool", stdlibTypeId("Bool")),
                  context.module
                ) =>
              Vector.empty[DiagnosticId].pure[Check]
            case Some(tpe) =>
              report(
                TypeError.TypeMismatch(
                  predicate,
                  TypeRef(cond.source, "Bool", stdlibTypeId("Bool")),
                  tpe,
                  phaseName,
                  None
                )
              )
            case None => report(TypeError.UnresolvableType(predicate, None, phaseName))
      branchRoots <-
        if !context.availability.isAvailable(yes) || !context.availability.isAvailable(no) then
          val unavailable = List(yes, no).filterNot(context.availability.isAvailable)
          val causes      = unavailable.map(context.provenRootsOf)
          val provenRoots =
            if causes.forall(_.nonEmpty) then causes.flatten.toVector.distinct
            else Vector.empty
          report(TypeError.ConditionalBranchTypeUnknown(cond, phaseName), provenRoots)
        else
          (yes.typeSpec, no.typeSpec) match
            case (Some(yesType), Some(noType))
                if !areTypesCompatible(yesType, noType, context.module) =>
              report(TypeError.ConditionalBranchTypeMismatch(cond, yesType, noType, phaseName))
            case (Some(_), Some(_)) => Vector.empty[DiagnosticId].pure[Check]
            case _ => report(TypeError.ConditionalBranchTypeUnknown(cond, phaseName))
    yield
      val resultType = (yes.typeSpec, no.typeSpec) match
        case (Some(yesType), Some(noType)) if areTypesCompatible(yesType, noType, context.module) =>
          yesType.some
        case _ => expectedType.orElse(yes.typeSpec).orElse(no.typeSpec)
      val updated = cond.copy(
        cond     = predicate,
        ifTrue   = yes,
        ifFalse  = no,
        typeSpec = resultType
      )
      invalidResult(updated, (predicateRoots ++ branchRoots).distinct)
  private def extractTypeFnFromCallee(callee: Callee, module: Module): Option[TypeFn] =
    callee match
      case ref: Ref => extractTypeFnFromRef(ref, module)
      case _ => callee.typeSpec.flatMap(extractTypeFn(_, module))

  private def extractTypeFnFromRef(ref: Ref, module: Module): Option[TypeFn] =
    val directType = ref.typeSpec.flatMap(extractTypeFn(_, module))
    directType.orElse {
      ref.resolvedId.flatMap(module.resolvables.lookup).flatMap {
        case value: Typeable => value.typeSpec.flatMap(extractTypeFn(_, module))
        case _ => None
      }
    }

  private def expectedArgumentType(callee: Callee, module: Module): Option[Type] =
    extractTypeFnFromCallee(callee, module).map(_.paramTypes.head)

  private def extractTypeFn(typeSpec: Type, module: Module): Option[TypeFn] =
    TypeUtils.canonical(typeSpec, module.resolvables).collect { case fn: TypeFn => fn }

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

  private def areTypesCompatible(t1: Type, t2: Type, module: Module): Boolean =
    TypeCompatibility.same(t1, t2, module.resolvables)

  private def resolveStructType(typeSpec: Type, module: Module): Option[TypeStruct] =
    TypeUtils.canonical(typeSpec, module.resolvables).flatMap {
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
    }

  private def canonicalCallableParamTypes(
    source:     SourceOrigin,
    paramTypes: List[Type]
  ): NonEmptyList[Type] =
    NonEmptyList
      .fromList(paramTypes)
      .getOrElse(NonEmptyList.one(TypeRef(source, "Unit", stdlibTypeId("Unit"), Nil)))

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
