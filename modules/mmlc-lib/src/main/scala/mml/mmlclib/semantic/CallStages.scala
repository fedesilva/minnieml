package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Typed invocation boundaries within one left-associated application chain. */
object CallStages:

  final case class Stage(
    callee:       Ref | App | Lambda,
    applications: List[App],
    signature:    Option[TypeFn],
    result:       App,
    complete:     Boolean
  ):
    def arguments:  List[Expr]   = applications.map(_.arg)
    def resultType: Option[Type] = result.typeSpec

    def isPartial: Boolean = signature.exists(applications.size < _.paramTypes.size)

  /** Nullary callables consume their typed Unit input without inventing a source parameter. */
  def apply(
    app:      App,
    index:    ResolvablesIndex,
    recovery: ValueAvailability
  ): List[Stage] =

    @scala.annotation.tailrec
    def applications(current: App, collected: List[App]): (Ref | Lambda, List[App]) =
      current.fn match
        case inner:  App => applications(inner, current :: collected)
        case callee: (Ref | Lambda) => (callee, current :: collected)

    def signature(callee: Ref | App | Lambda): Option[TypeFn] =
      val declaredType = callee match
        case ref: Ref =>
          ref.resolvedId
            .flatMap(index.lookup)
            .collect { case value: Typeable => value }
            .flatMap(_.typeSpec)
        case _ => none

      Option.when(recovery.isAvailable(callee))(()).flatMap { _ =>
        callee.typeSpec
          .orElse(declaredType)
          .flatMap(TypeUtils.canonical(_, index))
          .collect { case callable: TypeFn => callable }
      }

    @scala.annotation.tailrec
    def partition(
      callee:    Ref | App | Lambda,
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

    val (callee, chain) = applications(app, Nil)
    partition(callee, chain, Nil)
