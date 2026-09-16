package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files
import scala.sys.process.Process

class NestedRecursionCodegenTests extends BaseEffFunSuite:

  private val nestedLoops = """
    fn compute(n: Int): Int =
      fn outer(i: Int, total: Int): Int =
        fn middle(j: Int, subtotal: Int): Int =
          fn inner(k: Int, acc: Int): Int =
            if k < n then
              inner (k + 1) (acc + i * 100 + j * 10 + k);
            else acc;
            ;
          ;
          if j < n then middle (j + 1) (subtotal + inner 0 0);
          else subtotal;
          ;
        ;
        if i < n then outer (i + 1) (total + middle 0 0);
        else total;
        ;
      ;
      outer 0 0;
    ;
    """

  private def assemble(ir: String): IO[Unit] =
    IO.blocking(Files.createTempFile("mml-nested-recursion", ".ll"))
      .bracket { path =>
        IO.blocking {
          Files.writeString(path, ir)
          val exit = Process(Seq("llvm-as", path.toString, "-o", "/dev/null")).!
          assertEquals(exit, 0, ir)
        }
      }(path => IO.blocking(Files.deleteIfExists(path)).void)

  private def assertLoop(ir: String, name: String): Unit =
    val body = functionBodyMatching(ir, s"test_${name}_\\d+\\([^\\n]*")
    assert(body.contains("loop.header:"), body)
    assertPhiPredecessors(body)
    assertEquals(phiCount(body), 2, body)
    val loopBody = body.substring(body.indexOf("loop.header:"))
    assert(!loopBody.contains("alloca"), body)

  test("nested capturing helpers all assemble and become loops") {
    compileAndGenerate(nestedLoops).flatMap { ir =>
      assemble(ir).map { _ =>
        List("outer", "middle", "inner").foreach(assertLoop(ir, _))
      }
    }
  }

  test("nested capturing recursion still assembles with TCO disabled") {
    compileAndGenerate(nestedLoops, config = CompilerConfig.default.copy(noTco = true))
      .flatMap { ir =>
        assemble(ir).map(_ => assert(!ir.contains("loop.header:"), ir))
      }
  }

  test("recursive helpers inside conditional branches become loops") {
    val source = """
      fn choose(n: Int, enabled: Bool): Int =
        if enabled then
          fn count(i: Int, acc: Int): Int =
            if i < n then count (i + 1) (acc + i);
            else acc;
            ;
          ;
          count 0 0;
        else 0;
        ;
      ;
      """
    compileAndGenerate(source).flatMap { ir =>
      assemble(ir).map(_ => assertLoop(ir, "count"))
    }
  }

  List(true, false).foreach { captures =>
    test(s"nested mixed recursion retains its self closure (captures=$captures)") {
      val source  = """
      fn choose(base: Int, enabled: Bool): Int =
        if enabled then
          fn walk(i: Int, acc: Int): Int =
            if i == 0 then acc + base;
            elif i == 1 then 1 + walk 0 acc;
            else walk (i - 1) (acc + i);
            ;
          ;
          walk 4 0;
        else 0;
        ;
      ;
      """
      val program = if captures then source else source.replace("acc + base", "acc + 7")
      compileAndGenerate(program).flatMap { ir =>
        assemble(ir).map(_ => assertLoop(ir, "walk"))
      }
    }
  }

  test("branch-local recursion permits synchronous higher-order borrows") {
    val source = """
      fn apply(f: Int -> Int, x: Int): Int = f x;;
      fn choose(n: Int): Int =
        if n > 0 then
          fn walk(i: Int, acc: Int): Int =
            if i > 0 then
              fn add(x: Int): Int = i + x;;
              walk (i - 1) (acc + apply add 1);
            else acc;
            ;
          ;
          walk n 0;
        else 0;
        ;
      ;
      """
    compileAndGenerate(source).flatMap { ir =>
      assemble(ir).map(_ => assertLoop(ir, "walk"))
    }
  }

  test("higher-order identity cannot carry a borrowed closure across a back edge") {
    val source = """
      fn identity(f: Int -> Int): Int -> Int = f;;
      fn seed(x: Int): Int = x;;
      fn choose(n: Int): Int =
        if n > 0 then
          fn walk(i: Int, current: Int -> Int): Int =
            fn add(x: Int): Int = i + x;;
            if i > 0 then walk (i - 1) (identity add);
            else current 1;
            ;
          ;
          walk n seed;
        else 0;
        ;
      ;
      """
    semState(source).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowEscapeViaReturn]),
        result.errors.toString
      )
    }
  }
