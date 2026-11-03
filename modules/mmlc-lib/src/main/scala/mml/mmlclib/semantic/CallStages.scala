package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.ApplicationQueries

/** Typed invocation boundaries within one left-associated application chain. */
object CallStages:

  /** One invocation's supplied nodes and final result. Completion requires every input in a known
    * signature to be supplied, with available operands and result. A partial stage retains its
    * supplied prefix.
    */
  final case class Stage(
    callee:       Callee,
    applications: List[App],
    signature:    Option[TypeFn],
    result:       App,
    complete:     Boolean
  ):
    def arguments:  List[Expr]   = applications.map(_.arg)
    def resultType: Option[Type] = result.typeSpec

    def isPartial: Boolean = signature.exists(applications.size < _.paramTypes.size)

  /** Partitions a chain by callable arity, retaining incomplete stages when values are unavailable.
    * Nullary callables consume their typed Unit input without inventing a source parameter.
    */
  def apply(
    app:      App,
    index:    ResolvablesIndex,
    recovery: ValueAvailability
  ): List[Stage] =

    def signature(callee: Callee): Option[TypeFn] =
      Option
        .when(recovery.isAvailable(callee))(())
        .flatMap(_ => ApplicationQueries.signature(callee, index))

    /** A known signature ends a stage at its arity; unknown signatures retain the remaining chain
      * as one incomplete stage because no later invocation boundary can be established.
      */
    @scala.annotation.tailrec
    def partition(
      callee:    Callee,
      remaining: List[App],
      stages:    List[Stage]
    ): List[Stage] =
      remaining match
        case Nil => stages.reverse
        case first :: _ =>
          val callable         = signature(callee)
          val arity            = callable.fold(remaining.size)(_.paramTypes.size)
          val (supplied, rest) = remaining.splitAt(arity)
          val result           = supplied.lastOption.getOrElse(first)
          val complete = callable.isDefined && supplied.size == arity &&
            supplied.forall(application => recovery.isAvailable(application.arg)) &&
            recovery.isAvailable(result)
          val stage = Stage(callee, supplied, callable, result, complete)
          partition(result, rest, stage :: stages)

    val (callee, chain) = ApplicationQueries.applicationNodes(app)
    partition(callee, chain, Nil)
