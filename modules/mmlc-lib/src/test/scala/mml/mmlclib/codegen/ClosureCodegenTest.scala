package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class ClosureCodegenTest extends BaseEffFunSuite:

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
