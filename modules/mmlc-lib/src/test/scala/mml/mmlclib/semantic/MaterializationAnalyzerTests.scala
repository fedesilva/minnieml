package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*

class MaterializationAnalyzerTests extends BaseEffFunSuite:

  // ---- helpers ------------------------------------------------------------

  /** Find the top-level binding by name and return its lambda value. */
  private def topLambda(module: Module, name: String): Lambda =
    module.members
      .collectFirst {
        case b: Bnd if b.name == name =>
          b.value match
            case TXExprLambda(lambda) => lambda
            case _ => fail(s"Binding '$name' does not hold a lambda")
      }
      .getOrElse(fail(s"No binding named '$name'"))

  /** Walk a function's body to find a let-bound lambda by binder name. */
  private def letBoundLambda(module: Module, fnName: String, binder: String): Lambda =
    def search(term: Term): Option[Lambda] = term match
      case TXScopedBinding(bindingLambda, boundValue) =>
        if bindingLambda.params.exists(_.name == binder) then
          boundValue match
            case lambda: Lambda => Some(lambda)
            case _ => searchExpr(bindingLambda.body)
        else searchExpr(bindingLambda.body).orElse(search(boundValue))
      case lambda: Lambda => searchExpr(lambda.body)
      case App(_, fn, arg, _, _) => search(fn).orElse(searchExpr(arg))
      case Cond(_, c, t, f, _, _) => searchExpr(c).orElse(searchExpr(t)).orElse(searchExpr(f))
      case TermGroup(_, inner, _) => searchExpr(inner)
      case _ => None

    def searchExpr(expr: Expr): Option[Lambda] =
      expr.terms.iterator.map(search).collectFirst { case Some(l) => l }

    val outer = topLambda(module, fnName)
    searchExpr(outer.body).getOrElse(fail(s"No let-bound lambda '$binder' in fn '$fnName'"))

  private def isDirect(lambda: Lambda): Boolean =
    // Pending compiler API; the source assertion is preserved below.
    /*
    lambda.meta.exists(_.isDirect)
     */
    fail("LambdaMeta.isDirect is absent from the parent AST.")

  // Pending direct-entry lowering.
  // See context/tasks/unify-lambdas-ignored-tests.md, Materialization repair plans.
  test("let-bound lambda used only directly is direct".ignore) {
    val code =
      """
        fn main(dummy: Int): Int =
          let id = { x: Int -> x };
          id dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val id = letBoundLambda(module, "main", "id")
      assert(isDirect(id), "let-bound id is only invoked")
    }
  }

  // Pending PAP target lowering.
  // See context/tasks/unify-lambdas-ignored-tests.md, Materialization repair plans.
  test("let-bound lambda used through partial application remains direct".ignore) {
    val code =
      """
        fn main(dummy: Int): Int =
          let add: Int -> Int -> Int = { x: Int, y: Int -> x + y };
          let addDummy: Int -> Int = add dummy;
          addDummy 1;
        ;
      """
    semNotFailed(code).map { module =>
      val add = letBoundLambda(module, "main", "add")
      assert(isDirect(add), "partial application builds a derived closure, not a value of add")
    }
  }

  // Pending nullary immediate-application rejection; see unify-lambdas-ignored-tests.md.
  test("nullary lambda literal in immediate application is direct".ignore) {
    val code =
      """
        fn main(): Int = ({ 42; } ());;
      """
    semNotFailed(code).map { module =>
      val main = topLambda(module, "main")
      def findLit(term: Term): Option[Lambda] = term match
        case app: App =>
          app.fn match
            case l: Lambda => Some(l)
            case _ => None
        case group: TermGroup =>
          group.inner.terms.iterator.map(findLit).collectFirst { case Some(l) => l }
        case _ => None
      val lit = main.body.terms.iterator
        .map(findLit)
        .collectFirst { case Some(l) => l }
        .getOrElse(fail("expected immediate-application body"))
      assert(isDirect(lit), "immediately-applied nullary lambda is direct")
    }
  }

  // Pending function-value lowering.
  // See context/tasks/unify-lambdas-ignored-tests.md, Materialization repair plans.
  test("nullary top-level fn used as value is not direct".ignore) {
    val code =
      """
        fn nada(): Int = 42;;
        let a = nada;
      """
    semNotFailed(code).map { module =>
      assert(!isDirect(topLambda(module, "nada")), "bare nada ref is a value-position use")
    }
  }
