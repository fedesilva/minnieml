package mml.mmlclib.ast

/** Shared structural queries include operands of compiler-generated effects. */
object TermTraversal:
  def children(term: Term): List[Term] = term match
    case e: Expr => e.terms
    case a: App => List(a.fn, a.arg)
    case l: Lambda => List(l.body)
    case c: Cond => List(c.cond, c.ifTrue, c.ifFalse)
    case g: TermGroup => List(g.inner)
    case t: Tuple => t.elements.toList
    case r: Ref => r.qualifier.toList
    case i: InvalidExpression => List(i.originalExpr)
    case d: Destruction => List(d.operand)
    case _ => Nil

  def collect[A](term: Term)(query: PartialFunction[Term, A]): List[A] =
    query.lift(term).toList ++ children(term).flatMap(collect(_)(query))
