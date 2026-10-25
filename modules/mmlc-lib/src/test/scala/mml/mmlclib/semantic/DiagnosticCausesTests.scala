package mml.mmlclib.semantic

import cats.data.NonEmptyList
import mml.mmlclib.ast.*
import mml.mmlclib.errors.DiagnosticId
import mml.mmlclib.test.BaseEffFunSuite

class DiagnosticCausesTests extends BaseEffFunSuite:

  private val source      = SourceOrigin.Synth
  private val intType     = NativePrimitive(source, "i32")
  private val boolType    = NativePrimitive(source, "i1")
  private val firstRoots  = Vector(DiagnosticId(10L))
  private val secondRoots = Vector(DiagnosticId(20L))
  private val thirdRoots  = Vector(DiagnosticId(30L))

  private def assertEvidence(
    evidence: CauseEvidence,
    roots:    Vector[DiagnosticId],
    complete: Boolean
  ): Unit =
    assertEquals(evidence.knownRoots, roots)
    assertEquals(evidence.isComplete, complete)
    assertEquals(evidence.provenRoots, if complete then roots else Vector.empty)

  private def expr(term: Term): Expr = Expr(term.source, List(term), typeSpec = term.typeSpec)

  private def integer(value: Long = 1L): Expr =
    expr(LiteralInt(source, value, Some(intType)))

  private def failed(roots: Vector[DiagnosticId]): Expr =
    expr(InvalidExpression(source, integer(), typeSpec = Some(intType), causes = roots))

  private def parameter(id: String, tpe: Type = intType): FnParam =
    FnParam(source, Name.synth("value"), typeSpec = Some(tpe), id = Some(id))

  private def reference(id: String): Expr =
    expr(Ref(source, "value", typeSpec = Some(intType), resolvedId = Some(id)))

  private def binding(id: String, value: Expr): Bnd =
    Bnd(source = source, nameNode = Name.synth("value"), value = value, id = Some(id))

  private def module(members: List[Member]): Module =
    ResolvablesIndexer.refresh(Module(source, "Causes", Visibility.Public, members))

  private def replaceBinding(input: Module, replacement: Bnd): Module =
    input.copy(
      members = input.members.map {
        case binding: Bnd if binding.id == replacement.id => replacement
        case other => other
      },
      resolvables = input.resolvables.updated(replacement)
    )

  test("checked binding updates preserve clean locals and propagate a new failed result") {
    val local = parameter("scope::local")
    val original = binding(
      "global::value",
      expr(LocalBindings.bind(local, integer(), reference(local.id.get)))
    )
    val alias         = binding("global::alias", reference(original.id.get))
    val input         = module(List(alias, original))
    val clean         = original.copy(typeSpec = Some(intType))
    val checkedModule = replaceBinding(input, clean)
    val context       = DiagnosticCauses.fromModule(input).updatedModule(checkedModule, clean)

    assertEvidence(context.evidenceOf(reference(alias.id.get)), Vector.empty, complete = true)
    assertEvidence(context.evidenceOf(reference(local.id.get)), Vector.empty, complete = true)

    val broken       = clean.copy(value = failed(firstRoots))
    val failedModule = replaceBinding(checkedModule, broken)
    val updated      = context.updatedModule(failedModule, broken)

    assertEvidence(updated.evidenceOf(reference(alias.id.get)), firstRoots, complete = true)
    assertEquals(
      updated.evidenceOf(reference(alias.id.get)),
      DiagnosticCauses.fromModule(failedModule).evidenceOf(reference(alias.id.get))
    )
  }

  test("checked binding updates replace incomplete evidence without retaining old roots") {
    val unresolved = Ref(source, "missing", typeSpec = Some(intType))
    val original = binding(
      "global::value",
      expr(Tuple(source, NonEmptyList.of(failed(firstRoots), expr(unresolved))))
    )
    val alias       = binding("global::alias", reference(original.id.get))
    val input       = module(List(alias, original))
    val context     = DiagnosticCauses.fromModule(input)
    val replacement = original.copy(value = failed(secondRoots))
    val updated     = context.updatedModule(replaceBinding(input, replacement), replacement)

    assertEvidence(context.evidenceOf(reference(alias.id.get)), firstRoots, complete  = false)
    assertEvidence(updated.evidenceOf(reference(alias.id.get)), secondRoots, complete = true)
  }

  test("checked binding updates clear obsolete causes throughout a recursive dependency") {
    val first = binding(
      "global::first",
      expr(Tuple(source, NonEmptyList.of(reference("global::second"), failed(firstRoots))))
    )
    val second        = binding("global::second", reference(first.id.get))
    val input         = module(List(first, second))
    val context       = DiagnosticCauses.fromModule(input)
    val replacement   = first.copy(value = reference(second.id.get))
    val updatedModule = replaceBinding(input, replacement)
    val updated       = context.updatedModule(updatedModule, replacement)

    assertEvidence(context.evidenceOf(reference(second.id.get)), firstRoots, complete   = true)
    assertEvidence(updated.evidenceOf(reference(second.id.get)), Vector.empty, complete = true)
    assertEquals(
      updated.evidenceOf(reference(second.id.get)),
      DiagnosticCauses.fromModule(updatedModule).evidenceOf(reference(second.id.get))
    )
  }

  test("removing an unused failed local clears its cached evidence") {
    val local = parameter("scope::discarded")
    val original = binding(
      "global::value",
      expr(LocalBindings.bind(local, failed(firstRoots), integer()))
    )
    val input       = module(List(original))
    val context     = DiagnosticCauses.fromModule(input)
    val replacement = original.copy(value = integer())
    val updated     = context.updatedModule(replaceBinding(input, replacement), replacement)

    assertEvidence(context.evidenceOf(reference(original.id.get)), Vector.empty, complete = true)
    assertEvidence(context.evidenceOf(reference(local.id.get)), firstRoots, complete      = true)
    assertEvidence(updated.evidenceOf(reference(local.id.get)), Vector.empty, complete    = true)
  }

  test("updated type declarations invalidate named type evidence") {
    val alias = TypeAlias(
      source   = source,
      nameNode = Name.synth("Value"),
      typeRef  = intType,
      id       = Some("types::value")
    )
    val declared = TypeRef(source, "Value", alias.id)
    val value    = binding("global::value", integer()).copy(typeSpec = Some(declared))
    val input    = module(List(alias, value))
    val context  = DiagnosticCauses.fromModule(input)
    val changed = alias.copy(
      typeRef = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    )
    val updated = context.updatedModule(module(List(changed, value)), value)

    assertEvidence(context.evidenceOfType(declared), Vector.empty, complete          = true)
    assertEvidence(updated.evidenceOfType(declared), firstRoots, complete            = true)
    assertEvidence(updated.evidenceOf(reference(value.id.get)), firstRoots, complete = true)
  }

  test("each mutually recursive nominal type retains failures reached through the other") {
    val firstId  = "types::first"
    val secondId = "types::second"
    def declaration(id: String, other: String, roots: Vector[DiagnosticId]): TypeStruct =
      TypeStruct(
        source,
        None,
        Visibility.Public,
        Name.synth("Node"),
        Vector(
          Field(source, Name.synth("next"), TypeRef(source, "Node", Some(other))),
          Field(
            source,
            Name.synth("failure"),
            UndefinedTypeError(source, TypeRef(source, "Missing"), roots)
          )
        ),
        id = Some(id)
      )

    val context = DiagnosticCauses.fromModule(
      module(
        List(
          declaration(firstId, secondId, firstRoots),
          declaration(secondId, firstId, secondRoots)
        )
      )
    )

    List(firstId, secondId).foreach { id =>
      assertEvidence(
        context.evidenceOfType(TypeRef(source, "Node", Some(id))),
        firstRoots ++ secondRoots,
        complete = true
      )
    }
  }

  test("an unused failed local does not taint an independent continuation") {
    val local       = parameter("scope::unused")
    val initializer = failed(firstRoots)
    val independent = LocalBindings.bind(local, initializer, integer(7))
    val dependent   = LocalBindings.bind(local, initializer, reference(local.id.get))
    val causes      = DiagnosticCauses.empty

    assertEquals(causes.evidenceOf(independent), CauseEvidence.empty)
    assertEquals(causes.knownRootsOf(dependent), firstRoots)
    assertEquals(causes.provenRootsOf(dependent), firstRoots)
    assertEquals(
      causes.withBinding(local, initializer).knownRootsOf(reference(local.id.get)),
      firstRoots
    )
  }

  test("forward aliases and shadowed locals follow resolved identities rather than spelling") {
    val root      = binding("global::root", failed(firstRoots))
    val alias     = binding("global::alias", reference(root.id.get))
    val forwarded = binding("global::forwarded", reference(alias.id.get))
    val local     = parameter("scope::shadow")
    val independent = binding(
      "global::independent",
      expr(LocalBindings.bind(local, integer(), reference(local.id.get)))
    )
    val stillGlobal =
      binding("global::outer", expr(LocalBindings.bind(local, integer(), reference(root.id.get))))
    val context =
      DiagnosticCauses.fromModule(module(List(forwarded, alias, independent, stillGlobal, root)))

    assertEquals(context.provenRootsOf(reference(forwarded.id.get)), firstRoots)
    assertEquals(context.provenRootsOf(reference(alias.id.get)), firstRoots)
    assertEquals(context.evidenceOf(reference(independent.id.get)), CauseEvidence.empty)
    assertEquals(context.provenRootsOf(reference(stillGlobal.id.get)), firstRoots)
    assertEquals(context.evidenceOf(reference(local.id.get)), CauseEvidence.empty)
  }

  test("ownership bindings transfer initializer causes only through used bound results") {
    val value       = parameter("ownership::value")
    val witness     = parameter("ownership::owns", boolType)
    val initializer = failed(firstRoots)
    val independent = BindOwnership(source, value, witness, initializer, integer(5))
    val usesValue   = independent.copy(body = reference(value.id.get))
    val usesWitness = independent.copy(body = reference(witness.id.get))
    val context     = DiagnosticCauses.fromModule(module(List(binding("main", expr(independent)))))

    assertEquals(context.evidenceOf(independent), CauseEvidence.empty)
    assertEquals(context.provenRootsOf(usesValue), firstRoots)
    assertEquals(context.provenRootsOf(usesWitness), firstRoots)
    assertEquals(context.provenRootsOf(reference(value.id.get)), firstRoots)
    assertEquals(context.provenRootsOf(reference(witness.id.get)), firstRoots)
  }

  test("conditional result evidence includes its predicate and both possible branches") {
    val condition = Cond(source, failed(firstRoots), failed(secondRoots), integer())
    val context   = DiagnosticCauses.empty

    assertEquals(context.knownRootsOf(condition), firstRoots ++ secondRoots)
    assertEquals(context.provenRootsOf(condition), firstRoots ++ secondRoots)
    assertEquals(
      context.knownRootsOf(condition.copy(ifFalse = failed(firstRoots))),
      firstRoots ++ secondRoots
    )
  }

  test("mixed known and unattributed failures retain roots without proving suppression") {
    val value = Tuple(
      source,
      NonEmptyList.of(
        failed(secondRoots),
        failed(Vector.empty),
        failed(firstRoots),
        failed(secondRoots)
      )
    )
    val context  = DiagnosticCauses.empty
    val evidence = context.evidenceOf(value)

    assertEquals(evidence.knownRoots, firstRoots ++ secondRoots)
    assert(!evidence.isComplete)
    assertEquals(context.provenRootsOf(value), Vector.empty)
    val owner = parameter("scope::mixed")
    assertEquals(
      context.withBinding(owner, expr(value)).evidenceOf(reference(owner.id.get)),
      evidence
    )
  }

  test("type alias and native field references propagate original type failure identities") {
    val failedType = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    val root = TypeAlias(
      source   = source,
      nameNode = Name.synth("Same"),
      typeRef  = failedType,
      id       = Some("types::root")
    )
    val alias = TypeAlias(
      source   = source,
      nameNode = Name.synth("Same"),
      typeRef  = TypeRef(source, "Same", root.id),
      id       = Some("types::alias")
    )
    val record = TypeDef(
      source   = source,
      nameNode = Name.synth("Record"),
      typeSpec = Some(NativeStruct(source, List("field" -> TypeRef(source, "Same", alias.id)))),
      id       = Some("types::record")
    )
    val context   = DiagnosticCauses.fromModule(module(List(alias, record, root)))
    val reference = TypeRef(source, "Record", record.id)
    val value     = LiteralInt(source, 1L, typeSpec = Some(reference))

    assertEquals(context.knownRootsOfType(reference), firstRoots)
    assertEquals(context.provenRootsOfType(reference), firstRoots)
    assertEquals(context.provenRootsOf(value), firstRoots)
  }

  test("an unresolved type beside a known failed type keeps the causal proof incomplete") {
    val known     = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    val unknown   = TypeRef(source, "Unavailable", Some("types::absent"))
    val composite = TypeFn(source, NonEmptyList.of(known, unknown), intType)
    val context   = DiagnosticCauses.empty

    assertEquals(context.knownRootsOfType(composite), firstRoots)
    assertEquals(context.provenRootsOfType(composite), Vector.empty)
  }

  test("unresolved alias cycles do not erase unknown evidence beside a known type failure") {
    val first = TypeAlias(
      source   = source,
      nameNode = Name.synth("First"),
      typeRef  = TypeRef(source, "Second", Some("types::second")),
      id       = Some("types::first")
    )
    val second = TypeAlias(
      source   = source,
      nameNode = Name.synth("Second"),
      typeRef  = TypeRef(source, "First", first.id),
      id       = Some("types::second")
    )
    val known     = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    val cycle     = TypeRef(source, "First", first.id)
    val composite = TypeTuple(source, List(cycle, known))
    val input     = module(List(first, second))
    val context   = DiagnosticCauses.fromModule(input)

    assertEquals(TypeUtils.canonical(cycle, input.resolvables), None)
    assertEquals(context.knownRootsOfType(composite), firstRoots)
    assertEquals(context.provenRootsOfType(composite), Vector.empty)
  }

  test("recursive nominal fields terminate without hiding independent failed fields") {
    val id        = "types::node"
    val recursive = Field(source, Name.synth("next"), TypeRef(source, "Node", Some(id)))
    val failedField = Field(
      source,
      Name.synth("failed"),
      UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    )
    val declaration = TypeStruct(
      source,
      None,
      Visibility.Public,
      Name.synth("Node"),
      Vector(recursive, failedField),
      id = Some(id)
    )
    val context   = DiagnosticCauses.fromModule(module(List(declaration)))
    val reference = TypeRef(source, "Node", declaration.id)

    assertEquals(context.knownRootsOfType(reference), firstRoots)
    assertEquals(context.provenRootsOfType(reference), firstRoots)
  }

  test("unavailable values without diagnostics do not acquire invented roots") {
    val untyped   = LiteralInt(source, 1L, typeSpec = None)
    val malformed = Expr(source, List(untyped, failed(firstRoots)))

    assert(!ValueAvailability.empty.isAvailable(untyped))
    assertEquals(DiagnosticCauses.empty.evidenceOf(untyped), CauseEvidence.empty)
    assertEquals(DiagnosticCauses.empty.knownRootsOf(malformed), firstRoots)
    assertEquals(DiagnosticCauses.empty.provenRootsOf(malformed), Vector.empty)
  }

  test("an unresolved value keeps companion causes without proving the whole result") {
    val declared   = binding("values::clean", integer())
    val context    = DiagnosticCauses.fromModule(module(List(declared)))
    val unresolved = Ref(source, "value", typeSpec = Some(intType))
    val resolved   = unresolved.copy(resolvedId = declared.id)
    val mixed      = Tuple(source, NonEmptyList.of(failed(firstRoots), expr(unresolved)))
    val complete   = mixed.copy(elements = NonEmptyList.of(failed(firstRoots), expr(resolved)))

    assertEvidence(context.evidenceOf(unresolved), Vector.empty, complete = false)
    assertEvidence(context.evidenceOf(mixed), firstRoots, complete        = false)
    assertEvidence(context.evidenceOf(complete), firstRoots, complete     = true)
    assertEvidence(context.evidenceOf(resolved), Vector.empty, complete   = true)
  }

  test("qualified access combines qualifier failures with the resolved declaration's failed type") {
    val written    = SourceOrigin.Loc(SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 8, 7)))
    val failedType = UndefinedTypeError(written, TypeRef(written, "Missing"), secondRoots)
    val field = Field(written, Name("value", written), failedType, Some("types::record::value"))
    val declaration = TypeStruct(
      written,
      None,
      Visibility.Public,
      Name("Record", written),
      Vector(field),
      Some("types::record")
    )
    val context = DiagnosticCauses.fromModule(module(List(declaration)))
    val access  = Ref(written, "value", resolvedId = field.id, qualifier = Some(failed(firstRoots)))
    val cleanQualifier   = access.copy(qualifier = Some(integer()))
    val cleanDeclaration = declaration.copy(fields = Vector(field.copy(typeSpec = intType)))
    val cleanContext     = DiagnosticCauses.fromModule(module(List(cleanDeclaration)))

    assertEvidence(context.evidenceOf(access), firstRoots ++ secondRoots, complete = true)
    assertEvidence(context.evidenceOf(cleanQualifier), secondRoots, complete       = true)
    assertEvidence(cleanContext.evidenceOf(access), firstRoots, complete           = true)
    assertEvidence(cleanContext.evidenceOf(cleanQualifier), Vector.empty, complete = true)
  }

  test("a type without an identity and a missing indexed identity remain separately unresolved") {
    val known = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    val declaration = TypeDef(
      source   = source,
      nameNode = Name.synth("Available"),
      typeSpec = Some(intType),
      id       = Some("types::available")
    )
    val context    = DiagnosticCauses.fromModule(module(List(declaration)))
    val unassigned = TypeRef(source, "Available")
    val absent     = TypeRef(source, "Available", Some("types::not-in-index"))
    val resolved   = TypeRef(source, "Available", declaration.id)
    val unresolved = List(unassigned, absent)

    unresolved.foreach { reference =>
      assertEvidence(context.evidenceOfType(reference), Vector.empty, complete = false)
      val composite = TypeTuple(source, List(known, reference))
      assertEvidence(context.evidenceOfType(composite), firstRoots, complete   = false)
    }
    assertEvidence(context.evidenceOfType(resolved), Vector.empty, complete = true)
    assertEvidence(
      context.evidenceOfType(TypeTuple(source, List(known, resolved))),
      firstRoots,
      complete = true
    )
  }

  test("an unstamped parser failure cannot borrow a companion failure's proof") {
    val stamped    = ParseTermError(source, "Invalid identifier", Some("123name"), secondRoots)
    val unstamped  = stamped.copy(causes = Vector.empty)
    val context    = DiagnosticCauses.empty
    val complete   = Tuple(source, NonEmptyList.of(failed(firstRoots), expr(stamped)))
    val incomplete = complete.copy(elements = NonEmptyList.of(failed(firstRoots), expr(unstamped)))

    assertEvidence(context.evidenceOf(stamped), secondRoots, complete                = true)
    assertEvidence(context.evidenceOf(unstamped), Vector.empty, complete             = false)
    assertEvidence(context.evidenceOf(complete), firstRoots ++ secondRoots, complete = true)
    assertEvidence(context.evidenceOf(incomplete), firstRoots, complete              = false)
  }

  test("ordinary applications require the callee result and argument result") {
    val param = parameter("function::argument")
    val function =
      binding("function::callee", expr(Lambda(source, List(param), failed(firstRoots), Nil)))
    val cleanFunction = function.copy(value = expr(Lambda(source, List(param), integer(), Nil)))
    val callee        = Ref(source, "value", resolvedId = function.id)
    val application   = App(source, callee, failed(secondRoots))
    val context       = DiagnosticCauses.fromModule(module(List(function)))
    val cleanContext  = DiagnosticCauses.fromModule(module(List(cleanFunction)))

    assertEvidence(context.evidenceOf(application), firstRoots ++ secondRoots, complete = true)
    assertEvidence(
      context.evidenceOf(application.copy(arg = integer())),
      firstRoots,
      complete = true
    )
    assertEvidence(cleanContext.evidenceOf(application), secondRoots, complete = true)
    assertEvidence(
      cleanContext.evidenceOf(application.copy(arg = integer())),
      Vector.empty,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(application.copy(fn = callee.copy(resolvedId = None))),
      secondRoots,
      complete = false
    )
  }

  test("lambda evidence accounts for parameter types, captured results, and its body") {
    val failedType  = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    val param       = parameter("lambda::argument", failedType)
    val captured    = binding("lambda::captured", failed(secondRoots))
    val capture     = Capture.CapturedRef(Ref(source, "value", resolvedId = captured.id))
    val lambda      = Lambda(source, List(param), failed(thirdRoots), List(capture))
    val context     = DiagnosticCauses.fromModule(module(List(captured)))
    val cleanParams = List(param.copy(typeSpec = Some(intType)))

    assertEvidence(
      context.evidenceOf(lambda),
      firstRoots ++ secondRoots ++ thirdRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(lambda.copy(params = cleanParams)),
      secondRoots ++ thirdRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(lambda.copy(captures = Nil)),
      firstRoots ++ thirdRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(lambda.copy(body = integer())),
      firstRoots ++ secondRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(lambda.copy(params = cleanParams, captures = Nil, body = integer())),
      Vector.empty,
      complete = true
    )
  }

  test("grouped ownership results require both their value and cleanup witness") {
    val boolean = expr(LiteralBool(source, false, Some(boolType)))
    val failedWitness =
      expr(InvalidExpression(source, boolean, typeSpec = Some(boolType), causes = secondRoots))
    val yielded = YieldOwnership(source, failed(firstRoots), failedWitness)
    val grouped = TermGroup(source, expr(yielded))
    val context = DiagnosticCauses.empty
    val clean   = yielded.copy(value = integer(), owns = boolean)

    assertEvidence(context.evidenceOf(grouped), firstRoots ++ secondRoots, complete = true)
    assertEvidence(
      context.evidenceOf(grouped.copy(inner = expr(yielded.copy(value = integer())))),
      secondRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(grouped.copy(inner = expr(yielded.copy(owns = boolean)))),
      firstRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(grouped.copy(inner = expr(clean))),
      Vector.empty,
      complete = true
    )
  }

  test("invalid wrappers own their proof independently of partial editor type metadata") {
    val unknownType = TypeRef(source, "Unavailable")
    val failedType  = UndefinedTypeError(source, TypeRef(source, "Missing"), secondRoots)
    val wrapper = InvalidExpression(
      source,
      failed(thirdRoots),
      typeSpec = Some(unknownType),
      typeAsc  = Some(failedType),
      causes   = firstRoots
    )
    val context = DiagnosticCauses.empty

    assertEvidence(context.evidenceOf(wrapper), firstRoots, complete                     = true)
    assertEvidence(context.evidenceOf(Expr(source, List(wrapper))), firstRoots, complete = true)
    assertEvidence(
      context.evidenceOf(wrapper.copy(typeSpec = None, typeAsc = None)),
      firstRoots,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(wrapper.copy(causes = Vector.empty)),
      Vector.empty,
      complete = false
    )
  }

  test("unsupported refinement validity cannot complete a companion type failure's proof") {
    val known      = UndefinedTypeError(source, TypeRef(source, "Missing"), firstRoots)
    val refinement = TypeRefinement(source, None, failed(secondRoots))
    val composite  = TypeTuple(source, List(known, refinement))
    val context    = DiagnosticCauses.empty

    assertEvidence(context.evidenceOfType(refinement), Vector.empty, complete = false)
    assertEvidence(context.evidenceOfType(composite), firstRoots, complete    = false)
    assertEvidence(
      context.evidenceOfType(TypeTuple(source, List(known, TypeUnit(source)))),
      firstRoots,
      complete = true
    )
  }

  test("destruction evidence follows its operand despite returning a clean Unit type") {
    val destruction = DispatchClosureDestructor(source, failed(firstRoots), Some(TypeUnit(source)))
    val context     = DiagnosticCauses.empty

    assertEvidence(context.evidenceOf(destruction), firstRoots, complete = true)
    assertEvidence(
      context.evidenceOf(destruction.copy(operand = integer())),
      Vector.empty,
      complete = true
    )
    assertEvidence(
      context.evidenceOf(destruction.copy(operand = failed(Vector.empty))),
      Vector.empty,
      complete = false
    )
  }
