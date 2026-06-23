package mml.mmlclib.semantic

import cats.syntax.traverse.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*

class LambdaEquivalenceSemanticTest extends BaseEffFunSuite:

  private def topLambda(module: Module, name: String): Lambda =
    topBinding(module, name).value match
      case TXExprLambda(lambda) => lambda
      case _ => fail(s"Binding '$name' does not hold a lambda")

  private def topBinding(module: Module, name: String): Bnd =
    module.members
      .collectFirst {
        case bnd: Bnd if bnd.name == name => bnd
      }
      .getOrElse(fail(s"No binding named '$name'"))

  private def member(module: Module, name: String): Member =
    module.members
      .find {
        case bnd: Bnd => bnd.name == name
        case _ => false
      }
      .getOrElse(fail(s"No member named '$name'"))

  private def userLambdasIn(module: Module, fnName: String): List[Lambda] =
    collectUserLambdas(member(module, fnName))

  private def onlyUserLambdaIn(module: Module, fnName: String): Lambda =
    userLambdasIn(module, fnName) match
      case List(lambda) => lambda
      case other => fail(s"Expected one user lambda in '$fnName', got ${other.length}")

  private def fnParamId(module: Module, fnName: String, paramName: String): String =
    topLambda(module, fnName).params
      .find(_.name == paramName)
      .flatMap(_.id)
      .getOrElse(fail(s"Expected param id for '$fnName.$paramName'"))

  private def typeShape(tpe: Type): String =
    tpe match
      case TypeRef(_, name, _, _) => name
      case TypeUnit(_) => "Unit"
      case TypeFn(_, params, ret) =>
        val paramShapes = params.toList.map(typeShape).mkString("(", ", ", ")")
        s"$paramShapes -> ${typeShape(ret)}"
      case NativePrimitive(_, llvmType, _, _) => llvmType
      case other => other.getClass.getSimpleName

  private def bindingTypeShape(bnd: Bnd): String =
    bnd.typeSpec.map(typeShape).getOrElse(fail(s"Expected typeSpec for '${bnd.name}'"))

  private def assertSameFunctionType(module: Module, names: List[String]): Unit =
    val types    = names.map(name => name -> bindingTypeShape(topBinding(module, name)))
    val distinct = types.map(_._2).distinct
    assertEquals(
      distinct.length,
      1,
      s"Expected equivalent function types, got: ${types.mkString(", ")}"
    )

  private def assertDirect(lambda: Lambda, clue: String): Unit =
    assertEquals(lambda.materialization, Materialization.Direct, clue)

  private def assertNullEnv(lambda: Lambda, clue: String): Unit =
    assertEquals(lambda.materialization, Materialization.NullEnv, clue)
    assertEquals(lambda.closureEnvAllocation, ClosureEnvAllocation.NoEnv, clue)

  test("direct scalar calls have equivalent semantic shape") {
    val code =
      """
        fn top_inc(x: Int): Int = x + 1;;

        fn direct_top(x: Int): Int =
          top_inc x;
        ;

        fn direct_local(x: Int): Int =
          fn local_inc(y: Int): Int =
            y + 1;
          ;

          local_inc x;
        ;

        fn direct_let_lambda(x: Int): Int =
          let local_inc = { y: Int -> y + 1; };
          local_inc x;
        ;

        fn direct_inline_lambda(x: Int): Int =
          { y: Int -> y + 1; } x;
        ;
      """

    semNotFailed(code).map { module =>
      val forms = List("direct_top", "direct_local", "direct_let_lambda", "direct_inline_lambda")
      assertSameFunctionType(module, forms)
      assertDirect(topLambda(module, "top_inc"), "top_inc should stay direct")
      forms
        .flatMap(userLambdasIn(module, _))
        .foreach(lambda => assertDirect(lambda, "direct local/lambda forms should stay direct"))
    }
  }

  test("borrow-capture direct calls are equivalent to explicit top-level parameter passing") {
    val code =
      """
        fn top_add(seed: Int, x: Int): Int = x + seed;;

        fn explicit_param(seed: Int, x: Int): Int =
          top_add seed x;
        ;

        fn local_capture(seed: Int, x: Int): Int =
          fn add_seed(y: Int): Int =
            y + seed;
          ;

          add_seed x;
        ;

        fn let_capture(seed: Int, x: Int): Int =
          let add_seed = { y: Int -> y + seed; };
          add_seed x;
        ;

        fn inline_capture(seed: Int, x: Int): Int =
          { y: Int -> y + seed; } x;
        ;
      """

    semNotFailed(code).map { module =>
      val forms = List("explicit_param", "local_capture", "let_capture", "inline_capture")
      assertSameFunctionType(module, forms)

      List("local_capture", "let_capture").foreach { fnName =>
        val seedId = fnParamId(module, fnName, "seed")
        val lambda = onlyUserLambdaIn(module, fnName)
        assertDirect(lambda, s"$fnName should remain a direct call")
        assertEquals(captureResolvedIds(lambda), Set(seedId))
      }
    }
  }

  test("first-class non-capturing values use equivalent null-env materialization") {
    val code =
      """
        fn apply(f: Int -> Int, x: Int): Int = f x;;
        fn top_inc(x: Int): Int = x + 1;;

        fn first_class_top(x: Int): Int =
          apply top_inc x;
        ;

        fn first_class_lambda(x: Int): Int =
          let inc = { y: Int -> y + 1; };
          apply inc x;
        ;
      """

    semNotFailed(code).map { module =>
      assertSameFunctionType(module, List("first_class_top", "first_class_lambda"))
      assert(topLambda(module, "top_inc").captures.isEmpty, "top_inc should not capture")
      assertNullEnv(
        onlyUserLambdaIn(module, "first_class_lambda"),
        "non-capturing lambda value should materialize as NullEnv"
      )
    }
  }

  test("borrow-capturing escaping function values report equivalent error class") {
    val cases = List(
      "top-level partial application" ->
        """
          fn log_seed(seed: String, x: Int): Unit =
            println seed;
          ;

          fn make(seed: String): Int -> Unit =
            log_seed seed;
          ;
        """,
      "local function capture" ->
        """
          fn make(seed: String): Int -> Unit =
            fn log_seed(x: Int): Unit =
              println seed;
            ;

            log_seed;
          ;
        """,
      "let-bound lambda capture" ->
        """
          fn make(seed: String): Int -> Unit =
            let log_seed = { x: Int ->
              println seed;
            };
            log_seed;
          ;
        """
    )

    cases.traverse { case (name, code) =>
      semState(code, name = name).map { result =>
        val errors =
          result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
        assert(errors.nonEmpty, s"Expected BorrowClosureEscapeViaReturn for $name")
      }
    }
  }

  test("borrow-capturing function value cannot flow to consuming parameter") {
    val code =
      """
        fn consume_fn(~f: Int -> Int, x: Int): Int =
          f x;
        ;

        fn main(seed: Int): Int =
          let add_seed = { x: Int -> x + seed; };
          consume_fn add_seed 10;
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowedValuePassedToConsumingParam => e }
      assert(
        errors.exists(_.ref.name == "add_seed"),
        s"Expected borrowed closure value to be rejected at consuming parameter, got: ${result.errors}"
      )
    }
  }

  test("consuming-use invalid programs report equivalent use-after-move class") {
    val cases = List(
      "top-level helper" ->
        """
          fn consume(~s: String): Unit = println s;;

          fn main(): Unit =
            let s = "hello" ++ " world";
            consume s;
            consume s;
          ;
        """,
      "local helper" ->
        """
          fn main(): Unit =
            fn consume_local(~s: String): Unit =
              println s;
            ;

            let s = "hello" ++ " world";
            consume_local s;
            consume_local s;
          ;
        """,
      "let-bound lambda helper" ->
        """
          fn main(): Unit =
            let consume_local = { ~s: String ->
              println s;
            };

            let s = "hello" ++ " world";
            consume_local s;
            consume_local s;
          ;
        """
    )

    cases.traverse { case (name, code) =>
      semState(code, name = name).map { result =>
        val errors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
        assert(errors.nonEmpty, s"Expected UseAfterMove for $name")
      }
    }
  }
