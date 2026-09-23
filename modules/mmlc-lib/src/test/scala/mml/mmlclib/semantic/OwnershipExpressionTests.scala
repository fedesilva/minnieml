package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class OwnershipExpressionTests extends BaseEffFunSuite:

  private val phase = "ownership-analyzer"

  private def beforeOwnership(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val module  = injectCommonFunctions(injectStandardOperators(injectBasicTypes(parsed)))
      val initial = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val phases: List[CompilerState => CompilerState] = List(
        IdAssigner.rewriteModule,
        TypeResolver.rewriteModule,
        ConstructorGenerator.rewriteModule,
        MemoryFunctionGenerator.rewriteModule,
        RefResolver.rewriteModule,
        ExpressionRewriter.rewriteModule,
        Simplifier.rewriteModule,
        CaptureAnalyzer.rewriteModule,
        TypeChecker.rewriteModule,
        PartialApplicationElaborator.rewriteModule,
        ClosureMemoryFnGenerator.rewriteModule,
        StructDestructorBodyGenerator.rewriteModule
      )
      phases.foldLeft(initial) { (state, rewrite) =>
        val next = ResolvablesIndexer.rewriteModule(rewrite(state))
        assert(!next.hasErrors, next.errors)
        next
      }
    }

  private def binding(module: Module, name: String): Bnd =
    module.members.collectFirst { case b: Bnd if b.name == name => b }.get

  private def body(module: Module): Expr =
    binding(module, "example").value.terms.collectFirst { case l: Lambda => l.body }.get

  private def withBody(state: CompilerState, value: Expr): CompilerState =
    val original = binding(state.module, "example")
    val lambda   = original.value.terms.collectFirst { case l: Lambda => l }.get
    val updated =
      original.copy(value = original.value.copy(terms = List(lambda.copy(body = value))))
    val members = state.module.members.map {
      case b: Bnd if b.id == original.id => updated
      case other => other
    }
    ResolvablesIndexer.rewriteModule(state.withModule(state.module.copy(members = members)))

  private def shapeErrors(state: CompilerState): List[SemanticError.InvalidExpression] =
    state.semanticErrors.toList.collect {
      case e: SemanticError.InvalidExpression if e.phase == phase => e
    }

  private def cloneReferences(module: Module, value: Expr): List[Ref] =
    val cloneName = TypeUtils.cloneFnFor("String", module.resolvables).get
    val cloneId   = binding(module, cloneName).id
    TermTraversal.collect(value) { case r: Ref if r.resolvedId == cloneId => r }

  private def assertNoAddedLocals(before: Module, after: Module): Unit =
    def localIds(module: Module): Set[String] =
      TermTraversal.collect(body(module)) { case l: Lambda => l.params.flatMap(_.id) }.flatten.toSet
    assertEquals(localIds(after), localIds(before))

  private val forms = List(
    ("conditional", "if flag then \"yes\"; else \"no\";"),
    ("scoped application", "let text = \"yes\"; text")
  )

  for
    (form, argument) <- forms
    consuming <- List(false, true)
    inline <- List(false, true)
  do
    test(s"malformed $form preserves all terms: consuming=$consuming inline=$inline") {
      val marker = if consuming then "~" else ""
      val call   = if inline then s"{ ${marker}text: String -> text.length; }" else "take"
      beforeOwnership(s"""
        fn take(${marker}text: String): Int = text.length;;
        fn example(flag: Bool): Int = $call ($argument);;
      """).map { state =>
        val original  = body(state.module)
        val app       = original.terms.collectFirst { case a: App => a }.get
        val prefix    = LiteralString(app.arg.source, "prefix", typeSpec = app.arg.typeSpec)
        val malformed = app.arg.copy(terms = prefix :: app.arg.terms, typeAsc = app.arg.typeSpec)
        val input     = withBody(state, original.copy(terms = List(app.copy(arg = malformed))))
        val result    = OwnershipAnalyzer.rewriteModule(input)
        val errors    = shapeErrors(result)
        assertEquals(errors.map(_.expr), List(malformed))
        val preserved = TermTraversal.collect(body(result.module)) {
          case e: Expr if e.terms.headOption.contains(prefix) => e
        }
        assertEquals(preserved.size, 1)
        assertEquals(preserved.head.terms, malformed.terms)
        assertEquals(preserved.head.source, malformed.source)
        assertEquals(preserved.head.typeSpec, malformed.typeSpec)
        assertEquals(preserved.head.typeAsc, malformed.typeAsc)
        assertEquals(cloneReferences(result.module, body(result.module)), Nil)
        assertNoAddedLocals(input.module, result.module)
      }
    }

  for consuming <- List(false, true); inline <- List(false, true) do
    test(s"empty argument is unavailable: consuming=$consuming inline=$inline") {
      val marker = if consuming then "~" else ""
      val call   = if inline then s"{ ${marker}text: String -> text.length; }" else "take"
      beforeOwnership(s"""
        fn take(${marker}text: String): Int = text.length;;
        fn example(): Int = $call "text";;
      """).map { state =>
        val original = body(state.module)
        val app      = original.terms.collectFirst { case a: App => a }.get
        val empty    = app.arg.copy(terms = Nil)
        val input    = withBody(state, original.copy(terms = List(app.copy(arg = empty))))
        val result   = OwnershipAnalyzer.rewriteModule(input)
        assertEquals(shapeErrors(result).map(_.expr), List(empty))
        assertEquals(cloneReferences(result.module, body(result.module)), Nil)
        assertNoAddedLocals(input.module, result.module)
      }
    }

  test("groups preserve malformed conditional branches without witness allocation") {
    beforeOwnership("""
      fn take(text: String): Int = text.length;;
      fn example(flag: Bool): Int = take (if flag then int_to_str 123; else "no";);;
    """).map { state =>
      val original  = body(state.module)
      val app       = original.terms.collectFirst { case a: App => a }.get
      val cond      = app.arg.terms.collectFirst { case c: Cond => c }.get
      val prefix    = LiteralString(cond.ifTrue.source, "prefix", typeSpec = cond.ifTrue.typeSpec)
      val malformed = cond.ifTrue.copy(terms = prefix :: cond.ifTrue.terms)
      val grouped   = cond.ifTrue.copy(terms = List(TermGroup(malformed.source, malformed)))
      val argument  = app.arg.copy(terms = List(cond.copy(ifTrue = grouped)))
      val input     = withBody(state, original.copy(terms = List(app.copy(arg = argument))))
      val result    = OwnershipAnalyzer.rewriteModule(input)
      assertEquals(shapeErrors(result).map(_.expr), List(malformed))
      assertEquals(cloneReferences(result.module, body(result.module)), Nil)
      assertNoAddedLocals(input.module, result.module)
    }
  }

  test("invalid-expression payload keeps children and suppresses redundant shape errors") {
    beforeOwnership("""
      fn example(~text: String): Int = text.length;;
    """).map { state =>
      val original  = body(state.module)
      val malformed = original.copy(terms = original.terms ++ original.terms)
      val invalid   = original.copy(terms = List(InvalidExpression(original.source, malformed)))
      val input     = withBody(state, invalid)
      val prior  = SemanticError.InvalidExpression(malformed, "Retained frontend error", "frontend")
      val result = OwnershipAnalyzer.rewriteModule(input.addError(prior))
      assert(result.errors.contains(prior))
      assertEquals(shapeErrors(result), Nil)
      val payloads = TermTraversal.collect(body(result.module)) { case i: InvalidExpression =>
        i.originalExpr
      }
      assertEquals(payloads, List(malformed))
    }
  }

  List(
    ("consumption", "Int", "let alias = value; take alias"),
    ("return", "String", "let alias = value; alias"),
    ("capture", "Int -> Int", "let alias = value; ~{ n: Int -> alias.length + n; }")
  ).foreach { (label, resultType, continuation) =>
    test(s"malformed values and aliases cannot establish $label ownership") {
      beforeOwnership(s"""
        fn take(~text: String): Int = text.length;;
        fn example(): $resultType =
          let value = int_to_str 123;
          $continuation;
        ;
      """).map { state =>
        val original     = body(state.module)
        val app          = original.terms.collectFirst { case a: App => a }.get
        val malformed    = app.arg.copy(terms = app.arg.terms ++ app.arg.terms)
        val input        = withBody(state, original.copy(terms = List(app.copy(arg = malformed))))
        val availability = ValueAvailability.fromModule(input.module)
        val aliases = TermTraversal
          .collect(body(input.module)) { case l: Lambda =>
            l.params.filter(p => Set("value", "alias").contains(p.name))
          }
          .flatten
        assertEquals(aliases.size, 2)
        aliases.foreach { param =>
          val ref = Ref(param.source, param.name, typeSpec = param.typeSpec, resolvedId = param.id)
          assert(!availability.isAvailable(ref))
        }
        val result = OwnershipAnalyzer.rewriteModule(input)
        assertEquals(result.semanticErrors.toList, shapeErrors(result))
        assertEquals(shapeErrors(result).map(_.expr), List(malformed))
        assertEquals(cloneReferences(result.module, body(result.module)), Nil)
        assertNoAddedLocals(input.module, result.module)
      }
    }
  }

  private def rewriteApplications(value: Expr)(change: App => App): Expr =

    def expression(expr: Expr): Expr = expr.copy(terms = expr.terms.map(term))

    def lambda(value: Lambda): Lambda = value.copy(body = expression(value.body))

    def application(app: App): App =
      val fn: Ref | App | Lambda = app.fn match
        case l: Lambda => lambda(l)
        case a: App => application(a)
        case r: Ref => r
      change(app.copy(fn = fn, arg = expression(app.arg)))

    def term(value: Term): Term = value match
      case a: App => application(a)
      case l: Lambda => lambda(l)
      case e: Expr => expression(e)
      case g: TermGroup => g.copy(inner = expression(g.inner))
      case c: Cond =>
        c.copy(
          cond    = expression(c.cond),
          ifTrue  = expression(c.ifTrue),
          ifFalse = expression(c.ifFalse)
        )
      case other => other

    expression(value)

  test("malformed arguments retain child and continuation use-after-move diagnostics") {
    beforeOwnership("""
      fn take(~text: String): Int = text.length;;
      fn probe(text: String): Int = text.length;;
      fn example(): Int =
        let owned = int_to_str 123;
        let first = take owned;
        let broken = probe "done";
        take owned;
      ;
      fn independent(): Int =
        let owned = int_to_str 456;
        let first = take owned;
        take owned;
      ;
    """).map { state =>
      val takeId  = binding(state.module, "take").id
      val probeId = binding(state.module, "probe").id
      val calls = TermTraversal.collect(body(state.module)) {
        case a: App if (a.fn match
              case r: Ref => r.resolvedId == takeId
              case _ => false
            ) =>
          a
      }
      assertEquals(calls.size, 2)
      val firstCall = calls.minBy(_.source.spanOpt.get.start.index)
      val rewritten = rewriteApplications(body(state.module)) { app =>
        app.fn match
          case r: Ref if r.resolvedId == probeId =>
            app.copy(arg = app.arg.copy(terms = firstCall :: app.arg.terms))
          case _ => app
      }
      val input  = withBody(state, rewritten)
      val result = OwnershipAnalyzer.rewriteModule(input)
      assertEquals(shapeErrors(result).size, 1)
      val moves = result.semanticErrors.toList.collect { case e: SemanticError.UseAfterMove => e }
      val expected = calls.flatMap(a => a.arg.terms.collect { case r: Ref => r.source }).toSet
      assert(
        expected.subsetOf(moves.map(_.ref.source).toSet),
        s"Expected $expected; errors ${result.errors}"
      )
      val exampleOwner = calls.head.arg.terms.collectFirst { case r: Ref => r.resolvedId }.get
      assert(moves.exists(_.ref.resolvedId != exampleOwner))
      val retained = TermTraversal.collect(body(result.module)) {
        case e: Expr if e.terms.size == 2 => e
      }
      assertEquals(retained.size, 1)
      assertEquals(retained.head.terms.last, shapeErrors(result).head.expr.terms.last)
    }
  }

  List(
    "(\"x\" \"y\").length",
    "(let unused = (\"x\" \"y\"); \"z\").length"
  ).foreach { expression =>
    test(s"field selection preserves malformed qualifier syntax: $expression") {
      semState(s"fn example(): Int = $expression;;").map { result =>
        assertEquals(result.errors.count(_.isInstanceOf[SemanticError.DanglingTerms]), 1)
        val errors = result.errors.collect {
          case e: SemanticError.InvalidExpression if e.phase == phase => e
        }
        assertEquals(errors.size, 1)
        val retained = TermTraversal.collect(body(result.module)) {
          case e: Expr if e.terms.size == 2 => e
        }
        assertEquals(retained, errors.map(_.expr))
        assertEquals(result.errors.size, 2)
      }
    }
  }

  for wrapped <- List(false, true) do
    test(s"qualifier recovery preserves child and continuation diagnostics: wrapped=$wrapped") {
      beforeOwnership("""
        fn take(~text: String): Int = text.length;;
        fn example(): Int =
          let owned = int_to_str 123;
          let first = take owned;
          let broken = ("x").length;
          take owned;
        ;
      """).map { state =>
        val takeId = binding(state.module, "take").id
        val calls = TermTraversal
          .collect(body(state.module)) {
            case a: App if (a.fn match
                  case r: Ref => r.resolvedId == takeId
                  case _ => false
                ) =>
              a
          }
          .sortBy(_.source.spanOpt.get.start.index)
        assertEquals(calls.size, 2)
        val references = calls.map(_.arg.terms.collectFirst { case r: Ref => r }.get)
        val rewritten = rewriteApplications(body(state.module)) { app =>
          app.fn match
            case l: Lambda if l.params.exists(_.name == "broken") =>
              val projection = app.arg.terms.collectFirst { case r: Ref => r }.get
              val qualifier  = projection.qualifier.get
              val malformed =
                Expr(qualifier.source, List(calls.head, qualifier), typeSpec = qualifier.typeSpec)
              val retained: Term =
                if wrapped then
                  InvalidExpression(malformed.source, malformed, typeSpec = malformed.typeSpec)
                else malformed
              app.copy(arg =
                app.arg.copy(terms = List(projection.copy(qualifier = Some(retained))))
              )
            case _ => app
        }
        val result = OwnershipAnalyzer.rewriteModule(withBody(state, rewritten))
        assertEquals(shapeErrors(result).size, if wrapped then 0 else 1)
        val moved = result.semanticErrors.toList.collect { case e: SemanticError.UseAfterMove =>
          e.ref
        }
        references.foreach { ref =>
          assert(
            moved.exists(r => r.resolvedId == ref.resolvedId && r.source == ref.source),
            result.errors
          )
        }
        val retained = TermTraversal.collect(body(result.module)) {
          case e: Expr if e.terms.size == 2 => e
        }
        assertEquals(retained.size, 1)
      }
    }

  test("call-boundary lifetime checks do not repeat qualifier ownership effects") {
    beforeOwnership("""
      struct Box { text: String };
      fn take(~owned: Int): Bool = true;;
      fn inspect(text: String): Int = text.length;;
      fn example(~owned: Int, box: Box): Int =
        if take owned then inspect box.text; else inspect box.text; ;
      ;
    """).map { state =>
      val control = OwnershipAnalyzer.rewriteModule(state)
      assert(!control.hasErrors, control.errors)
      val original   = body(state.module)
      val cond       = original.terms.collectFirst { case c: Cond => c }.get
      val inspection = cond.ifTrue.terms.collectFirst { case a: App => a }.get
      val selected   = inspection.arg.terms.collectFirst { case r: Ref => r }.get
      val holder     = selected.qualifier.get
      val holderExpr = Expr(holder.source, List(holder), typeSpec = holder.typeSpec)
      val qualifier =
        cond.copy(ifTrue = holderExpr, ifFalse = holderExpr, typeSpec = holder.typeSpec)
      val replacement = inspection.copy(arg =
        inspection.arg.copy(terms = List(selected.copy(qualifier = Some(qualifier))))
      )
      val input       = withBody(state, original.copy(terms = List(replacement)))
      val typeChecked = TypeChecker.rewriteModule(input)
      assert(!typeChecked.hasErrors, typeChecked.errors)
      assert(ValueAvailability.fromModule(input.module).isAvailable(replacement))
      val consumed = TermTraversal.collect(cond.cond) { case r: Ref if r.name == "owned" => r }.head
      assert(!TypeUtils.requiresDestruction(consumed.typeSpec.get, input.module.resolvables))
      val result = OwnershipAnalyzer.rewriteModule(input)
      assert(!result.hasErrors, result.errors)
    }
  }
