package mml.mmlclib.test.extractors

import mml.mmlclib.ast.*

import scala.annotation.tailrec

/** Shared AST-shape extractors used across parser, semantic, and ownership tests.
  *
  * The `TX*` prefix makes it obvious that these helpers are test-only shape extractors rather than
  * production AST nodes or semantic utilities.
  */
object TXExpr1:
  /** Extracts the single term stored in an expression node.
    *
    * Example syntax:
    * ```mml
    * 1
    * name
    * sum 1 2
    * ```
    *
    * Tests use this when the parser or rewriter should collapse a construct to exactly one
    * top-level term.
    */
  def unapply(expr: Expr): Option[Term] =
    expr.terms match
      case List(term) => Some(term)
      case _ => None

object TXExprApp:
  /** Extracts an expression that should contain a rewritten application term.
    *
    * Example syntax:
    * ```mml
    * sum 1 2
    * a + b
    * ```
    */
  def unapply(expr: Expr): Option[(Ref, Option[Bnd], List[Expr])] =
    expr match
      case TXExpr1(TXApp(ref, binding, args)) => Some((ref, binding, args))
      case _ => None

object TXExprLambda:
  /** Extracts an expression that consists of a single lambda literal.
    *
    * Example syntax:
    * ```mml
    * (x) => x + 1
    * () => 1
    * ```
    */
  def unapply(expr: Expr): Option[Lambda] =
    expr match
      case TXExpr1(lambda: Lambda) => Some(lambda)
      case _ => None

object TXExprRefNamed:
  /** Extracts the unresolved display name from a single-term reference expression.
    *
    * Example syntax:
    * ```mml
    * total
    * math.sum
    * ```
    */
  def unapply(expr: Expr): Option[String] =
    expr match
      case TXExpr1(TXRefNamed(name)) => Some(name)
      case _ => None

object TXExprInt:
  /** Extracts the integer value from a single integer-literal expression.
    *
    * Example syntax:
    * ```mml
    * 1
    * 42
    * ```
    */
  def unapply(expr: Expr): Option[Int] =
    expr match
      case TXExpr1(LiteralInt(_, value)) => Some(value)
      case _ => None

object TXBndLambda:
  /** Extracts the lambda stored on the right-hand side of a binding or function member.
    *
    * Example syntax:
    * ```mml
    * fn inc(x) = x + 1;;
    * let add1 = (x) => x + 1;
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
  /** Extracts the synthetic scoped-binding form produced by expression-level `let`.
    *
    * Example syntax:
    * ```mml
    * let answer =
    *   let x = 1;
    *   x + 2;
    * ```
    *
    * After rewriting, the body above becomes an application of a synthetic lambda to the bound
    * value. This extractor exposes both pieces directly.
    */
  def unapply(term: Term): Option[(Lambda, Term)] =
    term match
      case App(_, bindingLambda: Lambda, TXExpr1(boundValue), _, _) =>
        Some((bindingLambda, boundValue))
      case _ =>
        None

object TXUnwrapped:
  /** Removes redundant grouping wrappers before matching the inner term.
    *
    * Example syntax:
    * ```mml
    * (1)
    * ((sum 1 2))
    * ```
    */
  @tailrec
  def unapply(term: Term): Option[Term] =
    term match
      case TermGroup(_, TXExpr1(inner), _) => unapply(inner)
      case _ => Some(term)

object TXCall1:
  /** Extracts a single application edge as `(callee, arg)`.
    *
    * Example syntax:
    * ```mml
    * inc 1
    * negate value
    * ```
    *
    * Prefer this over `TXApp` when the test only cares about one application step.
    */
  def unapply(term: Term): Option[(Term, Term)] =
    term match
      case TXUnwrapped(App(_, fn, TXExpr1(arg), _, _)) => Some((fn, arg))
      case _ => None

object TXRefNamed:
  /** Extracts the display name from a reference term, ignoring surrounding groups. */
  def unapply(term: Term): Option[String] =
    term match
      case TXUnwrapped(ref: Ref) => Some(ref.name)
      case _ => None

object TXRefResolved:
  /** Extracts the resolved symbol id from a reference term when name resolution has run. */
  def unapply(term: Term): Option[String] =
    term match
      case TXUnwrapped(ref: Ref) => ref.resolvedId
      case _ => None

/** Extracts a flattened call spine when a test wants to assert the callee and full arg list.
  *
  * Example syntax:
  * ```mml
  * sum 1 2
  * f (g x) y
  * a + b
  * ```
  *
  * This is intentionally reserved for tests that care about the whole application chain. Simpler
  * tests should prefer `TXCall1`, `TXExprRefNamed`, or direct literal extractors.
  */
object TXApp:
  def unapply(term: Term): Option[(Ref, Option[Bnd], List[Expr])] =
    @tailrec
    def collect(currentTerm: Term, accumulatedArgs: List[Expr]): Option[(Term, List[Expr])] =
      currentTerm match
        case App(_, fn, arg, _, _) =>
          // Walk outward through nested App nodes so assertions can treat curried calls and
          // rewritten operators as one flat call spine.
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
