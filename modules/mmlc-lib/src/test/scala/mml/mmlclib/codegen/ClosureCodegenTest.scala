package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class ClosureCodegenTest extends BaseEffFunSuite:

  private def functionBody(llvmIr: String, signaturePattern: String): String =
    val pattern = (s"(?s)define .*@$signaturePattern \\{\\n(.*?)\\n\\}").r
    pattern
      .findFirstMatchIn(llvmIr)
      .map(_.group(1))
      .getOrElse(fail(s"Missing function definition for $signaturePattern. IR:\n$llvmIr"))

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
        { x: Int -> x + a; };
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

  test("local capturing closures free through their specific env destructor") {
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
        """call void @test___free___closure_env_\d+\(ptr %\d+\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Expected local capturing closure cleanup to call its specific env destructor. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void @test___free_closure"),
        s"Known env cleanup should not route through the universal destructor. Body:\n$mainBody"
      )
      assert(
        !mainBody.contains("call void %"),
        s"Caller should not inline the env destructor dispatch. Body:\n$mainBody"
      )
    }
  }

  test("capture-site env stores use semantic env names and TBAA tags") {
    val source = """
      fn main(): Int =
        let a = 1;
        let f = { x: Int -> x + a; };
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
