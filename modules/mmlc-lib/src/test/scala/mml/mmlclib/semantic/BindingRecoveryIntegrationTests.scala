package mml.mmlclib.semantic

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, CompilerState, IngestStage}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.lsp.{DiagnosticSeverity, Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Files

class BindingRecoveryIntegrationTests extends BaseEffFunSuite:

  private val source =
    """fn example(): Int =
      |  let broken = missing @;
      |  3 + 4;
      |;
      |let after = 7;""".stripMargin

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected source binding $name"))

  @scala.annotation.tailrec
  private def function(value: Expr): Lambda = value match
    case TXExprLambda(lambda) => lambda
    case TXExpr1(invalid: InvalidExpression) => function(invalid.originalExpr)
    case other => fail(s"Expected function value, got $other")

  private def spanOf(code: String, fragment: String): SrcSpan =
    val start = code.indexOf(fragment)
    assert(start >= 0, s"Missing fixture fragment: $fragment")
    val info = SourceInfo(code)
    SrcSpan(info.pointAt(start), info.pointAt(start + fragment.length))

  private def references(value: Term): List[Ref] =
    TermTraversal.collect(value) { case reference: Ref => reference }

  private def parseErrors(value: Term): List[ParseTermError] =
    TermTraversal.collect(value) { case error: ParseTermError => error }

  private def malformedAnnotations(value: Term): List[ParseTypeError] =
    TermTraversal
      .collect(value) { case term =>
        term.typeAsc.collect { case error: ParseTypeError => error }
      }
      .flatten
      .distinct
      .sortBy(_.spanOpt.map(_.start.index))

  private def onlyParseError(value: Term): ParseTermError =
    val errors = parseErrors(value)
    assertEquals(errors.size, 1)
    errors.head

  private case class LocalBinding(application: App, scope: Lambda):

    def initializer: Expr = application.arg

    def parameter: FnParam =
      assertEquals(scope.params.size, 1)
      scope.params.head

  private def localBinding(value: Term, code: String, declaration: String): LocalBinding =
    val start = spanOf(code, declaration).start.index
    val bindings = TermTraversal
      .collect(value) {
        case app: App if app.spanOpt.exists(_.start.index == start) =>
          app.fn match
            case scope: Lambda if scope.source == app.source => LocalBinding(app, scope).some
            case _ => None
      }
      .flatten
    assertEquals(bindings.size, 1, s"Expected scope for $declaration")
    bindings.head

  private def sourceFunction(value: Term, code: String, startText: String): Lambda =
    val start = spanOf(code, startText).start.index
    val functions = TermTraversal.collect(value) {
      case lambda: Lambda if lambda.spanOpt.exists(_.start.index == start) => lambda
    }
    assertEquals(functions.size, 1, s"Expected source function at $startText")
    functions.head

  private def resolved(ingested: CompilerState): CompilerState =
    val module  = injectCommonFunctions(injectStandardOperators(injectBasicTypes(ingested.module)))
    val checked = DuplicateNameChecker.rewriteModule(ingested.withModule(module))
    phasesBeforeExpressionRewriting.foldLeft(checked) { (state, phase) =>
      ResolvablesIndexer.rewriteModule(phase(state))
    }

  private def compile(
    code:   String         = source,
    config: CompilerConfig = CompilerConfig.default
  ): IO[CompilerState] =
    CompilerApi.compileSource(code, "BindingRecovery", config).map {
      case Right(state) => state
      case Left(error) => fail(s"Expected retained compiler state, got $error")
    }

  private def assertAddition(value: Expr, state: CompilerState): Unit =
    val operator = protocolImplementation(state, "Num", "Int", "+", Some(CallableArity.Binary))
    assert(operator.id.nonEmpty)
    value match
      case TXExprApp(reference, _, List(TXExprInt(3), TXExprInt(4))) =>
        assertEquals(reference.resolvedId, operator.id)
      case other => fail(s"Expected normalized independent addition, got $other")

  List(
    ("missing equals", "fn broken(x: Int): Int x + 1;;", "Expected '='", ""),
    ("malformed body", "fn broken(x: Int): Int = x + 1 @;;", "Invalid expression syntax", "@")
  ).foreach { (label, declaration, message, fragment) =>
    test(s"a complete header retains parameter scope through $label"):
      val code = s"$declaration let after = 7;"
      for
        parsed <- parseNotFailed(code)
        ingested <- IO.blocking(IngestStage.fromSource(code, "BindingRecovery"))
        checked <- compile(code)
      yield
        val original = function(binding(parsed, "broken").value)
        val error    = onlyParseError(original)
        val expectedSpan =
          if fragment.nonEmpty then spanOf(code, fragment)
          else
            val point = SourceInfo(code).pointAt(code.indexOf("x + 1"))
            SrcSpan(point, point)
        assertEquals(error.message, message)
        assertEquals(error.failedCode, Option.when(fragment.nonEmpty)(fragment))
        assertEquals(error.spanOpt, expectedSpan.some)
        assertEquals(original.params.map(_.nameNode.spanOpt), List(spanOf(code, "x").some))
        assert(binding(parsed, "broken").meta.nonEmpty)
        assert(
          TermTraversal.collect(original) { case invalid: InvalidExpression => invalid }.isEmpty
        )
        original.body.terms match
          case List(prefix: Expr, terminal: ParseTermError) =>
            assertEquals(terminal, error)
            val parameterUse = spanOf(code, "x + 1").start
            assert(references(prefix).exists(_.spanOpt.exists(_.start.index == parameterUse.index)))
          case other => fail(s"Expected retained prefix and terminal parse failure, got $other")

        val stamped = onlyParseError(binding(ingested.module, "broken").value)
        assertEquals(stamped.copy(causes = Vector.empty), error)
        assertEquals(stamped.causes, ingested.diagnostics.records.map(_.id))
        assertEquals(
          ingested.diagnostics.records.map(_.relation),
          Vector(DiagnosticRelation.Primary)
        )
        val beforeNormalization = resolved(ingested)
        val retained            = function(binding(beforeNormalization.module, "broken").value)
        val parameter           = retained.params.head
        assert(parameter.id.nonEmpty)
        assert(references(retained.body).exists(_.resolvedId == parameter.id))
        assert(
          !beforeNormalization.semanticErrors.exists(_.isInstanceOf[SemanticError.UndefinedRef])
        )
        val checkedFunction = function(binding(checked.module, "broken").value)
        assert(
          references(checkedFunction.body).exists(_.resolvedId == checkedFunction.params.head.id)
        )
        assertEquals(checked.visibleErrors.size, 1, checked.visibleErrors)
        assertEquals(checked.visibleErrors.head, ingested.visibleErrors.head)
        assert(!ValueAvailability.fromModule(checked.module).isAvailable(checkedFunction))
        assert(
          ValueAvailability
            .fromModule(checked.module)
            .isAvailable(binding(checked.module, "after").value)
        )
  }

  List("x: Int, )" -> ", ", "x: Int " -> "").foreach { (parameters, fragment) =>
    test(s"a partial header '$parameters' preserves real parameters without a callable value"):
      val code = s"fn broken($parameters = x;; let alias = broken; let after = 7;"
      for
        parsed <- parseNotFailed(code)
        state <- compile(code)
      yield
        val original         = binding(parsed, "broken")
        val originalFunction = function(original.value)
        assertEquals(originalFunction.params.map(_.nameNode.spanOpt), List(spanOf(code, "x").some))
        assert(original.meta.isEmpty)
        assert(original.typeSpec.isEmpty)
        assert(original.typeAsc.isEmpty)
        assert(originalFunction.typeSpec.isEmpty)
        assert(originalFunction.typeAsc.isEmpty)
        val error = onlyParseError(originalFunction)
        assertEquals(error.message, "Invalid parameter list")
        assertEquals(error.failedCode, Option.when(fragment.nonEmpty)(fragment))
        val broken       = binding(state.module, "broken")
        val retained     = function(broken.value)
        val alias        = binding(state.module, "alias")
        val availability = ValueAvailability.fromModule(state.module)
        val causes       = DiagnosticCauses.fromModule(state.module)
        val roots        = onlyParseError(retained).causes
        assertEquals(roots.size, 1)
        assertEquals(state.visibleErrors.size, 1, state.visibleErrors)
        assert(retained.typeSpec.isEmpty)
        assert(broken.meta.isEmpty)
        assert(retained.params.head.id.nonEmpty)
        assert(references(retained.body).exists(_.resolvedId == retained.params.head.id))
        assert(broken.id.nonEmpty)
        assert(references(alias.value).exists(_.resolvedId == broken.id))
        List(broken.value, alias.value).foreach { value =>
          assert(!availability.isAvailable(value))
          assertEquals(causes.knownRootsOf(value), roots)
        }
        assert(availability.isAvailable(binding(state.module, "after").value))
  }

  test("a failed header without a parameter prefix cannot invent a nullary function"):
    val code = "fn broken(, ) = 1;; let alias = broken; let after = 7;"
    for
      parsed <- parseNotFailed(code)
      state <- compile(code)
    yield
      List(parsed, state.module).foreach { module =>
        val broken = binding(module, "broken")
        assert(TermTraversal.collect(broken.value) { case lambda: Lambda => lambda }.isEmpty)
        assert(broken.meta.isEmpty)
        assert(broken.typeSpec.isEmpty)
      }
      val availability = ValueAvailability.fromModule(state.module)
      assert(!availability.isAvailable(binding(state.module, "alias").value))
      assert(availability.isAvailable(binding(state.module, "after").value))
      assertEquals(state.visibleErrors.size, 1, state.visibleErrors)

  test(
    "partial local functions retain recursive and parameter identities beside independent errors"
  ):
    val code =
      "fn outer(): Int = fn inner(x: Int, ) = inner x;; let alias = inner; missing;;"
    val ingested            = IngestStage.fromSource(code, "BindingRecovery")
    val beforeNormalization = resolved(ingested)
    val original =
      localBinding(binding(beforeNormalization.module, "outer").value, code, "fn inner")
    compile(code).map { state =>
      val retained = localBinding(binding(state.module, "outer").value, code, "fn inner")
      val inner    = function(retained.initializer)
      val alias    = localBinding(binding(state.module, "outer").value, code, "let alias")
      assert(retained.parameter.id.nonEmpty)
      assertEquals(retained.parameter.id, original.parameter.id)
      assertEquals(inner.params.map(_.id), function(original.initializer).params.map(_.id))
      assert(inner.params.head.id.nonEmpty)
      assert(references(inner.body).exists(_.resolvedId == retained.parameter.id))
      assert(references(inner.body).exists(_.resolvedId == inner.params.head.id))
      assert(references(alias.initializer).exists(_.resolvedId == retained.parameter.id))
      assertEquals(
        state.module.resolvables.lookup(retained.parameter.id.get),
        retained.parameter.some
      )
      assertEquals(state.visibleErrors.size, 2, state.visibleErrors)
      assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.ParseTermErrorFound]), 1)
      assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.UndefinedRef]), 1)
      val availability = ValueAvailability.fromModule(state.module)
      assert(!availability.isAvailable(retained.initializer))
      assert(!availability.isAvailable(alias.initializer))
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(alias.initializer),
        onlyParseError(inner).causes
      )
    }

  test("a malformed initializer retains independent resolution and continuation normalization"):
    val ingested            = IngestStage.fromSource(source, "BindingRecovery")
    val beforeNormalization = resolved(ingested)
    val original =
      localBinding(binding(beforeNormalization.module, "example").value, source, "let broken")
    val normalized = ExpressionRewriter.rewriteModule(beforeNormalization)
    val rewritten  = localBinding(binding(normalized.module, "example").value, source, "let broken")
    assertEquals(rewritten.parameter.id, original.parameter.id)
    val signature = protocolSignature(normalized.module, "Num", "+", Some(CallableArity.Binary))
    rewritten.scope.body match
      case TXExprApp(reference, _, List(TXExprInt(3), TXExprInt(4))) =>
        assertEquals(reference.candidateIds, signature.id.toList)
        assertEquals(reference.resolvedId, None)
      case other => fail(s"Expected normalized pending addition, got $other")
    compile().map { state =>
      val retained = localBinding(binding(state.module, "example").value, source, "let broken")
      val roots    = state.diagnostics.visibleRecords.map(_.id)
      assertEquals(retained.parameter.id, original.parameter.id)
      assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.ParseTermErrorFound]), 1)
      assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.UndefinedRef]), 1)
      assertEquals(state.visibleErrors.size, 2, state.visibleErrors)
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(retained.initializer),
        roots
      )
      assertAddition(retained.scope.body, state)
      val availability = ValueAvailability.fromModule(state.module)
      assert(!availability.isAvailable(retained.initializer))
      assert(availability.isAvailable(binding(state.module, "example").value))
      assertEquals(
        state.module.resolvables.lookup(retained.parameter.id.get),
        retained.parameter.some
      )
    }

  List("let = missing;" -> true, "fn (x: Int) = x;;" -> false).foreach { (declaration, undefined) =>
    test(s"a nameless local '$declaration' preserves children in a synthetic statement scope"):
      val code = s"fn example(): Int = $declaration 3 + 4;;"
      for
        parsed <- parseNotFailed(code)
        state <- compile(code)
      yield
        val original = localBinding(binding(parsed, "example").value, code, declaration)
        val retained = localBinding(binding(state.module, "example").value, code, declaration)
        assert(!original.parameter.source.isFromSource)
        assert(!original.parameter.nameNode.source.isFromSource)
        assertEquals(parseErrors(original.initializer).size, 1)
        assertEquals(
          state.visibleErrors.count(_.isInstanceOf[SemanticError.ParseTermErrorFound]),
          1
        )
        assertEquals(
          state.visibleErrors.count(_.isInstanceOf[SemanticError.UndefinedRef]),
          if undefined then 1 else 0
        )
        assertEquals(state.visibleErrors.size, if undefined then 2 else 1, state.visibleErrors)
        if !undefined then
          val inner = sourceFunction(retained.initializer, code, "(x: Int)")
          assert(inner.params.head.id.nonEmpty)
          assert(references(inner.body).exists(_.resolvedId == inner.params.head.id))
        assertAddition(retained.scope.body, state)
        assert(
          ValueAvailability
            .fromModule(state.module)
            .isAvailable(binding(state.module, "example").value)
        )
  }

  List("let = missing;", "fn (x: Int) = x;;").foreach { declaration =>
    test(s"a nameless member '$declaration' retains its text and following member"):
      val code = s"$declaration let after = 7;"
      for
        parsed <- justParse(code)
        state <- compile(code)
      yield
        val errors = parsed.members.collect { case error: ParsingIdError => error }
        assertEquals(errors.size, 1)
        assertEquals(errors.head.message, "Expected binding name")
        assertEquals(errors.head.invalidId, "")
        assertEquals(errors.head.failedCode, declaration.some)
        assertEquals(errors.head.spanOpt, spanOf(code, declaration).some)
        assertEquals(parsed.members.collect { case value: Bnd => value.name }, List("after"))
        assertEquals(state.visibleErrors.size, 1, state.visibleErrors)
        assert(state.visibleErrors.head.isInstanceOf[SemanticError.ParsingIdErrorFound])
        assert(
          ValueAvailability
            .fromModule(state.module)
            .isAvailable(binding(state.module, "after").value)
        )
  }

  List(
    ("missing name", "", "1", List("bad ")),
    ("invalid name", "123invalid", "1", List("bad ")),
    ("missing name and annotated initializer", "", "1: nope", List("bad ", "nope"))
  ).foreach { (label, name, initializer, typeFragments) =>
    test(s"a local let with $label retains every malformed annotation and its own root"):
      val code = s"fn example(): Int = let $name: bad = $initializer; 3 + 4;;"
      val info = SourceInfo(code)
      val nameSpan =
        if name.nonEmpty then spanOf(code, name)
        else
          val point = info.pointAt(code.indexOf(": bad"))
          SrcSpan(point, point)
      val nameMessage =
        if name.isEmpty then "Expected binding name" else s"Invalid identifier '$name'"
      val typeSpans        = typeFragments.map(spanOf(code, _))
      val initializerStart = code.indexOf(s"= $initializer") + 2
      val initializerSpan =
        SrcSpan(info.pointAt(initializerStart), info.pointAt(initializerStart + 1))

      for
        parsed <- parseNotFailed(code)
        ingested <- IO.blocking(IngestStage.fromSource(code, "BindingRecovery"))
        state <- compile(code)
      yield
        List(parsed, ingested.module, state.module).foreach { module =>
          val retained    = localBinding(binding(module, "example").value, code, "let ")
          val nameError   = onlyParseError(retained.initializer)
          val annotations = malformedAnnotations(retained.initializer)
          assert(!retained.parameter.source.isFromSource)
          assert(!retained.parameter.nameNode.source.isFromSource)
          assertEquals(nameError.message, nameMessage)
          assertEquals(nameError.spanOpt, nameSpan.some)
          assertEquals(nameError.failedCode, Option.when(name.nonEmpty)(name))
          assertEquals(
            annotations.map(_.message),
            List.fill(typeFragments.size)("Invalid type syntax")
          )
          assertEquals(annotations.map(_.spanOpt), typeSpans.map(_.some))
          assertEquals(annotations.map(_.failedCode), typeFragments.map(_.some))
          val literals = TermTraversal.collect(retained.initializer) { case value: LiteralInt =>
            value
          }
          assertEquals(literals.map(_.value), List(1L))
          assertEquals(literals.map(_.spanOpt), List(initializerSpan.some))
        }

        List(ingested, state).foreach { current =>
          val primary = current.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
          val typeRoots = primary
            .collect {
              case record if record.error.isInstanceOf[SemanticError.ParseTypeErrorFound] => record
            }
            .sortBy { record =>
              record.error match
                case SemanticError.ParseTypeErrorFound(error, _) => error.spanOpt.map(_.start.index)
                case other => fail(s"Expected type parser root, got $other")
            }
          val nameRoots = primary.filter(_.error.isInstanceOf[SemanticError.ParseTermErrorFound])
          assertEquals(primary.size, typeFragments.size + 1, current.errors)
          assertEquals(primary.map(_.id).distinct.size, primary.size)
          assertEquals(current.diagnostics.visibleRecords.map(_.id), primary.map(_.id))
          assertEquals(typeRoots.size, typeFragments.size)
          assertEquals(nameRoots.size, 1)
          val retained = localBinding(binding(current.module, "example").value, code, "let ")
          assertEquals(onlyParseError(retained.initializer).causes, Vector(nameRoots.head.id))
          assertEquals(
            malformedAnnotations(retained.initializer).map(_.causes),
            typeRoots.map(record => Vector(record.id)).toList
          )

          val diagnostics = Diagnostics
            .fromCompilerState(current)
            .sortBy(diagnostic => (diagnostic.range.start.line, diagnostic.range.start.character))
          val expected = (s"Parsing error: $nameMessage", Range.fromSrcSpan(nameSpan)) ::
            typeSpans.map(span =>
              ("Parse error in type: Invalid type syntax", Range.fromSrcSpan(span))
            )
          assertEquals(
            diagnostics.map(diagnostic => (diagnostic.message, diagnostic.range)),
            expected
          )
          assert(diagnostics.forall(_.severity == DiagnosticSeverity.Error))
        }

        val beforeNormalization = resolved(ingested)
        val original =
          localBinding(binding(beforeNormalization.module, "example").value, code, "let ")
        val retained = localBinding(binding(state.module, "example").value, code, "let ")
        assert(original.parameter.id.nonEmpty)
        assertEquals(retained.parameter.id, original.parameter.id)
        assertEquals(
          state.module.resolvables.lookup(retained.parameter.id.get),
          retained.parameter.some
        )
        assertAddition(retained.scope.body, state)
        val availability = ValueAvailability.fromModule(state.module)
        assert(!availability.isAvailable(retained.initializer))
        assert(availability.isAvailable(binding(state.module, "example").value))
  }

  test("partial-header aliases and captures stay unavailable while independent ownership runs"):
    val code = """
      fn take(~text: String, extra: Int): Int = text.length + extra;;
      fn make(~text: String): Int -> Int = take text;;
      fn broken(): Unit -> (Int -> Int) =
        fn incomplete(x: Int, y: Int, ) = x + y;;
        let alias = incomplete;
        let partial = alias 1;
        ~{ partial; };
      ;
      fn independent(): Int =
        let pap = make (int_to_str 123);
        let first = pap 1;
        pap 2;
      ;
    """
    compile(code).map { state =>
      val broken       = binding(state.module, "broken")
      val incomplete   = localBinding(broken.value, code, "fn incomplete")
      val alias        = localBinding(broken.value, code, "let alias")
      val partial      = localBinding(broken.value, code, "let partial")
      val capture      = sourceFunction(broken.value, code, "~{ partial")
      val roots        = onlyParseError(incomplete.initializer).causes
      val availability = ValueAvailability.fromModule(state.module)
      val causes       = DiagnosticCauses.fromModule(state.module)
      assertEquals(function(incomplete.initializer).params.size, 2)
      assertEquals(roots.size, 1)
      List(incomplete.initializer, alias.initializer, partial.initializer, capture).foreach {
        value =>
          assert(!availability.isAvailable(value))
          assertEquals(causes.knownRootsOf(value), roots)
      }
      assert(references(alias.initializer).exists(_.resolvedId == incomplete.parameter.id))
      assert(references(partial.initializer).exists(_.resolvedId == alias.parameter.id))
      assert(references(capture.body).exists(_.resolvedId == partial.parameter.id))
      val failedLambdas = TermTraversal.collect(broken.value) { case lambda: Lambda => lambda }
      failedLambdas.foreach { lambda =>
        assert(!lambda.meta.exists(_.isPartialApplication))
        assert(lambda.meta.flatMap(_.envLayoutId).isEmpty)
        assert(
          lambda.meta.forall(meta =>
            meta.transferredCaptures.isEmpty &&
              meta.borrowedCaptures.isEmpty && meta.retainedCaptures.isEmpty
          )
        )
        assert(lambda.captures.forall(_.isInstanceOf[Capture.CapturedRef]))
      }
      assert(
        TermTraversal.collect(broken.value) { case destruction: Destruction => destruction }.isEmpty
      )
      assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.ParseTermErrorFound]), 1)
      assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.UseAfterMove]), 1)
      assertEquals(state.visibleErrors.size, 2, state.visibleErrors)
      val validPaps = TermTraversal.collect(binding(state.module, "make").value) {
        case lambda: Lambda if lambda.meta.exists(_.isPartialApplication) => lambda
      }
      assert(validPaps.nonEmpty)
      assert(validPaps.exists(_.meta.exists(_.transferredCaptures.nonEmpty)))
    }

  test("a malformed grouped lambda retains its parameter and independent statements"):
    val code                = """
      fn add(a: Int, b: Int): Int = a + b;;
      fn example(): Int =
        { a: Int -> add ({ b: Int -> add b @; } a 2); } 1 3;
        3 + 4;
      ;
      let after = 7;
    """
    val ingested            = IngestStage.fromSource(code, "BindingRecovery")
    val beforeNormalization = resolved(ingested)
    val original =
      sourceFunction(binding(beforeNormalization.module, "example").value, code, "{ b: Int")
    assert(original.params.head.id.nonEmpty)
    assert(references(original.body).exists(_.resolvedId == original.params.head.id))
    compile(code).map { state =>
      val retained = sourceFunction(binding(state.module, "example").value, code, "{ b: Int")
      assertEquals(retained.params.map(_.id), original.params.map(_.id))
      assert(references(retained.body).exists(_.resolvedId == retained.params.head.id))
      assertEquals(onlyParseError(retained).spanOpt, spanOf(code, "@").some)
      assertEquals(state.visibleErrors.size, 1, state.visibleErrors)
      assert(state.visibleErrors.head.isInstanceOf[SemanticError.ParseTermErrorFound])
      val continuations = TermTraversal.collect(binding(state.module, "example").value) {
        case lambda: Lambda
            if lambda.body.spanOpt.exists(_.start.index == spanOf(code, "3 + 4").start.index) =>
          lambda.body
      }
      assertEquals(continuations.size, 1)
      assertAddition(continuations.head, state)
      val availability = ValueAvailability.fromModule(state.module)
      assert(availability.isAvailable(binding(state.module, "example").value))
      assert(availability.isAvailable(binding(state.module, "after").value))
    }

  test("LSP retains the original malformed fragment and independent undefined reference"):
    compile().map { state =>
      val diagnostics = Diagnostics.fromCompilerState(state)
      assertEquals(
        diagnostics.map(_.message),
        List("Parsing error: Invalid expression syntax", "Undefined reference: missing")
      )
      assertEquals(
        diagnostics.map(_.range),
        List("@", "missing").map(fragment => Range.fromSrcSpan(spanOf(source, fragment)))
      )
      assert(diagnostics.forall(_.severity == DiagnosticSeverity.Error))
    }

  test("CLI and quiet entrypoints retain source roots and produce no output artifacts"):
    withSourceFixture(source, "binding-recovery.mml") { (path, config) =>
      for
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, message => output.update(_ :+ message))
        messages <- output.get
        state <- compile(config = config)
        ast <- CompilerApi.processAstQuiet(path, config)
        ir <- CompilerApi.processIrQuiet(path, config)
        native <- CompilerApi.compileNativeQuiet(path, config)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assertEquals(exit, ExitCode.Error)
        val rendered = messages.mkString("\n")
        List("Parser error", "Undefined reference 'missing'").foreach { header =>
          assertEquals(rendered.linesIterator.count(_.contains(header)), 1, rendered)
        }
        assert(rendered.contains("Invalid expression syntax"), rendered)
        List("@", "missing").foreach { fragment =>
          assert(rendered.contains(ErrorPrinter.formatLocation(spanOf(source, fragment))), rendered)
          assert(
            rendered.contains(s"${Console.RED}${Console.BOLD}$fragment${Console.RESET}"),
            rendered
          )
        }
        assertEquals(state.visibleErrors.size, 2)
        val expected = state.visibleErrors.map(_.message).mkString("\n").some
        assertEquals(ast.left.toOption, expected)
        assertEquals(ir.left.toOption, expected)
        assertEquals(native.left.toOption, expected)
        assert(!outputExists)
    }

  test("retained raw errors block codegen even when emission is manually requested"):
    withSourceFixture(source, "binding-recovery.mml") { (_, config) =>
      for
        state <- compile(config = config)
        validated = CodegenStage.validate(state.copy(canEmitCode = true))
        emitted <- CodegenStage.emitIrOnly(validated)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assert(state.hasErrors)
        assertEquals(state.visibleErrors.size, 2)
        assert(state.errors.size > state.visibleErrors.size)
        assert(!validated.canEmitCode)
        assertEquals(validated.diagnostics, state.diagnostics)
        assertEquals(emitted.diagnostics, state.diagnostics)
        assertEquals(emitted.llvmIr, None)
        assertEquals(emitted.nativeResult, None)
        assertEquals(emitted.resolvedTriple, None)
        assert(!outputExists)
    }

  test("valid local functions preserve annotations, identity, and available results"):
    val code = "fn outer(): Int = fn inner(x: Int): Int = x + 1;; inner 2;; let after = 7;"
    semNotFailed(code).map { module =>
      val inner  = localBinding(binding(module, "outer").value, code, "fn inner")
      val lambda = function(inner.initializer)
      assert(inner.parameter.typeAsc.nonEmpty)
      assert(lambda.params.head.typeAsc.nonEmpty)
      assert(inner.parameter.id.nonEmpty)
      assert(lambda.params.head.id.nonEmpty)
      assert(references(lambda.body).exists(_.resolvedId == lambda.params.head.id))
      assert(references(inner.scope.body).exists(_.resolvedId == inner.parameter.id))
      val availability = ValueAvailability.fromModule(module)
      assert(availability.isAvailable(inner.initializer))
      assert(availability.isAvailable(binding(module, "outer").value))
      assert(availability.isAvailable(binding(module, "after").value))
    }
