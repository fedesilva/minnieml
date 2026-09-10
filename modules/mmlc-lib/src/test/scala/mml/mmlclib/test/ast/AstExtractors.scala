package mml.mmlclib.test.ast

import mml.mmlclib.ast.*

import scala.annotation.tailrec

/** Pattern-match helpers for AST tests.
  *
  * `TX` means "test extractor". These helpers make test assertions shorter without adding new AST
  * nodes or compiler behavior.
  */
object TXExpr1:
  /** Matches an expression with exactly one term and returns that term.
    *
    * Example:
    * ```scala
    * Expr(terms = List(term)) match
    *   case TXExpr1(found) => found == term
    * ```
    */
  def unapply(expr: Expr): Option[Term] =
    expr.terms match
      case List(term) => Some(term)
      case _ => None

object TXExprApp:
  /** Matches a one-term expression whose term is a flattened call.
    *
    * Example:
    * ```scala
    * Expr(terms = List(App(App(sum, one), two))) match
    *   case TXExprApp(fn, _, args) => fn == sum && args == List(one, two)
    * ```
    */
  def unapply(expr: Expr): Option[(Ref, Option[Bnd], List[Expr])] =
    expr match
      case TXExpr1(TXApp(ref, binding, args)) => Some((ref, binding, args))
      case _ => None

object TXExprLambda:
  /** Matches a one-term expression whose term is a lambda.
    *
    * Example:
    * ```scala
    * Expr(terms = List(lambda)) match
    *   case TXExprLambda(found) => found == lambda
    * ```
    */
  def unapply(expr: Expr): Option[Lambda] =
    expr match
      case TXExpr1(lambda: Lambda) => Some(lambda)
      case _ => None

object TXExprRefNamed:
  /** Matches a one-term expression whose term is a reference and returns the source name.
    *
    * Example:
    * ```scala
    * Expr(terms = List(Ref(name = "total"))) match
    *   case TXExprRefNamed(name) => name == "total"
    * ```
    */
  def unapply(expr: Expr): Option[String] =
    expr match
      case TXExpr1(TXRefNamed(name)) => Some(name)
      case _ => None

object TXExprInt:
  /** Matches a one-term expression whose term is an integer literal.
    *
    * Example:
    * ```scala
    * Expr(terms = List(LiteralInt(value = 42))) match
    *   case TXExprInt(value) => value == 42
    * ```
    */
  def unapply(expr: Expr): Option[Int] =
    expr match
      case TXExpr1(LiteralInt(_, value)) => Some(value)
      case _ => None

object TXBndLambda:
  /** Matches a binding whose right-hand side is a lambda.
    *
    * Example:
    * ```scala
    * Bnd(value = Expr(terms = List(lambda))) match
    *   case TXBndLambda(found) => found == lambda
    * ```
    */
  def unapply(member: Member): Option[Lambda] =
    member match
      case bnd: Bnd =>
        bnd.value match
          case TXExprLambda(lambda) => Some(lambda)
          case _ => None
      case _ => None

object TXScopedBinding:
  /** Matches the AST form used for expression-level `let`.
    *
    * Parser-lowered `let x = value; body` is stored as a lambda call:
    * ```scala
    * App(bindingLambda, value)
    * ```
    *
    * This returns `(bindingLambda, value)`.
    */
  def unapply(term: Term): Option[(Lambda, Term)] =
    term match
      case App(_, bindingLambda: Lambda, TXExpr1(boundValue), _, _) =>
        Some((bindingLambda, boundValue))
      case _ =>
        None

object TXUnwrapped:
  /** Removes one-term `TermGroup` wrappers and returns the inner term.
    *
    * Example:
    * ```scala
    * TermGroup(Expr(terms = List(TermGroup(Expr(terms = List(term)))))) match
    *   case TXUnwrapped(found) => found == term
    * ```
    */
  @tailrec
  def unapply(term: Term): Option[Term] =
    term match
      case TermGroup(_, TXExpr1(inner), _) => unapply(inner)
      case _ => Some(term)

object TXCall1:
  /** Extracts one layer of a function call as `(function, argument)`.
    *
    * `inc 1` is one call layer:
    * ```scala
    * TXCall1(inc, one)
    * ```
    *
    * `sum 1 2` is nested:
    * ```scala
    * TXCall1(TXCall1(sum, one), two)
    * ```
    *
    * Use `TXApp` when the test wants the whole flattened call: `(sum, List(one, two))`.
    */
  def unapply(term: Term): Option[(Term, Term)] =
    term match
      case TXUnwrapped(App(_, fn, TXExpr1(arg), _, _)) => Some((fn, arg))
      case _ => None

object TXRefNamed:
  /** Matches a reference term and returns the source name, ignoring surrounding groups. */
  def unapply(term: Term): Option[String] =
    term match
      case TXUnwrapped(ref: Ref) => Some(ref.name)
      case _ => None

object TXRefResolved:
  /** Matches a reference term and returns its resolved id, ignoring surrounding groups. */
  def unapply(term: Term): Option[String] =
    term match
      case TXUnwrapped(ref: Ref) => ref.resolvedId
      case _ => None

/** Extracts a nested function call as `(function, ignoredBinding, arguments)`.
  *
  * Example:
  * ```scala
  * App(App(sum, one), two) match
  *   case TXApp(fn, _, args) => fn == sum && args == List(one, two)
  * ```
  *
  * Use this when the test cares about the full call. Use `TXCall1` when it only needs one call
  * layer.
  */
object TXApp:
  def unapply(term: Term): Option[(Ref, Option[Bnd], List[Expr])] =
    @tailrec
    def collect(currentTerm: Term, accumulatedArgs: List[Expr]): Option[(Term, List[Expr])] =
      currentTerm match
        case App(_, fn, arg, _, _) =>
          // Walk outward through nested App nodes so tests can assert one function plus all args.
          collect(fn, arg :: accumulatedArgs)
        case baseTerm =>
          Some((baseTerm, accumulatedArgs))

    term match
      case app: App =>
        collect(app, Nil).flatMap {
          case (ref: Ref, args) =>
            // Scoped-binding apps use a lambda callee, so only real reference callees match here.
            Some((ref, None, args))
          case _ =>
            None
        }
      case _ =>
        None
