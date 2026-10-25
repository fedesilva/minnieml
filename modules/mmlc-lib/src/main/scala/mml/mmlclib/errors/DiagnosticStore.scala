package mml.mmlclib.errors

import cats.data.State
import cats.syntax.all.*

/** Identity of a diagnostic occurrence within one compilation. */
final case class DiagnosticId(value: Long) derives CanEqual

enum DiagnosticRelation derives CanEqual:
  case Primary
  case Consequence(roots: Vector[DiagnosticId])

/** Original error evidence and its relationship to the compilation's primary failures. */
final case class DiagnosticRecord(
  id:       DiagnosticId,
  error:    CompilationError,
  relation: DiagnosticRelation
):

  def causes: Vector[DiagnosticId] = relation match
    case DiagnosticRelation.Primary => Vector.empty
    case DiagnosticRelation.Consequence(roots) => roots

/** Retains raw diagnostics independently of presentation filtering. */
final case class DiagnosticStore(
  records: Vector[DiagnosticRecord] = Vector.empty,
  nextId:  Long                     = 0L
):

  def errors: Vector[CompilationError] = records.map(_.error)

  def lookup(id: DiagnosticId): Option[DiagnosticRecord] = records.find(_.id == id)

  private def primaryRoots(record: DiagnosticRecord): Option[Vector[DiagnosticId]] =
    record.relation match
      case DiagnosticRelation.Primary => Vector(record.id).some
      case DiagnosticRelation.Consequence(roots) =>
        Option
          .when(roots.nonEmpty)(roots)
          .flatMap(_.traverse { id =>
            lookup(id).filter(_.relation == DiagnosticRelation.Primary).as(id)
          })

  /** Resolve occurrences to primary roots in emission order. Empty input yields Some(empty);
    * missing occurrences or invalid root relations yield None, preserving incomplete evidence.
    */
  def roots(ids: Iterable[DiagnosticId]): Option[Vector[DiagnosticId]] =
    ids.toVector
      .traverse { id =>
        lookup(id).flatMap(primaryRoots)
      }
      .map(_.flatten.distinct.sortBy(_.value))

  /** Invalid causal references cannot make a diagnostic disappear. */
  def visibleRecords: Vector[DiagnosticRecord] = records.filter { record =>
    record.relation match
      case DiagnosticRelation.Primary => true
      case DiagnosticRelation.Consequence(_) => primaryRoots(record).isEmpty
  }

  def visibleErrors: Vector[CompilationError] = visibleRecords.map(_.error)

  /** Register an occurrence and return the primary roots its failed result carries. */
  def emit(
    error:  CompilationError,
    causes: Vector[DiagnosticId] = Vector.empty
  ): (DiagnosticStore, Vector[DiagnosticId]) =
    val id         = DiagnosticId(nextId)
    val knownRoots = roots(causes).filter(_.nonEmpty)
    val relation = knownRoots.fold[DiagnosticRelation](DiagnosticRelation.Primary)(
      DiagnosticRelation.Consequence.apply
    )
    val record = DiagnosticRecord(id, error, relation)
    (copy(records = records :+ record, nextId = nextId + 1), knownRoots.getOrElse(Vector(id)))

object DiagnosticStore:

  /** Emit through explicitly threaded immutable diagnostic state. */
  def report(
    error:  CompilationError,
    causes: Vector[DiagnosticId] = Vector.empty
  ): State[DiagnosticStore, Vector[DiagnosticId]] = State(_.emit(error, causes))

  def reportAll(errors: Iterable[CompilationError]): State[DiagnosticStore, Vector[DiagnosticId]] =
    errors.toVector.traverse(report(_)).map(_.flatten.distinct)
