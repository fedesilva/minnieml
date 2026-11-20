package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.TypeQueries

import scala.annotation.tailrec

/** Infers one substitution for a generic callable, including any later uses of a stored partial.
  * Caller variables stay rigid. A generic function passed as an argument gets fresh variables at
  * each occurrence.
  */
object GenericInference:

  enum Failure:
    case TypeArgumentCount(expected: Int, actual: Int)
    case MissingArguments(parameters: List[TypeVariable])
    case ConflictingArgument(variable: TypeVariable, first: Type, second: Type)
    case IncompatibleTypes(expected: Type, actual: Type)
    case NotCallable(tpe: Type)
    case UnresolvedParameter(parameter: TypeVariable)

  /** A type supplied at a written operand, ascription, or expected result. */
  case class LocatedType(tpe: Type, source: SourceOrigin)

  /** Solved callee arguments and the binders still awaiting evidence. Every substitution value is
    * fully resolved. Missing binders include those introduced by generic function arguments.
    * Observations carry the solved types and written sites collected for a conflict diagnostic.
    */
  case class Partial(
    substitution: Map[String, Type],
    missing:      List[TypeVariable],
    observations: List[LocatedType] = Nil
  ):

    def complete: Either[Failure, Map[String, Type]] =
      if missing.isEmpty then substitution.asRight
      else Failure.MissingArguments(missing).asLeft

  /** Arguments and their expected remaining signature jointly determine type arguments. A scheme
    * supplied as argument evidence contributes fresh inference variables: apply identity 1 can
    * infer both the input and result of identity. Explicit arguments bind every callee parameter in
    * declaration order. Callee ascriptions constrain each corresponding application boundary. The
    * returned substitution contains only callee binders and has no unsolved inference variables.
    */
  def infer(
    signature:         TypeFn,
    parameters:        List[TypeVariable],
    argumentTypes:     List[Option[Type]],
    expectedResult:    Option[Type],
    explicitTypes:     Option[List[Type]],
    index:             ResolvablesIndex,
    calleeAscriptions: List[List[Type]] = Nil
  ): Either[Failure, Map[String, Type]] =
    inferPartial(
      signature,
      parameters,
      argumentTypes,
      expectedResult,
      explicitTypes,
      index,
      calleeAscriptions
    ).flatMap(_.complete)

  /** Solves the available evidence without requiring every binder to be determined. The checker
    * uses solved input types to check contextual callbacks before requesting a complete solution.
    */
  def inferPartial(
    signature:         TypeFn,
    parameters:        List[TypeVariable],
    argumentTypes:     List[Option[Type]],
    expectedResult:    Option[Type],
    explicitTypes:     Option[List[Type]],
    index:             ResolvablesIndex,
    calleeAscriptions: List[List[Type]] = Nil
  ): Either[Failure, Partial] =
    inferUses(
      signature,
      parameters,
      Call(argumentTypes, expectedResult, calleeAscriptions),
      Nil,
      explicitTypes,
      parameters,
      index
    )

  /** A call or function-value use contributing evidence for the same callable. Diagnostic inference
    * supplies an operand source per argument and a site for the expected result.
    */
  case class Call(
    arguments:       List[Option[Type]],
    result:          Option[Type],
    ascriptions:     List[List[Type]],
    argumentSources: List[SourceOrigin] = Nil,
    resultSource:    SourceOrigin       = SourceOrigin.Synth
  )

  /** Supplies missing argument types by checking contextual callbacks. The inputs are the current
    * callable type, argument types in source order, and unresolved callee binder IDs. A refinement
    * preserves the list length, positions, and all known types. Each change fills an empty slot.
    */
  type RefineArguments = (Type, List[Option[Type]], Set[String]) => List[Option[Type]]

  /** Infer a complete substitution, using known inputs to type contextual callbacks. Each retry
    * fills a previously unknown argument, so the number of arguments bounds the retries.
    */
  @tailrec
  def inferCallable(
    signature:          TypeFn,
    parameters:         List[TypeVariable],
    call:               Call,
    otherUses:          List[Call],
    explicitTypes:      Option[List[Type]],
    explicitParameters: List[TypeVariable],
    index:              ResolvablesIndex,
    refine:             RefineArguments
  ): Either[Failure, Map[String, Type]] =
    inferUses(
      signature,
      parameters,
      call,
      otherUses,
      explicitTypes,
      explicitParameters,
      index
    ) match
      case Left(failure) => Left(failure)
      case Right(partial) =>
        val missing = parameters.flatMap(_.resolvedId).toSet -- partial.substitution.keySet
        val next =
          refine(TypeSubstitution(signature, partial.substitution), call.arguments, missing)
        if next == call.arguments then partial.complete
        else
          inferCallable(
            signature,
            parameters,
            call.copy(arguments = next),
            otherUses,
            explicitTypes,
            explicitParameters,
            index,
            refine
          )

  /** Solves shared binder types across all uses. Only explicitParameters accept written type
    * arguments. To diagnose a failed call, each use can get its own identity for one observed
    * binder. Its requirements keep their written sites. Other callee binders remain shared, and
    * each generic callback occurrence has its own binders. The substitution contains the solved
    * shared callee types. Even a partial solution can prove a conflict from two resolved receiver
    * observations. Partial.complete requires all callee, callback and per-use receiver binders to
    * be solved.
    */
  private[semantic] def inferUses(
    signature:          TypeFn,
    parameters:         List[TypeVariable],
    call:               Call,
    otherUses:          List[Call],
    explicitTypes:      Option[List[Type]],
    explicitParameters: List[TypeVariable],
    index:              ResolvablesIndex,
    observe:            Option[TypeVariable] = None
  ): Either[Failure, Partial] =
    val calls = call :: otherUses
    val inputs = signature :: (explicitTypes.toList.flatten ++
      calls.flatMap(use => use.arguments.flatten ++ use.result ++ use.ascriptions.flatten))
    val occupied = inputs
      .flatMap(
        TypeTraversal
          .collect(_) { case variable: TypeVariable =>
            variable.resolvedId
          }
          .flatten
      )
      .toSet

    for
      callee <- freshParameters(
        parameters.filterNot(v => observe.exists(_.resolvedId == v.resolvedId)),
        "callee",
        occupied
      )
      prepared <- calls.zipWithIndex.traverse { (use, useIndex) =>
        for
          arguments <- use.arguments.zipWithIndex.traverse { (argument, position) =>
            prepareArgument(argument, s"$useIndex:$position", occupied)
          }
          receiver <- freshParameters(observe.toList, s"observed:$useIndex", occupied)
        yield PreparedUse(
          use.copy(arguments = arguments.map(_.tpe)),
          arguments.flatMap(_.binders),
          receiver.headOption
        )
      }
      explicitIds     = explicitParameters.flatMap(_.resolvedId).toSet
      explicitBinders = callee.collect { case (v, id) if v.resolvedId.exists(explicitIds) => id }
      initial <- explicitTypes match
        case Some(types) if types.size != explicitBinders.size =>
          Failure.TypeArgumentCount(explicitBinders.size, types.size).asLeft
        case Some(types) => explicitBinders.zip(types).toMap.asRight[Failure]
        case None => Map.empty[String, Type].asRight[Failure]
      replacements = callee.map { (original, id) =>
        original.resolvedId.getOrElse(id) -> original.copy(resolvedId = id.some)
      }.toMap
      callable = TypeSubstitution(signature, replacements)
      binders  = callee ++ prepared.flatMap(use => use.argumentBinders ++ use.observed)
      solver   = Solver(binders.map { (original, id) => id -> original }.toMap, index, None)
      matched <- prepared
        .foldLeftM(Solution(initial)) { (known, use) =>
          use.observed match
            case None => solver.consume(callable, use.call, known)
            case Some((original, id)) =>
              // A shared member-local type can contain a previous use's receiver. Each receiver
              // keeps its identity while shared types and use-local callback binders unify.
              val local = original.copy(resolvedId = id.some)
              val localCallable =
                TypeSubstitution(callable, original.resolvedId.map(_ -> local).toMap)
              // Keep equality within a call that fits by itself. Only a call already inconsistent
              // in isolation can contribute distinct demands on its own receiver.
              val internallyInconsistent =
                solver.consume(localCallable, use.call, Solution(initial)).isLeft
              solver
                .copy(observed = id.some, allowReceiverConflict = internallyInconsistent)
                .consume(localCallable, use.call, known)
        }
        .leftMap(solver.restoreFailure)
      solved <- solver.validateEqualities(matched).leftMap(solver.restoreFailure)
      inferred = solved.types
      unresolved = binders
        .filter { (_, id) =>
          inferred.get(id).forall(solver.containsUnsolved(_, inferred))
        }
        .map(_._1)
      result <- callee.traverse { (original, id) =>
        original.resolvedId.toRight(Failure.UnresolvedParameter(original)).map { originalId =>
          inferred
            .get(id)
            .filterNot(solver.containsUnsolved(_, inferred))
            .map(tpe => originalId -> solver.resolve(tpe, inferred))
        }
      }
      observations = solved.observations.reverse.collect {
        case LocatedType(tpe, source) if !solver.containsUnsolved(tpe, inferred) =>
          LocatedType(solver.resolve(tpe, inferred), source)
      }
    yield Partial(result.flatten.toMap, unresolved.distinct, observations)

  /** Pairs each source binder with a temporary inference identity outside the occupied input IDs.
    * Callers supply distinct scopes for the callee and each argument, keeping their substitutions
    * independent even when they instantiate the same declaration.
    */
  private def freshParameters(
    parameters: List[TypeVariable],
    scope:      String,
    occupied:   Set[String]
  ): Either[Failure, List[(TypeVariable, String)]] =

    @tailrec
    def unused(candidate: String): String =
      if occupied.contains(candidate) then unused(s"$candidate:") else candidate

    parameters.zipWithIndex.traverse { (parameter, position) =>
      parameter.resolvedId.toRight(Failure.UnresolvedParameter(parameter)).map { _ =>
        parameter -> unused(s"generic-inference:$scope:$position")
      }
    }

  /** A use with fresh callback binders. During conflict recovery it also owns its receiver binder,
    * so references to that receiver within a shared member-local type survive later uses.
    */
  private case class PreparedUse(
    call:            Call,
    argumentBinders: List[(TypeVariable, String)],
    observed:        Option[(TypeVariable, String)]
  )

  /** Argument evidence after replacing its quantified binders with use-local identities.
    * Monomorphic arguments have no binders; unknown argument types supply no evidence.
    */
  private case class Argument(tpe: Option[Type], binders: List[(TypeVariable, String)])

  /** Opens a function argument's scheme without making its binders visible to another argument. */
  private def prepareArgument(
    argument: Option[Type],
    position: String,
    occupied: Set[String]
  ): Either[Failure, Argument] = argument match
    case Some(TypeGroup(_, List(inner))) => prepareArgument(inner.some, position, occupied)
    case Some(scheme: TypeScheme) =>
      freshParameters(scheme.vars, s"argument:$position", occupied).map { binders =>
        val replacements = binders.map { (original, id) =>
          original.resolvedId.getOrElse(id) -> original.copy(resolvedId = id.some)
        }.toMap
        Argument(TypeSubstitution(scheme.bodyType, replacements).some, binders)
      }
    case other => Argument(other, Nil).asRight

  /** Inference bindings and diagnostic evidence. A shared local type can mention a receiver whose
    * value is still changing during recovery. Its equalities must also hold once every use has
    * supplied its receiver types.
    */
  private case class Solution(
    types:        Map[String, Type],
    observations: List[LocatedType]  = Nil,
    equalities:   List[(Type, Type)] = Nil
  )

  /** Unifies fresh variables across the uses of one callable. All other variable identities are
    * rigid caller types. Substitutions stay acyclic and are shared across argument and
    * expected-type evidence.
    */
  private case class Solver(
    variables:             Map[String, TypeVariable],
    index:                 ResolvablesIndex,
    observed:              Option[String],
    allowReceiverConflict: Boolean      = false,
    source:                SourceOrigin = SourceOrigin.Synth
  ):

    private def at(site: SourceOrigin): Solver =
      if observed.isEmpty then this else copy(source = site)

    private type Substitution = Map[String, Type]
    private type Result       = Either[Failure, Solution]

    /** Checks repeated bindings after all receiver observations, using ordinary unification. */
    def validateEqualities(solution: Solution): Result =
      solution.equalities.foldLeftM(solution) { case (known, (left, right)) =>
        matchType(resolve(left, known.types), resolve(right, known.types), known)
      }

    /** Diagnostics name source binders rather than the solver's temporary identities. */
    def restoreFailure(failure: Failure): Failure = failure match
      case Failure.ConflictingArgument(variable, first, second) =>
        Failure.ConflictingArgument(
          variable,
          TypeSubstitution(first, variables),
          TypeSubstitution(second, variables)
        )
      case Failure.IncompatibleTypes(expected, actual) =>
        Failure.IncompatibleTypes(
          TypeSubstitution(expected, variables),
          TypeSubstitution(actual, variables)
        )
      case Failure.NotCallable(tpe) => Failure.NotCallable(TypeSubstitution(tpe, variables))
      case other => other

    /** Follows substitution chains throughout a type; bind rejects cycles before recording them. */
    def resolve(tpe: Type, known: Substitution): Type = tpe match
      case variable: TypeVariable =>
        variable.resolvedId.flatMap(known.get).fold[Type](variable)(resolve(_, known))
      case other => TypeTraversal.traverseChildren[cats.Id](other)(resolve(_, known))

    def containsUnsolved(tpe: Type, known: Substitution): Boolean =
      TypeTraversal.exists(resolve(tpe, known)) {
        case variable: TypeVariable => variable.resolvedId.exists(variables.contains)
        case _ => false
      }

    /** Records a first binding or unifies a later demand with it. A receiver used inconsistently
      * within one call can retain multiple concrete demands for the diagnostic. Other binders must
      * still have one type across all their occurrences.
      */
    private def bind(variable: TypeVariable, actual: Type, known: Solution): Result =
      variable.resolvedId.toRight(Failure.UnresolvedParameter(variable)).flatMap { id =>
        val concrete = resolve(actual, known.types)
        if isVariable(concrete, id) then known.asRight
        else
          val original  = variables(id)
          val recording = observed.contains(id)
          val evidence =
            if recording then
              known.copy(observations = LocatedType(concrete, source) :: known.observations)
            else known

          known.types.get(id) match

            case Some(first) =>
              // Another binder's first binding and the type supplied here must still agree after
              // recovery. Either type can contain a receiver that gains a different binding.
              val pending =
                if allowReceiverConflict && !recording then
                  evidence.copy(equalities = (first, actual) :: evidence.equalities)
                else evidence

              // The receiver's first binding can itself be a callback variable. Follow its
              // current binding so a disagreement counts as a demand on the receiver.
              val canReplaceReceiver = recording && allowReceiverConflict
              val expected = if canReplaceReceiver then resolve(first, known.types) else first
              matchType(expected, concrete, pending) match

                case Left(_: Failure.IncompatibleTypes) if canReplaceReceiver =>
                  if occurs(id, concrete) then Failure.IncompatibleTypes(original, concrete).asLeft
                  else evidence.copy(types = evidence.types.updated(id, concrete)).asRight

                case Left(_: Failure.IncompatibleTypes) =>
                  Failure
                    .ConflictingArgument(original, resolve(first, known.types), concrete)
                    .asLeft

                case other => other

            case None if occurs(id, concrete) =>
              Failure.IncompatibleTypes(original, concrete).asLeft

            case None => evidence.copy(types = evidence.types.updated(id, concrete)).asRight
      }

    private def occurs(id: String, tpe: Type): Boolean = TypeTraversal.exists(tpe) {
      case variable: TypeVariable => variable.resolvedId.contains(id)
      case _ => false
    }

    private def isVariable(tpe: Type, id: String): Boolean = tpe match
      case variable: TypeVariable => variable.resolvedId.contains(id)
      case _ => false

    private def matchType(wanted: Type, actual: Type, known: Solution): Result =
      (TypeQueries.canonical(wanted, index), TypeQueries.canonical(actual, index)) match

        case (Some(variable: TypeVariable), Some(other))
            if variable.resolvedId.exists(variables.contains) =>
          bind(variable, other, known)

        case (Some(other), Some(variable: TypeVariable))
            if variable.resolvedId.exists(variables.contains) =>
          bind(variable, other, known)

        case (Some(left: TypeFn), Some(right: TypeFn))
            if left.paramTypes.size == right.paramTypes.size =>
          matchChildren(left, right, known)

        case (Some(left: TypeTuple), Some(right: TypeTuple))
            if left.elements.size == right.elements.size =>
          matchChildren(left, right, known)

        case (Some(left: TypeApplication), Some(right: TypeApplication))
            if left.args.size == right.args.size =>
          matchChildren(left, right, known)

        case (Some(left), Some(right)) if TypeCompatibility.same(left, right, index) =>
          known.asRight
        case _ => Failure.IncompatibleTypes(wanted, actual).asLeft

    private def matchChildren(wanted: Type, actual: Type, known: Solution): Result =
      TypeTraversal.children(wanted).zip(TypeTraversal.children(actual)).foldLeftM(known) {
        case (current, (left, right)) => matchType(left, right, current)
      }

    def consume(remaining: Type, call: Call, known: Solution): Result = call.arguments match

      case Nil =>
        call.result.fold(known.asRight[Failure])(
          at(call.resultSource).matchType(remaining, _, known)
        )

      case argument :: rest =>
        val site = call.argumentSources.headOption.getOrElse(SourceOrigin.Synth)
        val later = call.copy(
          arguments       = rest,
          ascriptions     = call.ascriptions.drop(1),
          argumentSources = call.argumentSources.drop(1)
        )
        call.ascriptions.headOption
          .getOrElse(Nil)
          .foldLeftM(known) { (current, annotation) =>
            at(annotation.source).matchType(remaining, annotation, current)
          }
          .flatMap(consumeArgument(remaining, argument, site, later, _))

    /** Matches an operand against the next input, including a returned function. One final known
      * input and a non-function result determine an unknown callable's arity. Otherwise matching
      * stops for this call, retaining the partial solution for callback refinement.
      */
    @tailrec
    private def consumeArgument(
      remaining: Type,
      argument:  Option[Type],
      site:      SourceOrigin,
      later:     Call,
      known:     Solution
    ): Result =
      TypeQueries.canonical(remaining, index) match

        case Some(function: TypeFn) =>
          argument
            .fold(known.asRight[Failure])(at(site).matchType(function.paramTypes.head, _, known))
            .flatMap(consume(remainingCallableType(function, function.source), later, _))

        case Some(variable: TypeVariable) if variable.resolvedId.exists(variables.contains) =>
          val resolved = resolve(variable, known.types)
          if resolved != variable then consumeArgument(resolved, argument, site, later, known)
          else
            (argument, later.arguments, later.result) match
              case (Some(input), Nil, Some(result))
                  if !TypeQueries.canonical(result, index).exists(_.isInstanceOf[TypeFn]) =>
                at(site).matchType(
                  variable,
                  TypeFn(variable.source, NonEmptyList.one(input), result),
                  known
                )
              case _ => known.asRight

        case _ => Failure.NotCallable(remaining).asLeft
