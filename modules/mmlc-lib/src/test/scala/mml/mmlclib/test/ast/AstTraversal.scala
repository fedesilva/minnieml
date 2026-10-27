package mml.mmlclib.test.ast

import cats.syntax.all.*
import mml.mmlclib.ast.*

import scala.annotation.tailrec

/** Removes expression and parenthesis wrappers while preserving multi-term expressions. */
@tailrec
def unwrappedTerm(term: Term): Term = term match
  case expr:  Expr if expr.terms.size == 1 => unwrappedTerm(expr.terms.head)
  case group: TermGroup => unwrappedTerm(group.inner)
  case other => other

/** Finds a reference beneath expression and parenthesis wrappers. */
def termReference(term: Term): Option[Ref] = unwrappedTerm(term) match
  case ref: Ref => ref.some
  case _ => none[Ref]

/** Finds one application beneath expression and parenthesis wrappers. */
def termApplication(term: Term): Option[App] = unwrappedTerm(term) match
  case app: App => app.some
  case _ => none[App]

/** Finds a lambda beneath expression and parenthesis wrappers. */
def termLambda(term: Term): Option[Lambda] = unwrappedTerm(term) match
  case lambda: Lambda => lambda.some
  case _ => none[Lambda]

/** Follows nested applications to their reference callee, ignoring expression and parentheses. */
@tailrec
def callRootReference(term: Term): Option[Ref] = unwrappedTerm(term) match
  case app: App => callRootReference(app.fn)
  case other => termReference(other)

/** Collects matching terms from every binding in the module. */
def collectModuleTerms[A](module: Module)(query: PartialFunction[Term, A]): List[A] =
  module.members
    .collect { case binding: Bnd => binding }
    .flatMap(binding => TermTraversal.collect(binding.value)(query))

/** Returns true when any term in this tree matches the predicate.
  *
  * Example:
  * ```scala
  * existsTerm(body) { case TXRefNamed("x") => true }
  * ```
  */
def existsTerm(term: Term)(predicate: PartialFunction[Term, Boolean]): Boolean =
  predicate.applyOrElse(term, (_: Term) => false) ||
    TermTraversal.children(term).exists(existsTerm(_)(predicate))

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
    case app: App => countTerms(app.fn)(predicate) + countExprTerms(app.arg)(predicate)
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
