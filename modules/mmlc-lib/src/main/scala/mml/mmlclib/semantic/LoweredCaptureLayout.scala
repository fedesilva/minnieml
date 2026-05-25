package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Indexes lambda values by the semantic id of the binding that introduces them. */
case class LambdaBindingIndex(bindings: Map[String, Lambda]):
  def lookup(ref: Ref): Option[Lambda] =
    ref.resolvedId.flatMap(bindings.get)

object LambdaBindingIndex:
  def from(module: Module): LambdaBindingIndex =
    def walkExpr(expr: Expr, acc: Map[String, Lambda]): Map[String, Lambda] =
      expr.terms.foldLeft(acc)((a, term) => walkTerm(term, a))

    def walkCallable(fn: Ref | App | Lambda, acc: Map[String, Lambda]): Map[String, Lambda] =
      fn match
        case _:      Ref => acc
        case app:    App => walkTerm(app, acc)
        case lambda: Lambda => walkExpr(lambda.body, acc)

    def walkTerm(term: Term, acc: Map[String, Lambda]): Map[String, Lambda] =
      term match
        case app: App =>
          val withScopedBinding =
            app.fn match
              case scoped: Lambda if scoped.params.size == 1 =>
                app.arg.terms.headOption match
                  case Some(bound: Lambda) =>
                    scoped.params.head.id.fold(acc)(id => acc.updated(id, bound))
                  case _ => acc
              case _ => acc
          walkExpr(app.arg, walkCallable(app.fn, withScopedBinding))
        case lambda: Lambda =>
          walkExpr(lambda.body, acc)
        case cond: Cond =>
          walkExpr(cond.ifFalse, walkExpr(cond.ifTrue, walkExpr(cond.cond, acc)))
        case group: TermGroup =>
          walkExpr(group.inner, acc)
        case tuple: Tuple =>
          tuple.elements.toList.foldLeft(acc)((a, element) => walkExpr(element, a))
        case ref: Ref =>
          ref.qualifier.fold(acc)(walkTerm(_, acc))
        case expr: Expr =>
          walkExpr(expr, acc)
        case _ => acc

    val bindings = module.members.foldLeft(Map.empty[String, Lambda]) {
      case (acc, bnd: Bnd) =>
        val withTop = bnd.value.terms.headOption match
          case Some(lambda: Lambda) => bnd.id.fold(acc)(id => acc.updated(id, lambda))
          case _ => acc
        walkExpr(bnd.value, withTop)
      case (acc, _) => acc
    }
    LambdaBindingIndex(bindings)

/** Computes the value fields needed to represent captures after Direct-callable expansion. */
object LoweredCaptureLayout:
  sealed trait Slot:
    def fieldRef:  Ref
    def fieldName: String

  case class Value(capture: Capture) extends Slot:
    override def fieldRef:  Ref    = capture.ref
    override def fieldName: String = capture.ref.name

  case class DirectOperand(
    callable: Ref,
    operand:  Ref,
    ordinal:  Int
  ) extends Slot:
    override def fieldRef:  Ref    = operand
    override def fieldName: String = s"${callable.name}__${operand.name}_$ordinal"

  def slotsFor(lambda: Lambda, index: LambdaBindingIndex): List[Slot] =
    lowerCaptures(lambda.captures, index, Set.empty)

  private def lowerCaptures(
    captures: List[Capture],
    index:    LambdaBindingIndex,
    seen:     Set[String]
  ): List[Slot] =
    captures.flatMap(lowerCapture(_, index, seen))

  private def lowerCapture(
    capture: Capture,
    index:   LambdaBindingIndex,
    seen:    Set[String]
  ): List[Slot] =
    capture match
      case Capture.CapturedRef(ref) =>
        ref.resolvedId.flatMap(index.bindings.get) match
          case Some(lambda) if lambda.materialization == Materialization.Direct =>
            ref.resolvedId match
              case Some(id) if !seen.contains(id) =>
                directOperands(ref, lambda, index, seen + id)
              case _ =>
                Nil
          case _ =>
            List(Value(capture))
      case _ =>
        List(Value(capture))

  private def directOperands(
    callable: Ref,
    lambda:   Lambda,
    index:    LambdaBindingIndex,
    seen:     Set[String]
  ): List[DirectOperand] =
    val operands = lambda.captures.flatMap {
      case Capture.CapturedRef(ref) =>
        ref.resolvedId.flatMap(index.bindings.get) match
          case Some(nested) if nested.materialization == Materialization.Direct =>
            ref.resolvedId match
              case Some(id) if !seen.contains(id) =>
                directOperands(callable, nested, index, seen + id).map(_.operand)
              case _ =>
                Nil
          case _ =>
            List(ref)
      case cap =>
        List(cap.ref)
    }

    operands.zipWithIndex.map { case (operand, idx) =>
      DirectOperand(callable, operand, idx)
    }
