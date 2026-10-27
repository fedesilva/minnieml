package mml.mmlclib.semantic

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, CompilerState, IngestStage}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.lsp.{AstLookup, DiagnosticSeverity, Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Files

class TypeRecoveryIntegrationTests extends BaseEffFunSuite:

  private val source =
    """let broken: wrong = 1;
      |let alias = broken;
      |let independent = missing;
      |let good = 7;
      |let later = good;""".stripMargin
  private val parseMessage = "Parse error in type: Invalid type syntax"
  private val rootMessages = Vector(parseMessage, "Undefined reference: 'missing'")

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected binding $name"))

  private def lambda(value: Bnd): Lambda =
    value.value.terms
      .collectFirst { case function: Lambda => function }
      .getOrElse(fail("Expected function lambda"))

  private def malformed(annotation: Option[Type]): ParseTypeError =
    annotation
      .collect { case error: ParseTypeError => error }
      .getOrElse(fail("Expected malformed annotation"))

  private def spanOf(code: String, fragment: String): SrcSpan =
    val start = code.indexOf(fragment)
    assert(start >= 0, s"Missing fixture fragment: $fragment")
    val info = SourceInfo(code)
    SrcSpan(info.pointAt(start), info.pointAt(start + fragment.length))

  private def localFunction(module: Module, code: String): Lambda =
    val start = code.indexOf('(', code.indexOf("fn inner"))
    val functions = TermTraversal.collect(binding(module, "outer").value) {
      case function: Lambda if function.spanOpt.exists(_.start.index == start) => function
    }
    assertEquals(functions.size, 1)
    functions.head

  private def compile(
    code:   String         = source,
    config: CompilerConfig = CompilerConfig.default
  ): IO[CompilerState] =
    CompilerApi.compileSource(code, "TypeRecovery", config).map {
      case Right(state) => state
      case Left(error) => fail(s"Expected retained compiler state, got $error")
    }

  test("malformed annotation source survives parsing and ingest with one exact diagnostic root") {
    for
      parsed <- parseNotFailed(source)
      ingested <- IO.blocking(IngestStage.fromSource(source, "TypeRecovery"))
    yield
      val original = malformed(binding(parsed, "broken").typeAsc)
      val stamped  = malformed(binding(ingested.module, "broken").typeAsc)
      assertEquals(original.spanOpt, spanOf(source, "wrong ").some)
      assertEquals(original.failedCode, "wrong ".some)
      assertEquals(original.message, "Invalid type syntax")
      assertEquals(original.causes, Vector.empty)
      assertEquals(stamped.copy(causes = Vector.empty), original)
      assertEquals(ingested.errors.map(_.message), Vector(parseMessage))
      assertEquals(ingested.diagnostics.records.map(_.relation), Vector(DiagnosticRelation.Primary))
      assertEquals(stamped.causes, ingested.diagnostics.records.map(_.id))
      assertEquals(binding(ingested.module, "later").spanOpt, binding(parsed, "later").spanOpt)
  }

  test("malformed annotations taint aliases while independent errors and definitions survive") {
    compile().map { state =>
      val broken       = binding(state.module, "broken")
      val alias        = binding(state.module, "alias")
      val good         = binding(state.module, "good")
      val later        = binding(state.module, "later")
      val parseError   = malformed(broken.typeAsc)
      val availability = ValueAvailability.fromModule(state.module)
      val causes       = DiagnosticCauses.fromModule(state.module)
      val aliasRefs    = TermTraversal.collect(alias.value) { case reference: Ref => reference }
      val laterRefs    = TermTraversal.collect(later.value) { case reference: Ref => reference }

      assertEquals(state.visibleErrors.map(_.message), rootMessages)
      assert(!state.semanticErrors.exists(_.isInstanceOf[SemanticError.UndefinedTypeRef]))
      assertEquals(aliasRefs.map(_.resolvedId), List(broken.id))
      assert(broken.id.nonEmpty)
      assertEquals(causes.knownRootsOf(alias.value), parseError.causes)
      assertEquals(parseError.causes.size, 1)
      assert(!availability.isAvailable(alias.value))
      assert(availability.isAvailable(good.value))
      assert(availability.isAvailable(later.value))
      assertEquals(laterRefs.map(_.resolvedId), List(good.id))
      assert(good.id.nonEmpty)
      assertEquals(good.id.flatMap(state.module.resolvables.lookup), good.some)

      val use = laterRefs.head.spanOpt.getOrElse(fail("Expected later reference span"))
      assertEquals(
        AstLookup.findDefinitionAt(state.module, use.start.line, use.start.col),
        good.spanOpt.toList
      )
      val failed = spanOf(source, "wrong ").start
      assertEquals(AstLookup.findDefinitionAt(state.module, failed.line, failed.col), Nil)
      assertEquals(
        AstLookup.findReferencesAt(
          state,
          failed.line,
          failed.col,
          includeDeclaration = true
        ),
        Nil
      )
    }
  }

  test("an absent parameter annotation and an explicitly empty annotation remain distinct") {
    val absentSource = "fn absent(value) = 1;;"
    val emptySource  = "fn empty(value:) = 1;;"
    for
      absent <- compile(absentSource)
      empty <- compile(emptySource)
    yield
      val missing = absent.semanticErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.MissingParameterType) => error
      }
      assertEquals(missing.size, 1)
      assert(missing.head.param.typeAsc.isEmpty)
      assert(!absent.semanticErrors.exists(_.isInstanceOf[SemanticError.ParseTypeErrorFound]))
      assert(!empty.semanticErrors.exists {
        case SemanticError.TypeCheckingError(_: TypeError.MissingParameterType) => true
        case _ => false
      })
      val errors = empty.semanticErrors.collect {
        case SemanticError.ParseTypeErrorFound(error, _) => error
      }
      assertEquals(errors.size, 1)
      val boundary = SourceInfo(emptySource).pointAt(emptySource.indexOf(')'))
      assertEquals(errors.head.spanOpt, SrcSpan(boundary, boundary).some)
      assertEquals(errors.head.failedCode, None)
      assertEquals(empty.visibleErrors.map(_.message), Vector("Parse error in type: Expected type"))
  }

  test("a malformed function result shares its original root across mirrored annotations") {
    val code = "fn broken(value: Int): wrong = value;; let after = 7;"
    IO.blocking(IngestStage.fromSource(code, "TypeRecovery")).map { state =>
      val declaration = binding(state.module, "broken")
      val result      = malformed(declaration.typeAsc)
      val function    = lambda(declaration)
      assert(function.typeAsc.exists(_ eq result))
      assertEquals(result.spanOpt, spanOf(code, "wrong ").some)
      assertEquals(result.failedCode, "wrong ".some)
      assertEquals(state.visibleErrors.map(_.message), Vector(parseMessage))
      assertEquals(result.causes, state.diagnostics.records.map(_.id))
      assertEquals(state.diagnostics.records.size, 1)
      assertEquals(function.params.size, 1)
      assert(function.params.head.typeAsc.exists(_.isInstanceOf[TypeRef]))
      assertEquals(function.params.head.typeAsc.flatMap(_.spanOpt), spanOf(code, "Int").some)
      assert(binding(state.module, "after").source.isFromSource)
    }
  }

  List("arg", "arg: Int", "").foreach { parameters =>
    List("wrong", "", "Int ->").foreach { result =>
      test(s"local function result '$result' survives ingest with parameters '$parameters'") {
        val code = s"fn outer(): Int = fn inner($parameters): $result = 1;; 2;;"
        IO.blocking(IngestStage.fromSource(code, "LocalTypeRecovery")).map { state =>
          val errors = state.semanticErrors.collect {
            case SemanticError.ParseTypeErrorFound(error, _) => error
          }
          assertEquals(errors.size, 1)
          val end   = code.indexOf("= 1")
          val start = if result == "wrong" then code.indexOf(result) else end
          val info  = SourceInfo(code)
          assertEquals(errors.head.spanOpt, SrcSpan(info.pointAt(start), info.pointAt(end)).some)
          assertEquals(errors.head.failedCode, Option.when(start < end)(code.substring(start, end)))
          assertEquals(state.diagnostics.records.size, 1)
          assertEquals(state.diagnostics.records.head.relation, DiagnosticRelation.Primary)
          assertEquals(
            DiagnosticCauses
              .fromModule(state.module)
              .knownRootsOf(localFunction(state.module, code)),
            state.diagnostics.records.map(_.id)
          )
          val literals = TermTraversal
            .collect(binding(state.module, "outer").value) { case value: LiteralInt =>
              value
            }
            .sortBy(_.spanOpt.map(_.start.index))
          assertEquals(literals.map(_.value), List(1L, 2L))
          assertEquals(literals.last.spanOpt, spanOf(code, "2").some)
        }
      }
    }
  }

  List("2" -> true, "let alias = inner; alias 1" -> false).foreach { (continuation, available) =>
    test(s"a failed local result preserves independent continuation '$continuation'") {
      val code = "fn outer(): Int = fn inner(value): wrong = value + 1;; " +
        s"$continuation;; let independent = missing;"
      compile(code).map { state =>
        val function     = localFunction(state.module, code)
        val availability = ValueAvailability.fromModule(state.module)
        val parseRoots = state.diagnostics.records.collect {
          case record if record.error.isInstanceOf[SemanticError.ParseTypeErrorFound] => record.id
        }
        assertEquals(state.visibleErrors.map(_.message), rootMessages)
        assertEquals(parseRoots.size, 1)
        assertEquals(DiagnosticCauses.fromModule(state.module).knownRootsOf(function), parseRoots)
        assert(!availability.isAvailable(function))
        assertEquals(availability.isAvailable(binding(state.module, "outer").value), available)
        val references = TermTraversal.collect(function.body) { case reference: Ref => reference }
        val parameter  = function.params.head
        assert(parameter.id.nonEmpty)
        assert(references.exists(_.resolvedId == parameter.id))
      }
    }
  }

  test("independent malformed and unresolved type annotations retain separate visible roots") {
    val code =
      "let first: wrong = 1; let second: nope = 2; let unknown: Missing=3; let good = 7;"
    compile(code).map { state =>
      val parseErrors = state.semanticErrors.collect {
        case SemanticError.ParseTypeErrorFound(error, _) => error
      }
      val unresolved = state.semanticErrors.collect { case error: SemanticError.UndefinedTypeRef =>
        error
      }
      val parseRoots = state.diagnostics.records.collect {
        case record if record.error.isInstanceOf[SemanticError.ParseTypeErrorFound] => record.id
      }
      val stamped = Vector("first", "second").map { name =>
        malformed(binding(state.module, name).typeAsc)
      }
      assertEquals(parseErrors.map(_.failedCode), Vector("wrong ".some, "nope ".some))
      assertEquals(parseErrors.map(_.spanOpt), Vector("wrong ", "nope ").map(spanOf(code, _).some))
      assertEquals(parseRoots.distinct.size, 2)
      assertEquals(stamped.map(_.causes), parseRoots.map(Vector(_)))
      assertEquals(stamped.map(_.spanOpt), parseErrors.map(_.spanOpt))
      assertEquals(unresolved.map(_.typeRef.spanOpt), Vector(spanOf(code, "Missing").some))
      assertEquals(state.visibleErrors.size, 3)
      val primary = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
      assertEquals(primary.map(_.error), state.visibleErrors)
      assertEquals(primary.map(_.id).distinct.size, 3)
      val good = binding(state.module, "good")
      assert(ValueAvailability.fromModule(state.module).isAvailable(good.value))
    }
  }

  test("LSP reports the malformed fragment and independent error at their original ranges") {
    compile().map { state =>
      val diagnostics = Diagnostics.fromCompilerState(state)
      assertEquals(diagnostics.map(_.message), List(parseMessage, "Undefined reference: missing"))
      assertEquals(
        diagnostics.map(_.range),
        List("wrong ", "missing").map(fragment => Range.fromSrcSpan(spanOf(source, fragment)))
      )
      assert(diagnostics.forall(_.severity == DiagnosticSeverity.Error))
    }
  }

  test("CLI and quiet entrypoints report original failures without producing artifacts") {
    withSourceFixture(source, "type-recovery.mml") { (path, config) =>
      for
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, message => output.update(_ :+ message))
        messages <- output.get
        ast <- CompilerApi.processAstQuiet(path, config)
        ir <- CompilerApi.processIrQuiet(path, config)
        native <- CompilerApi.compileNativeQuiet(path, config)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assertEquals(exit, ExitCode.Error)
        val rendered = messages.mkString("\n")
        val headers  = List("Type parser error", "Undefined reference 'missing'")
        headers.foreach { header =>
          assertEquals(rendered.linesIterator.count(_.contains(header)), 1, rendered)
        }
        assert(rendered.indexOf(headers.head) < rendered.indexOf(headers.last), rendered)
        assert(rendered.contains("Invalid type syntax"), rendered)
        List("wrong ", "missing").foreach { fragment =>
          assert(rendered.contains(ErrorPrinter.formatLocation(spanOf(source, fragment))), rendered)
          assert(
            rendered.contains(s"${Console.RED}${Console.BOLD}$fragment${Console.RESET}"),
            rendered
          )
        }
        assert(!rendered.contains("Type mismatch"), rendered)
        assert(!rendered.contains("Invalid expression"), rendered)
        val expected = rootMessages.mkString("\n").some
        assertEquals(ast.left.toOption, expected)
        assertEquals(ir.left.toOption, expected)
        assertEquals(native.left.toOption, expected)
        assert(!outputExists)
    }
  }

  test("retained malformed annotation errors prevent codegen even when emission is requested") {
    withSourceFixture(source, "type-recovery.mml") { (_, config) =>
      for
        state <- compile(config = config)
        validated = CodegenStage.validate(state.copy(canEmitCode = true))
        emitted <- CodegenStage.emitIrOnly(validated)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assert(state.hasErrors)
        assertEquals(state.visibleErrors.map(_.message), rootMessages)
        assert(!validated.canEmitCode)
        assertEquals(validated.diagnostics, state.diagnostics)
        assertEquals(emitted.diagnostics, state.diagnostics)
        assertEquals(emitted.llvmIr, None)
        assertEquals(emitted.nativeResult, None)
        assertEquals(emitted.resolvedTriple, None)
        assert(!outputExists)
    }
  }

  test("valid explicit and inferred bindings remain available and retain reference identity") {
    semNotFailed("let value: Int = 1; let alias = value; let later = alias;").map { module =>
      val value        = binding(module, "value")
      val alias        = binding(module, "alias")
      val later        = binding(module, "later")
      val availability = ValueAvailability.fromModule(module)
      assert(List(value, alias, later).forall(binding => availability.isAvailable(binding.value)))
      val aliasRefs = TermTraversal.collect(alias.value) { case reference: Ref => reference }
      val laterRefs = TermTraversal.collect(later.value) { case reference: Ref => reference }
      assertEquals(aliasRefs.map(_.resolvedId), List(value.id))
      assertEquals(laterRefs.map(_.resolvedId), List(alias.id))
      assert(value.id.nonEmpty)
      assert(alias.id.nonEmpty)
    }
  }
