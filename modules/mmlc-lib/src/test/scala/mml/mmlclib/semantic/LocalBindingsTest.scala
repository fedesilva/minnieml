package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class LocalBindingsTest extends BaseEffFunSuite:

  test("generated memory helper parameters have identities and indexed references") {
    semNotFailed("""
      struct Label { text: String };
      fn make(): Label = Label "name";;
    """).map { module =>
      assertLocalIndex(module)
      val helper = module.members
        .collectFirst {
          case binding: Bnd if binding.name == "__clone_Label" => binding
        }
        .getOrElse(fail("Expected generated clone helper"))
      val params =
        TermTraversal.collect(helper.value) { case lambda: Lambda => lambda.params }.flatten
      assert(params.nonEmpty)
      params.foreach { param =>
        val id = param.id.getOrElse(fail(s"Missing identity for ${param.name}"))
        assertEquals(module.resolvables.lookup(id), Some(param))
        val references = TermTraversal.collect(helper.value) {
          case ref: Ref if ref.resolvedId.contains(id) => ref
        }
        assert(references.nonEmpty, s"Missing resolved reference to ${param.name}")
      }
    }
  }

  test("ownership binding wrappers retain function types and indexed local identities") {
    semNotFailed("""
      fn example(): Int =
        let text = int_to_str 123;
        text.length;
      ;
    """).map { module =>
      val binding = module.members
        .collectFirst {
          case binding: Bnd if binding.name == "example" => binding
        }
        .getOrElse(fail("Expected example binding"))
      assertBindingTypes(binding.value, module)
    }
  }

  private def bindingApplications(expr: Expr): List[App] =
    TermTraversal.collect(expr) {
      case app: App if app.fn.isInstanceOf[Lambda] => app
    }

  private def assertBindingTypes(expr: Expr, module: Module): Unit =
    val wrappers = bindingApplications(expr)
    assert(wrappers.nonEmpty)
    wrappers.foreach { app =>
      app.fn match
        case lambda: Lambda =>
          val param = lambda.params.headOption.getOrElse(fail("Expected binding parameter"))
          val id    = param.id.getOrElse(fail("Expected binding identity"))
          assertEquals(module.resolvables.lookup(id), Some(param))
          lambda.typeSpec match
            case Some(signature: TypeFn) =>
              assertEquals(signature.paramTypes.toList, param.typeSpec.toList)
              assertEquals(Some(signature.returnType), app.typeSpec)
              assertEquals(lambda.body.typeSpec, app.typeSpec)
            case other => fail(s"Expected function type on binding lambda, found $other")
        case _ => fail("Expected lambda application")
    }

  List(
    "conditional witness" -> """
      fn example(flag: Bool): Int =
        let text = if flag then int_to_str 123; else "abc"; ;
        text.length;
      ;
    """,
    "allocating arguments and saved predicates" -> """
      fn length(text: String): Int = text.length;;
      fn example(flag: Bool): Int =
        length (if flag then int_to_str 123; else "abc";);
      ;
    """,
    "direct consuming lambda" -> """
      fn example(): Int =
        { ~text: String, n: Int -> text.length + n; } (int_to_str 123) 1;
      ;
    """,
    "PAP payload and invocation cleanup" -> """
      fn take(~text: String, n: Int): Int = text.length + n;;
      fn example(): Int =
        let partial = take (int_to_str 123);
        partial 1;
      ;
    """,
    "early scalar PAP release" -> """
      fn add(a: Int, b: Int): Int = a + b;;
      fn example(): Int =
        let partial = add 1;
        let result = partial 2;
        add result 3;
      ;
    """,
    "generated struct cleanup" -> """
      struct Label { first: String, second: String };
      fn example(): Int =
        let label = Label (int_to_str 123) (int_to_str 456);
        label.first.length;
      ;
    """
  ).foreach { (name, source) =>
    test(s"binding types survive $name") {
      semNotFailed(source).map { module =>
        assertLocalIndex(module)
        module.members.collect { case binding: Bnd => binding }.foreach { binding =>
          val hasBindings = bindingApplications(binding.value).nonEmpty
          if hasBindings then assertBindingTypes(binding.value, module)
        }
      }
    }
  }

  test("binding construction preserves consuming parameters and declared input types") {
    val source = SourceOrigin.Synth
    val input  = TypeRef(source, "String", "stdlib::typedef::String".some)
    val output = TypeRef(source, "Int", "stdlib::typealias::Int".some)
    val allocation = LocalBindings.local(
      BindingOwner.binding("Test", "main"),
      "text",
      typeAsc   = input.some,
      consuming = true
    )
    val (_, local) = allocation.run(BindingIdSupply()).value
    val value =
      Expr(source, List(LiteralString(source, "abc", typeSpec = input.some)), typeSpec = input.some)
    val body =
      Expr(source, List(LiteralInt(source, 1, typeSpec = output.some)), typeSpec = output.some)
    val app = LocalBindings.bind(local.param, value, body)
    app.fn match
      case lambda: Lambda =>
        assertEquals(lambda.params, List(local.param))
        assert(lambda.params.head.consuming)
        assertEquals(lambda.body, body)
        lambda.typeSpec match
          case Some(signature: TypeFn) =>
            assertEquals(signature.paramTypes.toList, List(input))
            assertEquals(signature.returnType, output)
          case other => fail(s"Expected function type, found $other")
      case _ => fail("Expected binding scope")
    assertEquals(app.arg, value)
    assertEquals(app.typeSpec, body.typeSpec)
    assertEquals(local.ref.resolvedId, local.param.id)
    assertEquals(local.ref.typeSpec, input.some)
  }

  test("cleanup sequencing preserves continuation annotations and effect placement") {
    val source   = SourceOrigin.Loc(SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 2, 1)))
    val output   = TypeRef(source, "Int", "stdlib::typealias::Int".some)
    val unitType = TypeRef(SourceOrigin.Synth, "Unit", "stdlib::typedef::Unit".some)
    val body = Expr(
      source,
      List(LiteralInt(source, 1, typeSpec = output.some)),
      typeAsc  = output.some,
      typeSpec = output.some
    )
    val effect = LiteralUnit(SourceOrigin.Synth, typeSpec = unitType.some)
    val (_, result) = LocalBindings
      .sequence(effect, body, BindingOwner.binding("Test", "main"), unitType)
      .run(BindingIdSupply())
      .value
    assertEquals(result.source, body.source)
    assertEquals(result.typeAsc, body.typeAsc)
    assertEquals(result.typeSpec, body.typeSpec)
    assertEquals(TermTraversal.collect(result) { case unit: LiteralUnit => unit }, List(effect))
    result.terms match
      case List(app: App) =>
        assertEquals(app.arg.terms, List(effect))
        app.fn match
          case lambda: Lambda =>
            assertEquals(lambda.body, body)
            assert(lambda.params.forall(_.id.nonEmpty))
            lambda.typeSpec match
              case Some(signature: TypeFn) =>
                assertEquals(signature.paramTypes.toList, List(unitType))
                assertEquals(signature.returnType, output)
              case other => fail(s"Expected function type, found $other")
          case _ => fail("Expected cleanup scope")
      case _ => fail("Expected effect before continuation")
  }

  private def parameters(module: Module): List[FnParam] =
    module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
      TermTraversal
        .collect(binding.value) { case lambda: Lambda =>
          lambda.params ++ lambda.meta.flatMap(_.environmentParam).toList
        }
        .flatten
    }

  private def assertDefinitionIndex(module: Module): Unit =
    val definitions = ResolvablesIndexer.definitions(module)
    assert(definitions.forall(_.id.nonEmpty), "Every definition requires an identity")
    assertEquals(definitions.flatMap(_.id).distinct.size, definitions.size)
    definitions.foreach { definition =>
      val indexed = definition match
        case tpe: ResolvableType => tpe.id.flatMap(module.resolvables.lookupType)
        case value => value.id.flatMap(module.resolvables.lookup)
      assertEquals(indexed, definition.some)
    }

  private def assertLocalIndex(module: Module): Unit =
    assertDefinitionIndex(module)

    def assertReference(ref: Ref): Unit =
      if ref.qualifier.isEmpty then assert(ref.resolvedId.nonEmpty, s"Missing target: ${ref.name}")
      ref.resolvedId.foreach { id =>
        assert(module.resolvables.lookup(id).nonEmpty, s"Dangling reference: $id")
      }

    module.members.collect { case binding: Bnd => binding }.foreach { binding =>
      TermTraversal.collect(binding.value) { case ref: Ref => ref }.foreach(assertReference)
      TermTraversal.collect(binding.value) { case lambda: Lambda => lambda }.foreach { lambda =>
        lambda.captures.foreach(capture => assertReference(capture.ref))
        lambda.meta.foreach { meta =>
          (meta.borrowedCaptures ++ meta.transferredCaptures).foreach { id =>
            assert(module.resolvables.lookup(id).nonEmpty, s"Dangling capture metadata: $id")
          }
        }
      }
    }

  test("shadowing and sibling anonymous scopes resolve to distinct indexed bindings") {
    semNotFailed("""
      fn main(): Int =
        let value = 1;
        let first = { x: Int -> let value = x; value; };
        let second = { x: Int -> let value = x; value; };
        first value + second value;
      ;
    """).map { module =>
      assertLocalIndex(module)
      val locals = parameters(module).filter(p => p.name == "value" && p.source.isFromSource)
      assertEquals(locals.size, 3)
      assert(locals.forall(_.id.exists(_.contains("::scope::"))))
      val bodies = module.members.collect {
        case binding: Bnd if binding.name == "main" => binding.value
      }
      val refs = bodies.flatMap(body => TermTraversal.collect(body) { case ref: Ref => ref })
      locals.foreach(p =>
        assert(
          refs.exists(_.resolvedId == p.id),
          s"Missing use of ${p.id}; targets: ${refs.map(_.resolvedId)}"
        )
      )
    }
  }

  test("parameterless sibling scopes receive independent paths before resolution") {
    parseNotFailed("""
      fn main(): Int =
        let first = { let value = 1; value; };
        let second = { let value = 2; value; };
        0;
      ;
    """).map { module =>
      val initial  = CompilerState.empty(module, SourceInfo(""), CompilerConfig.default)
      val assigned = IdAssigner.rewriteModule(initial)
      val locals   = parameters(assigned.module).filter(_.name == "value")
      assertEquals(locals.size, 2)
      assertEquals(locals.flatMap(_.id).distinct.size, 2)
      val repeated = IdAssigner.rewriteModule(assigned)
      assertEquals(parameters(repeated.module), parameters(assigned.module))
    }
  }

  test("fresh locals retain contracts and allocation history without reusing template identities") {
    val source = SourceOrigin.Synth
    val tpe    = TypeRef(source, "Int", "stdlib::typealias::Int".some)
    val template = FnParam(
      source,
      Name.synth("value"),
      typeSpec  = tpe.some,
      typeAsc   = tpe.some,
      id        = "existing::value".some,
      consuming = true
    )
    val owner = BindingOwner.binding("Test", "main")
    val allocation = for
      first <- LocalBindings.fresh(template, owner, "test")
      second <- LocalBindings.fresh(template, owner, "test")
    yield (first, second)
    val (supply, (first, second)) = allocation.run(BindingIdSupply()).value
    assert(first.param.id != second.param.id)
    assert(first.param.id != template.id)
    assertEquals(first.param.copy(id = template.id), template)
    assertEquals(first.ref.resolvedId, first.param.id)
    assertEquals(first.ref.candidateIds, first.param.id.toList)
    assertEquals(first.ref.typeSpec, template.typeSpec)
    val (_, third) = LocalBindings.fresh(template, owner, "test").run(supply).value
    assert(third.param.id != first.param.id && third.param.id != second.param.id)
  }

  test("allocation reserves definitions absent from the module index") {
    parseNotFailed("fn main(value: Int): Int = value;;").map { module =>
      val owner    = BindingOwner.binding(module.name, "main")
      val occupied = s"${owner.path}::generated::test::0::value"
      val members = module.members.map {
        case binding: Bnd =>
          binding.copy(value = binding.value.copy(terms = binding.value.terms.map {
            case lambda: Lambda =>
              lambda.copy(params = lambda.params.map(_.copy(id = occupied.some)))
            case term => term
          }))
        case member => member
      }
      val seeded =
        BindingIdSupply().include(module.copy(members = members, resolvables = ResolvablesIndex()))
      val template   = FnParam(SourceOrigin.Synth, Name.synth("value"))
      val (_, local) = LocalBindings.fresh(template, owner, "test").run(seeded).value
      assert(!local.param.id.contains(occupied))
    }
  }

  test("a reference to an unassigned parameter reports a construction error") {
    val param = FnParam(SourceOrigin.Synth, Name.synth("value"))
    assert(LocalBindings.reference(param).isLeft)
  }

  test("generated scope provenance stays distinct and stable on reentry and relocation") {
    val owner     = BindingOwner.binding("Test", "original")
    val relocated = BindingOwner.binding("Test", "relocated")
    val allocation = for
      first <- LocalBindings.local(owner, "value", purpose = "pap")
      second <- LocalBindings.local(owner, "value", purpose = "pap")
      firstScope <- BindingIds.within(List(first.param), owner)
      secondScope <- BindingIds.within(List(second.param), owner)
      repeatedScope <- BindingIds.within(List(first.param), firstScope)
      relocatedScope <- BindingIds.within(List(first.param), relocated)
      nested <- LocalBindings.local(relocatedScope, "value", purpose = "cleanup")
    yield (firstScope, secondScope, repeatedScope, relocatedScope, first, nested)

    val (_, (firstScope, secondScope, repeated, moved, first, nested)) =
      allocation.run(BindingIdSupply()).value
    assert(firstScope.path != secondScope.path)
    assertEquals(repeated, firstScope)
    assertEquals(moved, firstScope)
    assert(firstScope.path.contains("::generated::pap::"))
    assert(nested.param.id.exists(_.startsWith(firstScope.path + "::")))
    assertEquals(first.ref.resolvedId, first.param.id)
    assert(nested.ref.resolvedId != first.ref.resolvedId)
  }

  test("reindexing replaces definition versions while existing references keep their identity") {
    semNotFailed("fn identity(value: Int): Int = value;;").map { module =>
      val binding = module.members
        .collectFirst {
          case binding: Bnd if binding.name == "identity" => binding
        }
        .getOrElse(fail("Expected identity binding"))
      val lambda = binding.value.terms
        .collectFirst { case lambda: Lambda => lambda }
        .getOrElse(fail("Expected lambda"))
      val original = lambda.params.head
      val updated  = original.copy(consuming = true)
      val moved = Lambda(
        SourceOrigin.Synth,
        Nil,
        lambda.body.copy(terms = List(lambda.copy(params = List(updated)))),
        Nil
      )
      val rewritten = binding.copy(value = binding.value.copy(terms = List(moved)))
      val next = ResolvablesIndexer.refresh(module.copy(members = module.members.map {
        case b: Bnd if b.id == binding.id => rewritten
        case other => other
      }))
      assertEquals(updated.id.flatMap(next.resolvables.lookup), updated.some)
      val refs = TermTraversal.collect(lambda.body) {
        case ref: Ref if ref.resolvedId == original.id => ref
      }
      assert(refs.nonEmpty)
      refs.foreach(ref =>
        assertEquals(ref.resolvedId.flatMap(next.resolvables.lookup), updated.some)
      )
    }
  }

  test("branch allocation and staged PAP creation preserve unique indexed identities") {
    semNotFailed("""
      fn sum(a: Int, b: Int, c: Int): Int = a + b + c;;
      fn example(flag: Bool): Int =
        let first = sum 1;
        let second = first 2;
        if flag then
          let text = int_to_str (second 3);
          text.length;
        else
          let text = int_to_str (second 4);
          text.length;
        ;
      ;
    """).map(assertLocalIndex)
  }

  test("allocating phase checkpoints retain identities through repeated PAP elaboration") {
    parseNotFailed("""
      struct Label { text: String };
      fn take(~text: String, a: Int, b: Int): Int = text.length + a + b;;
      fn example(): Int =
        let first = take (int_to_str 123);
        let second = first 1;
        second 2;
      ;
    """).map { parsed =>
      val module  = injectCommonFunctions(injectStandardOperators(injectBasicTypes(parsed)))
      val initial = CompilerState.empty(module, SourceInfo(""), CompilerConfig.default)
      val phases: List[CompilerState => CompilerState] = List(
        IdAssigner.rewriteModule,
        TypeResolver.rewriteModule,
        ConstructorGenerator.rewriteModule,
        MemoryFunctionGenerator.rewriteModule,
        RefResolver.rewriteModule,
        ExpressionRewriter.rewriteModule,
        Simplifier.rewriteModule,
        CaptureAnalyzer.rewriteModule,
        TypeChecker.rewriteModule
      )
      val typed = phases.foldLeft(initial) { (state, phase) =>
        val next = ResolvablesIndexer.rewriteModule(phase(state))
        assert(!next.hasErrors, next.errors)
        assertDefinitionIndex(next.module)
        assert(state.bindingIds.allocated.subsetOf(next.bindingIds.allocated))
        assert(
          ResolvablesIndexer
            .definitions(next.module)
            .flatMap(_.id)
            .forall(next.bindingIds.allocated.contains)
        )
        next
      }
      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      val repeated   = PartialApplicationElaborator.rewriteModule(elaborated)
      assertEquals(repeated.module.members, elaborated.module.members)
      assert(elaborated.bindingIds.allocated.subsetOf(repeated.bindingIds.allocated))
      assertLocalIndex(repeated.module)
      val closures = ClosureMemoryFnGenerator.rewriteModule(repeated)
      assert(!closures.hasErrors, closures.errors)
      assertLocalIndex(closures.module)
      assert(
        ResolvablesIndexer
          .definitions(closures.module)
          .flatMap(_.id)
          .forall(closures.bindingIds.allocated.contains)
      )
      val environments =
        closures.module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
          TermTraversal
            .collect(binding.value) { case lambda: Lambda =>
              lambda.meta.flatMap(_.environmentParam)
            }
            .flatten
        }
      assert(environments.nonEmpty, "Expected environment parameters for transferred PAP captures")
      environments.foreach(param =>
        assertEquals(param.id.flatMap(closures.module.resolvables.lookup), param.some)
      )
    }
  }
