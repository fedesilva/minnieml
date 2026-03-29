package mml.mmlclib.test

import mml.mmlclib.ast.*

import scala.annotation.tailrec

/** Custom extractors for simplifying AST pattern matching in tests. */
object TestExtractors:

  object TXExpr1:
    def unapply(expr: Expr): Option[Term] =
      expr.terms match
        case List(term) => Some(term)
        case _ => None

  object TXBndLambda:
    def unapply(member: Member): Option[Lambda] =
      member match
        case bnd: Bnd =>
          bnd.value match
            case TXExpr1(lambda: Lambda) => Some(lambda)
            case _ => None
        case _ => None

  object TXScopedBinding:
    def unapply(term: Term): Option[(Lambda, Term)] =
      term match
        case App(_, bindingLambda: Lambda, TXExpr1(boundValue), _, _) =>
          Some((bindingLambda, boundValue))
        case _ =>
          None

  object TXUnwrapped:
    @tailrec
    def unapply(term: Term): Option[Term] =
      term match
        case TermGroup(_, TXExpr1(inner), _) => unapply(inner)
        case _ => Some(term)

  object TXCall1:
    def unapply(term: Term): Option[(Term, Term)] =
      term match
        case TXUnwrapped(App(_, fn, TXExpr1(arg), _, _)) => Some((fn, arg))
        case _ => None

  object TXRefNamed:
    def unapply(term: Term): Option[String] =
      term match
        case TXUnwrapped(ref: Ref) => Some(ref.name)
        case _ => None

  object TXRefResolved:
    def unapply(term: Term): Option[String] =
      term match
        case TXUnwrapped(ref: Ref) => ref.resolvedId
        case _ => None

  def collectUserLambdas(module: Module): List[Lambda] =
    module.members.flatMap(collectUserLambdas)

  def collectUserLambdas(member: Member): List[Lambda] =
    member match
      case bnd: Bnd if bnd.meta.isDefined =>
        bnd.value match
          case TXExpr1(lambda: Lambda) => collectUserLambdas(lambda.body)
          case expr: Expr => collectUserLambdas(expr)
      case bnd: Bnd =>
        collectUserLambdas(bnd.value)
      case _ =>
        Nil

  def collectUserLambdas(expr: Expr): List[Lambda] =
    expr.terms.flatMap(collectUserLambdas)

  def collectUserLambdas(term: Term): List[Lambda] =
    term match
      case lambda: Lambda =>
        lambda :: collectUserLambdas(lambda.body)
      case TXScopedBinding(bindingLambda, boundValue) =>
        collectUserLambdas(bindingLambda.body) ++ collectUserLambdas(boundValue)
      case App(_, fn, arg, _, _) =>
        collectUserLambdas(fn) ++ collectUserLambdas(arg)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        collectUserLambdas(cond) ++
          collectUserLambdas(ifTrue) ++
          collectUserLambdas(ifFalse)
      case group: TermGroup =>
        collectUserLambdas(group.inner)
      case tuple: Tuple =>
        tuple.elements.toList.flatMap(collectUserLambdas)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectUserLambdas)
      case expr: Expr =>
        collectUserLambdas(expr)
      case _ =>
        Nil

  def onlyUserLambda(module: Module): Option[Lambda] =
    collectUserLambdas(module) match
      case List(lambda) => Some(lambda)
      case _ => None

  def captureResolvedIds(lambda: Lambda): Set[String] =
    lambda.captures.flatMap(_.ref.resolvedId).toSet

  /** Extractor for function applications (`App` nodes).
    *
    * Recursively unwraps nested `App` structures (like `App(_, App(_, fn, arg1), arg2)`) into a
    * tuple containing:
    *   1. The base `Ref` node being applied.
    *   2. An `Option[Bnd]` if the `Ref`'s `resolvedAs` field contains a `Bnd` (function/operator).
    *   3. A flat `List[Expr]` of the arguments applied to the function.
    *
    * Returns `None` if the base term being applied is not a `Ref` or if the input `term` is not an
    * `App` node.
    *
    * Example: `App(_, App(_, Ref(_, "f", ...), arg1), arg2)` becomes
    * `Some((Ref(_, "f", ...), resolvedBndOpt, List(arg1, arg2)))`
    */
  object TXApp:
    def unapply(term: Term): Option[(Ref, Option[Bnd], List[Expr])] =
      // Helper to recursively collect args and find the base term
      @tailrec
      def collect(currentTerm: Term, accumulatedArgs: List[Expr]): Option[(Term, List[Expr])] =
        currentTerm match
          case App(_, fn, arg, _, _) =>
            collect(fn, arg :: accumulatedArgs) // Prepend arg, recurse on fn
          case baseTerm =>
            Some(
              (baseTerm, accumulatedArgs)
            ) // Base case: return the non-App term and collected args

      term match
        // Start collection only if the input is an App
        case app: App =>
          collect(app, Nil)
            .flatMap { // flatMap to handle potential None from collect (though unlikely with current @tailrec impl)
              case (ref: Ref, args) => // Check if the base term is a Ref
                // Note: bndOpt is now always None - tests should resolve via module.resolvables if needed
                Some((ref, None, args))
              case _ => None // Base term was not a Ref, pattern fails
            }
        case _ => None // Input term was not an App node
