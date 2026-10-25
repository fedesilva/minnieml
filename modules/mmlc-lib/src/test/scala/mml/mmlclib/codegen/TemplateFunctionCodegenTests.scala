package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.llvm.ClosureAssertions

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

class TemplateFunctionCodegenTests extends BaseEffFunSuite with ClosureAssertions:

  private val numberedTemplate = """
    pub fn distance(a: Int, b: Int, c: Int, d: Int, e: Int,
                    f: Int, g: Int, h: Int, i: Int, j: Int): Int =
      @metal:tpl["sub %type10 %operand10, %operand1"];
    ;
  """

  private def cleanupTemplate(visibility: String): String = s"""
    type Handle${visibility.capitalize} = @metal:repr[t=ptr, mem=heap, free=release_$visibility];
    $visibility fn release_$visibility(~p: Handle${visibility.capitalize}): Unit =
      @metal:tpl["store i8 0, ptr %operand"];
    ;
    fn read_$visibility(p: Handle${visibility.capitalize}): Int =
      @metal:tpl["ptrtoint ptr %operand to i32"];
    ;
    fn make_$visibility(~p: Handle${visibility.capitalize}): Unit -> Int = ~{ read_$visibility p };;
    pub fn dispose_$visibility(~p: Handle${visibility.capitalize}): Unit =
      let f = make_$visibility p;
      let ignored = f ();
      ();
    ;
  """

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-template"))
      .bracket(use) { directory =>
        IO.blocking(Files.walk(directory))
          .bracket { paths =>
            IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
          }(paths => IO.blocking(paths.close()))
      }

  private def assemble(ir: String): IO[Unit] =
    withDirectory { directory =>
      val input = directory.resolve("module.ll")
      IO.blocking(Files.writeString(input, ir)) *>
        commandNotFailed(List("llvm-as", input.toString, "-o", "/dev/null"), directory)
    }

  test(
    "non-public templates expand directly without declarations or standalone definitions"
  ) {
    val source = """
      priv fn byte(n: Int): Int8 = @metal:tpl["trunc i32 %operand to i8"];;
      fn unused(n: Int): Int = @metal:tpl["add i32 %operand, 1"];;
      pub fn convert(n: Int): Int8 = byte n;;
    """
    compileAndGenerate(source).flatMap { ir =>
      val body = functionBody(ir, "test_convert")
      assert(body.contains("trunc i32 %0 to i8"), body)
      List("byte", "unused", "test_byte", "test_unused").foreach { name =>
        assert(!ir.contains(s"@$name("), ir)
      }
      assemble(ir)
    }
  }

  test("public templates use declared signatures and direct calls still expand") {
    val source = """
      pub fn byte(n: Int): Int8 = @metal:tpl["trunc i32 %operand to i8", name="metadata"];;
      pub fn subtract(a: Int, u: Unit, b: Int): Int =
        @metal:tpl["sub %type %operand1, %operand2"];
      ;
      pub fn answer(): Int = @metal:tpl["add i32 40, 2"];;
      fn host_record(n: Int): Unit = @metal:ffi;;
      pub fn record(u: Unit, n: Int): Unit = @metal:tpl["call void @host_record(i32 %operand)"];;
      pub fn convert(n: Int): Int8 = byte n;;
    """
    compileAndGenerate(source).flatMap { ir =>
      List(
        "define i8 @test_byte(i32 %0)",
        "define i32 @test_subtract(i32 %0, i32 %1)",
        "define i32 @test_answer()",
        "define void @test_record(i32 %0)"
      ).foreach(signature => assert(ir.contains(signature), ir))
      assert(functionBody(ir, "test_byte").contains("trunc i32 %0 to i8"), ir)
      assert(functionBody(ir, "test_subtract").contains("sub i32 %0, %1"), ir)
      assert(functionBody(ir, "test_answer").contains("add i32 40, 2"), ir)
      val record = functionBody(ir, "test_record")
      assert(record.contains("call void @host_record(i32 %0)"), record)
      assert(record.contains("ret void"), record)
      assert(ir.contains("declare void @host_record(i32)"), ir)
      assert(!ir.contains("@metadata("), ir)
      val direct = functionBody(ir, "test_convert")
      assert(direct.contains("trunc i32 %0 to i8"), direct)
      assert(!direct.contains("call "), direct)
      assemble(ir)
    }
  }

  test("private and public template values have closure entries with an environment parameter") {
    val source = """
      priv fn increment(n: Int): Int = @metal:tpl["add i32 %operand, 1"];;
      pub fn decrement(n: Int): Int = @metal:tpl["sub i32 %operand, 1"];;
      pub fn give_private(): Int -> Int = increment;;
      pub fn give_public(): Int -> Int = decrement;;
    """
    compileAndGenerate(source).flatMap { ir =>
      List("private" -> "add", "public" -> "sub").foreach { case (visibility, instruction) =>
        val producer = functionBody(ir, s"test_give_$visibility")
        val entry    = uniqueMatch(producer, nullEnvironmentClosure).group("entry")
        val body     = functionBodyMatching(ir, s"${Regex.quote(entry)}\\(i32 %0, ptr %1\\).*")
        assert(body.contains(s"$instruction i32 %0, 1"), body)
        assert(!body.contains("call "), body)
      }
      assert(!ir.contains("@increment("), ir)
      assert(!ir.contains("@decrement("), ir)
      assemble(ir)
    }
  }

  test("numbered placeholders preserve their full index in exports and direct calls") {
    val source = numberedTemplate + """
      pub fn calculate(): Int = distance 11 22 33 44 55 66 77 88 99 123;;
    """
    compileAndGenerate(source).flatMap { ir =>
      val exported = functionBody(ir, "test_distance")
      assert(exported.contains("sub i32 %9, %0"), exported)
      val direct = functionBody(ir, "test_calculate")
      assert(direct.contains("sub i32 123, 11"), direct)
      assert(!direct.contains("call "), direct)
      assemble(ir)
    }
  }

  for visibility <- List("pub", "priv") do
    test(s"$visibility template cleanup expands without an external declaration") {
      compileAndGenerate(cleanupTemplate(visibility)).flatMap { ir =>
        val name = s"release_$visibility"
        assert(!ir.contains(s"@$name("), ir)
        assert(!ir.contains(s"declare void @test_$name("), ir)
        assertEquals(ir.contains(s"define void @test_$name("), visibility == "pub", ir)

        val producer = functionBody(ir, s"test_make_$visibility")
        val cleanup  = functionBody(ir, storedDestructor(producer))
        assert(cleanup.contains("store i8 0, ptr"), cleanup)
        assert(cleanup.indexOf("store i8 0, ptr") < cleanup.indexOf("@mml_free_raw"), cleanup)
        assemble(ir)
      }
    }

  for optimization <- List(0, 3) do
    test(
      s"separate C caller uses otherwise-unused public templates from a library at O$optimization"
    ) {
      val source = numberedTemplate + cleanupTemplate("pub") + cleanupTemplate("priv") + """
        pub fn subtract(a: Int, u: Unit, b: Int): Int =
          @metal:tpl["sub %type %operand1, %operand2"];
        ;
        pub fn answer(): Int = @metal:tpl["add i32 40, 2"];;
        fn record_host(n: Int): Unit = @metal:ffi[name="host_record"];;
        pub fn record(u: Unit, n: Int): Unit =
          @metal:tpl["call void @host_record(i32 %operand)"];
        ;
        fn double(n: Int): Int = @metal:tpl["mul i32 %operand, 2"];;
        fn apply(f: Int -> Int, n: Int): Int = f n;;
        pub fn via_value(n: Int): Int = apply double n;;
      """
      val caller = """
        #include <stdint.h>
        extern int32_t exports_subtract(int32_t a, int32_t b);
        extern int32_t exports_answer(void);
        extern void exports_record(int32_t n);
        extern int32_t exports_via_value(int32_t n);
        extern int32_t exports_distance(int32_t, int32_t, int32_t, int32_t, int32_t,
                                        int32_t, int32_t, int32_t, int32_t, int32_t);
        extern void exports_dispose_pub(void *p);
        extern void exports_dispose_priv(void *p);
        extern void exports_release_pub(void *p);
        static int32_t recorded = 0;
        void host_record(int32_t n) { recorded = n; }
        int main(void) {
          if (exports_subtract(50, 8) != 42) return 1;
          if (exports_answer() != 42) return 2;
          exports_record(37);
          if (recorded != 37) return 3;
          if (exports_via_value(21) != 42) return 4;
          if (exports_distance(11,22,33,44,55,66,77,88,99,123) != 112) return 5;
          unsigned char public_token = 7, private_token = 9, exported_token = 11;
          exports_dispose_pub(&public_token);
          if (public_token != 0) return 6;
          exports_dispose_priv(&private_token);
          if (private_token != 0) return 7;
          exports_release_pub(&exported_token);
          if (exported_token != 0) return 8;
          return 0;
        }
      """
      withDirectory { directory =>
        val config = CompilerConfig.default.copy(
          mode      = CompilationMode.Library,
          outputDir = directory,
          optLevel  = optimization
        )
        for
          ir <- compileAndGenerate(source, "Exports", config)
          triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing native target")))
          target <- ClangTarget
            .resolve(directory, LlvmToolchain.clangFlags(config, triple))
            .map(_.fold(error => fail(error.message), identity))
          input <- IO.blocking(Files.writeString(directory.resolve("exports.ll"), ir))
          result <- LlvmToolchain.compile(input, config, triple.some, target)
          _ = assertEquals(result._1, Right(0))
          driver <- IO.blocking(Files.writeString(directory.resolve("caller.c"), caller))
          binary = directory.resolve("caller")
          _ <- commandNotFailed(
            List(target.executable.toString, "-fuse-ld=lld") ++ target.flags ++ List(
              driver.toString,
              directory.resolve("target/exports.o").toString,
              directory.resolve(s"target/mml_runtime-$triple.o").toString,
              "-lm",
              "-o",
              binary.toString
            ),
            directory
          )
          _ <- programExits(List(binary.toString), directory, expectedCode = 0)
        yield ()
      }
    }
