package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class ClosureCodegenTest extends BaseEffFunSuite:

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
