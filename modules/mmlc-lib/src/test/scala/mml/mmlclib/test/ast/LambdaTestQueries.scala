package mml.mmlclib.test.ast

import mml.mmlclib.ast.*

/** Collects lambdas written by the user.
  *
  * This skips the wrapper lambda stored in a top-level `fn` binding and keeps lambdas from the
  * function body.
  */
def collectUserLambdas(module: Module): List[Lambda] =
  module.members.flatMap(collectUserLambdas)

/** Collects user-written lambdas inside one module member. */
def collectUserLambdas(member: Member): List[Lambda] =
  member match
    case bnd: Bnd if bnd.meta.isDefined =>
      bnd.value match
        case TXExprLambda(lambda) => collectUserLambdas(lambda.body)
        case expr: Expr => collectUserLambdas(expr)
    case bnd: Bnd =>
      collectUserLambdas(bnd.value)
    case _ =>
      Nil

/** Collects user-written lambdas inside an expression. */
def collectUserLambdas(expr: Expr): List[Lambda] =
  expr.terms.flatMap(collectUserLambdas)

/** Collects user-written lambdas inside a term.
  *
  * `let x = value; body` is stored as `App(bindingLambda, value)`. For that case this follows the
  * binding body and the bound value, but does not count the binding wrapper as a user lambda.
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

/** Returns the only user-written lambda in the module, or `None` when there is not exactly one. */
def onlyUserLambda(module: Module): Option[Lambda] =
  collectUserLambdas(module) match
    case List(lambda) => Some(lambda)
    case _ => None

/** Returns the resolved ids captured by a lambda after capture analysis. */
def captureResolvedIds(lambda: Lambda): Set[String] =
  lambda.captures.flatMap(_.ref.resolvedId).toSet
