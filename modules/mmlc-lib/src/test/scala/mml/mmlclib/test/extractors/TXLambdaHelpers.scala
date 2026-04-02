package mml.mmlclib.test.extractors

import mml.mmlclib.ast.*

/** Collects user-authored lambdas from a module while skipping synthetic wrappers.
  *
  * Example syntax:
  * ```mml
  * let add1 = (x) => x + 1;
  *
  * fn outer(y) =
  *   let f = (x) => x + y;
  *   f 1;;
  * ```
  */
def collectUserLambdas(module: Module): List[Lambda] =
  module.members.flatMap(collectUserLambdas)

/** Collects lambdas that originate from user code within a member. */
def collectUserLambdas(member: Member): List[Lambda] =
  member match
    case bnd: Bnd if bnd.meta.isDefined =>
      // Function members wrap the user lambda in a binding node; descend into the lambda body so
      // tests do not count the synthetic wrapper twice.
      bnd.value match
        case TXExprLambda(lambda) => collectUserLambdas(lambda.body)
        case expr: Expr => collectUserLambdas(expr)
    case bnd: Bnd =>
      collectUserLambdas(bnd.value)
    case _ =>
      Nil

/** Collects lambdas reachable from an expression node. */
def collectUserLambdas(expr: Expr): List[Lambda] =
  expr.terms.flatMap(collectUserLambdas)

/** Collects lambdas reachable from a term node.
  *
  * Expression-level `let` is rewritten to a synthetic application, so `TXScopedBinding` lets the
  * traversal keep following the user-authored lambda body and the bound value directly.
  */
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

/** Returns the only user-authored lambda in the module when the test expects exactly one. */
def onlyUserLambda(module: Module): Option[Lambda] =
  collectUserLambdas(module) match
    case List(lambda) => Some(lambda)
    case _ => None

/** Extracts the resolved ids captured by a lambda after capture analysis. */
def captureResolvedIds(lambda: Lambda): Set[String] =
  lambda.captures.flatMap(_.ref.resolvedId).toSet
