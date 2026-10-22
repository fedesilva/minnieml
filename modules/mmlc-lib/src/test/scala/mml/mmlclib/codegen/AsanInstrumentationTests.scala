package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class AsanInstrumentationTests extends BaseEffFunSuite:

  private val source = """
    fn make(~text: String): Unit -> Int = ~{ text.length; };;
    pub fn main(): Int =
      let closure = make (int_to_str 42);
      closure () - 2;
    ;
  """

  private val sanitizerEnvironment =
    List("env", "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=0")

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-asan"))
      .bracket(use) { directory =>
        IO.blocking(Files.walk(directory))
          .bracket { paths =>
            IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
          }(paths => IO.blocking(paths.close()))
      }

  /** Keep a real generated destructor, adding one observable read after its storage release. */
  private def readFreedEnvironment(ir: String): String =
    val release = "(?m)^  call void @mml_free_raw\\(ptr (%[A-Za-z0-9_.]+)\\)$".r
    assertEquals(release.findAllMatchIn(ir).size, 1, ir)
    release.replaceAllIn(
      ir,
      matched =>
        s"${matched.matched}\n  %asan_probe = load volatile i8, ptr ${matched.group(1)}, align 1"
    )

  private def nativeCompile(
    directory: Path,
    config:    CompilerConfig,
    corrupt:   Boolean
  ): IO[Path] =
    for
      ir <- compileAndGenerate(source, "Probe", config)
      triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing native target")))
      target <- ClangTarget
        .resolve(directory, LlvmToolchain.clangFlags(config, triple))
        .map(_.fold(error => fail(error.message), identity))
      path <- IO.blocking {
        Files.writeString(
          directory.resolve("probe.ll"),
          if corrupt then readFreedEnvironment(ir) else ir
        )
      }
      result <- LlvmToolchain.compile(path, config, triple.some, target)
      _      = assertEquals(result._1, Right(0))
      binary = directory.resolve("program")
      _ <-
        if config.mode == CompilationMode.Library then
          IO.blocking {
            Files.writeString(
              directory.resolve("driver.c"),
              "extern int probe_main(void); int main(void) { return probe_main(); }\n"
            )
          } *> commandNotFailed(
            List(target.executable.toString, "-fuse-ld=lld") ++ target.flags ++ List(
              directory.resolve("driver.c").toString,
              directory.resolve("target/probe.o").toString,
              directory.resolve(s"target/mml_runtime-$triple.o").toString,
              "-lm",
              "-o",
              binary.toString
            ),
            directory
          )
        else IO.unit
    yield binary

  for
    optimization <- List(0, 3)
    mode <- List(CompilationMode.Exe, CompilationMode.Library)
    corrupt <- List(false, true)
  do
    test(s"$mode at O$optimization checks generated closure accesses, invalid=$corrupt") {
      withDirectory { directory =>
        val config = CompilerConfig.default.copy(
          mode      = mode,
          outputDir = directory,
          outputName =
            Option.when(mode == CompilationMode.Exe)(directory.resolve("program").toString),
          optLevel = optimization,
          asan     = true
        )
        nativeCompile(directory, config, corrupt).flatMap { binary =>
          programExits(
            sanitizerEnvironment :+ binary.toString,
            directory,
            expectedCode  = if corrupt then 1 else 0,
            expectedError = Option.when(corrupt)("ERROR: AddressSanitizer: heap-use-after-free")
          )
        }
      }
    }

  test("sanitizer selection covers every generated definition and preserves ordinary emission") {
    List(false, true).traverse_ { sanitized =>
      val config = CompilerConfig.exe("build", asan = sanitized)
      compileAndGenerate(source, "Probe", config).map { ir =>
        val groups = "(?m)^attributes (#\\d+) = \\{ (.*) \\}$".r
          .findAllMatchIn(ir)
          .map(matched => matched.group(1) -> matched.group(2))
          .toMap
        val definitions = ir.linesIterator.filter(_.startsWith("define ")).toList
        assert(definitions.nonEmpty)
        definitions.foreach { definition =>
          val group = "#\\d+".r.findFirstIn(definition).getOrElse(fail(definition))
          assertEquals(groups(group).contains("sanitize_address"), sanitized, definition)
        }
        assertEquals(groups("#1").contains("sanitize_address"), sanitized)
        assert(groups("#1").contains("inlinehint"))
      }
    }
  }

  for optimization <- List(0, 3) do
    test(s"deferred C instrumentation preserves stack-use-after-scope checks at O$optimization") {
      withDirectory { directory =>
        val binary = directory.resolve("program")
        val config = CompilerConfig.exe(
          directory.toString,
          outputName = binary.toString.some,
          optLevel   = optimization,
          asan       = true
        )
        val cSource = """
          int main(void) {
            int *volatile escaped;
            { int value = 7; escaped = &value; }
            return *escaped;
          }
        """
        val input   = directory.resolve("scope.ll")

        for
          triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing native target")))
          target <- ClangTarget
            .resolve(directory, LlvmToolchain.clangFlags(config, triple))
            .map(_.fold(error => fail(error.message), identity))
          path <- IO.blocking(Files.writeString(directory.resolve("scope.c"), cSource))
          _ <- commandNotFailed(
            List(target.executable.toString) ++ target.flags ++ List(
              "-Xclang",
              "-disable-llvm-passes",
              "-S",
              "-emit-llvm",
              path.toString,
              "-o",
              input.toString
            ),
            directory
          )
          result <- LlvmToolchain.compile(input, config, triple.some, target)
          _ = assertEquals(result._1, Right(0))
          _ <- programExits(
            sanitizerEnvironment :+ binary.toString,
            directory,
            expectedCode  = 1,
            expectedError = "ERROR: AddressSanitizer: stack-use-after-scope".some
          )
        yield ()
      }
    }

  test(
    "sanitized and ordinary builds share a directory without reusing incompatible runtime code"
  ) {
    withDirectory { directory =>
      List(true, false, true).traverse_ { sanitized =>
        val config = CompilerConfig.exe(
          directory.toString,
          outputName = directory.resolve("program").toString.some,
          optLevel   = 0,
          asan       = sanitized
        )
        nativeCompile(directory, config, corrupt = sanitized).flatMap { binary =>
          programExits(
            sanitizerEnvironment :+ binary.toString,
            directory,
            expectedCode  = if sanitized then 1 else 0,
            expectedError = Option.when(sanitized)("ERROR: AddressSanitizer: heap-use-after-free")
          )
        }
      }
    }
  }

  test("cached pre-instrumented runtime bitcode cannot suppress generated-code instrumentation") {
    withDirectory { directory =>
      val config = CompilerConfig.exe(
        directory.toString,
        outputName = directory.resolve("program").toString.some,
        optLevel   = 0,
        asan       = true
      )
      val runtimeSource =
        Path.of("modules/mmlc-lib/src/main/resources/mml_runtime.c").toAbsolutePath

      for
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing native target")))
        target <- ClangTarget
          .resolve(directory, LlvmToolchain.clangFlags(config, triple))
          .map(_.fold(error => fail(error.message), identity))
        legacyPath <- IO.blocking {
          val name = LlvmToolchain.runtimeCacheFilename(
            triple,
            config.optLevel,
            List(target.cacheKey),
            "bc",
            Files.readAllBytes(runtimeSource)
          )
          Files.createDirectories(directory.resolve("out").resolve(triple)).resolve(name)
        }
        _ <- commandNotFailed(
          List(target.executable.toString, "-emit-llvm", "-c") ++ target.flags ++
            List(runtimeSource.toString, "-o", legacyPath.toString),
          directory
        )
        binary <- nativeCompile(directory, config, corrupt = true)
        _ <- programExits(
          sanitizerEnvironment :+ binary.toString,
          directory,
          expectedCode  = 1,
          expectedError = "ERROR: AddressSanitizer: heap-use-after-free".some
        )
      yield ()
    }
  }
