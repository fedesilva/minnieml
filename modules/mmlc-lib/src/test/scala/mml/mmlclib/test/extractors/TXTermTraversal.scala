package mml.mmlclib.test.extractors

import mml.mmlclib.ast.*

/** Returns true when any term in the tree satisfies the partial predicate.
  *
  * This is shared test infrastructure for ownership and semantic suites that need a lightweight AST
  * walk without re-implementing recursion in each file.
  */
def txExistsTerm(term: Term)(predicate: PartialFunction[Term, Boolean]): Boolean =
  predicate.applyOrElse(term, (_: Term) => false) || (term match
    // Application and expression nodes contain the bulk of the rewritten tree shapes.
    case App(_, fn, arg, _, _) => txExistsTerm(fn)(predicate) || txExistsExpr(arg)(predicate)
    case Expr(_, terms, _, _) => terms.exists(txExistsTerm(_)(predicate))
    case Lambda(_, _, body, _, _, _, _, _) => txExistsExpr(body)(predicate)
    case TermGroup(_, inner, _) => txExistsExpr(inner)(predicate)
    case Cond(_, cond, ifTrue, ifFalse, _, _) =>
      txExistsExpr(cond)(predicate) ||
      txExistsExpr(ifTrue)(predicate) ||
      txExistsExpr(ifFalse)(predicate)
    case Tuple(_, elements, _, _) => elements.exists(txExistsExpr(_)(predicate))
    case _ => false)

/** Expression-level wrapper for `txExistsTerm`. */
def txExistsExpr(expr: Expr)(predicate: PartialFunction[Term, Boolean]): Boolean =
  expr.terms.exists(txExistsTerm(_)(predicate))

/** Counts how many terms in the tree contribute to the partial predicate's integer total.
  *
  * Tests usually return `1` for matching nodes, but the partial predicate can encode weighted
  * counts when that keeps assertions simpler.
  */
def txCountTerm(term: Term)(predicate: PartialFunction[Term, Int]): Int =
  val matched = predicate.applyOrElse(term, (_: Term) => 0)
  // Accumulate the current match and then recurse into child terms using the same traversal shape
  // as `txExistsTerm` so existence and counting stay consistent across suites.
  val childCount = term match
    case App(_, fn, arg, _, _) => txCountTerm(fn)(predicate) + txCountExpr(arg)(predicate)
    case Expr(_, terms, _, _) => terms.map(txCountTerm(_)(predicate)).sum
    case Lambda(_, _, body, _, _, _, _, _) => txCountExpr(body)(predicate)
    case TermGroup(_, inner, _) => txCountExpr(inner)(predicate)
    case Cond(_, cond, ifTrue, ifFalse, _, _) =>
      txCountExpr(cond)(predicate) +
        txCountExpr(ifTrue)(predicate) +
        txCountExpr(ifFalse)(predicate)
    case Tuple(_, elements, _, _) => elements.toList.map(txCountTerm(_)(predicate)).sum
    case _ => 0
  matched + childCount

/** Expression-level wrapper for `txCountTerm`. */
def txCountExpr(expr: Expr)(predicate: PartialFunction[Term, Int]): Int =
  expr.terms.map(txCountTerm(_)(predicate)).sum
