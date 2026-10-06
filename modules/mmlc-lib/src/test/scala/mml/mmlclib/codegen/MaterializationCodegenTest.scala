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
      s"call i32 @${Regex.quote(callee)}\\(\\{ ptr, ptr \\} \\{ ptr @([^, ]+), ptr null \\}".r
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
    val invocation = s"call i32 $target\\((?:i32 %\\d+, )?ptr $envArg\\)".r
    assert(invocation.findFirstIn(calleeBody).nonEmpty, calleeBody)

  test("top-level fn called directly is direct") {
    val code =
      """
        fn id(x: Int): Int = x;;
        fn main(): Int = id 1;;
      """

    compileAndGenerate(code).map { ir =>
      val body = functionBody(ir, "test_main")
      functionBodyMatching(ir, "test_id\\(i32 %\\d+\\).*")
      assert(body.contains("call i32 @test_id(i32 1)"), body)
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
      val body = functionBodyMatching(ir, "test_fact\\(i32 %\\d+\\).*")
      assert("""call i32 @test_fact\(i32 %\d+\)""".r.findFirstIn(body).nonEmpty, body)
      assertNoClosureValue(body)
    }
  }

  test("let-bound lambda used only directly is direct") {
    val code =
      """
        fn main(dummy: Int): Int =
          let id = { x: Int -> x };
          id dummy;
        ;
      """
    compileAndGenerate(code).map { ir =>
      val body = functionBody(ir, "test_main")
      val call = """call i32 @([^ (]+)\(i32 %\d+\)""".r
        .findFirstMatchIn(body)
        .getOrElse(fail(s"Expected a plain local call:\n$body"))
      functionBodyMatching(ir, s"${Regex.quote(call.group(1))}\\(i32 %\\d+\\).*")
      assertNoClosureValue(body)
      assert(!ir.contains("{ ptr, ptr }"), ir)
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
      assertNullEnvArgument(ir, "test_main", "test_apply", "i32 %\\d+, ptr %\\d+")
    }
  }

  test("lambda literal in immediate application is direct") {
    val code =
      """
        fn main(): Int = { x: Int -> x } 5;;
      """

    compileAndGenerate(code).map { ir =>
      val body = functionBody(ir, "test_main")
      assert(body.contains("ret i32 5"), body)
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
      assert(body.contains("call i32 @test_nada()"), body)
      assertNoClosureValue(body)
    }
  }

  test("nullary top-level fn used as value is not direct") {
    val code =
      """
        fn nada(): Int = 42;;
        let a = nada;
      """

    compileAndGenerate(code).map { ir =>
      val initializer = functionBody(ir, "_init_global_test_a")
      val pair = """store \{ ptr, ptr \} \{ ptr @([^, ]+), ptr null \}""".r
        .findFirstMatchIn(initializer)
        .getOrElse(fail(s"Expected a callable value in the global initializer:\n$initializer"))
      val entry = functionBodyMatching(ir, s"${Regex.quote(pair.group(1))}\\(ptr %\\d+\\).*")
      assert(entry.contains("call i32 @test_nada()"), entry)
      assert(!initializer.contains("load "), initializer)
      assert(!initializer.contains("call "), initializer)
      assert(!initializer.contains("@malloc"), initializer)
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
    compileAndGenerate(code).map { ir =>
      val call = """call i32 @([^ (]+)\(i32 %\d+, i32 %\d+\)""".r
        .findFirstMatchIn(ir)
        .getOrElse(fail(ir))
      functionBodyMatching(ir, s"${Regex.quote(call.group(1))}\\(i32 %\\d+, i32 %\\d+\\).*")
      val main = functionBody(ir, "test_main")
      assert(!main.contains("store { ptr, ptr }"), main)
      assert("""%struct\.__closure_env_\d+ = type \{ ptr, i32 \}""".r.findFirstIn(ir).nonEmpty, ir)
    }
  }
