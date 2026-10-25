package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.lsp.{DiagnosticSeverity, Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class TypeResolverTests extends BaseEffFunSuite:

  private def resolvedTypeId(tpe: Option[Type], module: Module): String =
    tpe match
      case Some(ref: TypeRef) =>
        val id = ref.resolvedId.getOrElse(fail(s"Unresolved type: $ref"))
        assert(module.resolvables.lookupType(id).nonEmpty, s"Missing type identity: $id")
        id

      case other => fail(s"Expected a nominal type, got $other")

  private def conditionals(binding: Bnd): List[Cond] =
    TermTraversal.collect(binding.value) { case conditional: Cond => conditional }

  test("conditional Unit caches resolve before type checking for absent else and nested elif"):
    val code = """
      let single = if true then ();;
      let chained = if false then (); elif true then ();;
    """

    parseNotFailed(code).map { parsed =>
      val resolved = TypeResolver.rewriteModule(phaseInput(code, parsed))
      val unit = resolved.module.members
        .collectFirst {
          case definition: TypeDef if definition.name == "Unit" => definition
        }
        .getOrElse(fail("Expected the injected Unit declaration"))
      val conditions = resolved.module.members
        .collect { case binding: Bnd => binding }
        .flatMap(conditionals)
      val causes = DiagnosticCauses.fromModule(resolved.module)

      assertEquals(resolved.errors, Vector.empty)
      assertEquals(conditions.size, 3)
      conditions.foreach { conditional =>
        assertEquals(resolvedTypeId(conditional.typeSpec, resolved.module), unit.id.get)
        conditional.typeAsc.foreach { ascription =>
          assertEquals(resolvedTypeId(Some(ascription), resolved.module), unit.id.get)
        }
        assertEquals(causes.evidenceOf(conditional), CauseEvidence.empty)
      }
    }

  test("a conditional cache shares the written ascription failure without duplicate diagnostics"):
    val code = "let value = if true then ();;"

    parseNotFailed(code).map { parsed =>
      val initial     = phaseInput(code, parsed)
      val original    = onlyBinding(initial.module)
      val conditional = conditionals(original).head
      val reference   = TypeRef(conditional.source, "Missing")
      val cached      = conditional.copy(typeAsc = Some(reference), typeSpec = Some(reference))
      val binding     = original.copy(value = original.value.copy(terms = List(cached)))
      val members = initial.module.members.map {
        case current: Bnd if current.id == binding.id => binding
        case member => member
      }
      val resolved = TypeResolver.rewriteModule(
        initial.withModule(initial.module.copy(members = members))
      )
      val result     = conditionals(onlyBinding(resolved.module)).head
      val ascription = unresolvedType(result.typeAsc)
      val cachedType = unresolvedType(result.typeSpec)

      assertEquals(resolved.errors.map(_.message), Vector("Undefined type: 'Missing'"))
      assertEquals(ascription.causes, resolved.diagnostics.records.map(_.id))
      assertEquals(cachedType.causes, ascription.causes)
      assertEquals(cachedType.originalType, ascription.originalType)
    }

  test("TypeResolver resolves cached global literal types at the phase boundary"):
    val code = """
      type Number = Int;
      let integer: Number = 30;
      let boolean = true;
      let string = "hello";
      let float = 1.5;
      let computed = 0 + 30;
    """

    parseNotFailed(code).map { parsed =>
      val initial = CompilerState.empty(
        injectBasicTypes(parsed),
        SourceInfo(code),
        CompilerConfig.default
      )
      val resolved = TypeResolver.rewriteModule(IdAssigner.rewriteModule(initial))
      assertEquals(resolved.errors, Vector.empty)

      val bindings = resolved.module.members.collect { case b: Bnd => b }
      bindings.filterNot(_.name == "computed").foreach { binding =>
        val bindingType = resolvedTypeId(binding.typeSpec, resolved.module)
        assertEquals(resolvedTypeId(binding.value.typeSpec, resolved.module), bindingType)
        val literals = TermTraversal.collect(binding.value) { case lit: LiteralValue => lit }
        assertEquals(literals.size, 1)
        assertEquals(resolvedTypeId(literals.head.typeSpec, resolved.module), bindingType)
        assertEquals(resolved.module.resolvables.lookup(binding.id.get), Some(binding))
      }

      val integer = bindings.find(_.name == "integer").get
      val alias = resolved.module.members.collectFirst {
        case a: TypeAlias if a.name == "Number" => a
      }.get
      assertEquals(resolvedTypeId(integer.typeAsc, resolved.module), alias.id.get)

      val computed = bindings.find(_.name == "computed").get
      assertEquals(computed.typeSpec, None)
      assertEquals(computed.value.typeSpec, None)
    }

  private val literalCases = List(
    ("inferred integer", "let value = 30;", "int_to_str value"),
    ("Int annotation", "let value: Int = 30;", "int_to_str value"),
    ("Int32 annotation", "let value: Int32 = 30;", "int_to_str value"),
    ("term annotation", "let value = 30: Int32;", "int_to_str value"),
    ("arithmetic initializer", "let value = 0 + 30;", "int_to_str value"),
    ("boolean", "let value = true;", "if value then \"yes\"; else \"no\";"),
    ("string", "let value = \"hello\";", "value"),
    ("float", "let value = 1.5;", "int_to_str (float_to_int value)")
  )

  literalCases.foreach { (label, declaration, use) =>
    List(true, false).foreach { forward =>
      val order = if forward then "before" else "after"
      test(s"global $label references $order its declaration have resolved types"):
        val reader = s"fn read(): String = $use;;"
        val code =
          if forward then s"$reader\n$declaration"
          else s"$declaration\n$reader"

        semNotFailed(code).map { module =>
          val binding = module.members.collectFirst { case b: Bnd if b.name == "value" => b }.get
          val readerBinding = module.members.collectFirst {
            case b: Bnd if b.name == "read" => b
          }.get
          val refs = TermTraversal.collect(readerBinding.value) {
            case ref: Ref if ref.resolvedId == binding.id => ref
          }
          assert(refs.nonEmpty)
          val bindingType = resolvedTypeId(binding.typeSpec, module)
          refs.foreach(ref => assertEquals(resolvedTypeId(ref.typeSpec, module), bindingType))
        }
    }
  }

  test("global literal annotations still reject incompatible types"):
    semFailed("fn read(): Int = value;; let value: Bool = 30;")

  test("TypeResolver should resolve simple type references in bindings"):
    val code = """
      let x: Int = 42;
    """

    semNotFailed(code).map { module =>
      // Find the binding (skip stdlib injected bindings)
      val binding = module.members.collectFirst {
        case b: Bnd if b.name == "x" => b
      }.get

      // Check that the type ascription has been resolved
      clue(binding.typeAsc) match
        case Some(TypeRef(_, "Int", resolvedId, _)) =>
          assert(clue(resolvedId).isDefined, "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(clue(resolved.map(_.name)), Some("Int"))
        case other =>
          fail(s"Expected TypeRef with resolved type, got: ${clue(other)}")
    }

  test("TypeResolver should resolve type references in function parameters"):
    val code = """
      fn greet(name: String): String = name ++ "!";;
    """

    semNotFailed(code).map { module =>
      // Find the function (now Bnd with Lambda)
      val bnd = module.members.collectFirst {
        case b: Bnd
            if b.meta.exists(_.origin == BindingOrigin.Function) &&
              b.meta.exists(_.originalName == "greet") =>
          b
      }.get

      // Extract lambda params
      val lambda = bnd.value.terms.head.asInstanceOf[Lambda]

      // Check that the parameter type has been resolved
      lambda.params.head.typeAsc match
        case Some(TypeRef(_, "String", resolvedId, _)) =>
          assert(clue(resolvedId.isDefined), "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(clue(resolved.map(_.name)), Some("String"))
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type references in function return types"):
    val code = """
      fn isTrue(): Bool = true;;
    """

    semNotFailed(code).map { module =>
      // Find the function (now Bnd with Lambda)
      val bnd = module.members.collectFirst {
        case b: Bnd
            if b.meta.exists(_.origin == BindingOrigin.Function) &&
              b.meta.exists(_.originalName == "isTrue") =>
          b
      }.get

      // Extract lambda and check return type
      val lambda = bnd.value.terms.head.asInstanceOf[Lambda]

      // Return type ascription is on lambda or bnd
      val returnTypeAsc = lambda.typeAsc.orElse(bnd.typeAsc)
      returnTypeAsc match
        case Some(TypeRef(_, "Bool", resolvedId, _)) =>
          assert(resolvedId.isDefined, "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(resolved.map(_.name), Some("Bool"))
        case _ =>
          fail("Expected TypeRef with resolved type")
    }

  test("TypeResolver should resolve type aliases"):
    val code = """      
      type TestNumber = Int64;
      let x: TestNumber = int_to_int64 42;
    """

    semNotFailed(code).map { module =>
      // Find the TestNumber type alias
      val typeAlias = module.members.collectFirst {
        case t: TypeAlias if t.name == "TestNumber" => t
      }.get

      // Check that the type reference in the alias has been resolved
      typeAlias.typeRef match
        case TypeRef(_, "Int64", resolvedId, _) =>
          assert(resolvedId.isDefined, "Expected Int64 to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(resolved.map(_.name), Some("Int64"))
        case _ =>
          fail("Expected TypeRef with resolved type")

      // Find the binding (skip stdlib injected bindings)
      val binding = module.members.collectFirst {
        case b: Bnd if b.name == "x" => b
      }.get

      // Check that the binding's type has been resolved to the alias
      clue(binding.typeAsc) match
        case Some(TypeRef(_, "TestNumber", resolvedId, _)) =>
          assert(clue(resolvedId).isDefined, "Expected TypeRef to be resolved")
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assertEquals(clue(resolved.map(_.name)), Some("TestNumber"))
        case other =>
          fail(s"Expected TypeRef with resolved type, got: ${clue(other)}")
    }

  test("TypeResolver should report undefined type references"):
    val code = """
      let x: Unknown = 42;
    """

    semFailed(code)

  test("TypeResolver should resolve type alias chains to MML types, not native types"):
    val code = """
      type X = Int;
      let test: X = 42;
    """

    semNotFailed(code).map { module =>

      // Find the X type alias
      val typeAlias = module.members.collectFirst {
        case t: TypeAlias if t.name == "X" => t
      }.get

      // Check that the typeSpec resolves to Int32 (the MML type), not @metal:repr[t=i32]
      typeAlias.typeSpec match
        case Some(TypeRef(_, "Int32", resolvedId, _)) =>
          assert(resolvedId.isDefined, "Expected Int32 to be resolved")
          val td =
            resolvedId.flatMap(module.resolvables.lookupType).collect { case t: TypeDef => t }
          assert(td.isDefined, "Expected to resolve to TypeDef")
          assertEquals(td.get.name, "Int32")
          // Verify that Int32 itself has the native type, but that's not propagated to X
          assert(td.get.typeSpec.isDefined, "Int32 should have a native typeSpec")
          td.get.typeSpec match
            case Some(NativePrimitive(_, "i32", _, _)) => // correct
            case other => fail(s"Expected Int32 to have @metal:repr[t=i32], got $other")
        case other =>
          fail(s"Expected X to resolve to TypeRef(Int32), got $other")
    }

  test("TypeResolver should resolve type references to struct declarations"):
    val code = """
      struct Person {
        name: String
      };
      let p: Person = ???;
    """

    semNotFailed(code).map { module =>
      val binding = module.members.collectFirst {
        case b: Bnd if b.name == "p" => b
      }.get

      clue(binding.typeAsc) match
        case Some(TypeRef(_, "Person", resolvedId, _)) =>
          val resolved = resolvedId.flatMap(module.resolvables.lookupType)
          assert(
            resolved.exists(_.isInstanceOf[TypeStruct]),
            s"Expected TypeRef to resolve to TypeStruct, got: ${clue(resolved)}"
          )
        case other =>
          fail(s"Expected TypeRef to resolve to TypeStruct, got: ${clue(other)}")
    }

  private def phaseInput(code: String, parsed: Module): CompilerState =
    IdAssigner.rewriteModule(
      CompilerState.empty(injectBasicTypes(parsed), SourceInfo(code), CompilerConfig.default)
    )

  private def onlyBinding(module: Module): Bnd =
    val bindings = module.members.collect { case binding: Bnd => binding }
    assertEquals(bindings.size, 1)
    bindings.head

  private def onlyLambda(binding: Bnd): Lambda = binding.value.terms match
    case (lambda: Lambda) :: Nil => lambda
    case other => fail(s"Expected one lambda, got $other")

  private def unresolvedType(tpe: Option[Type]): UndefinedTypeError = tpe match
    case Some(error: UndefinedTypeError) => error
    case other => fail(s"Expected an undefined type, got $other")

  test("type resolution accumulates parameter, body, and result failures with original sources"):
    val code = "fn broken(a: MissingA, b: Int): MissingResult = 1: MissingBody;;"
    parseNotFailed(code).map { parsed =>
      val initial         = phaseInput(code, parsed)
      val originalBinding = onlyBinding(initial.module)
      val original        = onlyLambda(originalBinding)
      val originalLiteral = original.body.terms.head
      val resolved        = TypeResolver.rewriteModule(initial)
      val binding         = onlyBinding(resolved.module)
      val lambda          = onlyLambda(binding)
      val failures = Vector(
        unresolvedType(lambda.params.head.typeAsc),
        unresolvedType(lambda.body.terms.head.typeAsc),
        unresolvedType(lambda.typeAsc)
      )
      val originalTypes = Vector(
        original.params.head.typeAsc,
        originalLiteral.typeAsc,
        original.typeAsc
      ).map(_.get)

      assertEquals(failures.map(_.originalType), originalTypes)
      assertEquals(failures.map(_.source), originalTypes.map(_.source))
      assertEquals(
        resolved.errors.map(_.message),
        Vector(
          "Undefined type: 'MissingA'",
          "Undefined type: 'MissingBody'",
          "Undefined type: 'MissingResult'"
        )
      )
      assertEquals(failures.flatMap(_.causes), resolved.diagnostics.records.map(_.id))
      assertEquals(failures.map(_.causes.size), Vector(1, 1, 1))
      assertEquals(unresolvedType(lambda.body.typeAsc).causes, failures(1).causes)
      assertEquals(unresolvedType(binding.typeAsc).causes, failures(2).causes)
      assertEquals(resolved.module.resolvables.lookup(binding.id.get), Some(binding))
      lambda.params.foreach { param =>
        assertEquals(resolved.module.resolvables.lookup(param.id.get), Some(param))
      }
      assertEquals(lambda.params(1).source, original.params(1).source)
      assertEquals(lambda.params(1).typeAsc.map(_.source), original.params(1).typeAsc.map(_.source))
      resolvedTypeId(lambda.params(1).typeAsc, resolved.module)
      resolvedTypeId(lambda.body.terms.head.typeSpec, resolved.module)
      assertEquals(
        Diagnostics.fromCompilerState(resolved).map(_.range),
        failures.flatMap(_.spanOpt).map(Range.fromSrcSpan).toList
      )
      assert(Diagnostics.fromCompilerState(resolved).forall(_.severity == DiagnosticSeverity.Error))
    }

  test("type resolution preserves valid fields beside separate occurrences of an undefined type"):
    val code = "struct Record { first: Missing, valid: Int, last: Missing };"
    parseNotFailed(code).map { parsed =>
      val initial  = phaseInput(code, parsed)
      val original = initial.module.members.collectFirst { case struct: TypeStruct => struct }.get
      val resolved = TypeResolver.rewriteModule(initial)
      val struct   = resolved.module.members.collectFirst { case value: TypeStruct => value }.get
      val first    = unresolvedType(Some(struct.fields.head.typeSpec))
      val last     = unresolvedType(Some(struct.fields.last.typeSpec))
      val failures = Vector(first, last)

      assertEquals(resolved.errors.map(_.message), Vector.fill(2)("Undefined type: 'Missing'"))
      assertEquals(failures.flatMap(_.causes), resolved.diagnostics.records.map(_.id))
      assertEquals(failures.flatMap(_.causes).distinct.size, 2)
      assertEquals(first.originalType, original.fields.head.typeSpec)
      assertEquals(last.originalType, original.fields.last.typeSpec)
      assertEquals(struct.fields(1).source, original.fields(1).source)
      assertEquals(struct.fields(1).id, original.fields(1).id)
      assertEquals(struct.fields(1).typeSpec.source, original.fields(1).typeSpec.source)
      resolvedTypeId(Some(struct.fields(1).typeSpec), resolved.module)
      assertEquals(resolved.module.resolvables.lookupType(struct.id.get), Some(struct))
      struct.fields.foreach { field =>
        assertEquals(resolved.module.resolvables.lookup(field.id.get), Some(field))
      }
    }

  test("ambiguous type lookup retains all actual candidates and its written reference"):
    val code = """
      type Choice = @metal:repr[t=i32];
      type Choice = @metal:repr[t=i64];
      let value: Choice = ???;
    """
    parseNotFailed(code).map { parsed =>
      val assigned = phaseInput(code, parsed)
      val members = assigned.module.members.zipWithIndex.map {
        case (definition: TypeDef, index) if definition.source.isFromSource =>
          definition.copy(id = Some(s"candidate-$index"))
        case (member, _) => member
      }
      val initial =
        assigned.withModule(ResolvablesIndexer.refresh(assigned.module.copy(members = members)))
      val original = onlyBinding(initial.module)
      val candidates = members.collect {
        case definition: TypeDef if definition.source.isFromSource => definition
      }
      val resolved = TypeResolver.rewriteModule(initial)
      val binding  = onlyBinding(resolved.module)
      val error = binding.typeAsc match
        case Some(error: AmbiguousTypeError) => error
        case other => fail(s"Expected ambiguity, got $other")

      assertEquals(candidates.size, 2)
      assertEquals(error.originalType, original.typeAsc.get)
      assertEquals(error.source, original.typeAsc.get.source)
      assertEquals(error.candidates, candidates)
      assertEquals(resolved.errors.map(_.message), Vector("Ambiguous type: 'Choice'"))
      assertEquals(error.causes, resolved.diagnostics.records.map(_.id))
      resolved.errors.head match
        case diagnostic: SemanticError.AmbiguousTypeRef =>
          assertEquals(diagnostic.typeRef, error.originalType)
          assertEquals(diagnostic.candidates, error.candidates)
        case other => fail(s"Expected ambiguity diagnostic, got $other")
      assertEquals(
        Diagnostics.fromCompilerState(resolved).map(_.message),
        List("Ambiguous type: Choice")
      )
      assertEquals(
        Diagnostics.fromCompilerState(resolved).map(_.range),
        error.spanOpt.map(Range.fromSrcSpan).toList
      )
    }

  test("failed aliases preserve their root while a forward valid chain resolves completely"):
    val code = """
      type Failed = Missing;
      type First = Second;
      type Second = Third;
      type Third = Int;
      let value: First = 1;
    """
    parseNotFailed(code).map { parsed =>
      val initial  = phaseInput(code, parsed)
      val resolved = TypeResolver.rewriteModule(initial)
      val aliases = resolved.module.members.collect {
        case alias: TypeAlias if alias.source.isFromSource => alias
      }
      val failed    = aliases.head
      val error     = unresolvedType(Some(failed.typeRef))
      val targetIds = aliases.tail.map(alias => resolvedTypeId(alias.typeSpec, resolved.module))

      assertEquals(resolved.errors.map(_.message), Vector("Undefined type: 'Missing'"))
      assertEquals(error.causes, resolved.diagnostics.records.map(_.id))
      assertEquals(failed.typeSpec, None)
      assertEquals(targetIds.distinct.size, 1)
      aliases.foreach { alias =>
        assertEquals(resolved.module.resolvables.lookupType(alias.id.get), Some(alias))
      }
      val binding = onlyBinding(resolved.module)
      assertEquals(resolvedTypeId(binding.typeAsc, resolved.module), aliases(1).id.get)
    }

  test("distinct type reference occurrences retain separate roots even with identical contents"):
    val code = "let value: Missing = ???;"
    parseNotFailed(code).map { parsed =>
      val initial  = phaseInput(code, parsed)
      val original = onlyBinding(initial.module)
      val ref = original.typeAsc match
        case Some(ref: TypeRef) => ref
        case other => fail(s"Expected written reference, got $other")
      val secondOccurrence = ref.copy()
      val hole             = Hole(original.value.source, typeAsc = Some(secondOccurrence))
      val duplicated       = original.copy(value = original.value.copy(terms = List(hole)))
      val members = initial.module.members.map {
        case binding: Bnd if binding.id == original.id => duplicated
        case member => member
      }
      val result =
        TypeResolver.rewriteModule(initial.withModule(initial.module.copy(members = members)))
      val binding = onlyBinding(result.module)
      val first   = unresolvedType(binding.typeAsc)
      val second  = unresolvedType(binding.value.terms.head.typeAsc)

      assertEquals(first.originalType, second.originalType)
      assertEquals(result.errors.map(_.message), Vector.fill(2)("Undefined type: 'Missing'"))
      assertEquals((first.causes ++ second.causes).distinct.size, 2)
    }

  test("local function signature caches reuse the written parameter failure"):
    val code = "fn outer(): Int = fn inner(arg: Missing): Int = 1;; 2;;"
    parseNotFailed(code).map { parsed =>
      val result = TypeResolver.rewriteModule(phaseInput(code, parsed))
      val types = TermTraversal
        .collect(onlyBinding(result.module).value) { case lambda: Lambda =>
          lambda.params.flatMap(_.typeAsc)
        }
        .flatten
      val written    = types.collect { case error: UndefinedTypeError => error }
      val signatures = types.collect { case function: TypeFn => function }
      val cached = signatures.flatMap(_.paramTypes.toList).collect {
        case error: UndefinedTypeError => error
      }

      assertEquals(result.errors.map(_.message), Vector("Undefined type: 'Missing'"))
      assertEquals(written.size, 1)
      assertEquals(cached.size, 1)
      assertEquals(written.head.causes, result.diagnostics.records.map(_.id))
      assertEquals(cached.head.causes, written.head.causes)
      assertEquals(cached.head.originalType, written.head.originalType)
    }
