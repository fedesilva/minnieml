package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

class TailRecursionLoopificationTest extends BaseEffFunSuite:

  private def functionBody(llvmIr: String, signaturePattern: String): String =
    val pattern = (s"(?s)define .*@$signaturePattern \\{\\n(.*?)\\n\\}").r
    pattern
      .findFirstMatchIn(llvmIr)
      .map(_.group(1))
      .getOrElse(fail(s"Missing function definition for $signaturePattern. IR:\n$llvmIr"))

  test("emits tail-recursive function as a loop") {
    val source =
      """
      fn sum(i: Int, acc: Int): Int =
        if i < 10 then
          sum (i + 1) (acc + i);
        else
          acc;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("top-level loopified function uses the plain direct-call ABI") {
    val source =
      """
      fn sum(i: Int, acc: Int): Int =
        if i < 10 then
          sum (i + 1) (acc + i);
        else
          acc;
        ;
      ;

      fn main(): Int = sum 0 0;;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main\\(\\) #0")

      assert(
        llvmIr.contains("define internal i64 @test_sum(i64 %0, i64 %1) #0"),
        s"Loopified top-level function should not accept a closure env. IR:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("define internal i64 @test_sum(i64 %0, i64 %1, ptr %2) #0"),
        s"Loopified top-level function must not use closure-entry ABI. IR:\n$llvmIr"
      )
      assert(
        mainBody.contains("call i64 @test_sum(i64 0, i64 0)") &&
          !mainBody.contains("call i64 @test_sum(i64 0, i64 0, ptr null)"),
        s"Direct caller should pass only user arguments. Body:\n$mainBody"
      )
    }
  }

  test("named loopified function used as a value keeps a closure-entry wrapper") {
    val source =
      """
      fn apply(f: Int -> Int): Int = f 41;;

      fn down(x: Int): Int =
        if x == 0 then
          0;
        else
          down (x - 1);
        ;
      ;

      fn main(): Int = (apply down) + (down 3);;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      val applyBody = functionBody(llvmIr, "test_apply\\(\\{ ptr, ptr \\} %0\\) #0")
      val mainBody  = functionBody(llvmIr, "test_main\\(\\) #0")
      val wrapperMatch =
        """(?s)define internal i64 @(test__anon_\d+)\(i64 %0, ptr %1\) #0 \{\n(.*?)\n\}""".r
          .findFirstMatchIn(llvmIr)
          .getOrElse(fail(s"Missing closure-entry wrapper. IR:\n$llvmIr"))
      val wrapperName = wrapperMatch.group(1)
      val wrapperBody = wrapperMatch.group(2)

      assert(
        llvmIr.contains("define internal i64 @test_down(i64 %0) #0"),
        s"Direct loopified function should use plain ABI. IR:\n$llvmIr"
      )
      assert(
        wrapperBody.contains("call i64 @test_down(i64 %0)"),
        s"Wrapper should forward to the plain direct symbol. Body:\n$wrapperBody"
      )
      assert(
        """call i64 %\d+\(i64 41, ptr %\d+\)""".r.findFirstIn(applyBody).nonEmpty,
        s"Higher-order call should still pass the extracted env. Body:\n$applyBody"
      )
      assert(
        mainBody.contains(s"call i64 @test_apply({ ptr, ptr } { ptr @$wrapperName, ptr null })") &&
          mainBody.contains("call i64 @test_down(i64 3)") &&
          !mainBody.contains("call i64 @test_down(i64 3, ptr null)"),
        s"main should use both the wrapper value and the direct plain call. Body:\n$mainBody"
      )
    }
  }

  test("emits tail-recursive function with pre-statements as a loop") {
    val source =
      """
      fn loop(i: Int, to: Int): Unit =
        println (int_to_str i);
        if i <= to then
          loop (i + 1) to;
        else
          ();
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
      assert(llvmIr.contains("call void @println"))
    }
  }

  test("emits elif chain tail recursion as a loop") {
    val source =
      """
      fn find(i: Int, limit: Int): Int =
        if i > limit then 0;
        elif i == 42 then i;
        else find (i + 1) limit;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("emits tail-recursive function with let binding as a loop") {
    val source =
      """
      fn count(i: Int, acc: Int): Int =
        if i < 10 then
          let next = i + 1;
          count next (acc + i);
        else
          acc;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("emits both-branches-recursive tail recursion as a loop") {
    val source =
      """
      fn walk(i: Int, n: Int): Int =
        if i >= n then 0;
        elif i == 42 then walk (i + 2) n;
        else walk (i + 1) n;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(
        llvmIr.split("br label %loop.header").length >= 3,
        "expected at least 2 back edges"
      )
      assert(!llvmIr.contains("call i64 @walk"))
    }
  }

  test("emits nested both-branches-recursive with different args") {
    val source =
      """
      fn search(i: Int, step: Int, limit: Int): Int =
        if i >= limit then
          i;
        else
          if step > 5 then
            search (i + step) 1 limit;
          else
            search (i + 1) (step + 1) limit;
          ;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(
        llvmIr.split("br label %loop.header").length >= 3,
        "expected at least 2 back edges"
      )
      assert(!llvmIr.contains("call i64 @search"))
    }
  }

  test("emits deep nested conditionals mixing exits and calls") {
    val source =
      """
      fn deep(x: Int, y: Int): Int =
        if x <= 0 then
          y;
        else
          if y > 10 then
            if x > 5 then
              deep (x - 2) y;
            else
              999;
            ;
          else
            deep (x - 1) (y + 1);
          ;
        ;
      ;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
      assert(!llvmIr.contains("call i64 @deep"))
    }
  }
