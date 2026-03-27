package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class CaptureAnalyzerTests extends BaseEffFunSuite:

  /** Collect all real lambda literals from a module (not let-desugaring lambdas in App.fn
    * position).
    */
  private def collectAllLambdas(module: Module): List[Lambda] =
    module.members.flatMap {
      case bnd: Bnd =>
        bnd.value.terms match
          case (lambda: Lambda) :: _ =>
            collectLambdasInExpr(lambda.body)
          case _ => Nil
      case _ => Nil
    }

  private def collectLambdasInExpr(expr: Expr): List[Lambda] =
    expr.terms.flatMap {
      case lambda: Lambda =>
        lambda :: collectLambdasInExpr(lambda.body)
      case app: App =>
        app.fn match
          case fnLambda: Lambda =>
            collectLambdasInExpr(app.arg) ++
              collectLambdasInExpr(fnLambda.body)
          case _ =>
            collectLambdasInAppFn(app.fn) ++
              collectLambdasInExpr(app.arg)
      case cond: Cond =>
        collectLambdasInExpr(cond.cond) ++
          collectLambdasInExpr(cond.ifTrue) ++
          collectLambdasInExpr(cond.ifFalse)
      case e:     Expr => collectLambdasInExpr(e)
      case group: TermGroup => collectLambdasInExpr(group.inner)
      case _ => Nil
    }

  private def collectLambdasInAppFn(
    fn: Ref | App | Lambda
  ): List[Lambda] =
    fn match
      case _:   Ref => Nil
      case app: App =>
        collectLambdasInAppFn(app.fn) ++
          collectLambdasInExpr(app.arg)
      case lambda: Lambda =>
        lambda :: collectLambdasInExpr(lambda.body)

  test("simple capture of a let-binding") {
    val code =
      """
        fn foo(dummy: Int): Int =
          let a = 1;
          let l = { x: Int -> x + a; };
          l dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val userLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(userLambda.isDefined, "Expected a lambda literal")
      val capNames = userLambda.get.captures.map(_.name)
      assert(
        capNames.contains("a"),
        s"Expected capture of 'a', got $capNames"
      )
    }
  }

  test("no capture of module-level bindings") {
    val code =
      """
        let g = 42;
        fn foo(dummy: Int): Int =
          let l = { x: Int -> x + g; };
          l dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val userLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(userLambda.isDefined, "Expected a lambda literal")
      val capNames = userLambda.get.captures.map(_.name)
      assert(
        !capNames.contains("g"),
        s"Should NOT capture module-level 'g', got $capNames"
      )
    }
  }

  test("no false capture of lambda own params") {
    val code =
      """
        fn foo(dummy: Int): Int =
          let l = { x: Int -> x + 1; };
          l dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val userLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(userLambda.isDefined, "Expected a lambda literal")
      assert(
        userLambda.get.captures.isEmpty,
        s"Expected no captures, got ${userLambda.get.captures.map(_.name)}"
      )
    }
  }

  test("capture multiple let-bindings") {
    val code =
      """
        fn foo(dummy: Int): Int =
          let a = 1;
          let b = 2;
          let l = { x: Int -> x + a + b; };
          l dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val userLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(userLambda.isDefined, "Expected a lambda literal")
      val capNames = userLambda.get.captures.map(_.name).toSet
      assert(
        capNames.contains("a") && capNames.contains("b"),
        s"Expected captures of 'a' and 'b', got $capNames"
      )
    }
  }

  test("capture function param") {
    val code =
      """
        fn foo(a: Int, dummy: Int): Int =
          let l = { x: Int -> x + a; };
          l dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val userLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(userLambda.isDefined, "Expected a lambda literal")
      val capNames = userLambda.get.captures.map(_.name)
      assert(
        capNames.contains("a"),
        s"Expected capture of 'a', got $capNames"
      )
    }
  }

  test("let-bound lambda captures from enclosing let") {
    val code =
      """
        fn foo(dummy: Int): Int =
          let a = 1;
          let l = { x: Int -> x + a; };
          l dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val userLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(userLambda.isDefined, "Expected a let-bound lambda")
      val capNames = userLambda.get.captures.map(_.name)
      assert(
        capNames.contains("a"),
        s"Expected capture of 'a', got $capNames"
      )
    }
  }

  test("nested lambda propagates captures outward") {
    val code =
      """
        fn foo(a: Int): Int -> Int =
          { x: Int -> x + a; };
        ;
      """
    semNotFailed(code).map { module =>
      val lambdas = collectAllLambdas(module)
      val outerLambda =
        lambdas.find(_.params.exists(_.name == "x"))
      assert(outerLambda.isDefined, "Expected outer lambda")
      val outerCaps = outerLambda.get.captures.map(_.name)
      assert(
        outerCaps.contains("a"),
        s"Lambda should capture 'a', got $outerCaps"
      )
    }
  }

  test("non-capturing lambda has empty captures") {
    val code =
      """
        fn apply(f: Int -> Int, x: Int): Int = f x;;
        pub fn main(): Unit =
          let result = apply { x: Int -> x + 1; } 41;
          println (int_to_str result);
        ;
      """
    semNotFailed(code).map { module =>
      val allLambdas = collectAllLambdas(module)
      val userLambdas =
        allLambdas.filter(_.params.exists(_.name == "x"))
      userLambdas.foreach { l =>
        assert(
          l.captures.isEmpty,
          s"Expected no captures, got ${l.captures.map(_.name)}"
        )
      }
    }
  }
