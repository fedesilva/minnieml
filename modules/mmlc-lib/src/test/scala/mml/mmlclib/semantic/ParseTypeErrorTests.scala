package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{
  CodegenStage,
  CompilerConfig,
  CompilerState,
  ParsingErrorChecker,
  SemanticStage
}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.lsp.{AstLookup, DiagnosticSeverity, Diagnostics, Range, SemanticTokens}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.{ErrorPrinter, LocationPrinter, SemanticErrorPrinter}
import mml.mmlclib.util.prettyprint.ast.prettyPrintTypeSpec

class ParseTypeErrorTests extends BaseEffFunSuite:

  private val code = "fn example(value: Int): Int = value;; let after = 7;"
  private val info = SourceInfo(code.patch(code.indexOf("Int"), "???", 3))
  private val errorSpan = SrcSpan(
    info.pointAt(code.indexOf("Int")),
    info.pointAt(code.indexOf("Int") + 3)
  )

  private def malformed: ParseTypeError =
    ParseTypeError(errorSpan, "Expected a type after ':'", "???".some)

  private def stateOf(module: Module): CompilerState =
    CompilerState.empty(module, info, CompilerConfig.default)

  private def firstBinding(module: Module): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.source.isFromSource => binding }
      .getOrElse(fail("Expected function binding"))

  private def firstLambda(binding: Bnd): Lambda =
    binding.value.terms
      .collectFirst { case lambda: Lambda => lambda }
      .getOrElse(fail("Expected function lambda"))

  private def replaceFirst(module: Module, binding: Bnd): Module =
    module.copy(members = binding :: module.members.tail)

  /** Compare the entire retained tree while ignoring only the diagnostic attribution. */
  private def recoveredTree(value: Any): Any = value match
    case error:   ParseTypeError => error.copy(causes = Vector.empty)
    case values:  Iterable[?] => values.map(recoveredTree).toList
    case product: Product =>
      (product.productPrefix, product.productIterator.map(recoveredTree).toList)
    case other => other

  private def typeErrors(value: Any): List[ParseTypeError] = value match
    case error:   ParseTypeError => List(error)
    case values:  Iterable[?] => values.toList.flatMap(typeErrors)
    case product: Product => product.productIterator.toList.flatMap(typeErrors)
    case _ => Nil

  test("stamping visits retained callee annotations with their source evidence") {
    parseNotFailed(code).map { module =>
      val binding  = firstBinding(module)
      val function = firstLambda(binding)
      val argument = Expr(function.source, List(LiteralInt(function.source, 1, typeSpec = None)))
      val call = App(
        function.source,
        function,
        argument,
        calleeAscriptions = List(malformed)
      )
      val input = replaceFirst(module, binding.copy(value = binding.value.copy(terms = List(call))))
      val stamped = ParsingErrorChecker.checkModule(stateOf(input))
      val errors  = typeErrors(stamped.module)

      assertEquals(errors.size, 1)
      assertEquals(stamped.diagnostics.records.size, 1)
      assertEquals(errors.head.causes, stamped.diagnostics.records.map(_.id))
      assertEquals(errors.head.source, malformed.source)
      assertEquals(stamped.diagnostics.records.head.relation, DiagnosticRelation.Primary)
      assertEquals(recoveredTree(stamped.module), recoveredTree(input))
    }
  }

  test("mirrored malformed annotations share one root while equal occurrences remain independent") {
    parseNotFailed(code).map { module =>
      val binding  = firstBinding(module)
      val lambda   = firstLambda(binding)
      val shared   = malformed
      val separate = malformed
      assert(shared == separate)
      assert(!(shared eq separate))
      val mirrored = lambda.copy(
        params  = lambda.params.map(_.copy(typeAsc = separate.some)),
        typeAsc = shared.some
      )
      val recovered = replaceFirst(
        module,
        binding.copy(
          value   = binding.value.copy(terms = List(mirrored)),
          typeAsc = shared.some
        )
      )
      val result         = ParsingErrorChecker.checkModule(stateOf(recovered))
      val stampedBinding = firstBinding(result.module)
      val stampedLambda  = firstLambda(stampedBinding)
      val returnError = stampedLambda.typeAsc
        .collect { case error: ParseTypeError => error }
        .getOrElse(fail("Expected malformed return annotation"))
      val parameterError = stampedLambda.params.head.typeAsc
        .collect { case error: ParseTypeError => error }
        .getOrElse(fail("Expected malformed parameter annotation"))

      assertEquals(result.errors.size, 2)
      assert(result.diagnostics.records.forall(_.relation == DiagnosticRelation.Primary))
      assertEquals(returnError.causes.size, 1)
      assertEquals(parameterError.causes.size, 1)
      assert(returnError.causes != parameterError.causes)
      assert(stampedBinding.typeAsc.exists(_ eq returnError))
      assertEquals(recoveredTree(result.module), recoveredTree(recovered))
    }
  }

  test("stamping preserves reference identity in shared valid annotation containers") {
    parseNotFailed(code).map { module =>
      val binding    = firstBinding(module)
      val lambda     = firstLambda(binding)
      val original   = TypeRef(errorSpan, "Missing")
      val annotation = TypeFn(errorSpan, NonEmptyList.one(original), original)
      val changed = replaceFirst(
        module,
        binding.copy(
          value   = binding.value.copy(terms = List(lambda.copy(typeAsc = annotation.some))),
          typeAsc = annotation.some
        )
      )
      val stamped        = ParsingErrorChecker.checkModule(stateOf(changed))
      val stampedBinding = firstBinding(stamped.module)
      val stampedLambda  = firstLambda(stampedBinding)
      val annotations = List(stampedBinding.typeAsc, stampedLambda.typeAsc).flatten
        .collect { case function: TypeFn => function }
      assertEquals(annotations.size, 2)
      assert(annotations.forall(_.paramTypes.head eq original))
      assert(annotations.forall(_.returnType eq original))

      val resolved = TypeResolver.rewriteModule(IdAssigner.rewriteModule(stamped))
      val failures = resolved.errors.collect { case error: SemanticError.UndefinedTypeRef => error }
      assertEquals(failures.count(_.typeRef eq original), 1)
    }
  }

  test("ingest stamps nested type positions in declarations and refinement predicates") {
    parseNotFailed(code).map { module =>
      val source = SourceOrigin.Loc(errorSpan)
      val good   = TypeRef(source, "Int")
      val nested = TypeScheme(
        source,
        Nil,
        TypeApplication(
          source,
          malformed,
          List(
            TypeFn(source, NonEmptyList.one(malformed), malformed),
            TypeTuple(source, List(good, malformed)),
            TypeOpenRecord(source, List("field" -> malformed)),
            TypeGroup(
              source,
              List(Union(source, List(malformed)), Intersection(source, List(malformed)))
            ),
            TypeRefinement(
              source,
              None,
              Expr(source, List(LiteralInt(errorSpan, 1).copy(typeAsc = malformed.some)))
            )
          )
        )
      )
      val alias = TypeAlias(source = source, nameNode = Name.synth("Alias"), typeRef = nested)
      val native = TypeDef(
        source   = source,
        nameNode = Name.synth("Native"),
        typeSpec = NativeStruct(source, List("field" -> malformed)).some
      )
      val struct = TypeStruct(
        source,
        None,
        Visibility.Protected,
        Name.synth("Struct"),
        Vector(Field(source, Name.synth("field"), malformed))
      )
      val recovered = module.copy(members = List(alias, native, struct) ++ module.members)
      val result    = ParsingErrorChecker.checkModule(stateOf(recovered))
      val retained  = typeErrors(result.module.members)

      assertEquals(retained.size, 10)
      assertEquals(result.errors.size, retained.size)
      assertEquals(retained.flatMap(_.causes).distinct.size, retained.size)
      assertEquals(recoveredTree(result.module), recoveredTree(recovered))
      assert(result.diagnostics.records.forall(_.relation == DiagnosticRelation.Primary))
      assertEquals(
        result.errors.map(_.message).distinct,
        Vector("Parse error in type: Expected a type after ':'")
      )
    }
  }

  test("expression, application, local parameters, and leaf slots retain malformed ascriptions") {
    parseNotFailed("let result = 7;").map { module =>
      val binding     = firstBinding(module)
      val source      = SourceOrigin.Loc(errorSpan)
      val leaf        = LiteralInt(errorSpan, 7).copy(typeAsc = malformed.some)
      val argument    = Expr(source, List(leaf), typeAsc = malformed.some)
      val parameter   = FnParam(source, Name.synth("local"), typeAsc = malformed.some)
      val body        = Expr(source, List(Ref(source, "local", typeAsc = malformed.some)))
      val scope       = Lambda(source, List(parameter), body, Nil, typeAsc = malformed.some)
      val application = App(source, scope, argument, typeAsc = malformed.some)
      val recovered = replaceFirst(
        module,
        binding.copy(
          value   = Expr(source, List(application), typeAsc = malformed.some),
          typeAsc = malformed.some
        )
      )
      val result = ParsingErrorChecker.checkModule(stateOf(recovered))

      assertEquals(result.errors.size, 8)
      assertEquals(typeErrors(result.module.members).flatMap(_.causes).distinct.size, 8)
      assertEquals(recoveredTree(result.module), recoveredTree(recovered))
    }
  }

  private def withParameterType(module: Module, annotation: Option[Type]): Module =
    val binding = firstBinding(module)
    val lambda  = firstLambda(binding)
    val updated = lambda.copy(params = lambda.params.map(_.copy(typeAsc = annotation)))
    replaceFirst(module, binding.copy(value = binding.value.copy(terms = List(updated))))

  test("malformed parameter types survive semantics and suppress only dependent diagnostics") {
    val source = code + " let independent = missing;"
    parseNotFailed(source).map { module =>
      val recovered = withParameterType(module, malformed.some)
      val ingested  = ParsingErrorChecker.checkModule(stateOf(recovered))
      val root      = ingested.diagnostics.records.head.id
      val result    = SemanticStage.rewrite(ingested)
      val kept      = typeErrors(firstBinding(result.module))
      val missingParameters = result.semanticErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.MissingParameterType) => error
      }
      val visible = result.visibleErrors.collect { case error: SemanticError => error }

      assert(kept.nonEmpty)
      assert(kept.forall(_.causes == Vector(root)))
      assertEquals(missingParameters, Vector.empty)
      assertEquals(
        visible.map(_.message),
        Vector(
          "Parse error in type: Expected a type after ':'",
          "Undefined reference: 'missing'"
        )
      )
      assert(result.diagnostics.records.exists(_.causes == Vector(root)))
      val surviving = result.module.members.collect {
        case binding: Bnd if binding.name == "after" =>
          binding
      }
      assertEquals(surviving.size, 1)
      assert(ValueAvailability.fromModule(result.module).isAvailable(surviving.head.value))
      assert(
        !ValueAvailability.fromModule(result.module).isAvailable(firstBinding(result.module).value)
      )
    }
  }

  test("an absent parameter annotation remains distinct from malformed syntax") {
    parseNotFailed(code).map { module =>
      val absent = SemanticStage.rewrite(
        ParsingErrorChecker.checkModule(
          stateOf(withParameterType(module, None))
        )
      )
      val missing = absent.semanticErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.MissingParameterType) => error
      }
      assertEquals(missing.size, 1)
      assert(!absent.semanticErrors.exists(_.isInstanceOf[SemanticError.ParseTypeErrorFound]))
    }
  }

  test("malformed type source evidence reaches CLI printers and LSP without an invented symbol") {
    parseNotFailed(code).map { module =>
      val result =
        ParsingErrorChecker.checkModule(stateOf(withParameterType(module, malformed.some)))
      val errors      = result.semanticErrors.toList
      val rendered    = ErrorPrinter.prettyPrintSemanticErrors(errors, info.some)
      val fallback    = SemanticErrorPrinter.prettyPrint(errors)
      val diagnostics = Diagnostics.fromCompilerState(result)

      assert(rendered.contains("Type parser error"), rendered)
      assert(rendered.contains(ErrorPrinter.formatLocation(errorSpan)), rendered)
      assert(rendered.contains("Expected a type after ':'"), rendered)
      assert(rendered.contains(s"${Console.RED}${Console.BOLD}???${Console.RESET}"), rendered)
      assert(fallback.contains("\n???"), fallback)
      assert(fallback.contains(LocationPrinter.printSpan(errorSpan)), fallback)
      assertEquals(diagnostics.map(_.range), List(Range.fromSrcSpan(errorSpan)))
      assertEquals(
        diagnostics.map(_.message),
        List("Parse error in type: Expected a type after ':'")
      )
      assert(diagnostics.forall(_.severity == DiagnosticSeverity.Error))
      assertEquals(AstLookup.formatType(malformed.some), "malformed type")
      assertEquals(
        AstLookup.findDefinitionAt(result.module, errorSpan.start.line, errorSpan.start.col),
        Nil
      )
      assertEquals(
        AstLookup.findReferencesAt(
          result,
          errorSpan.start.line,
          errorSpan.start.col,
          includeDeclaration = true
        ),
        Nil
      )
      assert(SemanticTokens.compute(result.module).data.nonEmpty)
      val ast = prettyPrintTypeSpec(malformed.some, showSourceSpans = true)
      assert(ast.contains("ParseTypeError"), ast)
      assert(ast.contains("Expected a type after ':'"), ast)
      assert(ast.contains("???"), ast)
    }
  }

  test("retained malformed type diagnostics block code generation") {
    parseNotFailed(code).flatMap { module =>
      val state =
        ParsingErrorChecker.checkModule(stateOf(withParameterType(module, malformed.some)))
      val validated = CodegenStage.validate(state.copy(canEmitCode = true))
      CodegenStage.emitIrOnly(validated).map { emitted =>
        assert(!validated.canEmitCode)
        assertEquals(emitted.diagnostics, state.diagnostics)
        assertEquals(emitted.llvmIr, None)
        assertEquals(emitted.nativeResult, None)
        assertEquals(emitted.resolvedTriple, None)
      }
    }
  }

  test("nested malformed and unresolved types are unavailable in every existing type container") {
    val source    = SourceOrigin.Loc(errorSpan)
    val reference = TypeRef(source, "Missing")
    val failures: List[Type] = List(
      malformed,
      UndefinedTypeError(source, reference),
      AmbiguousTypeError(source, reference, Nil)
    )
    val containers: List[Type => Type] = List(
      identity,
      error => TypeFn(source, NonEmptyList.one(error), TypeUnit(source)),
      error => TypeFn(source, NonEmptyList.one(TypeUnit(source)), error),
      error => TypeTuple(source, List(error)),
      error => TypeApplication(source, error, Nil),
      error => TypeApplication(source, TypeUnit(source), List(error)),
      error => TypeGroup(source, List(TypeUnit(source), error)),
      error => TypeOpenRecord(source, List("field" -> error)),
      error => NativeStruct(source, List("field" -> error)),
      error => Union(source, List(error)),
      error => Intersection(source, List(error)),
      error => TypeScheme(source, Nil, error),
      error =>
        TypeRefinement(
          source,
          None,
          Expr(source, List(LiteralInt(errorSpan, 1).copy(typeAsc = error.some)))
        )
    )
    failures.foreach { failure =>
      containers.foreach { contain =>
        val tpe = contain(failure)
        assert(TypeUtils.containsInvalidType(tpe, ResolvablesIndex()), tpe)
        assert(!ValueAvailability.empty.isAvailable(LiteralInt(source, 1, tpe.some)), tpe)
        val valid = contain(TypeUnit(source))
        assert(!TypeUtils.containsInvalidType(valid, ResolvablesIndex()), valid)
        assert(ValueAvailability.empty.isAvailable(LiteralInt(source, 1, valid.some)), valid)
      }
    }
  }

  test("invalid-type discovery follows recursive named types and keeps alias cycles unavailable") {
    val source = SourceOrigin.Loc(errorSpan)
    val self   = TypeRef(source, "Recursive", "recursive".some)
    val recursive = TypeStruct(
      source,
      None,
      Visibility.Protected,
      Name.synth("Recursive"),
      Vector(
        Field(source, Name.synth("next"), self),
        Field(source, Name.synth("value"), NativeStruct(source, List("nested" -> malformed)))
      ),
      "recursive".some
    )
    val index = ResolvablesIndex().updatedType(recursive)
    val module =
      Module(source, "Recursive", Visibility.Protected, List(recursive), resolvables = index)
    val value              = LiteralInt(source, 1, self.some)
    val failedAvailability = ValueAvailability.fromModule(module)
    assert(TypeUtils.containsInvalidType(self, index))
    assert(TypeUtils.containsInvalidType(self, TypeUtils.invalidTypeIds(index)))
    assert(!failedAvailability.isAvailable(value))

    val valid = recursive.copy(fields = recursive.fields.map { field =>
      if field.name == "value" then field.copy(typeSpec = TypeUnit(source)) else field
    })
    val validIndex        = ResolvablesIndex().updatedType(valid)
    val validModule       = module.copy(members = List(valid), resolvables = validIndex)
    val validAvailability = ValueAvailability.fromModule(validModule, failedAvailability)
    assert(!TypeUtils.containsInvalidType(self, validIndex))
    assert(!TypeUtils.containsInvalidType(self, TypeUtils.invalidTypeIds(validIndex)))
    assert(validAvailability.isAvailable(value))
    assert(!ValueAvailability.fromModule(module, validAvailability).isAvailable(value))

    val first  = TypeRef(source, "First", "first".some)
    val second = TypeRef(source, "Second", "second".some)
    val firstAlias = TypeAlias(
      source   = source,
      nameNode = Name.synth("First"),
      typeRef  = second,
      id       = "first".some
    )
    val secondAlias = TypeAlias(
      source   = source,
      nameNode = Name.synth("Second"),
      typeRef  = first,
      id       = "second".some
    )
    val aliases    = List(firstAlias, secondAlias)
    val aliasIndex = ResolvablesIndex().updatedAllTypes(aliases)
    val cyclic     = module.copy(members = aliases, resolvables = aliasIndex)
    assert(!TypeUtils.containsInvalidType(first, aliasIndex))
    assert(!TypeUtils.containsInvalidType(first, TypeUtils.invalidTypeIds(aliasIndex)))
    assert(!ValueAvailability.fromModule(cyclic).isAvailable(value.copy(typeSpec = first.some)))
  }

  test("named failure summaries preserve alias targets and both nominal type annotations") {
    val source = SourceOrigin.Loc(errorSpan)
    val valid  = TypeUnit(source)
    val failedAlias = TypeAlias(
      source   = source,
      nameNode = Name.synth("FailedAlias"),
      typeRef  = malformed,
      typeSpec = valid.some,
      id       = "failed-alias".some
    )
    val validAlias = failedAlias.copy(
      nameNode = Name.synth("ValidAlias"),
      typeRef  = valid,
      typeSpec = malformed.some,
      id       = "valid-alias".some
    )
    val failedDefinition = TypeDef(
      source   = source,
      nameNode = Name.synth("FailedDefinition"),
      typeSpec = valid.some,
      typeAsc  = malformed.some,
      id       = "failed-definition".some
    )
    val declarations = List(failedAlias, validAlias, failedDefinition)
    val index        = ResolvablesIndex().updatedAllTypes(declarations)
    val failures     = TypeUtils.invalidTypeIds(index)
    assertEquals(failures, failedAlias.id.toSet ++ failedDefinition.id.toSet)
    declarations.foreach { declaration =>
      val reference = TypeRef(source, declaration.name, declaration.id)
      assertEquals(
        TypeUtils.containsInvalidType(reference, failures),
        TypeUtils.containsInvalidType(reference, index)
      )
    }
    assert(!TypeUtils.containsInvalidType(TypeRef(source, "Missing", "missing".some), failures))
  }
