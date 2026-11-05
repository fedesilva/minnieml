package mml.mmlclib.codegen

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.diagnosticMessage

import java.nio.file.{Files, Path}

class CompilerRunTests extends BaseEffFunSuite:

  private def hostTriple(outputDir: Path): IO[String] =
    LlvmToolchain
      .resolveTargetTriple(none, outputDir.toString)
      .map(_.fold(error => fail(diagnosticMessage(error)), identity))

  test("run uses an absolute custom output with spaces and an explicit host target"):
    withSourceFixture("pub fn main(): Int = 37;;", "RunFixture.mml") { (path, initialConfig) =>
      val output = path.getParent.resolve("output space/program file")

      for
        triple <- hostTriple(initialConfig.outputDir)
        config = initialConfig.copy(
          mode         = CompilationMode.Exe,
          outputName   = output.toString.some,
          targetTriple = triple.some
        )
        exit <- CompilerApi.processRun(path, config)
        _ = assertEquals(exit, ExitCode(37))
        _ = assert(Files.isExecutable(output))
      yield ()
    }

  test("run resolves a nested relative custom output from the process working directory"):
    withSourceFixture("pub fn main(): Int = 37;;", "RunFixture.mml") { (path, initialConfig) =>
      val output   = path.getParent.resolve("relative output/program")
      val relative = Path.of("").toAbsolutePath.relativize(output)
      val config = initialConfig.copy(
        mode       = CompilationMode.Exe,
        outputName = relative.toString.some
      )

      CompilerApi.processRun(path, config).map { exit =>
        assertEquals(exit, ExitCode(37))
        assert(Files.isExecutable(output))
      }
    }

  test("run executes a custom basename in the process working directory"):
    withSourceFixture("pub fn main(): Int = 37;;", "RunFixture.mml") { (path, initialConfig) =>
      IO.blocking(Files.createTempFile(Path.of("").toAbsolutePath, ".mml-run-", ".bin"))
        .bracket { output =>
          val config = initialConfig.copy(
            mode       = CompilationMode.Exe,
            outputName = output.getFileName.toString.some
          )

          CompilerApi.processRun(path, config).map(exit => assertEquals(exit, ExitCode(37)))
        }(output => IO.blocking(Files.deleteIfExists(output)).void)
    }

  test("run selects the custom output when a stale default executable exists"):
    withSourceFixture("pub fn main(): Int = 37;;", "RunFixture.mml") { (path, initialConfig) =>
      val defaultConfig = initialConfig.copy(mode = CompilationMode.Exe)
      val customConfig = defaultConfig.copy(
        outputName = path.getParent.resolve("custom-program").toString.some
      )

      for
        originalExit <- CompilerApi.processRun(path, defaultConfig)
        _ = assertEquals(originalExit, ExitCode(37))
        _ <- IO.blocking(Files.writeString(path, "pub fn main(): Int = 43;;"))
        customExit <- CompilerApi.processRun(path, customConfig)
        _ = assertEquals(customExit, ExitCode(43))
      yield ()
    }

  for explicitTarget <- List(false, true) do
    val targetKind = if explicitTarget then "explicit" else "implicit"

    test(s"run preserves default output naming with an $targetKind host target"):
      withSourceFixture("pub fn main(): Int = 37;;", "RunFixture.mml") { (path, initialConfig) =>
        val target =
          if explicitTarget then hostTriple(initialConfig.outputDir).map(_.some)
          else IO.pure(none[String])

        for
          triple <- target
          config = initialConfig.copy(mode = CompilationMode.Exe, targetTriple = triple)
          exit <- CompilerApi.processRun(path, config)
          outputName = triple.fold("runfixture")(value => s"runfixture-$value")
          _          = assertEquals(exit, ExitCode(37))
          _ = assert(Files.isExecutable(config.outputDir.resolve("target").resolve(outputName)))
        yield ()
      }
