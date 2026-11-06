package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class LocalProtocolInferenceTests extends BaseEffFunSuite:

  test("a local binding selects the concrete member and function type from a later call"):
    checkSelection("let selected = identity; selected 42;", "Int")

  test("call arguments supply evidence without a declared enclosing return type"):
    checkSelectedSource(
      identityInstances + "fn example() = let selected = identity; selected 42;;",
      List("Int")
    )

  test("a typed local alias constrains the original protocol binding"):
    checkSelectedSource(
      identityInstances +
        "fn example() = let selected = identity; let alias: Int -> Int = selected; alias 42;;",
      List("Int")
    )

  test("parentheses preserve evidence for a local protocol binding"):
    checkSelection("let selected = ((identity)); (selected) true;", "Bool")

  test("repeated uses select one concrete member"):
    checkSelection("let selected = identity; let first = selected 1; selected 2;", "Int")

  test("a simple alias supplies evidence to its protocol initializer"):
    checkSelection("let selected = identity; let alias = selected; alias true;", "Bool")

  test("an alias chain supplies evidence without changing the selected binding"):
    checkSelection(
      "let selected = identity; let first = selected; let second = first; second 42;",
      "Int"
    )

  test("both conditional branches contribute compatible local evidence"):
    checkSelection(
      "let selected = identity; if true then selected 1; else selected 2; ;",
      "Int"
    )

  test("a known consumer supplies the complete callable type"):
    val source = identityInstances + """
      fn consume(f: Int -> Int): Int = f 42;;
      fn example(): Int = let selected = identity; consume selected;;
    """
    checkSelectedSource(source, List("Int"))

  List(
    "let first = consume selected; selected 42;",
    "let first = selected 42; consume selected;"
  ).foreach { uses =>
    test(s"compatible value and call evidence is independent of use order: $uses"):
      val source = identityInstances + s"""
        fn consume(f: Int -> Int): Int = f 42;;
        fn example(): Int = let selected = identity; $uses ;
      """
      checkSelectedSource(source, List("Int"))
  }

  test("an independently typed local argument supplies call evidence"):
    checkSelectedSource(
      identityInstances +
        "fn example() = let selected = identity; let value = 42; selected value;;",
      List("Int")
    )

  test("an immediately applied unary lambda supplies local call evidence"):
    checkSelectedSource(
      identityInstances +
        "fn example() = let selected = identity; { ignored: Int -> selected 42; } 0;;",
      List("Int")
    )

  test("separately bound references choose independent instances"):
    val source = identityInstances + """
      fn example(): Int =
        let first = identity;
        let second = identity;
        let ignored = second true;
        first 42;
      ;
    """
    checkSelectedSource(source, List("Int", "Bool"))

  test("a shadowing binding cannot constrain the outer binding"):
    val source = identityInstances + """
      fn example(): Int =
        let selected = identity;
        let ignored = if true then
          let selected = identity;
          selected true;
        else false;
        ;
        selected 42;
      ;
    """
    checkSelectedSource(source, List("Int", "Bool"))

  List(
    "let first = selected 1; let second = selected true;",
    "let first = selected true; let second = selected 1;",
    "let alias = selected; let first = selected 1; let second = alias true;",
    "let result = if true then selected 1; else selected true; ;"
  ).foreach { uses =>
    test(s"conflicting uses cannot choose different instances: $uses"):
      checkProtocolFailure(
        identityInstances + s"fn example(): Unit = let selected = identity; $uses ();;",
        {
          case _: ProtocolError.ConflictingUses => true
          case _ => false
        }
      )
  }

  test("an unused binding remains insufficient even with one registered instance"):
    checkProtocolFailure(
      """
        protocol Identity 'T = fn identity(value: 'T): 'T; ;
        instance Identity Int = fn identity(value) = value;; ;
        fn example(): Int = let selected = identity; 42;;
      """,
      insufficient
    )

  test("a nested function body supplies no evidence for an outer binding"):
    checkProtocolFailure(
      identityInstances + """
        fn example(): Int =
          let selected = identity;
          let nested = { selected 42; };
          nested ();
        ;
      """,
      insufficient
    )

  test("top-level uses supply no evidence for a global protocol binding"):
    checkProtocolFailure(
      identityInstances + "let selected = identity; let result = selected 42;",
      insufficient
    )

  List(
    ("a later argument", "let selected = choose \"prefix\"; selected 42;", "Int"),
    (
      "a simple alias",
      "let selected = choose \"prefix\"; let alias = selected; alias true;",
      "Bool"
    ),
    (
      "a typed alias",
      "let selected = choose \"prefix\"; let alias: Int -> Int = selected; alias 42;",
      "Int"
    ),
    (
      "a known consumer",
      "let selected = choose \"prefix\"; consume selected;",
      "Int"
    ),
    (
      "a grouped initializer",
      "let selected = ((choose \"prefix\")); selected 42;",
      "Int"
    )
  ).foreach { (description, body, expectedType) =>
    test(s"a partial initializer selects its remaining callable type from $description"):
      val source = chooseInstances + s"""
        fn consume(f: Int -> Int): Int = f 42;;
        fn example() = $body ;
      """
      checkSelectedSource(source, List(expectedType), "Choose", "choose")
  }

  test("later local evidence follows every supplied argument in a partial initializer"):
    val source = """
      protocol Choose 'T = fn choose(label: String, flag: Bool, value: 'T): 'T; ;
      instance Choose Int = fn choose(label, flag, value) = value;; ;
      instance Choose Bool = fn choose(label, flag, value) = value;; ;
      fn example() = let selected = choose "prefix" true; selected 42;;
    """
    checkSelectedSource(source, List("Int"), "Choose", "choose")

  test("later partial uses distinguish members with already concrete protocol parameters"):
    val source = """
      protocol First 'T = fn choose(seed: 'T, value: Int): Int; ;
      protocol Second 'T = fn choose(seed: 'T, value: Bool): Int; ;
      instance First Int = fn choose(seed, value) = value;; ;
      instance Second Int = fn choose(seed, value) = seed;; ;
      fn example() = let selected = choose 1; selected true;;
    """
    checkSelectedSource(source, List("Int"), "Second", "choose")

  List(
    "let first = selected 1; selected true;",
    "let first = selected true; selected 1;",
    "let alias = selected; let first = selected 1; alias true;"
  ).foreach { uses =>
    test(s"conflicting partial uses cannot choose different instances: $uses"):
      checkProtocolFailure(
        chooseInstances + s"fn example() = let selected = choose \"prefix\"; $uses ;",
        {
          case _: ProtocolError.ConflictingUses => true
          case _ => false
        }
      )
  }

  test("supplied arguments and later partial uses must agree on the instance"):
    val source = """
      protocol Same 'T = fn same(first: 'T, second: 'T): 'T; ;
      instance Same Int = fn same(first, second) = second;; ;
      instance Same Bool = fn same(first, second) = second;; ;
      fn example() = let selected = same 1; selected true;;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)

      val result = TypeChecker.rewriteModule(initial)
      result.visibleErrors match
        case Vector(SemanticError.TypeCheckingError(_: TypeError.TypeMismatch)) => ()
        case other => fail(s"Expected one type mismatch at the later call, got $other")
    }

  test("an unused partial initializer has no evidence for its protocol parameter"):
    checkProtocolFailure(
      chooseInstances + "fn example() = let selected = choose \"prefix\"; ();;",
      insufficient
    )

  test("a nested function body supplies no evidence for a partial initializer"):
    checkProtocolFailure(
      chooseInstances + """
        fn example() =
          let selected = choose "prefix";
          let nested = { selected 42; };
          nested ();
        ;
      """,
      insufficient
    )

  test("ambiguous partial alternatives satisfy both the prefix and remaining local uses"):
    val source = """
      protocol First 'T = fn choose(label: String, value: 'T): 'T; ;
      protocol Second 'T = fn choose(label: String, value: 'T): 'T; ;
      protocol OtherPrefix 'T = fn choose(label: Bool, value: 'T): 'T; ;
      instance First Int = fn choose(label, value) = value;; ;
      instance First Bool = fn choose(label, value) = value;; ;
      instance Second Int = fn choose(label, value) = value;; ;
      instance OtherPrefix Int = fn choose(label, value) = value;; ;
      fn example() = let selected = choose "prefix"; selected 42;;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val expected = List("First", "Second").map { protocol =>
        protocolImplementation(initial, protocol, "Int", "choose")
      }

      val result = TypeChecker.rewriteModule(initial)
      result.visibleErrors match
        case Vector(
              SemanticError.ProtocolErrorFound(
                ProtocolError.AmbiguousSelection(_, alternatives),
                _
              ),
              SemanticError.TypeCheckingError(use: TypeError.UnresolvableType)
            ) =>
          assertEquals(use.context, Some(UnresolvableTypeContext.NamedValue("selected")))
          assertEquals(alternatives.flatMap(_.protocolName), List("First", "Second"))
          alternatives.zip(expected).foreach { (alternative, implementation) =>
            val expectedType = implementation.typeSpec
              .getOrElse(fail("Expected concrete implementation type"))
            assert(
              TypeCompatibility.same(
                alternative.functionType,
                expectedType,
                result.module.resolvables
              )
            )
          }
        case other => fail(s"Expected one ambiguous-selection diagnostic, got $other")
    }

  test("a conditional initializer does not gather later local evidence"):
    checkProtocolFailure(
      identityInstances + """
        fn example(): Int =
          let selected = if true then identity; else identity; ;
          selected 42;
        ;
      """,
      insufficient
    )

  test("concrete local evidence reports a missing instance"):
    checkProtocolFailure(
      identityInstances + "fn example(): Float = let selected = identity; selected 1.0;;",
      {
        case ProtocolError.MissingInstance(_, protocol, _, _) => protocol.name == "Identity"
        case _ => false
      }
    )

  test("equally applicable protocol members remain ambiguous through a local binding"):
    checkProtocolFailure(
      """
        protocol First 'T = fn identity(value: 'T): 'T; ;
        protocol Second 'T = fn identity(value: 'T): 'T; ;
        instance First Int = fn identity(value) = value;; ;
        instance Second Int = fn identity(value) = value;; ;
        fn example(): Int = let selected = identity; selected 42;;
      """,
      {
        case ProtocolError.AmbiguousSelection(_, _) => true
        case _ => false
      }
    )

  test("a binding annotation cannot be overridden by later incompatible calls"):
    val source = identityInstances + """
      fn example(): Bool = let selected: Int -> Int = identity; selected true;;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)

      val result = TypeChecker.rewriteModule(initial)
      assert(result.visibleErrors.nonEmpty)
      assert(
        result.visibleErrors.exists {
          case SemanticError.TypeCheckingError(_: TypeError.TypeMismatch) => true
          case SemanticError.ProtocolErrorFound(ProtocolError.IncompatibleArguments(_), _) => true
          case SemanticError.ProtocolErrorFound(_: ProtocolError.ConflictingUses, _) => true
          case _ => false
        },
        result.visibleErrors
      )
    }

  List(0, 3).foreach { optimization =>
    test(s"inferred local members, aliases, and partials execute at O$optimization"):
      val source = """
        protocol Render 'T = fn render(label: String, value: 'T): Unit; ;
        instance Render Int =
          fn render(label, value) = print label; println (int_to_str value);;
        ;
        instance Render Bool = fn render(label, value) = println label;; ;
        fn partial_label(): String = println "supplied label"; "ordered partial: ";;
        pub fn main(): Unit =
          let render_int = render;
          render_int "function value: " 5;
          let alias = render_int;
          alias "alias: " 6;
          let render_again = render "partial: ";
          render_again 15;
          let ordered = render (partial_label ());
          println "partial created";
          ordered 16;
        ;
      """
      runSanitizedProgram(
        source,
        s"LocalProtocolInferenceO$optimization",
        optLevel = optimization,
        expectedOutput = List(
          "function value: 5",
          "alias: 6",
          "partial: 15",
          "supplied label",
          "partial created",
          "ordered partial: 16"
        )
      )
  }

  private val identityInstances = """
    protocol Identity 'T = fn identity(value: 'T): 'T; ;
    instance Identity Int = fn identity(value) = value;; ;
    instance Identity Bool = fn identity(value) = value;; ;
  """

  private val chooseInstances = """
    protocol Choose 'T = fn choose(label: String, value: 'T): 'T; ;
    instance Choose Int = fn choose(label, value) = value;; ;
    instance Choose Bool = fn choose(label, value) = value;; ;
  """

  private def checkSelection(body: String, expectedType: String) =
    checkSelectedSource(
      identityInstances + s"fn example(): $expectedType = $body ;",
      List(expectedType)
    )

  private def checkSelectedSource(
    source:        String,
    expectedTypes: List[String],
    protocolName:  String = "Identity",
    memberName:    String = "identity"
  ) =
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)

      val expected = expectedTypes.map { name =>
        protocolImplementation(initial, protocolName, name, memberName)
      }
      val result = TypeChecker.rewriteModule(initial)
      assertEquals(result.errors, Vector.empty)

      val example = result.module.members
        .collectFirst {
          case value: Bnd if value.name == "example" => value
        }
        .getOrElse(fail("Expected the fixture's example function"))
      val selected = TermTraversal
        .collect(example.value) { case ref: Ref if ref.name == memberName => ref }
        .sortBy(_.spanOpt.get.start.index)
      assertEquals(selected.map(_.resolvedId), expected.map(_.id))
      selected.zip(expected).foreach { (reference, implementation) =>
        assertEquals(reference.candidateIds, implementation.id.toList)
        val actualType = reference.typeSpec.getOrElse(fail("Expected concrete reference type"))
        val expectedType = implementation.typeSpec
          .getOrElse(fail("Expected concrete implementation type"))
        assert(TypeCompatibility.same(actualType, expectedType, result.module.resolvables))
      }
    }

  private def checkProtocolFailure(source: String, matches: ProtocolError => Boolean) =
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)

      val result = TypeChecker.rewriteModule(initial)
      val protocolErrors = result.visibleErrors.collect {
        case SemanticError.ProtocolErrorFound(error, _) => error
      }
      assert(protocolErrors.exists(matches), result.visibleErrors)
    }

  private def insufficient(error: ProtocolError): Boolean = error match
    case ProtocolError.InsufficientTypeInformation(_, _) => true
    case _ => false
