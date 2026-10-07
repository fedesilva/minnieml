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
      fn identity(x: Int): Int = x;;
      fn combine(x: Int, y: Int): Int = x + y;;
      fn encode(flag: Bool): Int = if flag then 1; else 2;;;
      fn increment(x: Int): Int = @native[tpl="add i32 %operand, 1"];;
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
    argumentCounts: List[Int] = List(0)
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
            expectedCode = 0
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
    test(s"call exits preserve loop results and effect order at O$optimization") {
      IO.blocking(Files.readString(Path.of("tests/mem/call-exit-blocks.mml")))
        .flatMap(execute(_, optimization, List(0, 1, 2)))
    }

    for allocating <- List(false, true) do
      test(s"caption branches remain valid at O$optimization (allocating=$allocating)") {
        IO.blocking(Files.readString(Path.of("tests/mem/caption-returns.mml")))
          .map(source => if allocating then allocatingCaptions(source) else source)
          .flatMap(execute(_, optimization))
      }
