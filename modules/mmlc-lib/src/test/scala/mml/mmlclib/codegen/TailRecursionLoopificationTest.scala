package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

class TailRecursionLoopificationTest extends BaseEffFunSuite:

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
