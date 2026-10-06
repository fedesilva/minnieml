package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.ast.LinkEntry
import mml.mmlclib.compiler.{CompilerConfig, IngestStage}
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.sys.process.Process

class LinkingTests extends BaseEffFunSuite:

  private val ir = """declare i64 @link_fixture(i64)
                     |define i32 @main() {
                     |  %answer = call i64 @link_fixture(i64 35)
                     |  %exit = trunc i64 %answer to i32
                     |  ret i32 %exit
                     |}
                     |""".stripMargin

  private case class Fixture(directory: Path, triple: String, target: ClangTarget, source: Path):

    def compile(
      names:   List[String],
      timings: Boolean         = false,
      mode:    CompilationMode = CompilationMode.Exe
    ): IO[(Either[LlvmCompilationError, Int], Vector[PipelineTiming])] =
      val config = CompilerConfig.default.copy(
        mode        = mode,
        outputDir   = directory,
        outputName  = directory.resolve("program").toString.some,
        showTimings = timings
      )
      LlvmToolchain.compile(source, config, triple.some, target, entries(names))

    def calls: List[List[String]] =
      Files
        .readString(directory.resolve("calls"))
        .split("BEGIN\n")
        .toList
        .filter(_.nonEmpty)
        .map(_.linesIterator.toList)

    def run: IO[Int] = IO.blocking {
      Process(
        List(directory.resolve("program").toString),
        directory.toFile,
        "LD_LIBRARY_PATH" -> directory.toString
      ).!
    }

  private def entries(names: List[String]): List[LinkEntry] =
    val header = names.map(name => s"\"$name\"").mkString("@link[", ", ", "];")
    IngestStage.fromSource(header, "Inputs").linkEntries

  private def shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  private def withFixture(shared: Boolean = false)(use: Fixture => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-linking-"))
      .bracket { directory =>
        for
          triple <- LlvmToolchain.queryLocalTriple.map(
            _.getOrElse(fail("Clang target unavailable"))
          )
          target <- ClangTarget
            .resolve(directory, LlvmToolchain.clangFlags(CompilerConfig.default, triple))
            .map(_.fold(error => fail(error.message), identity))
          source <- IO.blocking {
            Files.writeString(
              directory.resolve("library.c"),
              "long long link_fixture(long long x) { return x + 7; }\n"
            )
            Files.writeString(directory.resolve("LinkTest.ll"), ir)
          }
          _ <- commandNotFailed(
            List(
              target.executable.toString,
              "-fPIC",
              "-c",
              directory.resolve("library.c").toString,
              "-o",
              directory.resolve("library.o").toString
            ),
            directory
          )
          _ <- commandNotFailed(
            List(
              "llvm-ar",
              "rcs",
              directory.resolve("libmmlfixture.a").toString,
              directory.resolve("library.o").toString
            ),
            directory
          )
          _ <- commandNotFailed(
            List(
              "llvm-ar",
              "rcs",
              directory.resolve("libmmlother.a").toString,
              directory.resolve("library.o").toString
            ),
            directory
          )
          _ <-
            if shared then
              val darwin = triple.contains("apple")
              commandNotFailed(
                List(
                  target.executable.toString,
                  if darwin then "-dynamiclib" else "-shared",
                  directory.resolve("library.o").toString,
                  "-o",
                  directory
                    .resolve(if darwin then "libmmlshared.dylib" else "libmmlshared.so")
                    .toString
                ),
                directory
              )
            else IO.unit
          wrapper <- IO.blocking {
            val script =
              s"""#!/bin/sh
                 |printf 'BEGIN\n' >> ${shellQuote(directory.resolve("calls").toString)}
                 |printf '%s\n' "$$@" >> ${shellQuote(directory.resolve("calls").toString)}
                 |export LIBRARY_PATH=${shellQuote(directory.toString)}
                 |exec ${shellQuote(target.executable.toString)} "$$@"
                 |""".stripMargin
            val path = Files.writeString(directory.resolve("clang-fixture"), script)
            assert(path.toFile.setExecutable(true))
            path
          }
          _ <- use(Fixture(directory, triple, target.copy(executable = wrapper), source))
        yield ()
      } { directory =>
        IO.blocking(Files.walk(directory))
          .bracket { paths =>
            IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))
          }(paths => IO.blocking(paths.close()))
      }

  test("static library calls execute with ordered repeated inputs, with and without timings") {
    withFixture() { fixture =>
      List(false, true).traverse_ { enabled =>
        for
          result <- fixture.compile(List("mmlfixture", "mmlother", "mmlfixture"), timings = enabled)
          _ = assertEquals(result._1, Right(0))
          _ = assertEquals(result._2.nonEmpty, enabled)
          exit <- fixture.run
          _ = assertEquals(exit, 42)
        yield ()
      } *> IO.blocking {
        val links = fixture.calls.filter(_.contains("-lmmlfixture"))
        assertEquals(links.size, 2)
        links.foreach { arguments =>
          assertEquals(
            arguments.filter(_.startsWith("-l")),
            List("-lmmlfixture", "-lmmlother", "-lmmlfixture")
          )
          assert(arguments.indexWhere(_.endsWith("LinkTest.s")) < arguments.indexOf("-lmmlfixture"))
          assert(!arguments.contains("-c"))
          assert(!arguments.contains("-emit-llvm"))
        }
      }
    }
  }

  test("shared library calls use normal linker discovery") {
    withFixture(shared = true) { fixture =>
      for
        result <- fixture.compile(List("mmlshared"))
        _ = assertEquals(result._1, Right(0))
        exit <- fixture.run
        _ = assertEquals(exit, 42)
      yield ()
    }
  }

  test("missing libraries and unresolved symbols fail executable linking") {
    withFixture() { fixture =>
      for
        missing <- fixture.compile(List("mml_missing_fixture"))
        _ = assert(
          missing._1.left.exists {
            case LlvmCompilationError.CommandExecutionError(_, diagnostic, _) =>
              diagnostic.contains("mml_missing_fixture")
            case _ => false
          },
          missing._1.toString
        )
        _ <- IO.blocking(
          Files.writeString(fixture.source, ir.replace("link_fixture", "missing_symbol"))
        )
        unresolved <- fixture.compile(List("mmlfixture"))
        _ = assert(
          unresolved._1.left.exists {
            case LlvmCompilationError.CommandExecutionError(_, diagnostic, _) =>
              diagnostic.contains("missing_symbol")
            case _ => false
          },
          unresolved._1.toString
        )
      yield ()
    }
  }

  test("object emission discards missing libraries and retains the separate runtime object") {
    withFixture() { fixture =>
      fixture.compile(List("mml_missing_fixture"), mode = CompilationMode.Library).map { result =>
        assertEquals(result._1, Right(0))
        assert(Files.isRegularFile(fixture.directory.resolve("program.o")))
        assert(
          Files.isRegularFile(fixture.directory.resolve(s"target/mml_runtime-${fixture.triple}.o"))
        )
        assert(!fixture.calls.flatten.contains("-lmml_missing_fixture"))
      }
    }
  }
