package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, IngestStage}
import mml.mmlclib.errors.CompilerWarning
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintModule

class LinkDirectiveTests extends BaseEffFunSuite:

  test("optional header preserves order, repeats, locations, and member docs") {
    val source = """// Header
                   |@link["one", "two", // library comment
                   |"three", "one"];
                   |/* The answer. */
                   |let answer = 42;
                   |""".stripMargin
    parseNotFailed(source).flatMap { module =>
      val directive = module.linkDirective.getOrElse(fail("Missing directive"))
      assertEquals(directive.entries.map(_.name), List("one", "two", "three", "one"))
      directive.entries.foreach { entry =>
        val text = source.substring(entry.span.start.index, entry.span.end.index)
        assert(text.contains(s"\"${entry.name}\""), text)
      }
      assertEquals(
        module.members.collect { case b: Bnd => b.docComment.map(_.text) },
        List("The answer.".some)
      )
      assert(prettyPrintModule(module).contains(directive.syntax))
      FrontEndApi.compile(source, "Links", CompilerConfig.ast("build")).value.map {
        case Right(state) =>
          assertEquals(state.errors, Vector.empty)
          assertEquals(state.linkEntries, directive.entries)
          assertEquals(state.module.linkDirective, directive.some)
        case Left(error) => fail(error.toString)
      }
    }
  }

  test("modules without a directive retain member documentation") {
    parseNotFailed("/* Answer. */ let answer = 42;").map { module =>
      assertEquals(module.linkDirective, none[LinkDirective])
      assertEquals(
        module.members.collect { case b: Bnd => b.docComment.map(_.text) },
        List("Answer.".some)
      )
      assertEquals(IngestStage.fromSource("let answer = 42;", "Plain").linkEntries, Nil)
    }
  }

  List(
    "@link[\"let/thing\"];",
    "@link[\"x; fn\"];",
    "@link[42, // let in comment\n\"x\"];",
    "@link[];",
    "@link[\"\"];",
    "@link[42];",
    "@link[other \"x\"];",
    "@link[\"x\",];",
    "@link[\"x\" \"y\"];",
    "@link[\"x\";",
    "@link[\"x\"]",
    "@link[\"../x\"];",
    "@link[\"-lfoo\"];",
    "@link[static \"x\"];",
    "@link[shared \"x\"];",
    "@link[\"a b\"];",
    "@link[\"a let b\"];",
    "@link[\"x\"]; @link[\"y\"];",
    "let before = 1; @link[\"x\"];"
  ).foreach { header =>
    test(s"invalid header recovers following declarations: $header") {
      val source = s"$header let after = 42;"
      for
        _ <- parseFailedWithErrors(source)
        module <- justParse(source)
        _ = assert(module.members.exists { case b: Bnd => b.name == "after"; case _ => false })
        _ = assert(IngestStage.fromSource(source, "BadLinks").hasErrors)
      yield ()
    }
  }

  test("unterminated link names preserve following declarations on the same line") {
    val cases = for
      name <- List("x", "let", "../let")
      value <- List("42", "\"hello\"", "\"]\"", "\"];\"")
      terminator <- List("];", "]", ";")
    yield s"@link[\"$name$terminator let after = $value;\nlet use = after;"

    cases.traverse_ { source =>
      for
        _ <- parseFailedWithErrors(source)
        module <- justParse(source)
        _ = assertEquals(module.members.collect { case b: Bnd => b.name }, List("after", "use"))
        result <- semState(source)
        _ = assertEquals(result.errors.size, 1, source)
      yield ()
    }
  }

  test("closed invalid names preserve quotes and declaration-like text") {
    val cases = for
      name <- List(
        "x]let",
        "x]; fn",
        "x; let fake = 42;",
        "x] let fake = 42;",
        "x; let fake = 2147483648;",
        "x; let fake = "
      )
      value <- List("42", "\";\"")
    yield s"@link[\"$name\"]; let after = $value;\nlet use = after;"

    cases.traverse_ { source =>
      for
        module <- justParse(source)
        _ = assertEquals(module.members.collect { case b: Bnd => b.name }, List("after", "use"))
        result <- semState(source)
        _ = assertEquals(result.errors.size, 1, source)
      yield ()
    }
  }

  test("duplicate and misplaced directives remain errors at end of file") {
    List(
      "@link[\"one\"]; @link[\"two\"];",
      "let answer = 42; @link[\"one\"];"
    ).traverse_ { source =>
      parseFailedWithErrors(source).map { errors =>
        assert(errors.nonEmpty)
        assert(IngestStage.fromSource(source, "TrailingLink").hasErrors)
      }
    }
  }

  test("directive is rejected inside an expression") {
    semFailed("fn bad(): Int = @link[\"x\"]; 42;;\nlet after = 1;")
  }

  test("object mode discards inputs and warns once per entry, including repeats") {
    val source    = "@link[\"one\", \"two\", \"one\"]; let answer = 42;"
    val state     = IngestStage.fromSource(source, "Discard", CompilerConfig.library("build"))
    val validated = CodegenStage.validate(state)
    assert(!validated.hasErrors)
    assert(validated.canEmitCode)
    assertEquals(
      validated.warnings.toList,
      state.linkEntries.map(CompilerWarning.DiscardedLinkDirective.apply)
    )
  }

  test("AST and IR modes retain inputs without library lookup") {
    val source = "@link[\"mml_missing_library\"]; let answer = 42;"
    val ast    = IngestStage.fromSource(source, "NoLookup", CompilerConfig.ast("build"))
    assert(!CodegenStage.validate(ast).hasErrors)
    assertEquals(CodegenStage.validate(ast).warnings, Vector.empty)
    compileAndGenerate(source, config = CompilerConfig.default.copy(mode = CompilationMode.Ir))
      .map(ir => assert(ir.contains("; @link[\"mml_missing_library\"];")))
  }
