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
        """(?s)define internal %struct.String @(test_[A-Za-z0-9_]+)\(i64 %0\) #0 \{\n(.*?)\n\}""".r
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

  test("recursive Direct lambda calls its plain direct entry") {
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
        """(?s)define internal i64 @(test_loop_\d+)\(i64 %0\) #0 \{\n(.*?)\n\}""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing recursive Direct body. IR:\n$llvmIr"))

      val loopName = loopMatch.group(1)
      val loopBody = loopMatch.group(2)

      assert(
        loopBody.contains(s"call i64 @$loopName(i64 %"),
        s"Expected loop body to recurse through its plain direct entry. Body:\n$loopBody"
      )
      assert(
        !loopBody.contains("insertvalue { ptr, ptr }") &&
          !loopBody.contains("extractvalue { ptr, ptr }"),
        s"Recursive Direct body must not rebuild or unpack a fat pointer. Body:\n$loopBody"
      )
    }
  }

  test("non-recursive named Direct lambda does not rebuild unused self closure") {
    val source = """
      fn main(): Int =
        let seed = 1;
        fn inner(x: Int): Int =
          x + seed;
        ;
        inner 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val innerMatch =
        """(?s)define internal i64 @(test_inner_\d+)\(i64 %0, i64 %1\) #0 \{\n(.*?)\n\}""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing named Direct body. IR:\n$llvmIr"))

      val innerName = innerMatch.group(1)
      val innerBody = innerMatch.group(2)

      assert(
        !innerBody.contains(s"insertvalue { ptr, ptr } undef, ptr @$innerName, 0"),
        s"Non-recursive Direct lambda should not rebuild its own function pointer. Body:\n$innerBody"
      )
      assert(
        !innerBody.contains("insertvalue { ptr, ptr }"),
        s"Non-recursive Direct body should not emit any self fat-pointer insertvalues. Body:\n$innerBody"
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
      val freeBody = functionBody(llvmIr, "test___free_closure\\(ptr %0\\) #\\d+")

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
      fn apply(g: Int -> Int, n: Int): Int = g n;;
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        apply f 41;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        mainBody.contains("call ptr @malloc"),
        s"Expected local move closure to heap-allocate its env. Body:\n$mainBody"
      )
      assert(
        """store ptr @test___free___closure_env_\d+, ptr %\d+""".r
          .findFirstIn(mainBody)
          .nonEmpty,
        s"Expected local move closure to store its env destructor. Body:\n$mainBody"
      )
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
      fn apply(g: Int -> Int): Int = g 41;;
      fn main(): Int =
        let a = 1;
        let f = { x: Int -> x + a; };
        apply f;
      ;
    """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        """%struct\.__closure_env_\d+ = type \{ i64 \}""".r.findFirstIn(llvmIr).nonEmpty,
        s"Expected borrow closure env type to contain captures only. IR:\n$llvmIr"
      )
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

  test("loopified Direct lambdas do not allocate borrow envs") {
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
      val loopBody = functionBody(llvmIr, "test_loop\\(i64 %0, i64 %1\\) #0")

      assert(
        loopBody.contains("call i64 @test_add_"),
        s"Expected loopified body to call the Direct lambda entry. Body:\n$loopBody"
      )
      assert(
        !loopBody.contains("alloca %struct.__closure_env_"),
        s"Loopified Direct lambda should not allocate a borrow env. Body:\n$loopBody"
      )
      assert(
        !loopBody.contains("call ptr @malloc"),
        s"Loopified Direct lambda should not heap-allocate an env. Body:\n$loopBody"
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

  test("loopified borrow closures stop being tracked after nested shadowing") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let f = { x: Int -> x + n; };
            fn step(dummy: Int): Int =
              let f = acc + 1;
              loop (n - 1) f;
            ;
            step 0;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loopBody = functionBody(llvmIr, "test_loop\\(i64 %0, i64 %1\\) #0")

      assert(
        loopBody.contains("call i64 @test_step_"),
        s"Expected the nested-shadowed Direct lambda to still codegen successfully. Body:\n$loopBody"
      )
    }
  }

  test("loopified borrow closure validation respects lambda parameter shadowing") {
    val source =
      """
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then
            acc;
          else
            let f = { x: Int -> x + n; };
            let g = { f: Int -> f + 1; };
            let next = g acc;
            loop (n - 1) next;
          ;
        ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val loopBody = functionBody(llvmIr, "test_loop\\(i64 %0, i64 %1\\) #0")

      assert(
        loopBody.contains("call i64 @test_g_"),
        s"Expected the inner lambda param to shadow the outer Direct lambda. Body:\n$loopBody"
      )
    }
  }

  test("capture-site env stores use semantic env names and TBAA tags") {
    val source = """
      fn apply(g: Int -> Int): Int = g 41;;
      fn main(): Int =
        let a = 1;
        let f = ~{ x: Int -> x + a; };
        apply f;
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
