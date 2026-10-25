package mml.mmlclib.lsp

import cats.effect.{ExitCode, IO}
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.{Bnd, FromSource, Ref, SrcPoint, SrcSpan, TermTraversal}
import mml.mmlclib.compiler.{CodegenStage, Compilation, CompilerConfig, CompilerState}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.{SemanticError, TypeError}
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Files

class DiagnosticPresentationTests extends BaseEffFunSuite:

  private val source       = "let value = missingOne missingTwo;"
  private val rootNames    = Vector("missingOne", "missingTwo")
  private val rootMessages = rootNames.map(name => s"Undefined reference: '$name'")

  private def originalSpans: IO[Vector[SrcSpan]] =
    parseNotFailed(source).map { module =>
      val refs = module.members
        .collect { case binding: Bnd => binding }
        .flatMap(binding => TermTraversal.collect(binding.value) { case ref: Ref => ref })
      assertEquals(refs.map(_.name).toVector, rootNames)
      refs.map(_.spanOpt.getOrElse(fail("Expected written reference span"))).toVector
    }

  private def compile(config: CompilerConfig): IO[CompilerState] =
    CompilerApi.compileSource(source, "Presentation", config).map {
      case Right(state) => state
      case Left(error) => fail(s"Expected retained compiler state, got $error")
    }

  test("retained diagnostics and LSP preserve independent root messages, spans, and order") {
    for
      expectedSpans <- originalSpans
      state <- compile(CompilerConfig.default)
    yield
      assertEquals(state.visibleErrors.map(_.message), rootMessages)
      assert(state.errors.size > state.visibleErrors.size)
      val consequences = state.diagnostics.records.filter { record =>
        record.relation match
          case DiagnosticRelation.Consequence(_) => true
          case DiagnosticRelation.Primary => false
      }
      assert(consequences.nonEmpty)
      val primary = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
      assertEquals(primary.map(_.error.message), rootMessages)
      assertEquals(
        primary.map(_.error).collect { case error: SemanticError.UndefinedRef =>
          error.ref.spanOpt
        },
        expectedSpans.map(Some(_))
      )
      assert(consequences.forall(_.causes.forall(id => primary.exists(_.id == id))))

      val lsp = Diagnostics.fromCompilerState(state)
      assertEquals(lsp.map(_.message), rootNames.map(name => s"Undefined reference: $name").toList)
      assertEquals(lsp.map(_.range), expectedSpans.map(Range.fromSrcSpan).toList)
      assert(lsp.forall(_.severity == DiagnosticSeverity.Error))

  }

  test("quiet AST, IR, and native entrypoints return only the original independent failures") {
    withSourceFixture(source, "presentation.mml") { (path, config) =>
      for
        ast <- CompilerApi.processAstQuiet(path, config)
        ir <- CompilerApi.processIrQuiet(path, config)
        native <- CompilerApi.compileNativeQuiet(path, config)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        val expected = Some(rootMessages.mkString("\n"))
        assertEquals(ast.left.toOption, expected)
        assertEquals(ir.left.toOption, expected)
        assertEquals(native.left.toOption, expected)
        assert(!outputExists)
    }
  }

  test("the normal CLI reports only original errors and exits before writing an artifact") {
    withSourceFixture(source, "presentation.mml") { (path, config) =>
      for
        expectedSpans <- originalSpans
        // Collect reported effects without redirecting the shared JVM console.
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, message => output.update(_ :+ message))
        messages <- output.get
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assertEquals(exit, ExitCode.Error)
        assert(!outputExists)
        val rendered = messages.mkString("\n")
        val headers  = rootNames.map(name => s"Undefined reference '$name'")
        headers.foreach { header =>
          assertEquals(rendered.linesIterator.count(_.contains(header)), 1, rendered)
        }
        assert(rendered.indexOf(headers.head) < rendered.indexOf(headers.last), rendered)
        expectedSpans.foreach { span =>
          val excerpt = SourceInfo(source).slice(span.start.index, span.end.index)
          assert(rendered.contains(ErrorPrinter.formatLocation(span)), rendered)
          assert(
            rendered.contains(s"${Console.RED}${Console.BOLD}$excerpt${Console.RESET}"),
            rendered
          )
        }
        assert(!rendered.contains("Invalid expression"), rendered)
    }
  }

  test("binding mismatch highlights end at the semicolon before a following comment") {
    val comment = "// c depends on b; this comment is outside the failed binding."
    val code = List(
      "let a = 1;",
      """let b: Int = "2";""",
      comment,
      "let c = a + b;"
    ).mkString("\n")
    val expectedSpan = SrcSpan(SrcPoint(2, 5, 15), SrcPoint(2, 18, 28))
    val message      = "Type mismatch: expected Int, got String"

    withSourceFixture(code, "binding-boundary.mml") { (path, config) =>
      for
        state <- Compilation.compileSource(code, "BindingBoundary", path.toString, config)
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, text => output.update(_ :+ text))
        messages <- output.get
      yield
        assertEquals(state.visibleErrors.map(_.message), Vector(message))
        val roots = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
        assertEquals(roots.size, 1)
        val root = roots.head
        val actualSpan = root.error match
          case SemanticError.TypeCheckingError(mismatch: TypeError.TypeMismatch) =>
            mismatch.node match
              case node: FromSource => node.spanOpt
              case other => fail(s"Expected source-bearing mismatch, got $other")
          case other => fail(s"Expected a binding type mismatch, got $other")
        assertEquals(actualSpan, Some(expectedSpan))
        assertEquals(code.slice(15, 28), """b: Int = "2";""")
        val consequences = state.diagnostics.records.filterNot(_.id == root.id)
        assert(consequences.nonEmpty)
        assert(consequences.forall(_.causes == Vector(root.id)), consequences)

        val diagnostics = Diagnostics.fromCompilerState(state)
        assertEquals(diagnostics.map(_.message), List(message))
        assertEquals(diagnostics.map(_.range), List(Range(Position(1, 4), Position(1, 17))))
        assertEquals(diagnostics.map(_.severity), List(DiagnosticSeverity.Error))

        assertEquals(exit, ExitCode.Error)
        val rendered = messages.mkString("\n")
        assertEquals(rendered.linesIterator.count(_.contains("Type mismatch at")), 1, rendered)
        assert(
          rendered.contains("Type mismatch at [2:5]-[2:18]: expected 'Int', got 'String'"),
          rendered
        )
        val highlighted = s"${Console.RED}${Console.BOLD}b: Int = \"2\";${Console.RESET}"
        assert(rendered.contains(highlighted), rendered)
        val commentLines = rendered.linesIterator.filter(_.contains(comment)).toList
        assertEquals(commentLines.size, 1, rendered)
        assert(commentLines.forall(line => !line.contains(Console.RED)), rendered)
        assert(commentLines.forall(line => !line.contains(Console.BOLD)), rendered)
        assert(!rendered.contains("Invalid expression"), rendered)
    }
  }

  test("retained consequences do not weaken the raw-error codegen gate") {
    withSourceFixture(source, "presentation.mml") { (_, config) =>
      for
        state <- compile(config)
        validated = CodegenStage.validate(state.copy(canEmitCode = true))
        emitted <- CodegenStage.emitIrOnly(validated)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assert(state.hasErrors)
        assert(state.errors.size > state.visibleErrors.size)
        assert(!validated.canEmitCode)
        assertEquals(validated.errors, state.errors)
        assertEquals(validated.diagnostics.records, state.diagnostics.records)
        assertEquals(emitted.errors, state.errors)
        assertEquals(emitted.llvmIr, None)
        assertEquals(emitted.nativeResult, None)
        assert(!outputExists)
    }
  }
