package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.termLambda
import mml.mmlclib.util.error.print.diagnosticMessage

class ProtocolImpliedRequirementTests extends BaseEffFunSuite:

  private val protocols = """
    protocol Render 'T where 'T: Describe & Display = ;
    protocol Describe 'T where 'T: Echo = fn describe(value: 'T): 'T; ;
    protocol Display 'T where 'T: Echo = ;
    protocol Echo 'T =
      fn echo(value: 'T): 'T;
      fn choose(label: String, value: 'T): 'T;
      op <+>(left: 'T, right: 'T): 'T 60 left;
    ;
  """

  private val forwarding = """
    fn relay(value: 'A): 'A where 'A: Echo = echo value;;
    fn labeled(label: String, value: 'A): 'A where 'A: Echo = choose label value;;
    fn apply(f: 'A -> 'B, value: 'A): 'B = f value;;
  """

  private def check(source: String): IO[CompilerState] =

    parseNotFailed(source).map { parsed =>
      val prepared = prepareForTypeChecking(source, parsed)
      assertEquals(prepared.errors.map(diagnosticMessage), Vector.empty)
      TypeChecker.rewriteModule(prepared)
    }

  private def binding(state: CompilerState, name: String): Bnd =
    state.module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Missing fixture binding $name"))

  private def function(state: CompilerState, name: String): Lambda =
    termLambda(binding(state, name).value).getOrElse(fail(s"Missing fixture function $name"))

  private def references(term: Term): List[Ref] =
    TermTraversal.collect(term) { case ref: Ref => ref }

  private def variableId(tpe: Type): Option[String] = tpe match
    case variable: TypeVariable => variable.resolvedId
    case other => fail(s"Expected a rigid type variable, got $other")

  private def missingRequirements(state: CompilerState): List[TypeError.MissingGenericRequirement] =
    state.errors.collect {
      case SemanticError.TypeCheckingError(error: TypeError.MissingGenericRequirement) => error
    }.toList

  private val memberUses = List(
    ("direct prerequisite", "describe value", "Describe", "describe"),
    ("transitive prerequisite", "echo value", "Echo", "echo"),
    ("function value", "let selected: 'T -> 'T = echo; selected value", "Echo", "echo"),
    ("inferred function value", "let selected = echo; selected value", "Echo", "echo"),
    ("inferred partial", "let selected = choose \"part\"; selected value", "Echo", "choose"),
    (
      "partial application",
      "let selected: 'T -> 'T = choose \"part\"; selected value",
      "Echo",
      "choose"
    ),
    ("operator", "value <+> value", "Echo", "<+>"),
    ("callback", "apply echo value", "Echo", "echo"),
    ("local function", "fn local(other: 'T): 'T = echo other;; local value", "Echo", "echo"),
    ("local generic function", "fn local(other: 'U): 'T = echo value;; local true", "Echo", "echo")
  )

  memberUses.foreach { (description, body, owner, member) =>
    test(s"an implied $description retains signature and binder identity without instances"):
      val source = protocols + forwarding + s"""
        fn use(value: 'T): 'T where 'T: Render = $body;;
      """
      check(source).map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        val lambda    = function(state, "use")
        val signature = protocolSignature(state.module, owner, member)
        val selected  = references(lambda.body).filter(_.resolvedId == signature.id)
        assertEquals(selected.size, 1)
        assertEquals(selected.head.candidateIds, signature.id.toList)
        assertEquals(
          selected.head.protocolType.map(variableId),
          Some(lambda.typeParams.head.resolvedId)
        )
        assert(selected.head.typeSpec.nonEmpty)
        assertEquals(lambda.constraints.flatMap(_.constraints).size, 1)
      }
  }

  List(
    ("inferred call", "relay value", "relay"),
    ("explicit call", "relay 'T value", "relay"),
    ("function value", "let selected: 'T -> 'T = relay; selected value", "relay"),
    ("partial application", "let selected: 'T -> 'T = labeled \"part\"; selected value", "labeled"),
    ("callback", "apply relay value", "relay")
  ).foreach { (description, body, target) =>
    test(s"generic forwarding to a transitive prerequisite supports $description"):
      check(protocols + forwarding + s"""
        fn use(value: 'T): 'T where 'T: Render = $body;;
      """).map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        val lambda = function(state, "use")
        val calls  = references(lambda.body).filter(_.resolvedId == binding(state, target).id)
        assertEquals(calls.size, 1)
        assertEquals(calls.head.typeArgs.map(variableId), List(lambda.typeParams.head.resolvedId))
      }
  }

  test("checking implied requirements leaves written constraints and locations unchanged"):
    val source = protocols + """
      fn use(value: 'T): 'T where 'T: Render & Echo =
        fn local(other: 'T): 'T where 'T: Echo = echo other;;
        local value;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val prepared = prepareForTypeChecking(source, parsed)
      assertEquals(prepared.errors.map(diagnosticMessage), Vector.empty)
      val checked = TypeChecker.rewriteModule(prepared)
      assertEquals(checked.errors.map(diagnosticMessage), Vector.empty)

      def clauses(state: CompilerState) =
        TermTraversal.collect(binding(state, "use").value) {
          case lambda: Lambda if lambda.constraints.nonEmpty => lambda.constraints
        }

      assertEquals(clauses(checked), clauses(prepared))
      assertEquals(clauses(checked).map(_.flatMap(_.constraints).size), List(2, 1))
      val local = TermTraversal.collect(function(checked, "use").body) {
        case lambda: Lambda if lambda.constraints.nonEmpty => lambda
      }
      assertEquals(local.size, 1)
      assertEquals(local.head.typeParams, Nil)
      assertEquals(
        local.head.constraints.head.variable.resolvedId,
        function(checked, "use").typeParams.head.resolvedId
      )
    }

  List("echo other", "relay other").foreach { body =>
    test(s"a prerequisite on one variable cannot justify $body for another"):
      check(protocols + forwarding + s"""
        fn use(value: 'T, other: 'U): 'U where 'T: Render = $body;;
      """).map { state =>
        val missing = missingRequirements(state)
        val lambda  = function(state, "use")
        assertEquals(missing.size, 1)
        assertEquals(variableId(missing.head.tpe), lambda.typeParams(1).resolvedId)
        assertNotEquals(variableId(missing.head.tpe), lambda.typeParams.head.resolvedId)
        assertEquals(
          missing.head.protocol.resolvedId,
          function(state, "relay").constraints.head.constraints.head.resolvedId
        )
      }
  }

  test("a requirement in another function with the same variable spelling supplies no evidence"):
    check(protocols + forwarding + """
      fn allowed(value: 'T): 'T where 'T: Render = relay value;;
      fn denied(value: 'T): 'T = relay value;;
    """).map { state =>
      val missing = missingRequirements(state)
      assertEquals(missing.size, 1)
      assertEquals(
        variableId(missing.head.tpe),
        function(state, "denied").typeParams.head.resolvedId
      )
      assertNotEquals(
        variableId(missing.head.tpe),
        function(state, "allowed").typeParams.head.resolvedId
      )
    }

  test("a local function's own variable cannot borrow an enclosing variable's prerequisites"):
    check(protocols + """
      fn use(value: 'T): 'T where 'T: Render =
        fn local(other: 'U): 'U = echo other;;
        value;
      ;
    """).map { state =>
      val outer = function(state, "use")
      val local = TermTraversal.collect(outer.body) {
        case lambda: Lambda if lambda.typeParams.nonEmpty => lambda
      }
      assertEquals(local.size, 1)
      val missing = missingRequirements(state)
      assertEquals(missing.size, 1)
      assertEquals(variableId(missing.head.tpe), local.head.typeParams.head.resolvedId)
      assertNotEquals(variableId(missing.head.tpe), outer.typeParams.head.resolvedId)
    }

  test("a weaker constraint cannot supply a stronger requirement"):
    check(protocols + """
      fn strong(value: 'T): 'T where 'T: Render = echo value;;
      fn weak(value: 'U): 'U where 'U: Echo = strong value;;
    """).map { state =>
      val missing = missingRequirements(state)
      assertEquals(missing.size, 1)
      assertEquals(variableId(missing.head.tpe), function(state, "weak").typeParams.head.resolvedId)
      assertEquals(
        missing.head.protocol.resolvedId,
        function(state, "strong").constraints.head.constraints.head.resolvedId
      )
    }

  test("lexical function shadowing takes precedence over implied member discovery"):
    check(protocols + """
      fn use(value: 'T): 'T where 'T: Render =
        fn echo(other: 'T): 'T = other;;
        echo value;
      ;
    """).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val lambda = function(state, "use")
      val local = TermTraversal
        .collect(lambda.body) { case scope: Lambda =>
          scope.params.filter(_.name == "echo")
        }
        .flatten
      assertEquals(local.size, 1)
      val selected = references(lambda.body).filter(_.resolvedId == local.head.id)
      assertEquals(selected.size, 1)
      assertEquals(selected.head.protocolType, None)
      assert(!references(lambda.body).exists(_.protocolType.nonEmpty))
    }

  test("same-named members of a prerequisite and its dependant remain ambiguous"):
    check(protocols + """
      protocol FancyEcho 'T where 'T: Echo = fn echo(value: 'T): 'T; ;
      fn use(value: 'T): 'T where 'T: FancyEcho = echo value;;
    """).map { state =>
      val ambiguous = state.errors.collect {
        case SemanticError.ProtocolErrorFound(error: ProtocolError.AmbiguousSelection, _) => error
      }
      assertEquals(ambiguous.size, 1)
      val signatures = List("Echo", "FancyEcho")
        .flatMap(protocolSignature(state.module, _, "echo").id)
        .toSet
      assertEquals(ambiguous.head.ref.candidateIds.toSet, signatures)
      assertEquals(
        ambiguous.head.alternatives.flatMap(_.protocolName).toSet,
        Set("Echo", "FancyEcho")
      )
      val binders = ambiguous.head.alternatives.flatMap { alternative =>
        TypeTraversal.collect(alternative.functionType) { case variable: TypeVariable =>
          variable.resolvedId
        }
      }
      assertEquals(binders.toSet, Set(function(state, "use").typeParams.head.resolvedId))
    }

  test("specialization resolves inherited signatures to concrete prerequisite implementations"):
    check(protocols + """
      instance Render Int = ;
      instance Describe Int = fn describe(value) = value;; ;
      instance Display Int = ;
      instance Echo Int =
        fn echo(value) = value;;
        fn choose(label, value) = value;;
        op <+>(left, right) = left + right;;
      ;
      fn use(value: 'T): 'T where 'T: Render = choose "part" (echo (value <+> value));;
      let result = use 7;
    """).map { checked =>
      assertEquals(checked.errors.map(diagnosticMessage), Vector.empty)
      val template = binding(checked, "use")
      val original = function(checked, "use")
      val state    = FnMonomorphizer.rewriteModule(ResolvablesIndexer.rewriteModule(checked))
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertEquals(binding(state, "use"), template)
      val specialized = state.module.members.collect {
        case binding: Bnd
            if termLambda(binding.value).exists(_.meta.exists(_.templateId == template.id)) =>
          binding
      }
      assertEquals(specialized.size, 1)
      val lambda = termLambda(specialized.head.value).getOrElse(fail("Missing specialized lambda"))
      assertNotEquals(lambda.params.head.id, original.params.head.id)
      assertEquals(lambda.params.head.source, original.params.head.source)
      assert(
        TypeCompatibility.same(
          lambda.params.head.typeSpec.get,
          typeReference("Int", state.module.resolvables),
          state.module.resolvables
        )
      )
      val selected = references(lambda.body)
      assert(!selected.exists(_.protocolType.nonEmpty))

      List("echo", "choose", "<+>").foreach { member =>
        val signature      = protocolSignature(state.module, "Echo", member)
        val implementation = protocolImplementation(state, "Echo", "Int", member)
        val calls          = selected.filter(_.sourceId == signature.id)
        assertEquals(calls.size, 1)
        assertEquals(calls.head.resolvedId, implementation.id)
        val written = references(original.body).filter(_.resolvedId == signature.id)
        assertEquals(calls.head.source, written.head.source)
      }
    }
