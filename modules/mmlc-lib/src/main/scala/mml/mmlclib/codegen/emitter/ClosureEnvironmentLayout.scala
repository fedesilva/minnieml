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
    val layouts = module.members.collect { case struct: TypeStruct => struct.name -> struct }.toMap
    val lambdas = module.members
      .collect { case binding: Bnd => binding }
      .flatMap(binding => TermTraversal.collect(binding.value) { case lambda: Lambda => lambda })
      .filter(_.captures.nonEmpty)
    lambdas
      .traverse { lambda =>
        for
          name <- lambda.meta
            .flatMap(_.envStructName)
            .toRight(CodeGenError("Capturing lambda missing environment identity", lambda.some))
          layout <- layouts
            .get(name)
            .toRight(CodeGenError("Missing closure environment", lambda.some))
          id <- layout.id.toRight(
            CodeGenError("Closure environment missing layout identity", lambda.some)
          )
          offset = if lambda.isMove then 1 else 0
          fields = layout.fields.toList.drop(offset)
          _ <- Either.cond(
            fields.size == lambda.captures.size,
            (),
            CodeGenError("Capture layout mismatch", lambda.some)
          )
          captures = lambda.captures.zip(fields).map { (capture, field) =>
            val target = Option
              .when(lambda.meta.exists(_.isPartialApplication))(targets.target(capture.ref))
              .flatten
              .flatMap(targets.definitions.get)
              .filter(_ => !capture.isInstanceOf[Capture.CapturedLiteral])
            val storage = target match
              case Some(definition) if definition.lambda.captures.isEmpty =>
                CaptureStorage.Static(definition.id)
              case Some(definition) =>
                val pointer = TypeRef(SourceOrigin.Synth, "RawPtr", "stdlib::typedef::RawPtr".some)
                CaptureStorage.Environment(field.copy(typeSpec = pointer), definition.id)
              case None => CaptureStorage.Value(field)
            PlannedCapture(capture, field, storage)
          }
          omitted = captures
            .collect { case PlannedCapture(_, field, _: CaptureStorage.Static) => field.id }
            .flatten
            .toSet
          cleanup = module.members
            .collect { case binding: Bnd => binding }
            .flatMap(binding =>
              TermTraversal.collect(binding.value) {
                case destruction: DestroyClosureEnvironment if destruction.layoutId == id =>
                  destruction
              }
            )
            .flatMap(_.fields)
          _ <- Either.cond(
            !cleanup.exists(field => omitted.contains(field.fieldId)),
            (),
            CodeGenError("Cannot omit a capture with payload destruction", lambda.some)
          )
          physical = layout.copy(fields =
            (layout.fields.take(offset) ++ captures.flatMap(_.storage.field)).toVector
          )
        yield id -> ClosureEnvironmentLayout(physical, captures)
      }
      .map(_.toMap)
