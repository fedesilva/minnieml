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
    lambda.meta.exists(_.isDirect)

  // ---- top-level functions -----------------------------------------------

  test("top-level fn called directly is direct") {
    val code =
      """
        fn id(x: Int): Int = x;;
        fn main(): Int = id 1;;
      """
    semNotFailed(code).map { module =>
      assert(isDirect(topLambda(module, "id")), "id should be direct")
    }
  }

  // Note on top-level fn "aliasing": ExpressionRewriter eta-expands bare callable refs
  // in argument position. `let f = id` becomes `let f = { $p0 -> id $p0 }`. When `f` is
  // only used in saturated calls, the analyzer correctly marks every lambda direct —
  // there is no observable closure to materialize. The remaining non-direct cases
  // appear when a *user-written lambda literal* is bound and the binder is then used as
  // a value (covered by "let-bound lambda used as value" below).

  test("recursive self-call keeps fn direct") {
    val code =
      """
        fn fact(n: Int): Int =
          if n <= 1 then 1;
          else n * (fact (n - 1));
          ;
        ;
      """
    semNotFailed(code).map { module =>
      assert(isDirect(topLambda(module, "fact")), "fact's only use is a direct self-call")
    }
  }

  // ---- let-bound lambdas --------------------------------------------------

  test("let-bound lambda used only directly is direct") {
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

  test("let-bound lambda used as value is not direct") {
    val code =
      """
        fn apply(g: Int -> Int, n: Int): Int = g n;;
        fn main(dummy: Int): Int =
          let id = { x: Int -> x };
          apply id dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val id = letBoundLambda(module, "main", "id")
      assert(!isDirect(id), "let-bound id passed as a value")
    }
  }

  test("let-bound lambda used through partial application remains direct") {
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

  // ---- lambda literals ----------------------------------------------------

  test("lambda literal in immediate application is direct") {
    val code =
      """
        fn main(): Int = { x: Int -> x } 5;;
      """
    semNotFailed(code).map { module =>
      val main = topLambda(module, "main")
      val lit = main.body.terms
        .collectFirst { case app: App =>
          app.fn match
            case l: Lambda => l
            case _ => fail("expected lambda head application")
        }
        .getOrElse(fail("expected immediate-application body"))
      assert(isDirect(lit), "immediate-application lambda is direct")
    }
  }

  // ---- nullary (arity-0) lambdas -----------------------------------------
  //
  // Saturation check is `depth >= max(arity, 1)`. The `max(_, 1)` floor keeps arity-0
  // lambdas sound: invocation is `App(ref, unit-literal)` so a called thunk's ref
  // sits at depth 1, while a value-position thunk ref sits at depth 0.

  test("nullary lambda literal in immediate application is direct") {
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

  test("let-bound nullary lambda used as value is not direct") {
    val code =
      """
        fn force(g: Unit -> Int): Int = g ();;
        fn main(): Int =
          let t = { 42; };
          force t;
        ;
      """
    semNotFailed(code).map { module =>
      val t = letBoundLambda(module, "main", "t")
      assert(!isDirect(t), "nullary t passed as a value must not be direct")
    }
  }

  test("nullary top-level fn invoked is direct") {
    val code =
      """
        fn nada(): Int = 42;;
        let a = nada ();
      """
    semNotFailed(code).map { module =>
      assert(isDirect(topLambda(module, "nada")), "nada is only ever called")
    }
  }

  test("nullary top-level fn used as value is not direct") {
    val code =
      """
        fn nada(): Int = 42;;
        let a = nada;
      """
    semNotFailed(code).map { module =>
      assert(!isDirect(topLambda(module, "nada")), "bare nada ref is a value-position use")
    }
  }
