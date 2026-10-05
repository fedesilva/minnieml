package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.codegen.emitter.*
import mml.mmlclib.codegen.emitter.abis.{AbiValue, NativeAbiPlan}
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** C fixtures are generated from this versioned, deterministic type matrix. */
class AggregateAbiTests extends BaseEffFunSuite:

  private case class Field(cType: String, llvmType: String, value: String)
  private case class Shape(name: String, fields: List[Field]):

    def llvmType:   String = s"%struct.$name"
    def definition: String = emitTypeDefinition(s"struct.$name", fields.map(_.llvmType))
    def constant:   String = fields.map(f => s"${f.llvmType} ${f.value}").mkString("{ ", ", ", " }")
    def field:      Field  = Field(name, llvmType, constant)

  private val scalars = List(
    Field("signed char", "i8", "-11"),
    Field("short", "i16", "-1234"),
    Field("int", "i32", "123456"),
    Field("long long", "i64", "1234567890123"),
    Field("float", "float", "1.5"),
    Field("double", "double", "-2.25"),
    Field("void *", "ptr", "null")
  )
  private val pairs = (for a <- scalars; b <- scalars yield List(a, b)).zipWithIndex
    .map((fields, i) => Shape(s"Pair$i", fields))
  private val triples = (for a <- scalars.take(6); b <- scalars.take(6); c <- scalars.take(6)
  yield List(a, b, c)).zipWithIndex.map((fields, i) => Shape(s"Triple$i", fields))
  private val special = List(
    Shape("Empty", Nil),
    Shape("Color", List.fill(4)(scalars.head)),
    Shape("ThreeBytes", List.fill(3)(scalars.head)),
    Shape("NineBytes", List.fill(9)(scalars.head)),
    Shape("Hfa4", List.fill(4)(scalars(4))),
    Shape("LargeHfa", List.fill(4)(scalars(5))),
    Shape("Large", List.fill(5)(scalars(3))),
    Shape("Half3", List.fill(3)(Field("_Float16", "half", "1.5"))),
    Shape("HalfFloat", List(Field("_Float16", "half", "1.5"), scalars(4)))
  )
  private val nested = pairs.zipWithIndex.map { (pair, i) =>
    Shape(s"Nested$i", List(scalars.head, pair.field, scalars(4)))
  } :+ Shape("NestedHfa", List(pairs(32).field, pairs(32).field))
  private val shapes      = pairs ++ triples ++ special ++ nested
  private val shapeByType = shapes.map(s => s.llvmType -> s).toMap

  private def checks(shape: Shape, prefix: String): List[String] =
    shape.fields.zipWithIndex.flatMap { (field, index) =>
      val access = s"$prefix.f$index"
      shapeByType.get(field.llvmType) match
        case Some(nested) => checks(nested, access)
        case None => List(s"$access == ${if field.value == "null" then "0" else field.value}")
    }

  private val prefixes = List(
    Nil,
    List.fill(5)(scalars(3)),
    List.fill(7)(scalars(3)),
    List.fill(7)(scalars(5)),
    List.fill(8)(scalars(5)),
    List.fill(6)(scalars(3)) ++ List.fill(8)(scalars(5))
  )

  private def cSource: String =
    val definitions = shapes.map { s =>
      s.fields.zipWithIndex
        .map((f, i) => s"${f.cType} f$i;")
        .mkString("typedef struct { ", " ", s" } ${s.name};")
    }
    val functions = shapes.flatMap { s =>
      val echo = s"${s.name} echo_${s.name}(${s.name} x) { return x; }"
      val tests = prefixes.zipWithIndex.map { (prefix, index) =>
        val params = prefix.zipWithIndex.map((f, i) => s"${f.cType} p$i") ++
          List(s"${s.name} x", "long long tail", "double last")
        val scalarChecks = prefix.zipWithIndex.map((f, i) => s"p$i == ${f.value}") ++
          List("tail == 77", "last == 3.5")
        s"int check_${s.name}_$index(${params.mkString(", ")}) { return !(" +
          (checks(s, "x") ++ scalarChecks).mkString(" && ") + "); }"
      }
      echo :: tests
    }
    (definitions ++ functions).mkString("\n")

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-aggregate-abi")).bracket(use) { directory =>
      IO.blocking(Files.walk(directory))
        .bracket { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))
        }(paths => IO.blocking(paths.close()))
    }

  private def plan(ret: String, params: List[String], state: CodeGenState): NativeAbiPlan =
    NativeAbiPlan.classify(ret, params, state).fold(e => fail(e.message), identity)

  private def fixtureIr(triple: String, attributes: TargetAttributes): String =
    val initial = CodeGenState(
      targetAbi   = TargetAbi.fromHint(triple.some),
      layout      = TargetLayout(attributes.dataLayout),
      nativeTypes = shapes.map(s => s"struct.${s.name}" -> s.definition).toMap
    ).emit("define i32 @main() sanitize_address {\nentry:")
    val (finished, status) = shapes.foldLeft((initial, "0")) { case ((state, status), shape) =>
      val echo     = plan(shape.llvmType, List(shape.llvmType), state)
      val echoName = s"echo_${shape.name}"
      val (reg, returned) =
        echo.emitCall(echoName, List(shape.constant), echo.declare(echoName, state))
      prefixes.zipWithIndex.foldLeft((returned, status)) { case ((st, acc), (prefix, index)) =>
        val name              = s"check_${shape.name}_$index"
        val params            = prefix.map(_.llvmType) ++ List(shape.llvmType, "i64", "double")
        val signature         = plan("i32", params, st)
        val values            = prefix.map(_.value) ++ List(s"%$reg", "77", "3.5")
        val (result, emitted) = signature.emitCall(name, values, signature.declare(name, st))
        val combined          = emitted.nextRegister
        (
          emitted.withRegister(combined + 1).emit(s"  %$combined = or i32 $acc, %$result"),
          s"%$combined"
        )
      }
    }
    val body = finished.emit(s"  ret i32 $status\n}")
    s"target triple = \"${attributes.triple.getOrElse(triple)}\"\n" +
      s"target datalayout = \"${attributes.dataLayout}\"\n" +
      shapes.map(_.definition).mkString("\n") + "\n" +
      body.functionDeclarations.values.mkString("\n") + "\n" + body.output.reverse.mkString("\n") +
      "\n" + body.deferredDefinitions.reverse.mkString("\n") +
      "\nattributes #0 = { sanitize_address }\n"

  test("generated mixed and nested C aggregates preserve fields through calls and returns") {
    withDirectory { directory =>
      for
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing native target")))
        target <- ClangTarget.resolve(directory, Nil).map(_.fold(e => fail(e.message), identity))
        _ <- IO.blocking {
          Files.writeString(directory.resolve("fixture.c"), cSource)
          Files.writeString(directory.resolve("caller.ll"), fixtureIr(triple, target.attributes))
        }
        _ <- List("-O0", "-O2").traverse_ { opt =>
          commandNotFailed(
            List(
              target.executable.toString,
              "-target",
              triple,
              opt,
              "-fsanitize=address,undefined",
              directory.resolve("fixture.c").toString,
              directory.resolve("caller.ll").toString,
              "-o",
              directory.resolve("program").toString
            ),
            directory
          ) *>
            commandNotFailed(List(directory.resolve("program").toString), directory)
        }
      yield ()
    }
  }

  List(
    "x86_64-unknown-linux-gnu",
    "x86_64-apple-macosx14.0.0",
    "aarch64-unknown-linux-gnu",
    "arm64-apple-macosx14.0.0"
  ).foreach { triple =>
    test(s"generated aggregate matrix cross-compiles with the Clang C reference for $triple") {
      withDirectory { directory =>
        for
          target <- ClangTarget
            .resolve(directory, List("-target", triple))
            .map(_.fold(e => fail(e.message), identity))
          _ <- IO.blocking {
            Files.writeString(directory.resolve("fixture.c"), cSource)
            Files.writeString(directory.resolve("caller.ll"), fixtureIr(triple, target.attributes))
          }
          _ <- List("fixture.c", "caller.ll").traverse_ { source =>
            commandNotFailed(
              List(
                target.executable.toString,
                "-target",
                triple,
                "-O2",
                "-c",
                directory.resolve(source).toString,
                "-o",
                directory.resolve(s"$source.o").toString
              ),
              directory
            )
          }
          _ <- commandNotFailed(
            List(
              target.executable.toString,
              "-target",
              triple,
              "-S",
              "-emit-llvm",
              directory.resolve("fixture.c").toString,
              "-o",
              directory.resolve("reference.ll").toString
            ),
            directory
          )
          _ <- IO.blocking {
            val reference = Files.readString(directory.resolve("reference.ll"))
            val color =
              reference.linesIterator.find(_.contains("@echo_Color(")).getOrElse(fail(reference))
            assert(color.contains("i32 @echo_Color("), color)
            assert(color.contains(if triple.startsWith("x86") then "i32 " else "i64 "), color)
          }
        yield ()
      }
    }
  }

  test("layout includes nested padding and inline closure fields") {
    val state = CodeGenState(nativeTypes =
      Map(
        "struct.Inner" -> "%struct.Inner = type { i8, double }",
        "struct.Outer" -> "%struct.Outer = type { i32, %struct.Inner, { ptr, ptr }, i8 }"
      )
    )
    val layout = state.layout.of("%struct.Outer", state).fold(e => fail(e.message), identity)
    assertEquals(layout.size, 48)
    assertEquals(layout.alignment, 8)
    assertEquals(layout.fields.map(_.offset), List(0, 8, 24, 40))
    assertEquals(layout.fields(1).layout.fields.map(_.offset), List(0, 8))
  }

  test("MML native calls, function values and destructors preserve aggregate fields") {
    val source = """
      type Color = @native { r: Int8, g: Int8, b: Int8, a: Int8 };
      type Mixed = @native { color: Color, value: Double };
      type Resource = @native[mem=heap, free=fixture_drop] {
        tag: Int8, mixed: Mixed, count: Int64
      };
      fn byte(n: Int): Int8 = @native[tpl="trunc i32 %operand to i8"];;
      fn wide(n: Float): Double = @native[tpl="fpext float %operand to double"];;
      fn fixture_color(c: Color): Color = @native;;
      fn fixture_mixed(m: Mixed): Mixed = @native;;
      fn fixture_resource(a: Int, b: Int, c: Int, d: Int, e: Int, m: Mixed): Resource =
        @native[mem=alloc];;
      fn fixture_use(r: Resource): Int = @native;;
      fn fixture_drop(~r: Resource): Unit = @native;;
      fn apply(f: Color -> Color, c: Color): Color = f c;;
      fn apply_mixed(f: Mixed -> Mixed, m: Mixed): Mixed = f m;;
      pub fn run(): Int =
        let c = Color (byte 11) (byte 22) (byte 33) (byte 44);
        let direct = fixture_color c;
        let indirect = apply fixture_color direct;
        let mixed = apply_mixed fixture_mixed (Mixed indirect (wide 2.5));
        let resource = fixture_resource 1 2 3 4 5 mixed;
        let text = "captured";
        let closure = ~{ fixture_use resource + text.length; };
        closure ();
      ;
    """
    val driver = """
      #include <assert.h>
      typedef struct { signed char r, g, b, a; } Color;
      typedef struct { Color color; double value; } Mixed;
      typedef struct { signed char tag; Mixed mixed; long long count; } Resource;
      static int colors, mixed_calls, uses, drops;
      static void check_color(Color c) {
        assert(c.r == 11 && c.g == 22 && c.b == 33 && c.a == 44);
      }
      Color fixture_color(Color c) { check_color(c); ++colors; return c; }
      Mixed fixture_mixed(Mixed m) {
        check_color(m.color); assert(m.value == 2.5); ++mixed_calls; return m;
      }
      Resource fixture_resource(int a, int b, int c,
                                int d, int e, Mixed m) {
        assert(a == 1 && b == 2 && c == 3 && d == 4 && e == 5);
        check_color(m.color); assert(m.value == 2.5);
        return (Resource){-7, {{11, 22, 33, 44}, 2.5}, 1234567890123LL};
      }
      static void check_resource(Resource r) {
        assert(r.tag == -7 && r.count == 1234567890123LL);
        check_color(r.mixed.color); assert(r.mixed.value == 2.5);
      }
      int fixture_use(Resource r) { check_resource(r); ++uses; return 0; }
      void fixture_drop(Resource r) { check_resource(r); ++drops; }
      extern int interop_run(void);
      int main(void) {
        assert(interop_run() == 8);
        assert(colors == 2 && mixed_calls == 1 && uses == 1 && drops == 1);
        return 0;
      }
    """
    withDirectory { directory =>
      val config = CompilerConfig.library(directory.toString, asan = true)
      for
        ir <- compileAndGenerate(source, "Interop", config)
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing native target")))
        target <- ClangTarget
          .resolve(directory, LlvmToolchain.clangFlags(config, triple))
          .map(_.fold(e => fail(e.message), identity))
        path <- IO.blocking {
          Files.writeString(directory.resolve("driver.c"), driver)
          Files.writeString(directory.resolve("Interop.ll"), ir)
        }
        result <- LlvmToolchain.compile(path, config, triple.some, target)
        _ = assertEquals(result._1, Right(0))
        _ <- commandNotFailed(
          List(
            target.executable.toString,
            "-fuse-ld=lld",
            "-fsanitize=address,undefined",
            directory.resolve("driver.c").toString,
            directory.resolve("target/interop.o").toString,
            directory.resolve(s"target/mml_runtime-$triple.o").toString,
            "-lm",
            "-o",
            directory.resolve("program").toString
          ),
          directory
        )
        _ <- commandNotFailed(List(directory.resolve("program").toString), directory)
      yield ()
    }
  }

  test("x86 register exhaustion rolls back both classes and counts the return buffer") {
    val state     = CodeGenState(targetAbi = TargetAbi.X86_64)
    val mixed     = "{ i64, double }"
    val signature = plan("void", List.fill(6)("i64") ++ List(mixed, "{ double, double }"), state)
    assert(signature.arguments(6).isInstanceOf[AbiValue.Indirect])
    assert(signature.arguments(7).isInstanceOf[AbiValue.Coerce])
    val returning = plan("{ i64, i64, i64 }", List.fill(5)("i64") :+ mixed, state)
    assert(returning.arguments.last.isInstanceOf[AbiValue.Indirect])
  }

  test("target selection rejects unsupported operating systems and ABI variants") {
    List(
      "x86_64-pc-windows-msvc",
      "aarch64-unknown-freebsd",
      "aarch64_be-unknown-linux-gnu",
      "x86_64-unknown-linux-gnux32",
      "aarch64-unknown-linux-gnu_ilp32"
    ).foreach { triple =>
      assertEquals(TargetAbi.fromHint(triple.some), TargetAbi.Default)
    }
    assertEquals(TargetAbi.fromHint("arm64-apple-macosx14.0.0".some), TargetAbi.AppleAArch64)
  }
