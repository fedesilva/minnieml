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

  test("cross-target default emission includes Clang CPU and feature attributes") {
    val config = CompilerConfig.default.copy(targetTriple = "x86_64-unknown-linux-gnu".some)
    compileAndGenerate("fn value(): Int = 42;;", config = config).map { ir =>
      val groups = ir.linesIterator.filter(_.startsWith("attributes #")).toList
      assertEquals(groups.size, 2)
      groups.foreach { group =>
        assert(group.contains("\"target-cpu\"=\"x86-64\""), group)
        assert(group.contains("\"target-features\"="), group)
      }
      assert("attributes\\s+#\\d+\\s*=\\s*\\{\\s*\\}".r.findFirstIn(ir).isEmpty, ir)
    }
  }

  test("global initializers share the target attributes of ordinary functions") {
    val source = """fn seed(): Int = 41;;
                   |let answer: Int = seed();
                   |fn value(): Int = answer;
                   |;
                   |""".stripMargin
    val config = CompilerConfig.default.copy(targetTriple = "x86_64-unknown-linux-gnu".some)
    compileAndGenerate(source, config = config).map { ir =>
      assert(ir.contains("@llvm.global_ctors"), ir)
      val definitions = ir.linesIterator.filter(_.startsWith("define ")).toList
      assert(definitions.size >= 3, ir)
      definitions.foreach { definition =>
        assert(definition.endsWith(" #0 {") || definition.endsWith(" #1 {"), definition)
      }
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
