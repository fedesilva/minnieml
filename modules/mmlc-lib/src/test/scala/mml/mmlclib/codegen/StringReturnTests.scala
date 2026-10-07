package mml.mmlclib.codegen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class StringReturnTests extends BaseEffFunSuite:

  private case class ReturnCase(
    name:     String,
    body:     String,
    expected: List[String],
    clones:   Int,
    owned:    Boolean = true
  ):

    def source: String = s"""
      fn caption(kind: Int): String = $body;;
      pub fn main(args: StringArray): Int =
        let kind = ar_str_len args;
        let value = caption kind;
        let expected =
          if kind == 0 then "${expected(0)}";
          elif kind == 1 then "${expected(1)}";
          else "${expected(2)}";
          ;
        if str_eq value expected then 0; else 1;;
      ;
    """

  private val cases = List(
    ReturnCase(
      "direct mixed branches",
      "if kind == 0 then int_to_str 123; else \"abc\";",
      List("123", "abc", "abc"),
      clones = 1
    ),
    ReturnCase(
      "local allocation before mixed branches",
      """let text = int_to_str 123;
         if kind == 0 then text ++ "!"; else "abc";""",
      List("123!", "abc", "abc"),
      clones = 1
    ),
    ReturnCase(
      "nested allocating alternatives",
      """if kind == 0 then int_to_str 123;
         elif kind == 1 then int_to_str 456;
         else "abc";""",
      List("123", "456", "abc"),
      clones = 1
    ),
    ReturnCase(
      "local allocation inside the other branch",
      """if kind == 0 then "abc";
         else let text = int_to_str 123; text ++ "!";""",
      List("abc", "123!", "123!"),
      clones = 1
    ),
    ReturnCase(
      "nested static alternatives",
      """if kind == 0 then int_to_str 123;
         elif kind == 1 then "abc";
         else "def";""",
      List("123", "abc", "def"),
      clones = 2
    ),
    ReturnCase(
      "owned local alias and static alternative",
      """if kind == 0 then
           let text = int_to_str 123;
           let alias = text;
           alias;
         else "abc";""",
      List("123", "abc", "abc"),
      clones = 1
    ),
    ReturnCase(
      "incidental allocation with static returns",
      """let text = int_to_str 123;
         println text;
         if kind == 0 then "abc"; else "def";""",
      List("abc", "def", "def"),
      clones = 0,
      owned  = false
    ),
    ReturnCase(
      "both scoped branches allocate",
      """if kind == 0 then let text = int_to_str 123; text ++ "!";
         else let text = int_to_str 456; text ++ "!";""",
      List("123!", "456!", "456!"),
      clones = 0
    )
  )

  private def member(module: Module, name: String): Bnd =
    module.members.collectFirst { case b: Bnd if b.name == name => b }.get

  private def referencesTo(module: Module, value: Term, name: String): List[Ref] =
    val target = member(module, name).id
    TermTraversal.collect(value) { case ref: Ref if ref.resolvedId == target => ref }

  cases.foreach { example =>
    test(s"return ownership: ${example.name}") {
      semNotFailed(example.source).map { module =>
        val cloneName = TypeUtils.cloneFnFor("String", module.resolvables).get
        val freeName  = TypeUtils.freeFnFor("String", module.resolvables).get
        val caption   = member(module, "caption").value
        val main      = member(module, "main").value
        assertEquals(referencesTo(module, caption, cloneName).size, example.clones)
        assertEquals(referencesTo(module, main, freeName).size, if example.owned then 1 else 0)
      }
    }
  }

  private def withDirectory(use: Path => IO[Unit]): IO[Unit] =
    IO.blocking(Files.createTempDirectory("mml-string-return-"))
      .bracket(use) { directory =>
        Resource.fromAutoCloseable(IO.blocking(Files.walk(directory))).use { paths =>
          IO.blocking(paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).void
        }
      }

  private def execute(source: String, optimization: Int): IO[Unit] =
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
        ir <- compileAndGenerate(source, "StringReturn", config)
        triple <- LlvmToolchain.queryLocalTriple.map(_.getOrElse(fail("Missing target")))
        target <- ClangTarget
          .resolve(directory, LlvmToolchain.clangFlags(config, triple))
          .map(_.fold(error => fail(error.message), identity))
        path <- IO.blocking(Files.writeString(directory.resolve("StringReturn.ll"), ir))
        _ <- commandNotFailed(List("llvm-as", path.toString, "-o", "/dev/null"), directory)
        result <- LlvmToolchain.compile(path, config, triple.some, target)
        _ = assertEquals(result._1, Right(0))
        _ <- List(0, 1, 2).traverse_ { count =>
          val command = List(
            "env",
            "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1",
            binary.toString
          ) ++ List.fill(count)("argument")
          programExits(command, directory, expectedCode = 0)
        }
      yield ()
    }

  for example <- cases; optimization <- List(0, 3) do
    test(s"sanitized return at O$optimization: ${example.name}") {
      execute(example.source, optimization)
    }

  for optimization <- List(0, 3) do
    test(s"mixed returns evaluate selected predicates and values once at O$optimization") {
      val source = """
        fn mark(counts: IntArray, index: Int, result: Bool): Bool =
          ar_int_set counts index (ar_int_get counts index + 1);
          result;
        ;
        fn make_text(counts: IntArray): String =
          ar_int_set counts 2 (ar_int_get counts 2 + 1);
          int_to_str 123;
        ;
        fn suffix(text: String, counts: IntArray): String =
          ar_int_set counts 3 (ar_int_get counts 3 + 1);
          text ++ "!";
        ;
        fn caption(kind: Int, counts: IntArray): String =
          let text = make_text counts;
          if mark counts 0 (kind == 0) then suffix text counts;
          elif mark counts 1 (kind == 1) then "abc";
          else "def";
          ;
        ;
        pub fn main(args: StringArray): Int =
          let kind = ar_str_len args;
          let counts = ar_int_new 4;
          ar_int_fill counts 0;
          let value = caption kind counts;
          let expected = if kind == 0 then "123!"; elif kind == 1 then "abc"; else "def";;
          let inner = if kind == 0 then 0; else 1;;
          let allocated = if kind == 0 then 1; else 0;;
          if str_eq value expected and ar_int_get counts 0 == 1 and
             ar_int_get counts 1 == inner and ar_int_get counts 2 == 1 and
             ar_int_get counts 3 == allocated then 0;
          else 1;
          ;
        ;
      """
      execute(source, optimization)
    }

  test("nested borrowed return remains rejected through local scopes") {
    semState("""
      fn caption(text: String, kind: Int): String =
        let local = int_to_str 123;
        if kind == 0 then local ++ "!";
        elif kind == 1 then let alias = text; alias;
        else "abc";
        ;
      ;
    """).map { result =>
      val parameter = member(result.module, "caption").value.terms.collectFirst {
        case lambda: Lambda => lambda.params.head
      }.get
      val borrowed = result.errors.collect { case error: SemanticError.BorrowEscapeViaReturn =>
        error.ref.resolvedId
      }
      assertEquals(borrowed, List(parameter.id))
    }
  }
