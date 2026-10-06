package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files
import scala.sys.process.Process
import scala.util.matching.Regex

class FunctionValueCodegenTests extends BaseEffFunSuite:

  private def assemble(ir: String): IO[Unit] =
    IO.blocking(Files.createTempFile("mml-function-value", ".ll"))
      .bracket { path =>
        IO.blocking {
          Files.writeString(path, ir)
          assertEquals(Process(Seq("llvm-as", path.toString, "-o", "/dev/null")).!, 0, ir)
        }
      }(path => IO.blocking(Files.deleteIfExists(path)).void)

  private def globalEntry(ir: String, name: String): String =
    val initializer = functionBody(ir, s"_init_global_test_$name")
    val pair        = """store \{ ptr, ptr \} \{ ptr @([^, ]+), ptr null \}""".r
    pair
      .findFirstMatchIn(initializer)
      .map(_.group(1))
      .getOrElse(fail(s"Expected a null-environment callable:\n$initializer"))

  test("callable adapters are reused across global and deferred function bodies") {
    val source = """
      fn nada(): Int = 42;;
      let first = nada;
      fn make(): Unit -> Int = nada;;
      fn apply(f: Unit -> Int): Int = f ();;
      fn nested(x: Int): Int =
        let inner = { y: Int -> apply nada + y; };
        inner x;
      ;
      let second = nada;
    """
    compileAndGenerate(source).flatMap { ir =>
      val entry = globalEntry(ir, "first")
      assertEquals(globalEntry(ir, "second"), entry)
      val body = functionBodyMatching(ir, s"${Regex.quote(entry)}\\(ptr %\\d+\\).*")
      assert(body.contains("call i32 @test_nada()"), body)
      assert(functionBody(ir, "test_make").contains(s"{ ptr @$entry, ptr null }"), ir)
      assertEquals("call i32 @test_nada()".r.findAllIn(ir).size, 1, ir)
      assemble(ir)
    }
  }

  test("stored callables preserve construction timing, aliases, returns and shadowing at runtime") {
    val source  = """
      fn observe(n: Int): Unit = @native;;
      fn nada(): Int = observe 1; 42;;
      fn force(g: Unit -> Int): Int = g ();;
      fn give(): Unit -> Int = nada;;
      fn native_nada(): Int = @native[name="host_nada"];;
      fn native_unit(): Unit = @native[name="host_unit"];;
      fn templ(): Int = @native[tpl="add i32 40, 2"];;
      fn unit_template(): Unit = @native[tpl="call void @host_unit()"];;
      let global = nada;
      let alias = global;
      let external = native_nada;
      let action = native_unit;
      let template = templ;
      let template_action = unit_template;
      pub fn main(): Int =
        observe 0;
        let local = nada;
        observe 2;
        let a = force alias;
        let returned = give ();
        let b = returned ();
        let nada = { 7; };
        let c = nada ();
        observe (a + b + c);
        observe (external ());
        action ();
        template_action ();
        observe (template ());
        local () - 42;
      ;
    """
    val runtime = """
      #include <stdint.h>
      #include <stdio.h>
      void observe(int32_t n) { printf("%lld\n", (long long)n); }
      int32_t host_nada(void) { observe(3); return 42; }
      void host_unit(void) { observe(4); }
      void mml_sys_flush(void) { fflush(stdout); }
    """
    compileAndGenerate(source, config = CompilerConfig.exe("build")).flatMap { ir =>
      assemble(ir) *> IO
        .blocking(Files.createTempDirectory("mml-function-value"))
        .bracket { dir =>
          IO.blocking {
            val input   = dir.resolve("program.ll")
            val support = dir.resolve("support.c")
            val live    = dir.resolve("live.ll")
            val binary  = dir.resolve("program")
            Files.writeString(input, ir)
            Files.writeString(support, runtime)
            assertEquals(
              Process(Seq("opt", "-passes=globaldce", "-S", input.toString, "-o", live.toString)).!,
              0
            )
            assertEquals(
              Process(
                Seq(
                  "clang",
                  "-Wno-override-module",
                  live.toString,
                  support.toString,
                  "-o",
                  binary.toString
                )
              ).!,
              0
            )
            val output = Process(Seq(binary.toString)).!!
            assertEquals(
              output.linesIterator.toList,
              List("0", "2", "1", "1", "91", "3", "42", "4", "4", "42", "1")
            )
          }
        } { dir =>
          IO.blocking {
            List("program.ll", "live.ll", "support.c", "program")
              .foreach(name => Files.deleteIfExists(dir.resolve(name)))
            Files.deleteIfExists(dir)
          }.void
        }
    }
  }

  for target <- List("x86_64-apple-macosx", "aarch64-apple-macosx") do
    test(s"native callable value preserves large structure return ABI on $target") {
      val source = """
        type Triple = @native { a: Int64, b: Int64, c: Int64 };
        fn produce(): Triple = @native[name="host_produce"];;
        let value = produce;
        fn invoke(): Triple = value ();;
      """
      compileAndGenerate(source, config = CompilerConfig.default.copy(targetTriple = Some(target)))
        .flatMap { ir =>
          val entry = globalEntry(ir, "value")
          val body  = expandNativeAdapters(ir, functionBody(ir, entry))
          assert(body.contains("call void @host_produce(ptr sret(%struct.Triple)"), body)
          assert(body.contains("load %struct.Triple"), body)
          assert(body.contains("ret %struct.Triple"), body)
          assemble(ir)
        }
    }

  test("unary function values preserve consuming pointer parameters and native templates") {
    val source = """
      type Handle = @native[t=ptr, mem=heap];
      fn release(~p: Handle): Unit = @native[name="host_release"];;
      fn consume(~p: Handle): Unit = release p;;
      fn apply(~p: Handle): Unit =
        let f = consume;
        f p;
      ;
      fn increment(x: Int): Int = @native[tpl="add i32 %operand, 1"];;
      fn calculate(x: Int): Int =
        let f = increment;
        f x;
      ;
    """
    compileAndGenerate(source).flatMap { ir =>
      // Discover the symbol at the call site; adapter creation can change generated-name numbering.
      def localEntry(caller: String, result: String, argument: String): String =
        val body = functionBody(ir, caller)
        s"call $result @([^ (]+)\\($argument %\\d+\\)".r
          .findFirstMatchIn(body)
          .map(_.group(1))
          .getOrElse(fail(body))

      val consumerName = localEntry("test_apply", "void", "ptr")
      val consumer =
        functionBodyMatching(ir, s"${Regex.quote(consumerName)}\\(ptr noalias %\\d+\\).*")
      assert(consumer.contains("call void @test_consume(ptr"), consumer)
      val incrementName = localEntry("test_calculate", "i32", "i32")
      val increment = functionBodyMatching(ir, s"${Regex.quote(incrementName)}\\(i32 %\\d+\\).*")
      assert(increment.contains("add i32 %0, 1"), increment)
      assert(!increment.contains("@increment"), increment)
      assemble(ir)
    }
  }
