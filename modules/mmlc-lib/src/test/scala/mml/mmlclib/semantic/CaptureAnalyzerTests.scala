package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.extractors.*

class CaptureAnalyzerTests extends BaseEffFunSuite:

  private def bindingId(module: Module, name: String): String =
    def fromExpr(expr: Expr): Option[String] =
      expr.terms.iterator.map(fromTerm).collectFirst { case Some(id) => id }

    def fromTerm(term: Term): Option[String] =
      term match
        case TXScopedBinding(bindingLambda, boundValue) =>
          bindingLambda.params
            .find(_.name == name)
            .flatMap(_.id)
            .orElse(fromExpr(bindingLambda.body))
            .orElse(fromTerm(boundValue))
        case lambda: Lambda =>
          fromExpr(lambda.body)
        case App(_, fn, arg, _, _) =>
          fromTerm(fn).orElse(fromExpr(arg))
        case Cond(_, cond, ifTrue, ifFalse, _, _) =>
          fromExpr(cond).orElse(fromExpr(ifTrue)).orElse(fromExpr(ifFalse))
        case TermGroup(_, inner, _) =>
          fromExpr(inner)
        case Tuple(_, elements, _, _) =>
          elements.toList.iterator.map(fromExpr).collectFirst { case Some(id) => id }
        case ref: Ref =>
          ref.qualifier.flatMap(fromTerm)
        case _ =>
          None

    module.members
      .collectFirst {
        case b: Bnd if b.name == name => b.id
      }
      .flatten
      .orElse(
        module.members.iterator
          .map {
            case b: Bnd => fromExpr(b.value)
            case _ => None
          }
          .collectFirst { case Some(id) => id }
      )
      .getOrElse(fail(s"Expected binding id for '$name'"))

  private def fnParamId(module: Module, fnName: String, paramName: String): String =
    module.members
      .collectFirst {
        case b: Bnd if b.name == fnName =>
          b.value match
            case TXExpr1(lambda: Lambda) =>
              lambda.params.find(_.name == paramName).flatMap(_.id)
            case _ =>
              None
      }
      .flatten
      .getOrElse(fail(s"Expected param id for '$fnName.$paramName'"))

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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(captureResolvedIds(lambda), Set(bindingId(module, "a")))
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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assert(!captureResolvedIds(lambda).contains(bindingId(module, "g")))
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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(captureResolvedIds(lambda), Set.empty[String])
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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(
        captureResolvedIds(lambda),
        Set(bindingId(module, "a"), bindingId(module, "b"))
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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(captureResolvedIds(lambda), Set(fnParamId(module, "foo", "a")))
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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(captureResolvedIds(lambda), Set(bindingId(module, "a")))
    }
  }

  test("inner fn captures from enclosing let through the same lambda path") {
    val code =
      """
        fn foo(dummy: Int): Int =
          let a = 1;
          fn addA(x: Int): Int = x + a;;
          addA dummy;
        ;
      """
    semNotFailed(code).map { module =>
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one inner-fn lambda"))
      assertEquals(captureResolvedIds(lambda), Set(bindingId(module, "a")))
    }
  }

  test("nested lambda propagates captures outward") {
    val code =
      """
        fn foo(a: Int): Int -> Int =
          ~{ x: Int -> x + a; };
        ;
      """
    semNotFailed(code).map { module =>
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(captureResolvedIds(lambda), Set(fnParamId(module, "foo", "a")))
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
      val lambda = onlyUserLambda(module).getOrElse(fail("Expected one lambda literal"))
      assertEquals(captureResolvedIds(lambda), Set.empty[String])
    }
  }
