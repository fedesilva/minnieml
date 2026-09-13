package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ClangTargetTests extends BaseEffFunSuite:

  private val probeIr =
    """define i32 @mml_target_probe(i32 %x) #7 {
      |  ret i32 %x
      |}
      |attributes #0 = { "target-cpu"="wrong" }
      |attributes #7 = { nounwind "target-cpu"="x86-64" "target-features"="+sse2,-avx" }
      |""".stripMargin

  private def withProbe[A](use: Path => IO[A]): IO[A] =
    IO.blocking {
      val dir = Files.createTempDirectory("mml clang target ")
      val script = """#!/bin/sh
                     |cd "$(dirname "$0")"
                     |cat > input.c
                     |printf '%s\n' "$@" > arguments
                     |echo probe >> calls
                     |if test -f fail; then echo 'unsupported target' >&2; exit 1; fi
                     |cat response.ll
                     |""".stripMargin
      val executable = Files.writeString(dir.resolve("clang"), script)
      assert(executable.toFile.setExecutable(true))
      Files.writeString(dir.resolve("response.ll"), probeIr)
      dir
    }.bracket(use) { dir =>
      IO.blocking(Files.walk(dir))
        .bracket { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))
        }(paths => IO.blocking(paths.close()))
    }

  private def resolve(dir: Path, flags: List[String] = Nil): IO[ClangTarget] =
    ClangTarget.resolveWithClang(dir.resolve("clang"), dir, flags).map {
      case Right(target) => target
      case Left(error) => fail(error.message)
    }

  test("probe attributes follow the probe function's group and preserve disabled features") {
    assertEquals(
      ClangTarget.parseAttributes(probeIr),
      TargetAttributes("x86-64".some, "+sse2,-avx".some).some
    )
    List(
      "",
      probeIr.replace("#7 {", "#8 {"),
      probeIr.replace("+sse2,-avx", "bad\nfeatures"),
      probeIr.replace("\"+sse2,-avx\"", "unquoted")
    )
      .foreach(ir => assertEquals(ClangTarget.parseAttributes(ir), none[TargetAttributes]))
  }

  test("warm cache skips Clang and corrupt or missing responses are regenerated") {
    withProbe { dir =>
      for
        first <- resolve(dir)
        _ <- IO.blocking(Files.writeString(dir.resolve("fail"), ""))
        second <- resolve(dir)
        _ = assertEquals(second, first)
        _ <- IO.blocking {
          assertEquals(Files.readAllLines(dir.resolve("calls")).size(), 1)
          Files.delete(dir.resolve("fail"))
          Files.writeString(dir.resolve(s"toolchain/target-${first.cacheKey}.ll"), "broken")
        }
        repaired <- resolve(dir)
        _ = assertEquals(repaired, first)
        _ <- IO.blocking(Files.delete(dir.resolve(s"toolchain/target-${first.cacheKey}.ll")))
        _ <- resolve(dir)
        _ <- IO.blocking(assertEquals(Files.readAllLines(dir.resolve("calls")).size(), 3))
      yield ()
    }
  }

  test("cache separates target flags, Clang upgrades, and resolved executable paths") {
    withProbe { dir =>
      val flags = List("-target", "x86_64-unknown-linux-gnu", "-march=x86-64")
      for
        first <- resolve(dir, flags)
        otherTarget <- resolve(dir, List("-target", "aarch64-unknown-linux-gnu"))
        otherCpu <- resolve(dir, flags.init :+ "-march=znver4")
        _ <- IO.blocking {
          assertEquals(
            Files.readAllLines(dir.resolve("arguments")).asScala.take(flags.size).toList,
            flags.init :+ "-march=znver4"
          )
          val path = dir.resolve("clang")
          Files.setLastModifiedTime(
            path,
            FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis + 1000)
          )
        }
        upgraded <- resolve(dir, flags)
        _ <- IO.blocking {
          Files.copy(dir.resolve("clang"), dir.resolve("clang-new"))
          Files.delete(dir.resolve("clang"))
          Files.createSymbolicLink(dir.resolve("clang"), dir.resolve("clang-new"))
        }
        relinked <- resolve(dir, flags)
        _ = assertEquals(
          List(first, otherTarget, otherCpu, upgraded, relinked).map(_.cacheKey).distinct.size,
          5
        )
        _ = assertEquals(relinked.executable, dir.resolve("clang-new").toRealPath())
      yield ()
    }
  }

  test("failed probes retain diagnostics and do not populate the cache") {
    withProbe { dir =>
      for
        _ <- IO.blocking(Files.writeString(dir.resolve("fail"), ""))
        result <- ClangTarget.resolveWithClang(dir.resolve("clang"), dir, Nil)
        _ = result match
          case Left(LlvmCompilationError.CommandExecutionError(_, message, code)) =>
            assertEquals(code, 1)
            assert(message.contains("unsupported target"), message)
          case other => fail(s"Expected Clang diagnostic, got $other")
        _ <- IO.blocking {
          assert(!Files.exists(dir.resolve("toolchain")))
          Files.delete(dir.resolve("fail"))
        }
        _ <- resolve(dir)
      yield ()
    }
  }

  test("native and explicit cross targets select their own Clang CPU flags") {
    val local = CompilerConfig.default
    val cross = local.copy(targetTriple = "x86_64-unknown-linux-gnu".some)
    assert(LlvmToolchain.clangFlags(local, "aarch64-apple-macosx").contains("-mcpu=native"))
    assert(!LlvmToolchain.clangFlags(cross, cross.targetTriple.get).exists(_.startsWith("-march=")))
    assert(
      LlvmToolchain
        .clangFlags(cross.copy(targetCpu = "znver4".some), cross.targetTriple.get)
        .contains("-march=znver4")
    )
  }
