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
    """target datalayout = "e-i64:64"
      |target triple = "x86_64-unknown-linux-gnu"
      |define i32 @mml_target_probe(i32 %x) #7 {
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
      TargetAttributes(
        "x86-64".some,
        "+sse2,-avx".some,
        triple = "x86_64-unknown-linux-gnu".some
      ).some
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

  test("CPU selection is portable by default and explicit overrides reach Clang") {
    val local = CompilerConfig.default
    val arm   = "arm64-apple-macosx15.0"
    val x86   = "x86_64-unknown-linux-gnu"
    List(arm, x86).foreach { triple =>
      List(local, local.copy(targetTriple = triple.some)).foreach { config =>
        val flags = LlvmToolchain.clangFlags(config, triple)
        assert(!flags.exists(flag => flag.startsWith("-mcpu=") || flag.startsWith("-march=")))
      }
    }
    List("native", "apple-m3").foreach { cpu =>
      assert(
        LlvmToolchain
          .clangFlags(local.copy(targetCpu = cpu.some), arm)
          .contains(s"-mcpu=$cpu")
      )
    }
    assert(
      LlvmToolchain
        .clangFlags(local.copy(targetCpu = "znver4".some), x86)
        .contains("-march=znver4")
    )
    assert(
      LlvmToolchain
        .clangFlags(local.copy(targetCpu = "native".some), x86)
        .contains("-march=native")
    )
  }

  test("explicit target triples preserve deployment versions and unversioned targets") {
    withProbe { dir =>
      List(
        "arm64-apple-macosx",
        "aarch64-apple-macosx",
        "arm64-apple-macos",
        "arm64-apple-darwin",
        "arm64-apple-macosx-unknown",
        "arm64-apple-macosx14.0",
        "arm64-apple-macosx14.0-unknown",
        "arm64-apple-macosx26.0",
        "arm64-apple-darwin24.0",
        "x86_64-apple-macosx15.0",
        "x86_64-unknown-linux-gnu",
        "aarch64-unknown-linux-gnu"
      ).traverse_ { triple =>
        LlvmToolchain.resolveTargetTriple(triple.some, dir.toString).map { result =>
          assertEquals(result, triple.asRight[LlvmCompilationError])
        }
      }
    }
  }

  test("cached local macOS triples leave deployment version selection to Clang") {
    withProbe { dir =>
      List(
        "arm64-apple-macosx" -> "arm64-apple-macosx",
        "arm64-apple-darwin25.6.0" -> "arm64-apple-macosx",
        "arm64-apple-macosx15.0" -> "arm64-apple-macosx",
        "arm64-apple-macosx26.0" -> "arm64-apple-macosx",
        "aarch64-apple-macosx15.0" -> "aarch64-apple-macosx",
        "x86_64-apple-darwin25.6.0" -> "x86_64-apple-macosx",
        "x86_64-apple-macosx15.0" -> "x86_64-apple-macosx",
        "x86_64-unknown-linux-gnu" -> "x86_64-unknown-linux-gnu",
        "aarch64-unknown-linux-gnu" -> "aarch64-unknown-linux-gnu"
      ).traverse_ { case (cached, expected) =>
        for
          _ <- IO.blocking(Files.writeString(dir.resolve("local-target-triple"), cached))
          result <- LlvmToolchain.resolveTargetTriple(none, dir.toString)
          _ = assertEquals(result, expected.asRight[LlvmCompilationError])
        yield ()
      }
    }
  }

  test("native CPU accepts architecture aliases and rejects cross-architecture targets") {
    List(
      "arm64-apple-macosx15.0" -> "aarch64-apple-macosx15.0",
      "aarch64-unknown-linux-gnu" -> "arm64-apple-macosx15.0",
      "x86_64-unknown-linux-gnu" -> "amd64-unknown-linux-gnu"
    ).foreach { case (target, host) =>
      assertEquals(LlvmToolchain.validateNativeCpu(target, host), ().asRight[LlvmCompilationError])
    }
    List(
      "x86_64-unknown-linux-gnu" -> "arm64-apple-macosx15.0",
      "arm64-apple-macosx15.0" -> "x86_64-unknown-linux-gnu"
    ).foreach { case (target, host) =>
      LlvmToolchain.validateNativeCpu(target, host) match
        case Left(LlvmCompilationError.TargetResolutionError(message)) =>
          assert(message.contains("--cpu native"), message)
          assert(message.contains(target) && message.contains(host), message)
        case other => fail(s"Expected native CPU diagnostic, got $other")
    }
  }

  test("target resolution enforces native CPU compatibility before the target probe") {
    withProbe { dir =>
      for
        host <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Clang is required")))
        incompatible =
          if host.startsWith("x86_64-") then "arm64-apple-macosx15.0"
          else "x86_64-unknown-linux-gnu"
        rejected <- LlvmToolchain.resolveTargetTriple(
          incompatible.some,
          dir.toString,
          "native".some
        )
        _ = assert(rejected.isLeft, rejected.toString)
        accepted <- LlvmToolchain.resolveTargetTriple(host.some, dir.toString, "native".some)
        _ = assertEquals(accepted, host.asRight[LlvmCompilationError])
      yield ()
    }
  }
