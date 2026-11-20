package mml.mmlclib.semantic

import cats.data.NonEmptyList
import mml.mmlclib.ast.*
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

import scala.annotation.tailrec

/** Validates protocol requirements and computes which protocols each accepted declaration implies.
  * Written edges retain their locations for duplicate, cycle, and dependent-declaration errors.
  * Instance availability is checked separately, after all instance declarations are known.
  */
private[semantic] object ProtocolPrerequisites:

  private val phaseName = "mml.mmlclib.semantic.ProtocolPrerequisites"

  case class Result(registry: ProtocolRegistry, diagnostics: DiagnosticStore)

  /** A resolved requirement and the written reference that introduces it. */
  private case class Edge(target: String, reference: ProtocolRef)

  /** The unvisited requirements of one declaration on the active depth-first path. */
  private case class Frame(id: String, remaining: List[Edge])

  /** Immutable graph results and diagnostic roots for rejected declarations. */
  private case class Analysis(
    diagnostics: DiagnosticStore,
    failures:    Map[String, Vector[DiagnosticId]] = Map.empty,
    implied:     Map[String, Set[String]]          = Map.empty,
    order:       Vector[String]                    = Vector.empty
  ):

    def reject(ids: Iterable[String], roots: Vector[DiagnosticId]): Analysis =
      copy(failures = ids.foldLeft(failures) { (current, id) =>
        current.updated(id, (current.getOrElse(id, Vector.empty) ++ roots).distinct)
      })

    def report(
      ids:    Iterable[String],
      error:  ProtocolError,
      causes: Vector[DiagnosticId] = Vector.empty
    ): Analysis =
      val (updated, roots) =
        diagnostics.emit(SemanticError.ProtocolErrorFound(error, phaseName), causes)
      copy(diagnostics = updated).reject(ids, roots)

  def check(
    protocols:   List[Protocol],
    registry:    ProtocolRegistry,
    index:       ResolvablesIndex,
    diagnostics: DiagnosticStore
  ): Result =
    val declarations = protocols.flatMap(protocol => protocol.id.map(_ -> protocol))
    val nodes        = declarations.toMap
    val edges = declarations.map { (id, protocol) =>
      val requirements = protocol.constraints
        .filter(entry => validTarget(entry, protocol))
        .flatMap(_.constraints)
        .flatMap(ref => ref.resolvedId.filter(nodes.contains).map(Edge(_, ref)))
        .distinctBy(_.target)
      id -> requirements
    }.toMap
    val initial = declarations.foldLeft(Analysis(diagnostics)) { case (current, (id, protocol)) =>
      val registered = registry.protocol(id, index) match
        case Left(ProtocolLookupError.RejectedDeclaration(roots)) => current.reject(List(id), roots)
        case Left(error) => current.report(List(id), ProtocolError.RegistryFailure(protocol, error))
        case Right(_) => current
      checkWritten(id, protocol, nodes.keySet, registered)
    }

    /** Finishing after the prerequisites gives each valid declaration its complete transitive set.
      * A rejected prerequisite contributes its original causes to the written dependent reference.
      */
    def finish(id: String, current: Analysis): Analysis =
      val requirements = edges.getOrElse(id, Nil)
      val checked =
        if current.failures.contains(id) then current
        else
          requirements.foldLeft(current) { (result, edge) =>
            result.failures.get(edge.target).fold(result) { roots =>
              result.report(List(id), ProtocolError.InvalidPrerequisite(edge.reference), roots)
            }
          }

      if checked.failures.contains(id) then checked
      else
        val implied = requirements.foldLeft(Set.empty[String]) { (all, edge) =>
          all ++ checked.implied.getOrElse(edge.target, Set.empty) + edge.target
        }
        checked.copy(implied = checked.implied.updated(id, implied), order = checked.order :+ id)

    /** Visits each protocol's prerequisites before finishing that protocol. `done` includes
      * rejected declarations, so completed targets are not visited again. `finish` propagates their
      * failures to dependants and adds only accepted declarations to implication results and
      * prerequisite order.
      */
    @tailrec
    def visit(
      pending: List[String],
      stack:   List[Frame],
      active:  Set[String],
      done:    Set[String],
      current: Analysis
    ): Analysis = stack match

      case Nil =>
        pending match
          case Nil => current
          case id :: rest if done.contains(id) => visit(rest, Nil, active, done, current)
          case id :: rest =>
            visit(rest, List(Frame(id, edges.getOrElse(id, Nil))), active + id, done, current)

      case Frame(id, Nil) :: outer =>
        visit(pending, outer, active - id, done + id, finish(id, current))

      case Frame(id, edge :: remaining) :: outer =>
        val advanced = Frame(id, remaining) :: outer
        if active.contains(edge.target) then
          val path   = edge.target :: stack.takeWhile(_.id != edge.target).map(_.id).reverse
          val closed = path :+ edge.target
          val checked =
            NonEmptyList.fromList(closed.flatMap(nodes.get)).fold(current) { declarations =>
              current.report(path, ProtocolError.PrerequisiteCycle(edge.reference, declarations))
            }
          visit(pending, advanced, active, done, checked)
        else if done.contains(edge.target) then visit(pending, advanced, active, done, current)
        else
          val next = Frame(edge.target, edges.getOrElse(edge.target, Nil))
          visit(pending, next :: advanced, active + edge.target, done, current)

    val checked = visit(declarations.map(_._1), Nil, Set.empty, Set.empty, initial)
    val rejected = declarations.foldLeft(Result(registry, checked.diagnostics)) {
      case (current, (id, protocol)) =>
        checked.failures.get(id).fold(current) { roots =>
          current.registry.rejectProtocol(id, roots, index) match
            case Right(updated) => current.copy(registry = updated)
            case Left(error) =>
              val (reported, _) = current.diagnostics.emit(
                SemanticError
                  .ProtocolErrorFound(ProtocolError.RegistryFailure(protocol, error), phaseName)
              )
              current.copy(diagnostics = reported)
        }
    }
    rejected.copy(registry = rejected.registry.withPrerequisites(checked.implied, checked.order))

  private def validTarget(entry: TypeVariableConstraints, protocol: Protocol): Boolean =
    protocol.typeParameter.resolvedId.nonEmpty &&
      entry.variable.resolvedId == protocol.typeParameter.resolvedId

  /** Retains resolver failures without repeating them, and reports independent written duplicates.
    * Synthetic declarations lacking resolver evidence receive their own targeted diagnostic.
    */
  private def checkWritten(
    id:       String,
    protocol: Protocol,
    known:    Set[String],
    initial:  Analysis
  ): Analysis =
    val resolved = protocol.constraints.foldLeft(initial) { (current, entry) =>
      val roots = current.diagnostics.roots(entry.causes).getOrElse(Vector.empty)
      if roots.nonEmpty then current.reject(List(id), roots)
      else
        val target =
          if validTarget(entry, protocol) then current
          else
            current.report(
              List(id),
              ProtocolError.InvalidPrerequisiteTarget(entry.variable, protocol)
            )
        entry.constraints.foldLeft(target) { (result, ref) =>
          if ref.resolvedId.exists(known.contains) then result
          else result.report(List(id), ProtocolError.UnknownProtocol(ref))
        }
    }
    protocol.constraints.foldLeft(resolved) { (current, entry) =>
      val (_, checked) = entry.constraints
        .foldLeft((Map.empty[String, ProtocolRef], current)) { case ((seen, result), ref) =>
          ref.resolvedId match
            case Some(target) =>
              seen.get(target) match
                case Some(first) =>
                  (seen, result.report(List(id), ProtocolError.DuplicatePrerequisite(ref, first)))
                case None => (seen.updated(target, ref), result)
            case None => (seen, result)
        }
      checked
    }
