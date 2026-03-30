package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig}
import mml.mmlclib.test.BaseEffFunSuite

class ClosureCodegenTest extends BaseEffFunSuite:

  private def functionBody(llvmIr: String, signaturePattern: String): String =
    val pattern = (s"(?s)define .*@$signaturePattern \\{\\n(.*?)\\n\\}").r
    pattern
      .findFirstMatchIn(llvmIr)
      .map(_.group(1))
      .getOrElse(fail(s"Missing function definition for $signaturePattern. IR:\n$llvmIr"))

  private def compileCodegenErrors(
    source: String,
    name:   String = "Test",
    config: CompilerConfig
  ): IO[List[CodeGenError]] =
    FrontEndApi.compile(source, name, config).value.flatMap {
      case Right(state) =>
        if state.errors.nonEmpty then fail(s"Compilation failed before codegen: ${state.errors}")
        else
          val validated = CodegenStage.validate(state)
          if validated.hasErrors then fail(s"Validation failed: ${validated.errors}")
          else
            CodegenStage.emitIrOnly(validated).map { codegenState =>
              codegenState.errors.collect { case error: CodeGenError => error }.toList
            }
      case Left(error) =>
        fail(s"Compilation failed: $error")
    }

  test("deferred lambda body preserves emitted metadata") {
    val source = """
      fn main(): String =
        let f = { x: Int ->
          println "lambda";
          "lambda";
        };
        f 1;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val lambdaMatch =
        """(?s)define internal %struct.String @(test_[A-Za-z0-9_]+)\(i64 %0, ptr %1\) #0 \{\n(.*?)\n\}""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing deferred lambda definition. IR:\n$llvmIr"))
      val lambdaBody = lambdaMatch.group(2)

      assert(
        llvmIr.contains("""@str.0 = private constant [6 x i8] c"lambda""""),
        s"Missing module-level string constant emitted from deferred lambda body. IR:\n$llvmIr"
      )
      assert(
        lambdaBody.contains("call void @println"),
        s"Expected deferred lambda body to keep its println call. Body:\n$lambdaBody"
      )
      assert(
        lambdaBody.contains("!tbaa !10") && lambdaBody.contains("!tbaa !11"),
        s"Expected deferred lambda body to keep TBAA-tagged String field stores. Body:\n$lambdaBody"
      )
    }
  }

  test("recursive capturing lambda rebuilds self closure from env") {
    val source = """
      fn main(): Int =
        fn inc(x: Int): Int = x + 1;;
        fn loop(n: Int): Int =
          if n == 0 then
            0;
          else
            let m = inc n;
            m + (loop (n - 1));
          ;
        ;
        loop 5;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val loopMatch =
        """(?s)define internal i64 @(test_loop_\d+)\(i64 %0, ptr %1\) #0 \{\n(.*?)\n\}""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing recursive closure body. IR:\n$llvmIr"))

      val loopName = loopMatch.group(1)
      val loopBody = loopMatch.group(2)

      assert(
        loopBody.contains(s"insertvalue { ptr, ptr } undef, ptr @$loopName, 0"),
        s"Expected loop body to rebuild its own function pointer. Body:\n$loopBody"
      )
      assert(
        loopBody.contains("insertvalue { ptr, ptr } %") && loopBody.contains("ptr %1, 1"),
        s"Expected loop body to thread the live env pointer into self closure. Body:\n$loopBody"
      )
      assert(
        !loopBody.contains(s"{ ptr @$loopName, ptr null }"),
        s"Recursive closure must not self-call through a null env stub. Body:\n$loopBody"
      )
    }
  }

  test("returned capturing closures free through generated __free_closure body") {
    val source = """
      fn makeAdder(a: Int): Int -> Int =
        ~{ x: Int -> x + a; };
      ;

      fn main(): Int =
        let f = makeAdder 1;
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")
      val freeBody = functionBody(llvmIr, "test___free_closure\\(ptr %0\\) #0")

      assert(
        mainBody.contains("call void @test___free_closure(ptr %"),
        s"Expected escaped closure cleanup to call the generated __free_closure. Body:\n$mainBody"
      )
      assert(
        freeBody.contains("icmp eq ptr %0, null"),
        s"Expected __free_closure to null-guard non-capturing closures. Body:\n$freeBody"
      )
      assert(
        freeBody.contains("load ptr, ptr %0") && freeBody.contains("call void %") &&
          freeBody.contains("(ptr %0)"),
        s"Expected __free_closure to dispatch through env field 0. Body:\n$freeBody"
      )
      assert(
        !mainBody.contains("call void %"),
        s"Caller should not inline the closure destructor dispatch anymore. Body:\n$mainBody"
      )
    }
  }

  test("local move capturing closures free through their specific env destructor") {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        """call void @test___free___closure_env_\d+\(ptr %\d+\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected local move closure cleanup to call its specific env destructor. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_closure"),
        s"Known env cleanup should not route through the universal destructor. Body:\n$mainBody"
      )
    }
  }

  test("local borrow capturing closures use alloca and no free") {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = { x: Int -> x + a; };
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains("alloca %struct.__closure_env_"),
        s"Expected borrow closure to use alloca for env. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call ptr @malloc"),
        s"Borrow closure should not heap-allocate env. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_"),
        s"Borrow closure should not generate free calls. Body:\n$mainBody"
      )
    }
  }

  test("loopified borrow closures hoist env alloca to function entry") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let add = { x: Int -> x + n; };
            let next = add acc;
            loop (n - 1) next;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loopBody      = functionBody(llvmIr, "test_loop\\(i64 %0, i64 %1, ptr %2\\) #0")
      val envAllocCount = "alloca %struct.__closure_env_".r.findAllIn(loopBody).length
      val allocaIndex   = loopBody.indexOf("alloca %struct.__closure_env_")
      val loopBrIndex   = loopBody.indexOf("br label %loop.header")
      val loopHeaderIdx = loopBody.indexOf("loop.header:")

      assertEquals(envAllocCount, 1, s"Expected one hoisted borrow env alloca. Body:\n$loopBody")
      assert(allocaIndex >= 0, s"Missing borrow env alloca in loopified function. Body:\n$loopBody")
      assert(loopBrIndex >= 0, s"Missing branch to loop header. Body:\n$loopBody")
      assert(loopHeaderIdx >= 0, s"Missing loop header label. Body:\n$loopBody")
      assert(
        allocaIndex < loopBrIndex && allocaIndex < loopHeaderIdx,
        s"Expected borrow env alloca in the entry block before loop control flow. Body:\n$loopBody"
      )
      assert(
        !loopBody.contains("call ptr @malloc"),
        s"Hoisted borrow closure should still use alloca, not malloc. Body:\n$loopBody"
      )
    }
  }

  test("loopified borrow closures carried across iterations are rejected") {
    val source =
      """
        fn seed(x: Int): Int = x;;

        fn loop(n: Int, current: Int -> Int): Int =
          let next = { x: Int -> x + n; };
          if n == 0 then
            current 1;
          else
            loop (n - 1) next;
          ;
        ;

        fn main(): Int =
          loop 2 seed;
        ;
      """

    compileCodegenErrors(source, config = CompilerConfig.default.copy(noTco = false)).map {
      errors =>
        assert(
          errors.exists(
            _.message.contains("Borrow closure on a loopified path may survive across iterations")
          ),
          s"Expected loopified borrow-closure reuse rejection, got: $errors"
        )
    }
  }

  test("capture-site env stores use semantic env names and TBAA tags") {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val envName = """%struct\.(__closure_env_\d+) = type \{ ptr, i64 \}""".r
        .findFirstMatchIn(llvmIr)
        .map(_.group(1))
        .getOrElse(fail(s"Expected closure env type definition. IR:\n$llvmIr"))
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains(s"getelementptr %struct.$envName, ptr %"),
        s"Expected env setup to use semantic env struct name '$envName'. Body:\n$mainBody"
      )
      assert(
        s"""store ptr @test___free_$envName, ptr %\\d+, !tbaa !\\d+""".r
          .findFirstIn(mainBody)
          .nonEmpty,
        s"Expected destructor slot store with TBAA tag. Body:\n$mainBody"
      )
      assert(
        """store i64 (?:%\d+|[-]?\d+), ptr %\d+, !tbaa !\d+""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected capture store with TBAA tag. Body:\n$mainBody"
      )
    }
  }
