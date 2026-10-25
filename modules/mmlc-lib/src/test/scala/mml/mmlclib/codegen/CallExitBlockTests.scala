package mml.mmlclib.codegen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class CallExitBlockTests extends BaseEffFunSuite:

  private case class CallCase(name: String, expression: String, setup: String = ""):

    def source: String = s"""
      struct Ops { call: Int -> Int };
      struct Value { value: Int };
      fn identity(x: Int): Int = x;;
      fn combine(x: Int, y: Int): Int = x + y;;
      fn predicate(flag: Bool): Bool = flag;;
      fn invoke(f: Unit -> Bool): Bool = f ();;
      fn encode(flag: Bool): Int = if flag then 1; else 2;;;
      fn increment(x: Int): Int = @metal:tpl["add i32 %operand, 1"];;
      fn keep(effect: Unit, value: Int): Int = value;;
      fn pick(enabled: Bool, flag: Bool, f: Int -> Int, box: Ops, u: Int -> Unit): Int =
        $setup
        if enabled then $expression;
        else 3;
        ;
      ;
    """

  private val calls = List(
    CallCase("ordinary call", "identity (if flag then 1; else 2;)"),
    CallCase("first argument", "combine (if flag then 1; else 2;) 10"),
    CallCase("last argument", "combine 10 (if flag then 1; else 2;)"),
    CallCase(
      "two conditional arguments",
      "combine (if flag then 1; else 2;) (if flag then 10; else 20;)"
    ),
    CallCase("native call", "str_to_int (if flag then \"1\"; else \"2\";)"),
    CallCase("native template", "increment (if flag then 1; else 2;)"),
    CallCase("binary left operand", "(if flag then 1; else 2;) + 10"),
    CallCase("binary right operand", "10 + (if flag then 1; else 2;)"),
    CallCase("unary operand", "encode (not (if flag then false; else true;))"),
    CallCase("Unit argument", "keep (if flag then println \"yes\"; else println \"no\";) 7"),
    CallCase("indirect call", "f (if flag then 1; else 2;)"),
    CallCase("indirect Unit call", "u (if flag then 1; else 2;); 7"),
    CallCase("qualified callee", "(if flag then box; else box;).call 7"),
    CallCase("qualified callee predicate call", "(if predicate flag then box; else box;).call 7"),
    CallCase("qualified callee predicate operator", "(if not flag then box; else box;).call 7"),
    CallCase(
      "qualified callee predicate call and operator",
      "(if encode flag == 1 then box; else box;).call 7"
    ),
    CallCase(
      "qualified callee capturing predicate",
      "(if invoke { flag } then box; else box;).call 7"
    ),
    CallCase(
      "qualified value capturing predicate",
      "(if invoke { flag } then yes; else no;).value",
      "let yes = Value 1; let no = Value 2;"
    ),
    CallCase(
      "arguments then qualified callee",
      "(if flag then box; else box;).call (if flag then 1; else 2;)"
    ),
    CallCase("local direct call", "local (if flag then 1; else 2;)", "fn local(x: Int): Int = x;;"),
    CallCase(
      "known capturing call",
      "local (if flag then 1; else 2;)",
      "let seed = 10; fn local(x: Int): Int = x + seed;;"
    ),
    CallCase(
      "known qualified callee",
      "(if flag then local; else local;).call 7",
      "let local = Ops identity;"
    ),
    CallCase(
      "known qualified environment",
      "(if flag then local; else local;).call 7",
      "let seed = 10; fn ~add(x: Int): Int = x + seed;; let local = Ops add;"
    )
  )

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-call-exit-"))
      .bracket(use) { directory =>
        Resource.fromAutoCloseable(IO.blocking(Files.walk(directory))).use { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
        }
      }

  private def assemble(ir: String, directory: Path): IO[Unit] =
    val path = directory.resolve("program.ll")
    IO.blocking(Files.writeString(path, ir)) *>
      commandNotFailed(List("llvm-as", path.toString, "-o", "/dev/null"), directory)

  calls.foreach { example =>
    test(s"conditional exits survive ${example.name}") {
      compileAndGenerate(example.source).flatMap { ir =>
        assertPhiPredecessors(functionBody(ir, "test_pick"))
        withDirectory(assemble(ir, _))
      }
    }
  }

  private val loop = """
    fn fill(values: IntArray, count: Int): Unit =
      fn loop(i: Int): Unit =
        if i < count then
          let selected = if i == 0 then true; else false;;
          ar_int_set values i (if selected then 1; else 0;);
          loop (i + 1);
        ;
      ;
      loop 0;
    ;
  """

  for noTco <- List(false, true) do
    test(s"conditional argument before recursive back edge (noTco=$noTco)") {
      compileAndGenerate(loop, config = CompilerConfig.default.copy(noTco = noTco)).flatMap { ir =>
        val body = functionBodyMatching(ir, "test_loop_\\d+\\([^\\n]*")
        assertEquals(body.contains("loop.header:"), !noTco)
        assertPhiPredecessors(body)
        withDirectory(assemble(ir, _))
      }
    }

  private def execute(
    source:         String,
    optimization:   Int,
    argumentCounts: List[Int]            = List(0),
    expectedOutput: Option[List[String]] = None
  ): IO[Unit] =
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
        ir <- compileAndGenerate(source, "CallExit", config)
        _ <- assemble(ir, directory)
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing target")))
        target <- ClangTarget
          .resolve(directory, LlvmToolchain.clangFlags(config, triple))
          .map(_.fold(error => fail(error.message), identity))
        result <- LlvmToolchain.compile(
          directory.resolve("program.ll"),
          config,
          triple.some,
          target
        )
        _ = assertEquals(result._1, Right(0))
        _ <- argumentCounts.traverse_ { count =>
          programExits(
            List(
              "env",
              "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1",
              binary.toString
            ) ++ List.fill(count)("argument"),
            directory,
            expectedCode   = 0,
            expectedOutput = expectedOutput
          )
        }
      yield ()
    }

  private def allocatingCaptions(source: String): String =
    val substitutions = List(
      "\"Goal reached! Reveal the final path.\";" ->
        "\"Goal reached! \" ++ \"Reveal the final path.\";",
      "\"No path found. The queue is empty. Close the window when done.\";" ->
        "\"No path found. \" ++ \"The queue is empty. Close the window when done.\";",
      "\"All reachable candidates have been processed.\";" ->
        "\"All reachable candidates \" ++ \"have been processed.\";"
    )
    substitutions.foldLeft(source) { case (current, (original, replacement)) =>
      assert(current.contains(original), s"Missing caption branch: $original")
      current.replace(original, replacement)
    }

  for optimization <- List(0, 3) do
    test(s"transferring a PAP payload preserves its retained borrowed callee at O$optimization") {
      execute(
        """
          struct Holder { call: String -> Int -> Int };
          pub fn main(): Int =
            let seed = int_to_str 7;
            let holder = Holder ~{ ~payload: String, n: Int ->
              seed.length + payload.length + n;
            };
            let text = int_to_str 123;
            let partial = ({ holder.call; } ()) text;
            partial 2 + holder.call (int_to_str 4) 3 - 11;
          ;
        """,
        optimization
      )
    }

    test(s"inline scalar returned-callee PAPs reach ownership sinks at O$optimization") {
      execute(
        """
          struct Holder { call: Int -> Int };
          fn make(seed: Int): Int -> Int -> Int =
            ~{ a: Int, b: Int -> seed + a + b; };
          ;
          fn consume(~call: Int -> Int): Int = call 2;;
          pub fn main(): Int =
            let inline_consumed = consume ((make 9) 1);
            let consumed = (make 19) 1;
            let bound_consumed = consume consumed;
            let inline_holder = Holder ((make 29) 1);
            let stored = (make 39) 1;
            let bound_holder = Holder stored;
            inline_consumed + bound_consumed + inline_holder.call 2 + bound_holder.call 2 - 108;
          ;
        """,
        optimization
      )
    }

    test(s"returned-callee PAPs transfer fresh environments to ownership sinks at O$optimization") {
      execute(
        """
          struct Holder { call: Int -> Int };
          fn make(~seed: String): Int -> Int -> Int =
            ~{ a: Int, b: Int -> str_to_int seed + a + b; };
          ;
          fn consume(~call: Int -> Int): Int = call 2;;
          fn drop(~call: Int -> Int): Int = 0;;
          pub fn main(): Int =
            let inline_consumed = consume ((make (int_to_str 9)) 1);
            let consumed = (make (int_to_str 19)) 1;
            let bound_consumed = consume consumed;
            let inline_holder = Holder ((make (int_to_str 29)) 1);
            let stored = (make (int_to_str 39)) 1;
            let bound_holder = Holder stored;
            let inline_dropped = drop ((make (int_to_str 49)) 1);
            let dropped = (make (int_to_str 59)) 1;
            let bound_dropped = drop dropped;
            let unused_inline_holder = Holder ((make (int_to_str 69)) 1);
            let unused_stored = (make (int_to_str 79)) 1;
            let unused_bound_holder = Holder unused_stored;
            inline_consumed + bound_consumed + inline_holder.call 2 + bound_holder.call 2 +
              inline_dropped + bound_dropped - 108;
          ;
        """,
        optimization
      )
    }

    test(s"staged PAP creation evaluates operands before its returned callee at O$optimization") {
      execute(
        """
          fn first(): Int = println "first"; 1;;
          fn second(): Int = println "second"; 2;;
          fn make(seed: Int): Int -> Int -> Int =
            println "make";
            ~{ a: Int, b: Int -> seed + a + b; };
          ;
          pub fn main(): Int =
            let q = (make (first ())) (second ());
            println "created";
            let result = q 3;
            println (int_to_str result);
            result - 6;
          ;
        """,
        optimization,
        expectedOutput = List("first", "second", "make", "created", "6").some
      )
    }

    for (factory, expected) <- List("pick" -> 48, "pick_owned" -> 63) do
      test(s"grouped and aliased returned $factory callees agree with bindings at O$optimization") {
        execute(
          s"""
          type Callback = Int -> Int -> Int;
          fn add(a: Int, b: Int): Int = a + b;;
          fn pick(): Callback = add;;
          fn make(seed: Int): Callback = ~{ a: Int, b: Int -> seed + a + b; };;
          fn partial(seed: Int): Int -> Int = (make seed) 1;;
          fn pick_owned(): Callback = make 5;;
          fn inline_apply(f: Unit -> (Int -> Int -> Int)): Int =
            let q = (f ()) 10;
            q 2;
          ;
          fn bound_apply(f: Unit -> (Int -> Int -> Int)): Int =
            let g = f ();
            let q = g 10;
            q 2;
          ;
          fn alias_apply(f: Unit -> (Callback)): Int =
            let q = (f ()) 10;
            q 2;
          ;
          pub fn main(): Int =
            inline_apply $factory + bound_apply $factory + alias_apply $factory +
              (partial 9) 2 - $expected;
          ;
        """,
          optimization
        )
      }

    test(s"returned callee partial applications execute at O$optimization") {
      execute(
        """
          fn add(a: Int, b: Int): Int = a + b;;
          fn pick(): Int -> Int -> Int = add;;
          fn make(seed: Int): Int -> Int -> Int = ~{ a: Int, b: Int -> seed + a + b; };;
          fn take(~text: String, a: Int, b: Int): Int = text.length + a + b;;
          fn make_once(~text: String): Int -> Int -> Int =
            ~{ a: Int, b: Int -> take text a b; };;
          fn apply(f: Int -> Int, n: Int): Int = f n;;

          pub fn main(): Int =
            let plain = (pick ()) 10;
            let owned = (make 45) 1;
            let dropped = (make 7) 1;
            let once = (make_once (int_to_str 123)) 1;
            let dropped_once = (make_once (int_to_str 789)) 1;
            apply plain 2 + plain 3 + apply owned 2 + owned 3 + once 2 - 128;
          ;
        """,
        optimization
      )
    }

    test(s"call exits preserve loop results and effect order at O$optimization") {
      IO.blocking(Files.readString(Path.of("tests/mem/call-exit-blocks.mml")))
        .flatMap(execute(_, optimization, List(0, 1, 2)))
    }

    test(s"temporary field owners survive projections and calls at O$optimization") {
      IO.blocking(Files.readString(Path.of("tests/mem/field-qualifier-owners.mml")))
        .flatMap(execute(_, optimization, List(0, 1, 2, 3)))
    }

    for allocating <- List(false, true) do
      test(s"caption branches remain valid at O$optimization (allocating=$allocating)") {
        IO.blocking(Files.readString(Path.of("tests/mem/caption-returns.mml")))
          .map(source => if allocating then allocatingCaptions(source) else source)
          .flatMap(execute(_, optimization))
      }
