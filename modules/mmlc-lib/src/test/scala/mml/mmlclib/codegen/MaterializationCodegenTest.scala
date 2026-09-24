package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

import scala.util.matching.Regex

class MaterializationCodegenTest extends BaseEffFunSuite:

  private def assertNoClosureValue(body: String): Unit =
    assert(!body.contains("{ ptr, ptr }"), body)
    assert(!body.contains("alloca "), body)
    assert(!body.contains("@malloc"), body)

  private def assertNullEnvArgument(
    ir:          String,
    caller:      String,
    callee:      String,
    entryParams: String
  ): Unit =
    val body = functionBody(ir, caller)
    val argument =
      s"call i64 @${Regex.quote(callee)}\\(\\{ ptr, ptr \\} \\{ ptr @([^, ]+), ptr null \\}".r
    val entries = argument.findAllMatchIn(body).map(_.group(1)).toList
    val entry = entries match
      case List(symbol) => symbol
      case _ => fail(s"Expected one null-environment argument to $callee:\n$body")

    functionBodyMatching(ir, s"${Regex.quote(entry)}\\($entryParams\\).*")
    assert(!body.contains("alloca "), body)
    assert(!body.contains("@malloc"), body)

    val calleeBody = functionBody(ir, callee)
    val pointer = """(%\d+) = extractvalue \{ ptr, ptr \} (%\d+), 0""".r
      .findFirstMatchIn(calleeBody)
      .getOrElse(fail(s"Expected a closure entry extraction:\n$calleeBody"))
    val environment =
      s"(%\\d+) = extractvalue \\{ ptr, ptr \\} ${Regex.quote(pointer.group(2))}, 1".r
        .findFirstMatchIn(calleeBody)
        .getOrElse(fail(s"Expected the same closure's environment:\n$calleeBody"))
    val target     = Regex.quote(pointer.group(1))
    val envArg     = Regex.quote(environment.group(1))
    val invocation = s"call i64 $target\\((?:i64 %\\d+, )?ptr $envArg\\)".r
    assert(invocation.findFirstIn(calleeBody).nonEmpty, calleeBody)

  test("top-level fn called directly is direct") {
    val code =
      """
        fn id(x: Int): Int = x;;
        fn main(): Int = id 1;;
      """

    compileAndGenerate(code).map { ir =>
      val body = functionBody(ir, "test_main")
      functionBodyMatching(ir, "test_id\\(i64 %\\d+\\).*")
      assert(body.contains("call i64 @test_id(i64 1)"), body)
      assertNoClosureValue(body)
    }
  }

  test("recursive self-call keeps fn direct") {
    val code =
      """
        fn fact(n: Int): Int =
          if n <= 1 then 1;
          else n * (fact (n - 1));
          ;
        ;
      """

    compileAndGenerate(code).map { ir =>
      val body = functionBodyMatching(ir, "test_fact\\(i64 %\\d+\\).*")
      assert("""call i64 @test_fact\(i64 %\d+\)""".r.findFirstIn(body).nonEmpty, body)
      assertNoClosureValue(body)
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

    compileAndGenerate(code).map { ir =>
      assertNullEnvArgument(ir, "test_main", "test_apply", "i64 %\\d+, ptr %\\d+")
    }
  }

  test("lambda literal in immediate application is direct") {
    val code =
      """
        fn main(): Int = { x: Int -> x } 5;;
      """

    compileAndGenerate(code).map { ir =>
      val body = functionBody(ir, "test_main")
      assert(body.contains("ret i64 5"), body)
      assert(!body.contains("call "), body)
      assertNoClosureValue(body)
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

    compileAndGenerate(code).map { ir =>
      assertNullEnvArgument(ir, "test_main", "test_force", "ptr %\\d+")
    }
  }

  test("nullary top-level fn invoked is direct") {
    val code =
      """
        fn nada(): Int = 42;;
        let a = nada ();
      """

    compileAndGenerate(code).map { ir =>
      val body = functionBody(ir, "_init_global_test_a")
      functionBodyMatching(ir, "test_nada\\(\\).*")
      assert(body.contains("call i64 @test_nada()"), body)
      assertNoClosureValue(body)
    }
  }
