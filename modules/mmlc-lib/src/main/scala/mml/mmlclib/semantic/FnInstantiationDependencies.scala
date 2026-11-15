package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.Ref

import scala.collection.immutable.Queue

/** Identifies a concrete function by source identity and canonical resolved argument types.
  * Enclosing substitutions distinguish local functions whose own type arguments are identical.
  */
private[semantic] case class FnInstanceKey(
  functionId: String,
  arguments:  List[ProtocolTypeKey],
  enclosing:  Map[String, ProtocolTypeKey] = Map.empty
) derives CanEqual

/** One concrete body requires another instance, either by calling it or taking its value. */
private[semantic] case class FnInstantiationDependency(
  caller: FnInstanceKey,
  callee: FnInstanceKey,
  site:   Ref
)

/** A dependency path returns to one source function with different concrete substitutions. */
private[semantic] case class ChangingTypeRecursion(
  path: NonEmptyList[FnInstantiationDependency]
)

/** Retains dependencies even when a callee's body is already cached. Every accepted path preserves
  * the substitution when it revisits a source function. Checking both sides of a new edge catches
  * violations regardless of the order in which concrete bodies are discovered.
  */
private[semantic] final class FnInstantiationDependencies private (
  val edges: Vector[FnInstantiationDependency]
):

  def add(
    edge: FnInstantiationDependency
  ): Either[ChangingTypeRecursion, FnInstantiationDependencies] =

    val callers = paths(edge.caller, backwards = true)
    val callees = paths(edge.callee, backwards = false)
    val conflict = callers.iterator
      .flatMap { case (caller, before) =>
        callees.iterator.collect {
          case (callee, after) if caller.functionId == callee.functionId && caller != callee =>
            ChangingTypeRecursion(NonEmptyList(edge, after).prependList(before))
        }
      }
      .nextOption()

    conflict match
      case Some(recursion) => recursion.asLeft
      case None =>
        val exists = edges.exists(e => e.caller == edge.caller && e.callee == edge.callee)
        (if exists then this else new FnInstantiationDependencies(edges :+ edge)).asRight

  /** Returns each reachable instance with a directed evidence path. Reverse traversal prepends
    * edges so its evidence still reads from an ancestor to the starting instance.
    */
  private def paths(
    start:     FnInstanceKey,
    backwards: Boolean
  ): Vector[(FnInstanceKey, List[FnInstantiationDependency])] =

    val adjacency = edges.groupBy(edge => if backwards then edge.callee else edge.caller)

    @scala.annotation.tailrec
    def visit(
      pending: Queue[(FnInstanceKey, List[FnInstantiationDependency])],
      seen:    Set[FnInstanceKey],
      result:  Vector[(FnInstanceKey, List[FnInstantiationDependency])]
    ): Vector[(FnInstanceKey, List[FnInstantiationDependency])] =
      pending.dequeueOption match
        case None => result
        case Some(((key, path), rest)) =>
          val next = adjacency
            .getOrElse(key, Vector.empty)
            .map { edge =>
              if backwards then (edge.caller, edge :: path)
              else (edge.callee, path :+ edge)
            }
            .filterNot { case (key, _) => seen.contains(key) }
          visit(rest.enqueueAll(next), seen ++ next.map(_._1), result :+ (key -> path))

    visit(Queue(start -> List.empty[FnInstantiationDependency]), Set(start), Vector.empty)

private[semantic] object FnInstantiationDependencies:

  val empty: FnInstantiationDependencies = new FnInstantiationDependencies(Vector.empty)
