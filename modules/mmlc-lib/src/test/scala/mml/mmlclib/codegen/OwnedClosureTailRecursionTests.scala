package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class OwnedClosureTailRecursionTests extends BaseEffFunSuite:

  // Native counters observe payload lifetime and argument effects independently of generated IR.
  private val runtime = """
    #include <stdint.h>
    #include <stdio.h>
    #include <stdlib.h>
    #include <string.h>
    typedef struct { int32_t length; char *data; } String;
    static int live, peak, allocated, destroyed, clones, ticks, trace, literal_checks;
    static uintptr_t stack_low = UINTPTR_MAX, stack_high;
    void mml_sys_flush(void) {}
    void mml_free_raw(void *p) { free(p); }
    String int_to_str(int32_t n) {
      char text[32];
      int size = snprintf(text, sizeof(text), "%d", n);
      char *data = malloc(size + 1);
      memcpy(data, text, size + 1);
      ++allocated;
      if (++live > peak) peak = live;
      return (String){size, data};
    }
    void __free_String(String s) { --live; ++destroyed; free(s.data); }
    String __clone_String(String s) {
      ++clones;
      char *data = malloc(s.length + 1);
      memcpy(data, s.data, s.length);
      data[s.length] = 0;
      ++allocated;
      if (++live > peak) peak = live;
      return (String){s.length, data};
    }
    void tick(void) {
      // Local variables can live on ASan's fake stack; measure the actual call frame.
      uintptr_t address = (uintptr_t)__builtin_frame_address(0);
      if (address < stack_low) stack_low = address;
      if (address > stack_high) stack_high = address;
      ++ticks;
    }
    void observe_literal(String s) {
      if (s.length == 4 && memcmp(s.data, "tail", 4) == 0) ++literal_checks;
    }
    int32_t mark(int32_t n) { trace = trace * 10 + n; return n; }
    int32_t observed(void) { return trace; }
    void effect(void) { mark(2); }
    void *allocate(int32_t n) { int32_t *p = malloc(sizeof(*p)); *p = n; return p; }
    int32_t inspect(const int32_t *p) { return *p; }
    void release(int32_t *p) { mark(*p); free(p); }
    int32_t pointer_matches(const char *p) { return memcmp(p, "42", 2) == 0; }
    int32_t balanced(void) { return live == 0 && allocated == destroyed && clones == 0; }
    int32_t stress_ok(void) {
      printf("allocations=%d destructions=%d peak=%d ticks=%d stack_span=%llu clones=%d\n",
        allocated, destroyed, peak, ticks,
        (unsigned long long)(stack_high - stack_low), clones);
      return balanced() && allocated == 1000000 && ticks == 1000000 &&
        literal_checks == 1000000 &&
        peak == 1 && stack_high - stack_low < 16384;
    }
  """

  private def verify(
    ir:            String,
    expected:      Int            = 0,
    optimization:  Int            = 0,
    sanitized:     Boolean        = true,
    smallStack:    Boolean        = false,
    expectedError: Option[String] = None
  ): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-tail-cleanup"))
      .bracket { dir =>
        val input     = dir.resolve("program.ll")
        val support   = dir.resolve("runtime.c")
        val optimized = dir.resolve("optimized.ll")
        val binary    = dir.resolve("program")
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
              optimized.toString
            ),
            dir
          ) *> commandNotFailed(
            List(
              "clang",
              s"-O$optimization",
              "-Wno-override-module",
              optimized.toString,
              support.toString,
              "-o",
              binary.toString
            ) ++
              (if sanitized then List("-fsanitize=address", "-fno-omit-frame-pointer") else Nil),
            dir
          ) *> programExits(
            if smallStack then
              List("sh", "-c", "ulimit -s 256; exec \"$1\"", "tail-cleanup", binary.toString)
            else
              List(
                "env",
                "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=0",
                binary.toString
              )
            ,
            dir,
            expected,
            expectedError = expectedError
          )
      } { dir =>
        IO.blocking(Files.walk(dir))
          .bracket { paths =>
            IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
          }(paths => IO.blocking(paths.close()))
      }

  /** Emit sanitizer-eligible IR; verify selects whether native execution uses ASan. */
  private def compile(source: String, noTco: Boolean = false): IO[String] =
    compileAndGenerate(source, config = CompilerConfig.exe("build", noTco = noTco, asan = true))

  for optimization <- List(0, 3) do
    test(s"ASan checks generated LLVM memory accesses at O$optimization") {
      verify(
        """declare ptr @malloc(i64)
          |declare void @free(ptr)
          |define i32 @main() sanitize_address {
          |entry:
          |  %data = call ptr @malloc(i64 4)
          |  call void @free(ptr %data)
          |  %value = load volatile i32, ptr %data
          |  ret i32 0
          |}
          |""".stripMargin,
        expected      = 1,
        optimization  = optimization,
        expectedError = "ERROR: AddressSanitizer: heap-use-after-free".some
      )
    }

  private def assertLoop(body: String): Unit =
    val cleanup = body.indexOf("call void @test___free_")
    assert(cleanup >= 0, body)
    assert(body.indexOf("br label %loop.header", cleanup) > cleanup, body)
    assert(!body.contains("alloca %struct.String"), body)
    assertPhiPredecessors(body)

  private val helpers = """
    fn make(~text: String): Unit -> Int = ~{ text.length; };;
    fn balanced(): Int = @native;;
    fn mark(n: Int): Int = @native;;
    fn observed(): Int = @native;;
    fn effect(): Unit = @native;;
  """

  private case class CleanupCase(
    name:       String,
    creation:   String,
    invocation: String = "f ()"
  )

  private val creations = List(
    CleanupCase("local", "let text = int_to_str n; let f = ~{ text.length; };"),
    CleanupCase("returned", "let f = make (int_to_str n);"),
    CleanupCase("alias", "let original = make (int_to_str n); let f = original;"),
    CleanupCase("nested closure", "let inner = make (int_to_str n); let f = ~{ inner (); };"),
    CleanupCase(
      "nested struct",
      "let record = Outer (Inner (int_to_str n)); let f = ~{ record.inner.text.length; };"
    ),
    CleanupCase(
      "function field",
      "let record = Holder (make (int_to_str n)); let f = ~{ record.f (); };"
    ),
    CleanupCase("PAP", "let f = add n;", "f 0"),
    CleanupCase("owning PAP", "let f = take (int_to_str n);", "f 0"),
    CleanupCase(
      "conditional",
      "let f = if n > 1 then make (int_to_str n); else make (int_to_str 1);;"
    ),
    CleanupCase("mixed", "let f = if n > 1 then make (int_to_str n); else { 1; };;")
  )

  for fixture <- creations do
    test(s"${fixture.name} cleanup preserves a loop and balanced payloads") {
      val source = s"""
        $helpers
        struct Inner { text: String };
        struct Outer { inner: Inner };
        struct Holder { f: Unit -> Int };
        fn add(n: Int, extra: Int): Int = n + extra;;
        fn take(~text: String, extra: Int): Int = text.length + extra;;
        fn loop(n: Int, acc: Int): Int =
          if n == 0 then acc;
          else
            ${fixture.creation}
            loop (n - 1) (acc + (${fixture.invocation}));
          ;
        ;
        pub fn main(): Int =
          let result = loop 4 0;
          if result > 0 and balanced () == 1 then 0; else 1;;
        ;
      """
      compile(source).flatMap { ir =>
        assertLoop(functionBody(ir, "test_loop"))
        verify(ir)
      }
    }

  test("conditional scalar and Unit arguments run once in source order before cleanup") {
    compile(s"""
      $helpers
      fn loop(n: Int, acc: Int, u: Unit): Int =
        if n == 0 then let ignored = u; acc;
        else
          let f = make (int_to_str n);
          loop
            (let ignored = mark 3; n - 1)
            (if n > 0 then let ignored = mark 1; acc + f (); else 0;)
            (if n > 0 then effect (); else ();)
          ;
        ;
      ;
      pub fn main(): Int =
        let result = loop 1 0 ();
        if result == 1 and observed () == 312 and balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      assertLoop(functionBody(ir, "test_loop"))
      verify(ir)
    }
  }

  for capturing <- List(false, true) do
    test(s"local recursive entry retains Unit bindings and cleanup, capturing=$capturing") {
      val capture = if capturing then "seed" else "1"
      compile(s"""
        $helpers
        fn run(seed: Int): Int =
          fn loop(n: Int, u: Unit): Int =
            if n == 0 then let ignored = u; $capture;
            else
              let f = make (int_to_str n);
              let used = f ();
              loop (n - 1) (if used > 0 then effect (); else (););
            ;
          ;
          loop 2 ();
        ;
        pub fn main(): Int =
          let result = run 1;
          if result == 1 and observed () == 22 and balanced () == 1 then 0; else 1;;
        ;
      """).flatMap { ir =>
        val loop = functionBodyMatching(ir, "[^\\n]*loop[^\\n]*")
        assertLoop(loop)
        verify(ir)
      }
    }

  test("Unit recursive arguments retain effects and final blocks without cleanup wrappers") {
    compile(s"""
      $helpers
      fn loop(n: Int, u: Unit): Int =
        if n == 0 then let ignored = u; 0;
        else loop (n - 1) (if n > 0 then effect (); else (););
        ;
      ;
      pub fn main(): Int =
        let result = loop 2 ();
        if result == 0 and observed () == 22 then 0; else 1;;
      ;
    """).flatMap { ir =>
      val body = functionBody(ir, "test_loop")
      assert(body.contains("loop.header"), body)
      assert(body.contains("call void @effect("), body)
      assertPhiPredecessors(body)
      verify(ir)
    }
  }

  test("shadowing and transitive borrowed closures preserve argument lifetimes") {
    compile(s"""
      $helpers
      fn loop(n: Int, acc: Int): Int =
        if n == 0 then acc;
        else
          let f = make (int_to_str n);
          let borrowed = { f (); };
          let indirect = borrowed;
          let inner = (let f = make (int_to_str 22); f ());
          loop (n - 1) (acc + inner + indirect ());
        ;
      ;
      pub fn main(): Int =
        let result = loop 3 0;
        if result == 9 and balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      assertLoop(functionBody(ir, "test_loop"))
      verify(ir)
    }
  }

  test("conditional transfer discharges retained cleanup on the other path") {
    compile(s"""
      $helpers
      fn drop(~f: Unit -> Int): Unit = let ignored = f (); ();;
      fn loop(n: Int): Unit =
        if n > 0 then
          let f = make (int_to_str n);
          if n > 1 then
            drop f;
            loop (n - 1);
          else
            loop (n - 1);
          ;
        ;
      ;
      pub fn main(): Int =
        loop 3;
        if balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      assertLoop(functionBody(ir, "test_loop"))
      verify(ir)
    }
  }

  test("consuming recursive arguments transfer cleanup exactly once") {
    compile(s"""
      $helpers
      fn drop(~f: Unit -> Int): Unit = let ignored = f (); ();;
      fn loop(n: Int, ~previous: Unit -> Int): Unit =
        drop previous;
        if n > 0 then
          let f = make (int_to_str n);
          loop (n - 1) f;
        ;
      ;
      pub fn main(): Int =
        loop 3 (make (int_to_str 0));
        if balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      val body = functionBody(ir, "test_loop")
      assert(body.contains("loop.header"), body)
      assert(!body.contains("call void @test___free_"), body)
      assertPhiPredecessors(body)
      verify(ir)
    }
  }

  private val handles = """
    type Handle = @native[t=ptr, mem=heap, free=release];
    fn allocate(n: Int): Handle = @native[mem=alloc];;
    fn inspect(p: Handle): Int = @native;;
    fn release(~p: Handle): Unit = @native;;
  """

  test("custom destructors run after recursive calls in their original order") {
    compile(s"""
      $helpers
      $handles
      fn loop(n: Int): Unit =
        if n > 0 then
          let payload = allocate n;
          let f = ~{ inspect payload; };
          let ignored = f ();
          loop (n - 1);
        ;
      ;
      pub fn main(): Int = loop 3; if observed () == 123 then 0; else 1;;;
    """).flatMap { ir =>
      val body = functionBody(ir, "test_loop")
      val call = body.indexOf("call void @test_loop")
      assert(call >= 0, body)
      assert(body.indexOf("call void @test___free_", call) > call, body)
      assert(!body.contains("loop.header"), body)
      verify(ir)
    }
  }

  for borrowed <- List("f", "alias", "indirect") do
    test(s"recursive argument borrowing through $borrowed retains the owner") {
      val aliases = borrowed match
        case "alias" => "let alias = { f (); };"
        case "indirect" => "let alias = { f (); }; let indirect = { alias (); };"
        case _ => ""
      compile(s"""
        $helpers
        fn loop(n: Int, previous: Unit -> Int): Int =
          if n == 0 then previous ();
          else
            let f = make (int_to_str n);
            $aliases
            loop (n - 1) $borrowed;
          ;
        ;
        pub fn main(): Int =
          let result = loop 3 { 0; };
          if result == 1 and balanced () == 1 then 0; else 1;;
        ;
      """).flatMap { ir =>
        val body = functionBody(ir, "test_loop")
        assert(body.contains("call i32 @test_loop"), body)
        assert(!body.contains("loop.header"), body)
        verify(ir)
      }
    }

  for argument <- List("alias", "{ alias (); }") do
    test(s"immediate borrowing parameters retain their owner through $argument") {
      compile(s"""
        $helpers
        fn loop(n: Int, previous: Unit -> Int): Int =
          if n == 0 then previous ();
          else
            let f = make (int_to_str n);
            { alias: (Unit -> Int) -> loop (n - 1) $argument; } f;
          ;
        ;
        pub fn main(): Int =
          let result = loop 3 { 0; };
          if result == 1 and balanced () == 1 then 0; else 1;;
        ;
      """).flatMap { ir =>
        val body = functionBody(ir, "test_loop")
        assert(body.contains("call i32 @test_loop"), body)
        assert(!body.contains("loop.header"), body)
        verify(ir)
      }
    }

  private val pointerUses = List(
    "direct" -> "pointer_matches (f ())",
    "alias" -> "let pointer = f (); pointer_matches pointer",
    "field alias" -> "let box = Pointer (f ()); pointer_matches box.data"
  )

  for (name, use) <- pointerUses do
    test(s"$name pointer argument retains its closure payload until the call returns") {
      compile(s"""
        $helpers
        struct Pointer { data: CharPtr };
        fn pointer_matches(p: CharPtr): Int = @native;;
        fn run(): Int =
          let text = int_to_str 42;
          let f = ~{ text.data; };
          $use;
        ;
        pub fn main(): Int =
          let result = run ();
          if result == 1 and balanced () == 1 then 0; else 1;;
        ;
      """).flatMap { ir =>
        val body = functionBody(ir, "test_run")
        val call = body.indexOf("call i32 @pointer_matches")
        assert(call >= 0, body)
        assert(body.indexOf("call void @test___free_", call) > call, body)
        verify(ir)
      }
    }

  private val preCaptureAliases = List(
    "local closure" ->
      "let pointer = text.data; let f = ~{ text.length; }; pointer_matches pointer;",
    "returned closure" ->
      "let pointer = text.data; let f = make text; pointer_matches pointer;",
    "nested closure and alias" ->
      """let pointer = text.data; let alias = pointer;
        |let inner = make text; let f = ~{ inner (); }; pointer_matches alias;""".stripMargin,
    "pointer field" ->
      "let box = Pointer text.data; let f = make text; pointer_matches box.data;",
    "borrowed reader" ->
      """let pointer = text.data; let read = { pointer_matches pointer; };
        |let f = ~{ text.length; }; read ();""".stripMargin
  )

  for
    (name, body) <- preCaptureAliases
    optimization <- List(0, 3)
  do
    test(s"pre-capture $name alias retains its backing storage at O$optimization") {
      compile(s"""
        $helpers
        struct Pointer { data: CharPtr };
        fn pointer_matches(p: CharPtr): Int = @native;;
        fn run(): Int =
          let text = int_to_str 42;
          $body
        ;
        pub fn main(): Int =
          let result = run ();
          if result == 1 and balanced () == 1 then 0; else 1;;
        ;
      """).flatMap { ir =>
        val emitted = functionBody(ir, "test_run")
        val call    = emitted.indexOf("call i32 ")
        assert(call >= 0, emitted)
        assert(emitted.indexOf("call void @test___free_", call) > call, emitted)
        verify(ir, optimization = optimization)
      }
    }

  test("an unrelated literal pointer permits early closure cleanup") {
    compile(s"""
      $helpers
      fn pointer_matches(p: CharPtr): Int = @native;;
      fn run(): Int =
        let text = "42";
        let pointer = text.data;
        let payload = int_to_str 42;
        let f = ~{ payload.length; };
        pointer_matches pointer;
      ;
      pub fn main(): Int =
        let result = run ();
        if result == 1 and balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      val body    = functionBody(ir, "test_run")
      val cleanup = body.indexOf("call void @test___free_")
      assert(cleanup >= 0, body)
      assert(body.indexOf("call i32 @pointer_matches", cleanup) > cleanup, body)
      verify(ir)
    }
  }

  test("retained cleanup does not delay an unrelated closure release") {
    compile(s"""
      $helpers
      fn use(f: Unit -> Int): Int = f ();;
      fn run(): Int =
        let first = int_to_str 1;
        let retained = ~{ first.length; };
        let second = int_to_str 2;
        let unrelated = ~{ second.length; };
        use retained;
      ;
      pub fn main(): Int =
        let result = run ();
        if result == 1 and balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      val body         = functionBody(ir, "test_run")
      val call         = body.indexOf("call i32 @test_use")
      val firstCleanup = body.indexOf("call void @test___free_")
      val lastCleanup  = body.lastIndexOf("call void @test___free_")

      assert(call >= 0, body)
      assert(firstCleanup >= 0 && firstCleanup < call, body)
      assert(lastCleanup > call, body)
      verify(ir)
    }
  }

  for optimization <- List(0, 3) do
    test(s"returned PAP cleanup preserves a shared borrowed factory at O$optimization") {
      compile("""
        fn run(): Int =
          let add = { n: Int, extra: Int -> n + extra; };
          let make = { n: Int -> add n; };
          fn loop(n: Int, total: Int): Int =
            if n == 0 then total;
            else
              let f = make 1;
              let result = f 1;
              loop (n - 1) (total + result);
            ;
          ;
          loop 1000000 0;
        ;
        pub fn main(): Int = if run () == 2000000 then 0; else 1;;;
      """).flatMap { ir =>
        assertLoop(functionBodyMatching(ir, "[^\\n]*loop[^\\n]*"))
        verify(ir, optimization = optimization)
      }
    }

  test("unknown callable alternatives cannot prove storage-only destruction") {
    compile(s"""
      $helpers
      $handles
      fn choose(flag: Bool, ~unknown: Unit -> Int): Unit -> Int =
        if flag then make (int_to_str 1); else unknown;;
      ;
      fn loop(n: Int, ~unknown: Unit -> Int): Unit =
        if n > 0 then
          let f = choose false unknown;
          loop (n - 1) (make (int_to_str n));
        ;
      ;
      fn custom(n: Int): Unit -> Int =
        let p = allocate n;
        ~{ inspect p; };
      ;
      pub fn main(): Int = loop 3 (custom 3); if observed () == 3 then 0; else 1;;;
    """).flatMap { ir =>
      val body = functionBody(ir, "test_loop")
      assert(body.contains("call void @test_loop"), body)
      assert(!body.contains("loop.header"), body)
      verify(ir)
    }
  }

  test("no-tco preserves ordinary calls after safe cleanup placement") {
    compile(
      s"""
      $helpers
      fn loop(n: Int): Int =
        if n == 0 then 0;
        else
          let f = make (int_to_str n);
          let ignored = f ();
          loop (n - 1);
        ;
      ;
      pub fn main(): Int = loop 4;;
    """,
      noTco = true
    ).flatMap { ir =>
      val body = functionBody(ir, "test_loop")
      assert(body.contains("call i32 @test_loop"), body)
      assert(!body.contains("loop.header"), body)
      verify(ir)
    }
  }

  test("recursive callable-origin evidence retains late cleanup") {
    compile(s"""
      $helpers
      fn recursive(n: Int): Unit -> Int =
        if n > 0 then recursive (n - 1);
        else make (int_to_str 1);;
      ;
      fn loop(n: Int): Unit =
        if n > 0 then
          let f = recursive 1;
          loop (n - 1);
        ;
      ;
      pub fn main(): Int =
        loop 3;
        if balanced () == 1 then 0; else 1;;
      ;
    """).flatMap { ir =>
      val body = functionBody(ir, "test_loop")
      assert(body.contains("call void @test_loop"), body)
      assert(!body.contains("loop.header"), body)
      verify(ir)
    }
  }

  for
    optimization <- List(0, 3)
    sanitized <- List(false, true)
  do
    test(s"million iterations bound live payloads and stack at O$optimization, ASan=$sanitized") {
      compile("""
        fn tick(): Unit = @native;;
        fn observe_literal(text: String): Unit = @native;;
        fn stress_ok(): Int = @native;;
        fn make(~text: String): Unit -> Int = ~{ tick (); text.length; };;
        fn loop(n: Int): Unit =
          if n > 0 then
            observe_literal "tail";
            let f = make (int_to_str n);
            let length = f ();
            loop (n - 1);
          ;
        ;
        pub fn main(): Int =
          loop 1000000;
          if stress_ok () == 1 then 0; else 1;;
        ;
      """).flatMap { ir =>
        assertLoop(functionBody(ir, "test_loop"))
        verify(ir, optimization = optimization, sanitized = sanitized, smallStack = !sanitized)
      }
    }
