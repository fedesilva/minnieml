package mml.mmlclib.ast

/** A generated layout paired with the lambda whose captures define its payload. */
case class ClosureEnvironment(layout: TypeStruct, lambda: Lambda):

  def headerSize: Int = if lambda.isMove then 1 else 0

  def captureFields: List[(Field, Capture)] =
    layout.fields.toList.drop(headerSize).zip(lambda.captures)

case class ClosureEnvironmentError(lambda: Lambda, message: String)

/** Only unambiguous, shape-checked associations enter the environment index. */
case class ClosureEnvironments(
  byId:   Map[String, ClosureEnvironment],
  errors: List[ClosureEnvironmentError]
)

object ClosureEnvironments:

  private def layoutId(lambda: Lambda): Either[ClosureEnvironmentError, String] =
    lambda.meta
      .flatMap(_.envLayoutId)
      .toRight(
        ClosureEnvironmentError(lambda, "Capturing lambda missing environment layout identity")
      )

  private def sameCapture(left: Capture, right: Capture): Boolean =
    left.ref.resolvedId == right.ref.resolvedId && ((left, right) match
      case (_: Capture.CapturedRef, _: Capture.CapturedRef) => true
      case (_: Capture.BorrowedRef, _: Capture.BorrowedRef) => true
      case (Capture.OwnedClosure(_, leftTarget), Capture.OwnedClosure(_, rightTarget)) =>
        leftTarget == rightTarget
      case (Capture.CapturedLiteral(_, leftClone), Capture.CapturedLiteral(_, rightClone)) =>
        leftClone == rightClone
      case (
            Capture.CapturedConditional(_, leftWitness, leftClone),
            Capture.CapturedConditional(_, rightWitness, rightClone)
          ) =>
        leftWitness.resolvedId == rightWitness.resolvedId && leftClone == rightClone
      case _ => false)

  /** Resolve the declaration identity retained across lambda rewrites. */
  def layout(lambda: Lambda, index: ResolvablesIndex): Either[ClosureEnvironmentError, TypeStruct] =
    for
      id <- layoutId(lambda)
      struct <- index
        .lookupType(id)
        .collect {
          case struct: TypeStruct if struct.id.contains(id) =>
            struct
        }
        .toRight(ClosureEnvironmentError(lambda, s"Missing closure environment layout: $id"))
    yield struct

  /** Repeated occurrences may share a layout only when their capture contracts agree. */
  def analyze(lambdas: List[Lambda], index: ResolvablesIndex): ClosureEnvironments =

    val identities = lambdas.map(lambda => layoutId(lambda).map(_ -> lambda))
    val identified = identities.collect { case Right(entry) => entry }
    val missing    = identities.collect { case Left(error) => error }

    val checked = identified.groupMap(_._1)(_._2).toList.sortBy(_._1).flatMap {

      case (id, lambda :: rest) =>
        val conflict = rest.exists { other =>
          other.isMove != lambda.isMove || other.captures.size != lambda.captures.size ||
          !other.captures.zip(lambda.captures).forall(sameCapture) ||
          other.meta.exists(_.isPartialApplication) != lambda.meta.exists(_.isPartialApplication)
        }

        val result = for
          _ <- Either.cond(
            !conflict,
            (),
            ClosureEnvironmentError(lambda, s"Conflicting closure environment metadata: $id")
          )
          struct <- layout(lambda, index)
          environment = ClosureEnvironment(struct, lambda)
          _ <- Either.cond(
            struct.fields.size == lambda.captures.size + environment.headerSize,
            (),
            ClosureEnvironmentError(lambda, s"Closure environment capture count mismatch: $id")
          )
        yield id -> environment

        List(result)

      case _ => Nil
    }

    ClosureEnvironments(
      checked.collect { case Right(entry) => entry }.toMap,
      missing ++ checked.collect { case Left(error) => error }
    )
