package mml.mmlclib.test.ast

import mml.mmlclib.ast.*

/** Returns true when any term in this tree matches the predicate.
  *
  * Example:
  * ```scala
  * existsTerm(body) { case TXRefNamed("x") => true }
  * ```
  */
def existsTerm(term: Term)(predicate: PartialFunction[Term, Boolean]): Boolean =
  predicate.applyOrElse(term, (_: Term) => false) || (term match
    case App(_, fn, arg, _, _) => existsTerm(fn)(predicate) || existsExpr(arg)(predicate)
    case Expr(_, terms, _, _) => terms.exists(existsTerm(_)(predicate))
    case lambda: Lambda => existsExpr(lambda.body)(predicate)
    case TermGroup(_, inner, _) => existsExpr(inner)(predicate)
    case Cond(_, cond, ifTrue, ifFalse, _, _) =>
      existsExpr(cond)(predicate) ||
      existsExpr(ifTrue)(predicate) ||
      existsExpr(ifFalse)(predicate)
    case Tuple(_, elements, _, _) => elements.exists(existsExpr(_)(predicate))
    case d: Destruction => existsExpr(d.operand)(predicate)
    case _ => false)

/** Returns true when any term inside this expression matches the predicate. */
def existsExpr(expr: Expr)(predicate: PartialFunction[Term, Boolean]): Boolean =
  expr.terms.exists(existsTerm(_)(predicate))

/** Adds up the predicate result for every term in this tree.
  *
  * Most tests return `1` for a match and `0` by leaving other terms unmatched.
  * ```scala
  * countTerms(body) { case TXRefNamed("x") => 1 }
  * ```
  */
def countTerms(term: Term)(predicate: PartialFunction[Term, Int]): Int =
  val matched = predicate.applyOrElse(term, (_: Term) => 0)
  val childCount = term match
    case App(_, fn, arg, _, _) => countTerms(fn)(predicate) + countExprTerms(arg)(predicate)
    case Expr(_, terms, _, _) => terms.map(countTerms(_)(predicate)).sum
    case lambda: Lambda => countExprTerms(lambda.body)(predicate)
    case TermGroup(_, inner, _) => countExprTerms(inner)(predicate)
    case Cond(_, cond, ifTrue, ifFalse, _, _) =>
      countExprTerms(cond)(predicate) +
        countExprTerms(ifTrue)(predicate) +
        countExprTerms(ifFalse)(predicate)
    case Tuple(_, elements, _, _) => elements.toList.map(countTerms(_)(predicate)).sum
    case d: Destruction => countExprTerms(d.operand)(predicate)
    case _ => 0
  matched + childCount

/** Adds up the predicate result for every term inside this expression. */
def countExprTerms(expr: Expr)(predicate: PartialFunction[Term, Int]): Int =
  expr.terms.map(countTerms(_)(predicate)).sum
