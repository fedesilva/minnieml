package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.TypeQueries

import scala.annotation.tailrec

/** Solves one generic callable's quantified variables from a single use. Caller variables remain
  * rigid types, while quantified function arguments are instantiated independently at each use.
  */
object GenericInference:

  enum Failure:
    case TypeArgumentCount(expected: Int, actual: Int)
    case MissingArguments(parameters: List[TypeVariable])
    case ConflictingArgument(variable: TypeVariable, first: Type, second: Type)
    case IncompatibleTypes(expected: Type, actual: Type)
    case NotCallable(tpe: Type)
    case UnresolvedParameter(parameter: TypeVariable)

  /** Solved callee arguments and the binders still awaiting evidence. Every substitution value is
    * fully resolved; missing binders include those introduced by generic function arguments.
    */
  case class Partial(substitution: Map[String, Type], missing: List[TypeVariable]):

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
    val inputs = signature :: (argumentTypes.flatten ++ expectedResult ++
      explicitTypes.toList.flatten ++ calleeAscriptions.flatten)
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
      callee <- freshParameters(parameters, "callee", occupied)
      arguments <- argumentTypes.zipWithIndex.traverse { (argument, position) =>
        prepareArgument(argument, position, occupied)
      }
      initial <- explicitTypes match
        case Some(types) if types.size != parameters.size =>
          Failure.TypeArgumentCount(parameters.size, types.size).asLeft
        case Some(types) => callee.map(_._2).zip(types).toMap.asRight[Failure]
        case None => Map.empty[String, Type].asRight[Failure]
      replacements = callee.map { (original, id) =>
        original.resolvedId.getOrElse(id) -> original.copy(resolvedId = id.some)
      }.toMap
      callable = signature.copy(
        paramTypes = signature.paramTypes.map(TypeSubstitution(_, replacements)),
        returnType = TypeSubstitution(signature.returnType, replacements)
      )
      binders = callee ++ arguments.flatMap(_.binders)
      solver  = Solver(binders.map { (original, id) => id -> original }.toMap, index)
      inferred <- solver
        .consume(
          callable,
          arguments.map(_.tpe),
          expectedResult,
          initial,
          calleeAscriptions
        )
        .leftMap(solver.restoreFailure)
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
    yield Partial(result.flatten.toMap, unresolved.distinct)

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

  /** Argument evidence after replacing its quantified binders with use-local identities.
    * Monomorphic arguments have no binders; unknown argument types supply no evidence.
    */
  private case class Argument(tpe: Option[Type], binders: List[(TypeVariable, String)])

  /** Opens a function argument's scheme without making its binders visible to another argument. */
  private def prepareArgument(
    argument: Option[Type],
    position: Int,
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

  /** Unifies the fresh variables for one call. All other variable identities are rigid caller
    * types. Substitutions stay acyclic and are shared across argument and expected-type evidence.
    */
  private case class Solver(variables: Map[String, TypeVariable], index: ResolvablesIndex):

    private type Substitution = Map[String, Type]
    private type Result       = Either[Failure, Substitution]

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

    private def bind(variable: TypeVariable, actual: Type, known: Substitution): Result =
      variable.resolvedId.toRight(Failure.UnresolvedParameter(variable)).flatMap { id =>
        val concrete = resolve(actual, known)
        val original = variables(id)
        known.get(id) match
          case Some(first) =>
            matchType(first, concrete, known).leftMap {
              case _: Failure.IncompatibleTypes =>
                Failure.ConflictingArgument(original, resolve(first, known), concrete)
              case other => other
            }
          case None if isVariable(concrete, id) =>
            known.asRight
          case None if TypeTraversal.exists(concrete) {
                case other: TypeVariable => other.resolvedId.contains(id)
                case _ => false
              } =>
            Failure.IncompatibleTypes(original, concrete).asLeft
          case None => known.updated(id, concrete).asRight
      }

    private def isVariable(tpe: Type, id: String): Boolean = tpe match
      case variable: TypeVariable => variable.resolvedId.contains(id)
      case _ => false

    private def matchType(wanted: Type, actual: Type, known: Substitution): Result =
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

    private def matchChildren(wanted: Type, actual: Type, known: Substitution): Result =
      TypeTraversal.children(wanted).zip(TypeTraversal.children(actual)).foldLeftM(known) {
        case (current, (left, right)) => matchType(left, right, current)
      }

    def consume(
      remaining:         Type,
      arguments:         List[Option[Type]],
      expected:          Option[Type],
      known:             Substitution,
      calleeAscriptions: List[List[Type]]
    ): Result = arguments match

      case Nil => expected.fold(known.asRight[Failure])(matchType(remaining, _, known))

      case argument :: rest =>
        val annotations = calleeAscriptions.headOption.getOrElse(Nil)
        annotations
          .foldLeftM(known) { (current, annotation) =>
            matchType(remaining, annotation, current)
          }
          .flatMap { constrained =>
            consumeArgument(
              remaining,
              argument,
              rest,
              expected,
              constrained,
              calleeAscriptions.drop(1)
            )
          }

    /** Matches one argument against the next callable input, preserving returned-call boundaries.
      * An unknown callable can be inferred only from one final known input and a non-function
      * result; longer chains do not determine its arity. Otherwise the partial solution retains the
      * missing binder so contextual callback evidence can determine that boundary later.
      */
    private def consumeArgument(
      remaining:        Type,
      argument:         Option[Type],
      rest:             List[Option[Type]],
      expected:         Option[Type],
      known:            Substitution,
      laterAscriptions: List[List[Type]]
    ): Result =
      TypeQueries.canonical(remaining, index) match

        case Some(function: TypeFn) =>
          argument
            .fold(known.asRight[Failure])(matchType(function.paramTypes.head, _, known))
            .flatMap { inferred =>
              consume(
                remainingCallableType(function, function.source),
                rest,
                expected,
                inferred,
                laterAscriptions
              )
            }

        case Some(variable: TypeVariable) if variable.resolvedId.exists(variables.contains) =>
          val resolved = resolve(variable, known)
          if resolved != variable then
            consume(resolved, argument :: rest, expected, known, Nil :: laterAscriptions)
          else
            (argument :: rest, expected) match
              case (List(Some(input)), Some(result))
                  if !TypeQueries.canonical(result, index).exists(_.isInstanceOf[TypeFn]) =>
                matchType(variable, TypeFn(variable.source, NonEmptyList.one(input), result), known)
              case _ => known.asRight

        case _ => Failure.NotCallable(remaining).asLeft
