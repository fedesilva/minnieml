package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

class TailRecursionLoopificationTest extends BaseEffFunSuite:

  test("emits tail-recursive function as a loop") {
    val source =
      """
      fn sum(i: Int, acc: Int): Int =
        if i < 10 then
          sum (i + 1) (acc + i)
        else
          acc
        end;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("loop.latch:"))
      assert(llvmIr.contains("loop.exit.0:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("br label %loop.header"))
    }
  }

  test("emits tail-recursive function with pre-statements as a loop") {
    val source =
      """
      fn loop(i: Int, to: Int): Unit =
        println (to_string i);
        if i <= to then
          loop (i + 1) to
        else
          ()
        end;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("loop.latch:"))
      assert(llvmIr.contains("loop.exit.0:"))
      assert(llvmIr.contains("phi i64"))
      assert(llvmIr.contains("call void @println"))
    }
  }

  test("emits elif chain tail recursion as a loop") {
    val source =
      """
      fn find(i: Int, limit: Int): Int =
        if i > limit then 0
        elif i == 42 then i
        else find (i + 1) limit
        end;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("loop.latch:"))
      assert(llvmIr.contains("loop.exit.0:"))
      assert(llvmIr.contains("loop.exit.1:"))
      assert(llvmIr.contains("phi i64"))
    }
  }

  test("emits tail-recursive function with let binding as a loop") {
    val source =
      """
      fn count(i: Int, acc: Int): Int =
        if i < 10 then
          let next = i + 1;
          count next (acc + i)
        else acc
        end;
      """

    compileAndGenerate(source, config = CompilerConfig.default.copy(noTco = false)).map { llvmIr =>
      assert(llvmIr.contains("loop.header:"))
      assert(llvmIr.contains("loop.latch:"))
      assert(llvmIr.contains("loop.exit.0:"))
      assert(llvmIr.contains("phi i64"))
    }
  }
