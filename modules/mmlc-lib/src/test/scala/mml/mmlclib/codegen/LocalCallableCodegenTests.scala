package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
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
    void mml_free_raw(void *p) { free(p); }
    int32_t observe(int32_t n) { trace = trace * 10 + n; return n; }
    void effect(void) { observe(1); }
    int32_t observed(void) { return trace; }
    void *allocate(void) { ++live; unsigned char *p = malloc(1); *p = 7; return p; }
    int32_t inspect(const void *p) { return *(const unsigned char *)p; }
    void release(void *p) { --live; free(p); }
    int32_t outstanding(void) { return live; }
  """

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-local-callable"))
      .bracket(use) { dir =>
        IO.blocking(Files.walk(dir))
          .bracket { paths =>
            IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
          }(paths => IO.blocking(paths.close()))
      }

  /** Assembles the original IR, then removes unused library definitions so the executable can link
    * against the small support runtime above. The program's exit status checks its computed result.
    */
  private def verify(
    ir:           String,
    expected:     Int,
    sanitized:    Boolean = false,
    optimization: Int     = 0
  ): IO[Unit] =
    withDirectory { dir =>
      val input   = dir.resolve("program.ll")
      val support = dir.resolve("support.c")
      val live    = dir.resolve("live.ll")
      val binary  = dir.resolve("program")
      IO.blocking {
        Files.writeString(input, ir)
        Files.writeString(support, runtime)
      } *> commandNotFailed(List("llvm-as", input.toString, "-o", "/dev/null"), dir) *>
        commandNotFailed(
          List(
            "opt",
            s"-passes=globaldce,default<O$optimization>",
            "-S",
            input.toString,
            "-o",
            live.toString
          ),
          dir
        ) *>
        commandNotFailed(
          List(
            "clang",
            s"-O$optimization",
            "-Wno-override-module",
            live.toString,
            support.toString,
            "-o",
            binary.toString
          ) ++ (if sanitized then List("-fsanitize=address", "-fno-omit-frame-pointer") else Nil),
          dir
        ) *>
        programExits(
          (if sanitized then
             List("env", "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1")
           else Nil) :+ binary.toString,
          dir,
          expected
        )
    }

  private def executeWithRuntime(source: String, optimization: Int): IO[Unit] =
    withDirectory { directory =>
      val binary = directory.resolve("program")
      val config = CompilerConfig
        .exe(directory.toString)
        .copy(
          outputName = binary.toString.some,
          optLevel   = optimization,
          asan       = true
        )
      for
        ir <- compileAndGenerate(source, config = config)
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing target")))
        target <- ClangTarget
          .resolve(directory, LlvmToolchain.clangFlags(config, triple))
          .map(_.fold(error => fail(error.message), identity))
        path <- IO.blocking(Files.writeString(directory.resolve("program.ll"), ir))
        _ <- commandNotFailed(List("llvm-as", path.toString, "-o", "/dev/null"), directory)
        result <- LlvmToolchain.compile(path, config, triple.some, target)
        _ = assertEquals(result._1, Right(0))
        _ <- programExits(
          List(
            "env",
            "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1",
            binary.toString
          ),
          directory,
          expectedCode = 0
        )
      yield ()
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

  List(0, 3).foreach { optimization =>
    test(s"recursive higher-order application produces its result at O$optimization") {
      compile("""
        fn increment(x: Int): Int = x + 1;;
        fn apply_n(f: Int -> Int, n: Int, acc: Int): Int =
          if n == 0 then acc;
          else apply_n f (n - 1) (f acc);
          ;
        ;
        pub fn main(): Int = apply_n increment 3 0;;
      """).flatMap(verify(_, expected = 3, optimization = optimization))
    }

    List("" -> "borrowing", "~" -> "consuming").foreach { (marker, contract) =>
      test(s"recursive $contract callback releases each heap argument at O$optimization") {
        compile(s"""
          type Handle = @native[t=ptr, mem=heap, free=release];
          fn allocate(): Handle = @native[mem=alloc];;
          fn release(~p: Handle): Unit = @native;;
          fn inspect(p: Handle): Int = @native;;
          fn outstanding(): Int = @native;;
          fn read(${marker}p: Handle): Int = inspect p;;
          fn run(f: Handle -> Int, n: Int): Int =
            if n == 0 then 0;
            else f (allocate ()) + run f (n - 1);
            ;
          ;
          pub fn main(): Int =
            let result = run read 3;
            if result == 21 then outstanding (); else 100; ;
          ;
        """).flatMap(verify(_, expected = 0, optimization = optimization))
      }
    }
  }

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

  test("nullary immediate calls evaluate Unit effects before the body in lexical scope") {
    val source = """
      fn effect(): Unit = @native;;
      fn observe(n: Int): Int = @native;;
      fn observed(): Int = @native;;
      pub fn main(): Int =
        let captured = 40;
        let result = ({ observe 2 + captured; } (effect ()));
        result + observed ();
      ;
    """
    compile(source).flatMap { ir =>
      noClosure(functionBody(ir, "test_main"))
      verify(ir, 54)
    }
  }

  test("nullary immediate calls preserve conditional argument exit blocks") {
    val source = """
      fn effect(): Unit = @native;;
      fn observed(): Int = @native;;
      fn choose(argument: Bool, body: Bool): Int =
        if body then ({ 7; } (if argument then effect (); else ();));
        else 9;
        ;
      ;
      pub fn main(): Int =
        let result = choose true true + choose false true + choose true false + choose false false;
        result + observed ();
      ;
    """
    compile(source).flatMap { ir =>
      val body = functionBody(ir, "test_choose")
      noClosure(body)
      assertPhiPredecessors(body)
      verify(ir, 33)
    }
  }

  test("nullary immediate calls preserve Unit body effects") {
    val source = """
      fn effect(): Unit = @native;;
      fn observed(): Int = @native;;
      pub fn main(): Int =
        ({ effect (); } (effect ()));
        observed ();
      ;
    """
    compile(source).flatMap { ir =>
      noClosure(functionBody(ir, "test_main"))
      verify(ir, 11)
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

  private def assertKnownPapCall(ir: String, hasEnvironment: Boolean): Unit =
    val residual = "test_addDummy_\\d+\\([^\\n]*".r
      .findFirstIn(ir)
      .fold(ir)(_ => functionBodyMatching(ir, "test_addDummy_\\d+\\([^\\n]*"))
    val scalar      = "i32 (?:%\\d+|\\d+)"
    val environment = if hasEnvironment then ", ptr %\\d+" else "(?:, ptr null)?"
    val call = s"call i32 @([^ (]+)\\($scalar, $scalar$environment\\)".r
      .findFirstMatchIn(residual)
      .getOrElse(fail(residual))
    val entryEnvironment =
      if hasEnvironment || call.matched.contains("ptr null") then ", ptr[^)]*" else ""
    assertPlainEntry(ir, call.group(1), s"i32 %\\d+, i32 %\\d+$entryEnvironment")

  test("PAP aliases, staged applications, and ordinary value uses retain the known entry") {
    val source = """
      fn apply(f: Int -> Int -> Int): Int = f 20 1;;
      fn make(dummy: Int): Int -> Int =
        let add = { x: Int, y: Int -> x + y };
        let alias = (add);
        let value = apply alias;
        alias dummy;
      ;
      pub fn main(): Int =
        let add = { x: Int, y: Int, z: Int -> x + y + z };
        let alias = (add);
        let first = alias 10;
        let second = first 10;
        let returned = make 20;
        second 1 + returned 1;
      ;
    """
    compile(source).flatMap { ir =>
      assert(!"""store \{ ptr, ptr \}""".r.findAllIn(functionBody(ir, "test_make")).hasNext, ir)
      assertKnownPapCall(ir, false)
      verify(ir, 42, sanitized = true)
    }
  }

  test("shadowed PAP targets keep distinct entry identities") {
    val source = """
      fn check(dummy: Int): Int =
        let add = { x: Int, y: Int -> x + y };
        let first = add dummy;
        let result = first 1;
        let second = if true then
          let add = { x: Int, y: Int -> x + y + 20 };
          let addDummy = add dummy;
          addDummy 1;
        else 0;;
        result + second;
      ;
      pub fn main(): Int = check 10;;
    """
    compile(source).flatMap { ir =>
      val call = """call i32 @([^ (]+)\(i32 %\d+, i32 %\d+\)""".r
      val first = call
        .findFirstMatchIn(functionBodyMatching(ir, "test_first_\\d+\\([^\\n]*"))
        .getOrElse(fail(ir))
        .group(1)
      val second = call
        .findFirstMatchIn(functionBodyMatching(ir, "test_addDummy_\\d+\\([^\\n]*"))
        .getOrElse(fail(ir))
        .group(1)
      assertNotEquals(first, second)
      verify(ir, 42, sanitized = true)
    }
  }

  test("capturing PAPs save the environment and directly call one entry for different values") {
    val source = """
      fn check(offset: Int, dummy: Int): Int =
        let add = { x: Int, y: Int -> x + y + offset };
        let alias = (add);
        let addDummy = alias dummy;
        addDummy 1;
      ;
      pub fn main(): Int = check 5 10 + check 6 19;;
    """
    compile(source).flatMap { ir =>
      assertKnownPapCall(ir, true)
      assert(!functionBody(ir, "test_check").contains("store { ptr, ptr }"), ir)
      verify(ir, 42, sanitized = true)
    }
  }

  test("returned callable instances retain one entry and distinct environment pointers") {
    val source = """
      fn make(offset: Int): Int -> Int -> Int = ~{ x: Int, y: Int -> x + y + offset };;
      fn check(offset: Int, dummy: Int): Int =
        let add = make offset;
        let addDummy = add dummy;
        addDummy 1;
      ;
      pub fn main(): Int = check 5 10 + check 6 19;;
    """
    compile(source).flatMap { ir =>
      assertKnownPapCall(ir, true)
      assert(!functionBody(ir, "test_check").contains("store { ptr, ptr }"), ir)
      verify(ir, 42, sanitized = true)
    }
  }

  test("same-entry conditional selection keeps its selected environment") {
    val source = """
      fn make(offset: Int): Int -> Int -> Int = ~{ x: Int, y: Int -> x + y + offset };;
      fn check(flag: Bool, dummy: Int): Int =
        let add = if flag then make 5; else make 6;;
        let addDummy = add dummy;
        addDummy 1;
      ;
      pub fn main(): Int = check true 10 + check false 19;;
    """
    compile(source).flatMap { ir =>
      assertKnownPapCall(ir, true)
      assert(functionBody(ir, "test_check").contains("phi { ptr, ptr }"), ir)
      assert(!functionBody(ir, "test_check").contains("store { ptr, ptr }"), ir)
      verify(ir, 42, sanitized = true)
    }
  }

  test("a known branch beside an unknown parameter remains an indirect PAP target") {
    val source = """
      fn check(flag: Bool, unknown: Int -> Int -> Int): Int =
        let add = { x: Int, y: Int -> x + y };
        let selected = if flag then add; else unknown;;
        let addDummy = selected 10;
        addDummy 1;
      ;
      pub fn main(): Int =
        let other = { x: Int, y: Int -> x + y + 20 };
        check true other + check false other;
      ;
    """
    compile(source).flatMap { ir =>
      assert(
        """call i32 %\d+\(i32 (?:%\d+|\d+), i32 (?:%\d+|\d+), ptr %\d+\)""".r
          .findFirstIn(ir)
          .nonEmpty,
        ir
      )
      assert(functionBody(ir, "test_check").contains("store { ptr, ptr }"), ir)
      verify(ir, 42, sanitized = true)
    }
  }

  test("different known conditional entries remain an indirect PAP target") {
    val source = """
      fn check(flag: Bool): Int =
        let add = { x: Int, y: Int -> x + y };
        let other = { x: Int, y: Int -> x + y + 20 };
        let selected = if flag then add; else other;;
        let addDummy = selected 10;
        addDummy 1;
      ;
      pub fn main(): Int = check true + check false;;
    """
    compile(source).flatMap { ir =>
      assert(
        """call i32 %\d+\(i32 (?:%\d+|\d+), i32 (?:%\d+|\d+), ptr %\d+\)""".r
          .findFirstIn(ir)
          .nonEmpty,
        ir
      )
      verify(ir, 42, sanitized = true)
    }
  }

  test("constructor field flow proves an entry and preserves construction effects") {
    val source = """
      struct Holder { f: Int -> Int -> Int };
      fn observe(n: Int): Int = @native;;
      fn observed(): Int = @native;;
      fn make(n: Int): Holder =
        let ignored = observe n;
        Holder { x: Int, y: Int -> x + y };
      ;
      pub fn main(): Int =
        let holder = make 1;
        let addDummy = holder.f 10;
        let result = addDummy 1;
        result + observed ();
      ;
    """
    compile(source).flatMap { ir =>
      assertKnownPapCall(ir, false)
      verify(ir, 12, sanitized = true)
    }
  }

  test("PAP creation and invocation evaluate Unit and scalar effects once in order") {
    val source = """
      fn effect(): Unit = @native;;
      fn observe(n: Int): Int = @native;;
      fn observed(): Int = @native;;
      pub fn main(): Int =
        let add = { u: Unit, x: Int, y: Int -> x + y };
        let addDummy = add (effect ()) (observe 2);
        let result = addDummy (observe 3);
        observed ();
      ;
    """
    compile(source).flatMap(ir => verify(ir, 123, sanitized = true))
  }

  private val heapPrelude = """
    type Handle = @native[t=ptr, mem=heap, free=release];
    fn allocate(): Handle = @native[mem=alloc];;
    fn release(~p: Handle): Unit = @native;;
    fn outstanding(): Int = @native;;
  """

  test("a PAP returned by an immediately applied lambda executes its residual body") {
    compile("""
      fn add(a: Int, b: Int): Int = a + b;;
      pub fn main(): Int = { a: Int -> add a; } 1 2;;
    """).flatMap(ir => verify(ir, 3, sanitized = true))
  }

  for optimization <- List(0, 3) do
    test(s"PAPs inside callees preserve Unit effects and owned payload cleanup at O$optimization") {
      val source = s"""
        $heapPrelude
        fn effect(): Unit = @native;;
        fn observe(n: Int): Int = @native;;
        fn observed(): Int = @native;;
        fn signal(n: Int): Unit = let ignored = observe n; ();;
        fn take(u: Unit, ~h: Handle, a: Int, b: Int): Int = release h; a + b;;
        fn check(): Int =
          { u: Unit -> take (signal 3) (allocate ()) (observe 4); } (effect ()) (observe 2);
        ;
        pub fn main(): Int =
          let result = check ();
          if result == 6 and observed () == 1234 and outstanding () == 0 then 0;
          else 1;;
        ;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true, optimization = optimization))
    }

  for
    optimization <- List(0, 3)
    conditional <- List(false, true)
    selected <- (if conditional then List(false, true) else List(true))
  do
    test(
      s"PAP callee qualifier cleanup: O$optimization, conditional=$conditional, selected=$selected"
    ) {
      val callee = "{ a: Int -> pack (take (allocate ()) (observe 5)); } (observe 3) (observe 4)"
      val qualifier =
        if conditional then s"if $selected then $callee; else Holder add;"
        else callee
      val expectedTrace = if selected then 12345 else 12
      val source = s"""
        $heapPrelude
        struct Holder { f: Int -> Int -> Int };
        fn observe(n: Int): Int = @native;;
        fn observed(): Int = @native;;
        fn add(a: Int, b: Int): Int = a + b;;
        fn take(~h: Handle, a: Int, b: Int): Int = release h; a + b;;
        fn pack(~f: Int -> Int, n: Int): Holder = let result = f n; Holder add;;
        fn check(): Int = ($qualifier).f (observe 1) (observe 2);;
        pub fn main(): Int =
          let result = check ();
          if result == 3 and observed () == $expectedTrace and outstanding () == 0 then 0;
          else 1;;
        ;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true, optimization = optimization))
    }

  for optimization <- List(0, 3) do
    test(s"literal borrowing preserves owned and borrowed String inputs at O$optimization") {
      val source = """
        fn named(value: String, n: Int): Int = value.length + n;;
        fn borrowed(text: String): Int =
          let unary = { value: String -> value.length; } text;
          let multiple = { value: String, n: Int -> value.length + n; } text 1;
          let control = named text 1;
          if unary == 2 and multiple == control and text.length == 2 then 0;
          else 1;
          ;
        ;
        pub fn main(): Int =
          let text = int_to_str 42;
          let result = borrowed text;
          let immediate = { value: String, n: Int -> value.length + n; } text 1;
          if result == 0 and immediate == named text 1 and text.length == 2 then 0;
          else 1;
          ;
        ;
      """
      executeWithRuntime(source, optimization)
    }

  for optimization <- List(0, 3) do
    test(s"literal borrowing cleans conditional String allocations at O$optimization") {
      val source = """
        fn check(kind: Int, borrowed: String): Int =
          let length =
            { text: String -> text.length; }
            (if kind == 0 then int_to_str 123;
             elif kind == 1 then "ab";
             else borrowed;
             )
          ;
          let expected = if kind == 0 then 3; elif kind == 1 then 2; else 4;;
          if length == expected and borrowed.length == 4 then 0; else 1;;
        ;
        pub fn main(): Int =
          let original = int_to_str 1234;
          let result = check 0 original + check 1 original + check 2 original;
          if result == 0 and original.length == 4 then 0; else 1;;
        ;
      """
      executeWithRuntime(source, optimization)
    }

  for optimization <- List(0, 3) do
    test(s"literal borrowing retains Handle owners and cleans temporaries at O$optimization") {
      val source = s"""
        $heapPrelude
        fn inspect(h: Handle): Int = @native;;
        fn borrow(h: Handle): Int = inspect h;;
        fn named(h: Handle): Int = borrow h;;
        fn check(): Int =
          let original = allocate ();
          let control = named original;
          let unary = { h: Handle -> borrow h; } original;
          let multiple = { h: Handle, n: Int -> borrow h + n; } original 1;
          let temporary = { h: Handle ->
            if outstanding () == 2 then borrow h; else 0;;
          } (allocate ());
          let after = borrow original;
          if control == 7 and unary == 7 and multiple == 8 and temporary == 7 and
             after == 7 and outstanding () == 1 then 0;
          else 1;
          ;
        ;
        fn moved_argument(): Int =
          let original = allocate ();
          let result = { h: Handle ->
            if outstanding () == 1 then borrow h; else 0;;
          } (let moved = original; moved);
          if result == 7 and outstanding () == 0 then 0; else 1;;
        ;
        pub fn main(): Int =
          let result = check ();
          let moved = moved_argument ();
          if result == 0 and moved == 0 and outstanding () == 0 then 0; else 1;;
        ;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true, optimization = optimization))
    }

  for optimization <- List(0, 3) do
    test(s"literal borrowing preserves effect order and consuming parameters at O$optimization") {
      val source = s"""
        $heapPrelude
        fn inspect(h: Handle): Int = @native;;
        fn observe(n: Int): Int = @native;;
        fn observed(): Int = @native;;
        fn marked(n: Int): Handle =
          let ignored = observe n;
          allocate ();
        ;
        pub fn main(): Int =
          let result = { first: Handle, second: Handle ->
            let ignored = observe 3;
            if outstanding () == 2 then inspect first + inspect second; else 0;;
          } (marked 1) (marked 2);
          let consumed = { ~h: Handle -> let value = inspect h; release h; value; } (allocate ());
          if result == 14 and consumed == 7 and observed () == 123 and
             outstanding () == 0 then 0;
          else 1;
          ;
        ;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true, optimization = optimization))
    }

  test("immediate stages release allocating arguments through their custom destructor once") {
    val source = s"""
      $heapPrelude
      fn observe(n: Int): Int = @native;;
      fn observed(): Int = @native;;
      fn chained(): Int =
        { h: Handle ->
          let ignored = observe (outstanding ());
          { n: Int -> n; };
        } (allocate ()) 7;
      ;
      fn bound(): Int =
        let next = { h: Handle ->
          let ignored = observe (outstanding ());
          { n: Int -> n; };
        } (allocate ());
        next 7;
      ;
      pub fn main(): Int =
        let result = chained () + bound ();
        if result == 14 and observed () == 11 and outstanding () == 0 then 0;
        else 1;
        ;
      ;
    """
    compile(source).flatMap(ir => verify(ir, 0, sanitized = true))
  }

  for bound <- List(false, true) do
    test(s"evaluated callee preserves argument effects and caller-scope cleanup, bound=$bound") {
      val callee = """{ u: Unit ->
        let temporary = allocate ();
        let ignored = observe 2;
        p;
      }"""
      val invocation =
        if bound then s"""
          let unit = effect ();
          let number = observe 3;
          let callable = $callee unit;
          callable number;
        """
        else s"$callee (effect ()) (observe 3);"
      val source = s"""
        $heapPrelude
        fn observe(n: Int): Int = @native;;
        fn effect(): Unit = @native;;
        fn observed(): Int = @native;;
        fn take(~h: Handle, n: Int): Int = release h; n;;
        fn check(): Int =
          let p = take (allocate ());
          let outer = ~{ $invocation };
          outer ();
        ;
        pub fn main(): Int =
          let result = check ();
          if result == 3 and observed () == 132 and outstanding () == 0 then 0;
          else 1;
          ;
        ;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true))
    }

  for bound <- List(false, true) do
    test(s"returned conditional PAP cleans selected and unselected owned payloads, bound=$bound") {
      val callee = """{ u: Unit ->
        let first = p;
        let second = q;
        if flag then first; else second;;
      }"""
      val invocation =
        if bound then s"let callable = $callee (); callable 7;"
        else s"$callee () 7;"
      val source = s"""
        $heapPrelude
        fn take(~h: Handle, n: Int): Int = release h; n;;
        fn check(flag: Bool): Int =
          let p = take (allocate ());
          let q = take (allocate ());
          $invocation
        ;
        pub fn main(): Int =
          let result = check true + check false;
          if result == 14 and outstanding () == 0 then 0; else 1;;
        ;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true))
    }

  for invoked <- List(false, true) do
    test(
      s"known static PAP target preserves supplied consuming-payload cleanup, invoked=$invoked"
    ) {
      val call = if invoked then "let result = addDummy 1;" else ""
      val source = s"""
        $heapPrelude
        fn check(): Unit =
          let add = { ~h: Handle, y: Int -> release h; y; };
          let addDummy = add (allocate ());
          $call
          ();
        ;
        pub fn main(): Int = check (); outstanding ();;
      """
      compile(source).flatMap(ir => verify(ir, 0, sanitized = true))
    }

  for invoked <- List(false, true) do
    test(s"environment-only PAP payload preserves capturing-callee destruction, invoked=$invoked") {
      val call = if invoked then "let result = addDummy (allocate ());" else ""
      val source = s"""
        $heapPrelude
        fn check(): Unit =
          let owned = allocate ();
          let add = ~{ x: Int, ~other: Handle -> release owned; release other; x; };
          let addDummy = add 10;
          $call
          ();
        ;
        pub fn main(): Int = check (); outstanding ();;
      """
      compile(source).flatMap { ir =>
        assert(!functionBody(ir, "test_check").contains("store { ptr, ptr }"), ir)
        verify(ir, 0, sanitized = true)
      }
    }

  test("omitting a known function-value payload leaves valid empty borrow-environment metadata") {
    val source = """
      fn use(f: Int -> Int, n: Int): Int = f n;;
      pub fn main(): Int =
        let add = { x: Int -> x + 1 };
        let addDummy = use add;
        addDummy 41;
      ;
    """
    compile(source).flatMap { ir =>
      assert("""%struct\.__closure_env_\d+ = type \{  \}""".r.findFirstIn(ir).nonEmpty, ir)
      assert(!functionBody(ir, "test_main").contains("store { ptr, ptr }"), ir)
      verify(ir, 42, sanitized = true)
    }
  }
