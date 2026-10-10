package mml.mmlclib.codegen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class MixedOwnershipCodegenTests extends BaseEffFunSuite:

  private val fixtures =
    List("mixed-ownership-transfers", "mixed-ownership-captures", "mixed-ownership-invocation")

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-mixed-ownership-"))
      .bracket(use) { directory =>
        Resource.fromAutoCloseable(IO.blocking(Files.walk(directory))).use { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
        }
      }

  private def execute(source: String, optimization: Int): IO[Unit] =
    withDirectory { directory =>
      val binary = directory.resolve("program")
      val config = CompilerConfig.default.copy(
        mode       = CompilationMode.Exe,
        outputDir  = directory,
        outputName = binary.toString.some,
        optLevel   = optimization,
        asan       = true
      )

      for
        ir <- compileAndGenerate(source, "MixedOwnership", config)
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing target")))
        target <- ClangTarget
          .resolve(directory, LlvmToolchain.clangFlags(config, triple))
          .map(_.fold(error => fail(error.message), identity))
        path <- IO.blocking(Files.writeString(directory.resolve("MixedOwnership.ll"), ir))
        _ <- commandNotFailed(List("llvm-as", path.toString, "-o", "/dev/null"), directory)
        compiled <- LlvmToolchain.compile(path, config, triple.some, target)
        _ = assertEquals(compiled._1, Right(0))
        _ <- List(0, 1, 2, 3).traverse_ { count =>
          val command = List(
            "env",
            "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1",
            binary.toString
          ) ++ List.fill(count)("argument")
          programExits(command, directory, expectedCode = 0)
        }
      yield ()
    }

  for fixture <- fixtures; optimization <- List(0, 3) do
    test(s"$fixture preserves ownership and result values under ASan/LSan at O$optimization") {
      IO.blocking(Files.readString(Path.of(s"tests/mem/$fixture.mml")))
        .flatMap(source => execute(source, optimization))
    }
