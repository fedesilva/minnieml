package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files

class TargetCpuTests extends BaseEffFunSuite:
  test("host CPU discovery preserves CPU names and ignores unavailable CPU reports") {
    IO.blocking(Files.createTempDirectory("mml-target-cpu"))
      .bracket { dir =>
        List(
          "(unknown)" -> none[String],
          "unknown" -> none[String],
          "" -> none[String],
          "apple-m5" -> "apple-m5".some,
          "znver4" -> "znver4".some
        ).traverse_ { case (reported, expected) =>
          IO.blocking {
            Files.writeString(dir.resolve("llvm-info"), s"  Host CPU: $reported\n")
            LlvmToolchain.readHostCpu(dir.toString)
          }.map(cpu => assertEquals(cpu, expected))
        }
      } { dir =>
        IO.blocking {
          Files.deleteIfExists(dir.resolve("llvm-info"))
          Files.deleteIfExists(dir)
        }.void
      }
  }

  test("default CPU emission contains no empty attribute definitions") {
    val config = CompilerConfig.default.copy(targetTriple = "x86_64-unknown-linux-gnu".some)
    compileAndGenerate("fn value(): Int = 42;;", config = config).map { ir =>
      assert(!ir.contains("target-cpu"), ir)
      assert("attributes\\s+#\\d+\\s*=\\s*\\{\\s*\\}".r.findFirstIn(ir).isEmpty, ir)
    }
  }

  test("runtime cache separates CPU selections and legacy entries for bitcode and objects") {
    List("bc", "o").foreach { extension =>
      val triple     = "aarch64-apple-macosx"
      val selections = List(Nil, List("-mcpu=apple-m5"), List("-mcpu=apple-m1"))
      val filenames =
        selections.map(flags => LlvmToolchain.runtimeCacheFilename(triple, 3, flags, extension))
      assertEquals(filenames.distinct.size, selections.size)
      assert(!filenames.contains(s"mml_runtime-$triple.$extension"))
      assertEquals(
        LlvmToolchain.runtimeCacheFilename(triple, 3, selections(1), extension),
        filenames(1)
      )
    }
  }

  test("runtime cache includes optimization, sanitizer, stack and x86 CPU flags") {
    val triple = "x86_64-unknown-linux-gnu"
    val base   = LlvmToolchain.runtimeCacheFilename(triple, 3, List("-march=x86-64"), "bc")
    val alternatives = List(
      LlvmToolchain.runtimeCacheFilename(triple, 2, List("-march=x86-64"), "bc"),
      LlvmToolchain.runtimeCacheFilename(triple, 3, List("-march=znver4"), "bc"),
      LlvmToolchain.runtimeCacheFilename(
        triple,
        3,
        List("-march=x86-64", "-fsanitize=address"),
        "bc"
      ),
      LlvmToolchain.runtimeCacheFilename(
        triple,
        3,
        List("-march=x86-64", "-fno-stack-check"),
        "bc"
      )
    )
    assert(!alternatives.contains(base))
    assertEquals(alternatives.distinct.size, alternatives.size)
  }
