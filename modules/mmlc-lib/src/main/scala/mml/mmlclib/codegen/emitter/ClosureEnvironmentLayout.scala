package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Physical PAP payloads preserve semantic captures while omitting proven constant code pointers.
  */
enum CaptureStorage:
  case Value(value: Field)
  case Environment(value: Field, targetId: String)
  case Static(targetId: String)

  def field: Option[Field] = this match
    case Value(value) => value.some
    case Environment(value, _) => value.some
    case Static(_) => none

case class PlannedCapture(capture: Capture, semanticField: Field, storage: CaptureStorage)

/** Allocation, capture access, metadata, and destruction share this physical layout. */
case class ClosureEnvironmentLayout(layout: TypeStruct, captures: List[PlannedCapture]):

  def runtimeCaptures: List[PlannedCapture] = captures.filter(_.storage.field.isDefined)

  def storage(fieldId: String): Option[CaptureStorage] =
    captures.map(_.storage).find(_.field.exists(_.id.contains(fieldId)))

object ClosureEnvironmentLayout:

  /** Only PAP environments compress callable captures. Ordinary closure values retain their ABI. */
  def analyze(
    module:  Module,
    targets: CallableTargetAnalysis
  ): Either[CodeGenError, Map[String, ClosureEnvironmentLayout]] =
    // Semantic errors prevent codegen, so every capturing lambda requires a layout.
    val lambdas = module.members
      .collect { case binding: Bnd => binding }
      .flatMap(binding => TermTraversal.collect(binding.value) { case lambda: Lambda => lambda })
      .filter(lambda => lambda.captures.nonEmpty || lambda.meta.exists(_.envLayoutId.isDefined))
    val environments = ClosureEnvironments.analyze(lambdas, module.resolvables)
    environments.errors.headOption match
      case Some(error) => CodeGenError(error.message, error.lambda.some).asLeft
      case None => plan(module, targets, environments.byId)

  private def plan(
    module:       Module,
    targets:      CallableTargetAnalysis,
    environments: Map[String, ClosureEnvironment]
  ): Either[CodeGenError, Map[String, ClosureEnvironmentLayout]] =
    environments.toList
      .sortBy(_._1)
      .traverse { (id, environment) =>
        val lambda = environment.lambda
        val layout = environment.layout
        val captures = environment.captureFields.map { (field, capture) =>
          val target = Option
            .when(lambda.meta.exists(_.isPartialApplication))(targets.target(capture.ref))
            .flatten
            .flatMap(targets.definitions.get)
            .filter: _ =>
              capture match
                case _: Capture.CapturedLiteral | _: Capture.CapturedConditional => false
                case _ => true
          val storage = target match
            case Some(definition) if definition.lambda.captures.isEmpty =>
              CaptureStorage.Static(definition.id)
            case Some(definition) =>
              val pointer = TypeRef(SourceOrigin.Synth, "RawPtr", "stdlib::typedef::RawPtr".some)
              CaptureStorage.Environment(field.copy(typeSpec = pointer), definition.id)
            case None => CaptureStorage.Value(field)
          PlannedCapture(capture, field, storage)
        }
        val omitted = captures
          .collect { case PlannedCapture(_, field, _: CaptureStorage.Static) => field.id }
          .flatten
          .toSet
        val cleanup = module.members
          .collect { case binding: Bnd => binding }
          .flatMap(binding =>
            TermTraversal.collect(binding.value) {
              case destruction: DestroyClosureEnvironment if destruction.layoutId == id =>
                destruction
            }
          )
          .flatMap(_.fields)
        val physical = layout.copy(fields =
          (layout.fields.take(environment.headerSize) ++
            captures.flatMap(_.storage.field)).toVector
        )
        Either.cond(
          !cleanup.exists(field => omitted.contains(field.fieldId)),
          id -> ClosureEnvironmentLayout(physical, captures),
          CodeGenError("Cannot omit a capture with payload destruction", lambda.some)
        )
      }
      .map(_.toMap)
