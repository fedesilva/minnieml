package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/** Checks the optimization's call shapes before LLVM optimization, then assembles and runs the same
  * modules to catch calling-convention, evaluation-order, and ownership regressions.
  */
class LocalCallableCodegenTests extends BaseEffFunSuite:

  // trace records argument effects in order; live counts this fixture's explicit allocations.
  private val runtime = """
    #include <stdint.h>
    #include <stdlib.h>
    static int trace = 0;
    static int live = 0;
    void mml_sys_flush(void) {}
    int32_t observe(int32_t n) { trace = trace * 10 + n; return n; }
    void effect(void) { observe(1); }
    int32_t observed(void) { return trace; }
    void *allocate(void) { ++live; return malloc(1); }
    void release(void *p) { --live; free(p); }
    int32_t outstanding(void) { return live; }
  """

  /** Assembles the original IR, then removes unused library definitions so the executable can link
    * against the small support runtime above. The program's exit status checks its computed result.
    */
  private def verify(ir: String, expected: Int): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-local-callable"))
      .bracket { dir =>
        val input   = dir.resolve("program.ll")
        val support = dir.resolve("support.c")
        val live    = dir.resolve("live.ll")
        val binary  = dir.resolve("program")
        IO.blocking {
          Files.writeString(input, ir)
          Files.writeString(support, runtime)
        } *> commandNotFailed(List("llvm-as", input.toString, "-o", "/dev/null"), dir) *>
          commandNotFailed(
            List("opt", "-passes=globaldce", "-S", input.toString, "-o", live.toString),
            dir
          ) *>
          commandNotFailed(
            List(
              "clang",
              "-Wno-override-module",
              live.toString,
              support.toString,
              "-o",
              binary.toString
            ),
            dir
          ) *>
          programExits(List(binary.toString), dir, expected)
      } { dir =>
        IO.blocking(Files.walk(dir))
          .bracket { paths =>
            IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
          }(paths => IO.blocking(paths.close()))
      }

  /** Finds the target from a fixture's call operands so assertions do not depend on generated names
    * or on the order in which unrelated entries receive symbols.
    */
  private def plainCall(ir: String, caller: String, arguments: String): String =
    val body = functionBody(ir, caller)
    val call = s"call i32 @([^ (]+)\\($arguments\\)".r
      .findFirstMatchIn(body)
      .getOrElse(fail(s"Expected plain call with $arguments:\n$body"))
    call.group(1)

  private def assertPlainEntry(ir: String, symbol: String, parameters: String): String =
    functionBodyMatching(ir, s"${Regex.quote(symbol)}\\($parameters\\).*")

  // Used only for scalar fixtures with no other reason to allocate stack or heap storage.
  private def noClosure(body: String): Unit =
    assert(!body.contains("{ ptr, ptr }"), body)
    assert(!body.contains("alloca "), body)
    assert(!body.contains("@malloc"), body)

  private def compile(source: String, noTco: Boolean = false): IO[String] =
    compileAndGenerate(source, config = CompilerConfig.exe("build", noTco = noTco))

  test("alias chains and parenthesized aliases share one plain entry") {
    val source = """
      pub fn main(): Int =
        let f = { x: Int -> x + 1 };
        let a = (f);
        let b = (a);
        b 20 + a 20;
      ;
    """
    compile(source).flatMap { ir =>
      val target = plainCall(ir, "test_main", "i32 20")
      assertPlainEntry(ir, target, "i32 %\\d+")
      val body = functionBody(ir, "test_main")
      assertEquals(s"call i32 @${Regex.quote(target)}\\(i32 20\\)".r.findAllIn(body).size, 2)
      noClosure(body)
      assert(!ir.contains("{ ptr, ptr }"), ir)
      verify(ir, 42)
    }
  }

  test("local and parameter shadowing keep distinct callable identities") {
    val source = """
      pub fn main(): Int =
        let f = { x: Int -> x + 1 };
        let first = f 10;
        let apply = { f: (Int -> Int) -> f 20 };
        let second = if true then
          let f = { x: Int -> x + 2 };
          apply f + f 7;
        else 0;;
        first + second;
      ;
    """
    compile(source).flatMap { ir =>
      val first  = plainCall(ir, "test_main", "i32 10")
      val second = plainCall(ir, "test_main", "i32 7")
      assertNotEquals(first, second)
      assertPlainEntry(ir, first, "i32 %\\d+")
      assertPlainEntry(ir, second, "i32 %\\d+")
      assert("""call i32 %\d+\(i32 20, ptr %\d+\)""".r.findFirstIn(ir).nonEmpty, ir)
      verify(ir, 42)
    }
  }

  test("ordinary recursive local calls use the registered plain entry") {
    val source = """
      pub fn main(): Int =
        let factorial: Int -> Int = { n: Int ->
          if n <= 1 then 1; else n * factorial (n - 1);;
        };
        factorial 5;
      ;
    """
    compile(source).flatMap { ir =>
      val target = plainCall(ir, "test_main", "i32 5")
      val body   = assertPlainEntry(ir, target, "i32 %\\d+")
      assert(s"call i32 @${Regex.quote(target)}\\(i32 %\\d+\\)".r.findFirstIn(body).nonEmpty, body)
      noClosure(body)
      noClosure(functionBody(ir, "test_main"))
      assert(!ir.contains("{ ptr, ptr }"), ir)
      verify(ir, 120)
    }
  }

  for noTco <- List(false, true) do
    test(s"tail recursion shares a plain entry with its value adapter, noTco=$noTco") {
      val source = """
        fn apply(f: Int -> Int, n: Int): Int = f n;;
        pub fn main(): Int =
          let down: Int -> Int = { n: Int -> if n == 0 then 21; else down (n - 1);; };
          down 10 + apply down 10;
        ;
      """
      compile(source, noTco).flatMap { ir =>
        val target = plainCall(ir, "test_main", "i32 10")
        val body   = assertPlainEntry(ir, target, "i32 %\\d+")
        assertEquals(body.contains("loop.header:"), !noTco, body)
        if noTco then
          assert(
            s"call i32 @${Regex.quote(target)}\\(i32 %\\d+\\)".r.findFirstIn(body).nonEmpty,
            body
          )
        verify(ir, 42)
      }
    }

  test("mixed direct and repeated value uses share a single adapter") {
    val source = """
      fn apply(f: Int -> Int, n: Int): Int = f n;;
      pub fn main(): Int =
        let f = { n: Int -> n + 1 };
        let alias = f;
        f 9 + apply f 10 + apply alias 20;
      ;
    """
    compile(source).flatMap { ir =>
      val target = plainCall(ir, "test_main", "i32 9")
      assertPlainEntry(ir, target, "i32 %\\d+")
      val main = functionBody(ir, "test_main")
      val adapters =
        """\{ ptr @([^, ]+), ptr null \}""".r.findAllMatchIn(main).map(_.group(1)).toList
      assertEquals(adapters.size, 2)
      assertEquals(adapters.distinct.size, 1)
      val adapter = assertPlainEntry(ir, adapters.head, "i32 %\\d+, ptr %\\d+")
      assert(adapter.contains(s"call i32 @$target(i32 %0)"), adapter)
      verify(ir, 42)
    }
  }

  test("a directly called local function can be returned as a value") {
    val source = """
      fn make(): Int -> Int =
        let f = { n: Int -> n + 1 };
        let ignored = f 0;
        f;
      ;
      pub fn main(): Int = let f = make (); f 41;;
    """
    compile(source).flatMap { ir =>
      val target = plainCall(ir, "test_make", "i32 0")
      assertPlainEntry(ir, target, "i32 %\\d+")
      assert(functionBody(ir, "test_make").contains("ret { ptr, ptr } { ptr @"), ir)
      verify(ir, 42)
    }
  }

  test("capturing a callable stores its adapter and calls through the runtime capture") {
    val source = """
      fn apply(f: Int -> Int, n: Int): Int = f n;;
      pub fn main(): Int =
        let f = { n: Int -> n + 1 };
        let direct = f 19;
        let g = { n: Int -> f n };
        let indirect = g 20;
        direct + indirect + apply f 0;
      ;
    """
    compile(source).flatMap { ir =>
      val target = plainCall(ir, "test_main", "i32 19")
      assertPlainEntry(ir, target, "i32 %\\d+")
      val main = functionBody(ir, "test_main")
      val stored = """store \{ ptr, ptr \} \{ ptr @([^, ]+), ptr null \}""".r
        .findFirstMatchIn(main)
        .getOrElse(fail(main))
        .group(1)
      assert(main.contains(s"call i32 @test_apply({ ptr, ptr } { ptr @$stored, ptr null }"), main)
      assert("""call i32 %\d+\(i32 %\d+, ptr %\d+\)""".r.findFirstIn(ir).nonEmpty, ir)
      verify(ir, 42)
    }
  }

  test("Unit effects and ordinary arguments run once in source order") {
    val source = """
      fn effect(): Unit = @native;;
      fn observe(n: Int): Int = @native;;
      fn observed(): Int = @native;;
      pub fn main(): Int =
        let f = { u: Unit, a: Int, b: Int -> a + b };
        let ignored = f (effect ()) (observe 2) (observe 3);
        observed ();
      ;
    """
    compile(source).flatMap { ir =>
      val main   = functionBody(ir, "test_main")
      val target = plainCall(ir, "test_main", "i32 %\\d+, i32 %\\d+")
      assertPlainEntry(ir, target, "i32 %\\d+, i32 %\\d+")
      noClosure(main)
      verify(ir, 123)
    }
  }

  test("consuming parameters retain noalias in plain entries and adapters") {
    val source = """
      type Handle = @native[t=ptr, mem=heap];
      fn allocate(): Handle = @native[mem=alloc];;
      fn release(~p: Handle): Unit = @native;;
      fn outstanding(): Int = @native;;
      fn apply(f: (Handle -> Unit), ~p: Handle): Unit = f p;;
      pub fn main(): Int =
        let consume = { ~p: Handle -> release p };
        consume (allocate ());
        apply consume (allocate ());
        outstanding ();
      ;
    """
    compile(source).flatMap { ir =>
      val main = functionBody(ir, "test_main")
      val target = """call void @([^ (]+)\(ptr %\d+\)""".r
        .findFirstMatchIn(main)
        .getOrElse(fail(main))
        .group(1)
      assertPlainEntry(ir, target, "ptr noalias %\\d+")
      val adapter = """\{ ptr @([^, ]+), ptr null \}""".r
        .findFirstMatchIn(main)
        .getOrElse(fail(main))
        .group(1)
      assertPlainEntry(ir, adapter, "ptr noalias %\\d+, ptr %\\d+")
      verify(ir, 0)
    }
  }

  test("value-only locals keep a single closure body") {
    val source = """
      fn apply(f: Int -> Int): Int = f 41;;
      pub fn main(): Int = let f = { n: Int -> n + 1 }; apply f;;
    """
    compile(source).flatMap { ir =>
      val main = functionBody(ir, "test_main")
      val entry = """\{ ptr @([^, ]+), ptr null \}""".r
        .findFirstMatchIn(main)
        .getOrElse(fail(main))
        .group(1)
      val body = assertPlainEntry(ir, entry, "i32 %\\d+, ptr %\\d+")
      assert(body.contains("add i32"), body)
      assert(!body.contains("call "), body)
      verify(ir, 42)
    }
  }

  test("adapter registration survives emission of a recursive body") {
    val source = """
      fn apply(f: Int -> Int, n: Int): Int = f n;;
      pub fn main(): Int =
        let f: Int -> Int = { n: Int ->
          if n == 0 then 21; else apply f (n - 1);;
        };
        f 0 + apply f 2;
      ;
    """
    compile(source).flatMap { ir =>
      val target   = plainCall(ir, "test_main", "i32 0")
      val body     = assertPlainEntry(ir, target, "i32 %\\d+")
      val pair     = """\{ ptr @([^, ]+), ptr null \}""".r
      val internal = pair.findFirstMatchIn(body).getOrElse(fail(body)).group(1)
      val external = pair
        .findFirstMatchIn(functionBody(ir, "test_main"))
        .getOrElse(fail(ir))
        .group(1)
      assertEquals(internal, external)
      assertEquals(s"define internal i32 @${Regex.quote(internal)}\\(".r.findAllIn(ir).size, 1)
      verify(ir, 42)
    }
  }

  test("nullary Unit locals share a plain entry and adapter") {
    val source = """
      fn effect(): Unit = @native;;
      fn observed(): Int = @native;;
      fn force(f: Unit -> Unit): Unit = f ();;
      pub fn main(): Int =
        let f = { effect (); };
        f ();
        force f;
        observed ();
      ;
    """
    compile(source).flatMap { ir =>
      val main = functionBody(ir, "test_main")
      val target = """call void @([^ (]+)\(\)""".r
        .findFirstMatchIn(main)
        .getOrElse(fail(main))
        .group(1)
      assertPlainEntry(ir, target, "")
      val adapter = """\{ ptr @([^, ]+), ptr null \}""".r
        .findFirstMatchIn(main)
        .getOrElse(fail(main))
        .group(1)
      val body = assertPlainEntry(ir, adapter, "ptr %\\d+")
      assert(body.contains(s"call void @$target()"), body)
      assert(body.contains("ret void"), body)
      verify(ir, 11)
    }
  }

  test("conditional callable selection and capturing lambdas retain runtime values") {
    val source = """
      fn choose(flag: Bool): Int =
        let offset = 1;
        let f = { n: Int -> n + offset };
        let g = { n: Int -> n + 2 };
        let selected = if flag then f; else g;;
        selected 40;
      ;
      pub fn main(): Int = choose true + choose false - 41;;
    """
    compile(source).flatMap { ir =>
      val body = functionBody(ir, "test_choose")
      assert(body.contains("phi { ptr, ptr }"), body)
      assert(body.contains("extractvalue { ptr, ptr }"), body)
      verify(ir, 42)
    }
  }
