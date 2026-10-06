package mml.mmlclib.codegen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class EntryPointTests extends BaseEffFunSuite:

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-int32-"))
      .bracket(use) { directory =>
        Resource.fromAutoCloseable(IO.blocking(Files.walk(directory))).use { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
        }
      }

  private def targetAt(directory: Path, config: CompilerConfig): IO[(String, ClangTarget)] =
    for
      triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing target")))
      target <- ClangTarget
        .resolve(directory, LlvmToolchain.clangFlags(config, triple))
        .map(_.fold(error => fail(error.message), identity))
    yield (triple, target)

  List("Int", "Int32", "Int64", "Unit").foreach { resultType =>
    test(s"$resultType main preserves its exit status") {
      withDirectory { directory =>
        val body = resultType match
          case "Unit" => "()"
          case "Int64" => "int_to_int64 37"
          case _ => "37"
        val config = CompilerConfig.default.copy(
          mode       = CompilationMode.Exe,
          outputDir  = directory,
          outputName = directory.resolve("program").toString.some
        )
        for
          ir <- compileAndGenerate(s"pub fn main(): $resultType = $body;;", "Entry", config)
          pair <- targetAt(directory, config)
          (triple, target) = pair
          path <- IO.blocking(Files.writeString(directory.resolve("Entry.ll"), ir))
          result <- LlvmToolchain.compile(path, config, triple.some, target)
          _ = assertEquals(result._1, Right(0))
          _ <- programExits(
            List(directory.resolve("program").toString),
            directory,
            expectedCode = if resultType == "Unit" then 0 else 37
          )
        yield ()
      }
    }
  }
