package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.termLambda
import mml.mmlclib.util.error.print.diagnosticMessage

class GenericTypeCheckingTests extends BaseEffFunSuite:

  private def check(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val prepared = prepareForTypeChecking(source, parsed)
      assertEquals(prepared.errors, Vector.empty)
      TypeChecker.rewriteModule(prepared)
    }

  private def binding(state: CompilerState, name: String): Bnd =
    state.module.members
      .collectFirst { case b: Bnd if b.name == name => b }
      .getOrElse(fail(s"Missing fixture binding $name"))

  private def function(state: CompilerState, name: String): Lambda =
    termLambda(binding(state, name).value).getOrElse(fail(s"Expected fixture function $name"))

  private def referencesTo(owner: Bnd, target: Bnd): List[Ref] =
    TermTraversal.collect(owner.value) {
      case ref: Ref if ref.resolvedId == target.id => ref
    }

  private def typeKey(tpe: Type, state: CompilerState): ProtocolTypeKey =
    ProtocolRegistry
      .typeKey(tpe, state.module.resolvables)
      .fold(error => fail(s"Expected concrete fixture type: $error"), identity)

  private def namedKey(name: String, state: CompilerState): ProtocolTypeKey =
    typeKey(typeReference(name, state.module.resolvables), state)

  private def assertResult(state: CompilerState, name: String, expected: String): Unit =
    val tpe = binding(state, name).typeSpec.getOrElse(fail(s"Missing result type for $name"))
    assertEquals(typeKey(tpe, state), namedKey(expected, state))

  private def failures(state: CompilerState): List[TypeError] =
    state.errors.collect { case SemanticError.TypeCheckingError(error) => error }.toList

  private def assertConflictingTypes(state: CompilerState, first: String, second: String): Unit =
    val conflicts = failures(state).collect {
      case TypeError.GenericInferenceFailed(
            _,
            GenericInference.Failure.ConflictingArgument(_, left, right),
            _
          ) =>
        Set(typeKey(left, state), typeKey(right, state))
    }
    assert(conflicts.contains(Set(namedKey(first, state), namedKey(second, state))))

  private def localParameter(lambda: Lambda, name: String): FnParam =
    TermTraversal
      .collect(lambda.body) { case scope: Lambda => scope.params }
      .flatten
      .find(_.name == name)
      .getOrElse(fail(s"Missing fixture local binding $name"))

  private def constrainedLocalFunction(owner: Lambda): Lambda =

    val matches = TermTraversal.collect(owner.body) {
      case lambda: Lambda if lambda.constraints.nonEmpty => lambda
    }
    assertEquals(matches.size, 1, "Expected one constrained local function")
    matches.head

  private val identityDefinition = "fn identity(value: 'T): 'T = value;;"
  private val echoProtocol       = "protocol Echo 'T = fn echo(value: 'T): 'T; ;"
  private val echoInt            = "instance Echo Int = fn echo(value) = value;; ;"
  private val personDefinition   = "struct Person { age: Int };"
  private val applyDefinition =
    "fn apply(f: ('A -> 'B), value: 'A): 'B = f value;;"
  private val echoGeneric =
    "fn relay(value: 'T): 'T where 'T: Echo = echo value;;"

  test("inferred and explicit calls select the same concrete generic identity"):
    check(identityDefinition + "let inferred = identity 7; let explicit = identity Int 7;")
      .map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        val target   = binding(state, "identity")
        val inferred = referencesTo(binding(state, "inferred"), target)
        val explicit = referencesTo(binding(state, "explicit"), target)
        assertEquals(inferred.size, 1)
        assertEquals(explicit.size, 1)
        assertEquals(inferred.head.typeArgs.map(typeKey(_, state)), List(namedKey("Int", state)))
        assertEquals(explicit.head.typeArgs.map(typeKey(_, state)), List(namedKey("Int", state)))
        assertResult(state, "inferred", "Int")
        assertResult(state, "explicit", "Int")
      }

  test("omitted generic return types are inferred independently of callers"):
    check("fn identity(value: 'T) = value;; let integer = identity 7; let flag = identity true;")
      .map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        val lambda = function(state, "identity")
        val scheme = binding(state, "identity").typeSpec
          .collect { case s: TypeScheme => s }
          .getOrElse(fail("Expected quantified generic signature"))
        val result = scheme.bodyType match
          case fn: TypeFn => fn.returnType
          case other => fail(s"Expected generic callable, got $other")
        assert(TypeCompatibility.same(result, lambda.typeParams.head, state.module.resolvables))
        assertResult(state, "integer", "Int")
        assertResult(state, "flag", "Bool")
      }

  test("unused generic bodies cannot specialize a rigid result variable"):
    check("fn invalid(value: 'T): 'T = 1;;").map { state =>
      assert(failures(state).exists(_.isInstanceOf[TypeError.TypeMismatch]))
    }

  test("repeated variables reject conflicting arguments at their use"):
    check("fn first(left: 'T, right: 'T): 'T = left;; let bad = first 1 true;")
      .map { state =>
        assert(failures(state).exists {
          case TypeError
                .GenericInferenceFailed(_, _: GenericInference.Failure.ConflictingArgument, _) =>
            true
          case _ => false
        })
      }

  test("an unconstrained generic function value requires type evidence"):
    check(identityDefinition + "let unknown = identity;").map { state =>
      assert(failures(state).exists {
        case TypeError.GenericInferenceFailed(_, _: GenericInference.Failure.MissingArguments, _) =>
          true
        case _ => false
      })
    }

  test("explicit type argument counts cover every signature variable"):
    check("fn first(left: 'T, right: 'U): 'T = left;; let bad = first Int 1 true;")
      .map { state =>
        assert(failures(state).exists {
          case TypeError
                .GenericInferenceFailed(_, GenericInference.Failure.TypeArgumentCount(2, 1), _) =>
            true
          case _ => false
        })
      }

  test("a declared protocol requirement checks without any concrete instances"):
    check(echoProtocol + echoGeneric).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val lambda    = function(state, "relay")
      val signature = protocolSignature(state.module, "Echo", "echo")
      val selections = TermTraversal.collect(lambda.body) {
        case ref: Ref if ref.resolvedId == signature.id => ref
      }
      assertEquals(selections.size, 1)
      assertEquals(selections.head.protocolType, lambda.params.head.typeSpec)
      assert(selections.head.typeSpec.nonEmpty)
    }

  test("concrete generic calls validate every declared protocol requirement"):
    check(echoProtocol + echoInt + echoGeneric + "let good = relay 1; let bad = relay true;")
      .map { state =>
        assertResult(state, "good", "Int")
        val missing = failures(state).collect {
          case TypeError.MissingGenericRequirement(_, protocol, tpe, _) => (protocol, tpe)
        }
        assertEquals(missing.size, 1)
        val declared = function(state, "relay").constraints.head.constraints.head
        assertEquals(missing.head._1.resolvedId, declared.resolvedId)
        assertEquals(typeKey(missing.head._2, state), namedKey("Bool", state))
      }

  test("concrete instances cannot justify undeclared operations in a generic body"):
    check(echoProtocol + echoInt + "fn invalid(value: 'T): 'T = echo value;;").map { state =>
      assert(state.errors.nonEmpty)
      val body = function(state, "invalid").body
      assert(
        TermTraversal.collect(body) { case ref: Ref if ref.protocolType.nonEmpty => ref }.isEmpty
      )
    }

  test("generic forwarding retains the caller's rigid type and declared requirement"):
    val wrapper = "fn forward(value: 'U): 'U where 'U: Echo = relay value;;"
    check(echoProtocol + echoGeneric + wrapper).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val wrapperBody = binding(state, "forward")
      val calls       = referencesTo(wrapperBody, binding(state, "relay"))
      assertEquals(calls.size, 1)
      val parameter = function(state, "forward").typeParams.head
      assertEquals(calls.head.typeArgs, List(parameter))
    }

  test("generic forwarding rejects a missing caller requirement"):
    val wrapper = "fn forward(value: 'U): 'U = relay value;;"
    check(echoProtocol + echoGeneric + wrapper).map { state =>
      assert(failures(state).exists(_.isInstanceOf[TypeError.MissingGenericRequirement]))
    }

  test("explicit abstract arguments retain the caller binder through generic forwarding"):
    val wrapper = "fn forward(value: 'U): 'U where 'U: Echo = relay 'U value;;"
    check(echoProtocol + echoGeneric + wrapper).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val calls = referencesTo(binding(state, "forward"), binding(state, "relay"))
      assertEquals(calls.size, 1)
      val parameter = function(state, "forward").typeParams.head
      assertEquals(
        calls.head.typeArgs.map {
          case variable: TypeVariable => variable.resolvedId
          case other => fail(s"Expected caller's type variable, got $other")
        },
        List(parameter.resolvedId)
      )
    }

  test("expected callback types instantiate a generic function value"):
    val source = identityDefinition + """
      fn apply(f: (Int -> Int), value: Int): Int = f value;;
      let result = apply identity 4;
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val refs = referencesTo(binding(state, "result"), binding(state, "identity"))
      assertEquals(refs.size, 1)
      assertEquals(refs.head.typeArgs.map(typeKey(_, state)), List(namedKey("Int", state)))
      assertResult(state, "result", "Int")
    }

  test("remaining callable types complete inference for partial generic calls"):
    val source = """
      fn first(left: 'T, right: 'U): 'T = left;;
      let partial: Bool -> Int = first 1;
      let result = partial true;
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val refs = referencesTo(binding(state, "partial"), binding(state, "first"))
      assertEquals(refs.size, 1)
      assertEquals(
        refs.head.typeArgs.map(typeKey(_, state)),
        List(namedKey("Int", state), namedKey("Bool", state))
      )
      assertResult(state, "result", "Int")
    }

  test("expected results determine nullary generic calls"):
    check("fn make(): 'T = ???;; let result: Int = make ();").map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Int")
      val refs = referencesTo(binding(state, "result"), binding(state, "make"))
      assertEquals(refs.head.typeArgs.map(typeKey(_, state)), List(namedKey("Int", state)))
    }

  test("generic higher-order calls infer callback results without an expected result"):
    val source = identityDefinition + """
      fn apply(f: ('A -> 'B), value: 'A): 'B = f value;;
      let result = apply identity 1;
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Int")
      val result       = binding(state, "result")
      val applyRefs    = referencesTo(result, binding(state, "apply"))
      val identityRefs = referencesTo(result, binding(state, "identity"))
      assertEquals(applyRefs.size, 1)
      assertEquals(identityRefs.size, 1)
      val integer = namedKey("Int", state)
      assertEquals(applyRefs.head.typeArgs.map(typeKey(_, state)), List(integer, integer))
      assertEquals(identityRefs.head.typeArgs.map(typeKey(_, state)), List(integer))
    }

  test("explicit function type arguments constrain nested generic function values"):
    check(identityDefinition + "let result = identity (Int -> Int) identity 1;").map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Int")
      val refs = referencesTo(binding(state, "result"), binding(state, "identity"))
      assertEquals(refs.size, 2)
      val integer       = namedKey("Int", state)
      val selectedTypes = refs.flatMap(_.typeArgs).map(typeKey(_, state)).toSet
      assertEquals(selectedTypes, Set(integer, ProtocolTypeKey.Function(List(integer), integer)))
    }

  test("local generic declarations keep independent concrete substitutions at every use"):
    val source = """
      fn main(): Int =
        fn identity(value: 'T): 'T = value;;
        let integer = identity 1;
        let flag = identity true;
        if flag then integer; else 0;;
      ;
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val body = function(state, "main").body
      val definitions = TermTraversal.collect(body) {
        case lambda: Lambda if lambda.typeParams.nonEmpty => lambda
      }
      assertEquals(definitions.size, 1)
      val calls = TermTraversal.collect(body) { case ref: Ref if ref.typeArgs.nonEmpty => ref }
      assertEquals(calls.size, 2)
      assertEquals(calls.map(_.resolvedId).distinct.size, 1)
      assert(calls.head.resolvedId.nonEmpty)
      assertEquals(
        calls.flatMap(_.typeArgs).map(typeKey(_, state)).toSet,
        Set(namedKey("Int", state), namedKey("Bool", state))
      )
    }

  test("local generic recursion preserves its own rigid substitution"):
    val source = """
      fn main(): Int =
        fn repeat(value: 'T, done: Bool): 'T =
          if done then value; else repeat value true;;
        ;
        repeat 1 false;
      ;
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val body = function(state, "main").body
      val definition = TermTraversal
        .collect(body) {
          case lambda: Lambda if lambda.typeParams.nonEmpty => lambda
        }
        .head
      val calls = TermTraversal.collect(body) { case ref: Ref if ref.typeArgs.nonEmpty => ref }
      assertEquals(calls.size, 2)
      assertEquals(calls.map(_.resolvedId).distinct.size, 1)
      val recursiveTypes = calls.flatMap(_.typeArgs).collect { case variable: TypeVariable =>
        variable.resolvedId
      }
      assertEquals(recursiveTypes, List(definition.typeParams.head.resolvedId))
      val concrete = calls.flatMap(_.typeArgs).filterNot(_.isInstanceOf[TypeVariable])
      assertEquals(concrete.map(typeKey(_, state)), List(namedKey("Int", state)))
    }

  test("ordered explicit arguments check multiple constraints and multiple variables"):
    val source = echoProtocol + echoInt + """
      protocol Check 'T = fn check(value: 'T): Bool; ;
      instance Check Int = fn check(value) = true;; ;
      fn choose(first: 'A, second: 'B): 'A where 'A: Echo & Check, 'B: Echo = echo first;;
      let good = choose Int Int 1 2;
      let bad = choose Int Bool 1 true;
    """
    check(source).map { state =>
      assertResult(state, "good", "Int")
      val choose = binding(state, "choose")
      val good   = referencesTo(binding(state, "good"), choose)
      assertEquals(good.size, 1)
      assertEquals(good.head.typeArgs.map(typeKey(_, state)), List.fill(2)(namedKey("Int", state)))
      val missing = failures(state).collect {
        case TypeError.MissingGenericRequirement(_, protocol, tpe, _) => (protocol, tpe)
      }
      assertEquals(missing.size, 1)
      val secondRequirement = function(state, "choose").constraints(1).constraints.head
      assertEquals(missing.head._1.resolvedId, secondRequirement.resolvedId)
      assertEquals(typeKey(missing.head._2, state), namedKey("Bool", state))
    }

  test("type arguments do not fall back to constructor values when value checking fails"):
    check(identityDefinition + personDefinition + "let result = identity Person 1;").map { state =>
      assertConflictingTypes(state, "Person", "Int")
    }

  test("a constructor name selects a type before checking its value arguments"):
    val source = identityDefinition +
      "struct Person { age: Int }; let result = identity Person (Person 1);"
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Person")
      val refs = referencesTo(binding(state, "result"), binding(state, "identity"))
      assertEquals(refs.size, 1)
      assertEquals(refs.head.typeArgs.map(typeKey(_, state)), List(namedKey("Person", state)))
    }

  List("Person", "(Person)", "((Person))", "String").foreach { argument =>
    val name = if argument.contains("Person") then "Person" else "String"
    test(s"type argument $argument selects a concrete identity function"):
      check(identityDefinition + personDefinition + s"let result = identity $argument;").map {
        state =>
          assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
          val tpe    = namedKey(name, state)
          val result = binding(state, "result")
          assertEquals(
            typeKey(result.typeSpec.getOrElse(fail("Missing function type")), state),
            ProtocolTypeKey.Function(List(tpe), tpe)
          )
          val calls = referencesTo(result, binding(state, "identity"))
          assertEquals(calls.size, 1)
          assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(tpe))
          assertEquals(calls.head.explicitTypeArgs.toList.flatten.map(typeKey(_, state)), List(tpe))
      }
  }

  test("an expected constructor function type cannot reinterpret an explicit type argument"):
    check(
      identityDefinition + personDefinition +
        "let result: Int -> Person = identity Person;"
    ).map { state =>
      assertConflictingTypes(state, "Person", "Int")
    }

  test("a value alias passes a constructor to a generic function"):
    check(
      identityDefinition + personDefinition +
        "let make_person = Person; let result = identity make_person 7;"
    ).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Person")
      val calls = referencesTo(binding(state, "result"), binding(state, "identity"))
      assertEquals(calls.size, 1)
      assertEquals(calls.head.explicitTypeArgs, None)
      assertEquals(
        calls.head.typeArgs.map(typeKey(_, state)),
        List(
          ProtocolTypeKey.Function(List(namedKey("Int", state)), namedKey("Person", state))
        )
      )
    }

  List("(Person: Bool)", "((Person: Bool))", "((Person): Bool)").foreach { argument =>
    test(s"generic constructor arguments validate the annotation in $argument"):
      check(identityDefinition + personDefinition + s"let bad = identity $argument;")
        .map { state =>
          val mismatches = failures(state).collect { case error: TypeError.TypeMismatch =>
            error
          }
          val constructorType = ProtocolTypeKey.Function(
            List(namedKey("Int", state)),
            namedKey("Person", state)
          )
          assert(mismatches.exists { error =>
            typeKey(error.expected, state) == namedKey("Bool", state) &&
            typeKey(error.actual, state) == constructorType
          })
        }
  }

  test("ordinary constructor arguments reject the same invalid annotation"):
    val source = personDefinition + """
      fn accept(value: (Int -> Person)): Int = 1;;
      let bad = accept (Person: Bool);
    """
    check(source).map { state =>
      val mismatches = failures(state).collect { case error: TypeError.TypeMismatch => error }
      assert(mismatches.exists { error =>
        typeKey(error.expected, state) == namedKey("Bool", state) &&
        typeKey(error.actual, state) == ProtocolTypeKey.Function(
          List(namedKey("Int", state)),
          namedKey("Person", state)
        )
      })
    }

  List("(Person: Int -> Person)", "((Person: Int -> Person))", "((Person): Int -> Person)")
    .foreach { argument =>
      test(s"a valid constructor annotation remains on the value in $argument"):
        check(identityDefinition + personDefinition + s"let result = identity $argument 7;")
          .map { state =>
            assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
            assertResult(state, "result", "Person")
            val result = binding(state, "result")
            val calls  = referencesTo(result, binding(state, "identity"))
            val constructorType = ProtocolTypeKey.Function(
              List(namedKey("Int", state)),
              namedKey("Person", state)
            )
            assertEquals(calls.size, 1)
            assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(constructorType))
            assertEquals(calls.head.explicitTypeArgs, None)
            val annotated = TermTraversal.collect(result.value) {
              case term if term.typeAsc.nonEmpty => term
            }
            assert(annotated.exists { term =>
              term.typeAsc.exists(typeKey(_, state) == constructorType) &&
              term.typeSpec.exists(typeKey(_, state) == constructorType)
            })
          }
    }

  private val localRelays = List(
    (
      "an inherited variable",
      "fn relay(other: 'T): 'T where 'T: Echo = echo other;;",
      "relay value",
      0
    ),
    (
      "an inherited variable and recursive calls",
      """
        fn relay(other: 'T, done: Bool): 'T where 'T: Echo =
          if done then echo other; else relay other true;;
        ;
      """,
      "relay value false",
      0
    ),
    (
      "an inherited variable and an owned variable",
      "fn relay(other: 'T, extra: 'U): 'T where 'T: Echo = echo other;;",
      "relay value true",
      1
    )
  )

  localRelays.foreach { case (description, definition, call, ownedVariables) =>
    test(s"local constraints on $description reject an unconstrained outer definition"):
      val source = echoProtocol + s"""
        fn outer(value: 'T): 'T =
          $definition
          $call;
        ;
      """
      check(source).map { state =>
        val outer = function(state, "outer")
        val relay = constrainedLocalFunction(outer)
        assertEquals(relay.typeParams.size, ownedVariables)
        assertEquals(relay.constraints.head.variable.resolvedId, outer.typeParams.head.resolvedId)
        val localId = localParameter(outer, "relay").id
        assert(localId.nonEmpty)
        val missing = failures(state).collect { case error: TypeError.MissingGenericRequirement =>
          error
        }
        assertEquals(missing.size, 1)
        assertEquals(missing.head.ref.resolvedId, localId)
        assertEquals(
          missing.head.protocol.resolvedId,
          relay.constraints.head.constraints.head.resolvedId
        )
        assert(
          TypeCompatibility.same(
            missing.head.tpe,
            outer.typeParams.head,
            state.module.resolvables
          )
        )
      }

    test(s"local constraints on $description accept the outer requirement and concrete instance"):
      val source = echoProtocol + echoInt + s"""
        fn outer(value: 'T): 'T where 'T: Echo =
          $definition
          $call;
        ;
        let result = outer 1;
      """
      check(source).map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        assertResult(state, "result", "Int")
        val outer = function(state, "outer")
        val relay = constrainedLocalFunction(outer)
        assertEquals(relay.typeParams.size, ownedVariables)
        val signature = protocolSignature(state.module, "Echo", "echo")
        val selections = TermTraversal.collect(relay.body) {
          case ref: Ref if ref.resolvedId == signature.id => ref
        }
        assertEquals(selections.size, 1)
        assertEquals(selections.head.protocolType, relay.params.head.typeSpec)
        val calls = referencesTo(binding(state, "result"), binding(state, "outer"))
        assertEquals(calls.size, 1)
        assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(namedKey("Int", state)))
      }
  }

  private val callbackCases = List(
    ("callback first", applyDefinition, "let result = apply { x -> x } 1;"),
    (
      "callback last",
      "fn apply(value: 'A, f: ('A -> 'B)): 'B = f value;;",
      "let result = apply 1 { x -> x };"
    ),
    ("annotated callback", applyDefinition, "let result = apply { x: Int -> x } 1;"),
    ("expected result", applyDefinition, "let result: Int = apply { x -> x } 1;"),
    ("explicit arguments", applyDefinition, "let result = apply Int Int { x -> x } 1;")
  )

  callbackCases.foreach { case (description, definition, use) =>
    test(s"generic callback inference types every parameter and result with $description"):
      check(definition + use).map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        assertResult(state, "result", "Int")
        val result  = binding(state, "result")
        val integer = namedKey("Int", state)
        val calls   = referencesTo(result, binding(state, "apply"))
        assertEquals(calls.size, 1)
        assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(integer, integer))
        val callbacks = TermTraversal.collect(result.value) { case lambda: Lambda => lambda }
        assertEquals(callbacks.size, 1)
        val callback = callbacks.head
        assertEquals(callback.params.flatMap(_.typeSpec).map(typeKey(_, state)), List(integer))
        assertEquals(callback.body.typeSpec.map(typeKey(_, state)), Some(integer))
        val parameterUses = TermTraversal.collect(callback.body) {
          case ref: Ref if ref.resolvedId == callback.params.head.id => ref
        }
        assertEquals(parameterUses.size, 1)
        assertEquals(parameterUses.head.typeSpec.map(typeKey(_, state)), Some(integer))
      }
  }

  test("a literal callback infers a result type distinct from its input type"):
    check(applyDefinition + "let result = apply { x -> true } 1;").map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Bool")
      val result  = binding(state, "result")
      val integer = namedKey("Int", state)
      val boolean = namedKey("Bool", state)
      val calls   = referencesTo(result, binding(state, "apply"))
      assertEquals(calls.size, 1)
      assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(integer, boolean))
      val callbacks = TermTraversal.collect(result.value) { case lambda: Lambda => lambda }
      assertEquals(callbacks.size, 1)
      assertEquals(callbacks.head.params.flatMap(_.typeSpec).map(typeKey(_, state)), List(integer))
      assertEquals(callbacks.head.body.typeSpec.map(typeKey(_, state)), Some(boolean))
      assertEquals(
        callbacks.head.typeSpec.map(typeKey(_, state)),
        Some(ProtocolTypeKey.Function(List(integer), boolean))
      )
    }

  test("an earlier callback receives type evidence from a later callback result"):
    val source = """
      fn chain(value: 'A, finish: ('B -> 'C), start: ('A -> 'B)): 'C =
        finish (start value);
      ;
      let result = chain 1 { flag -> flag } { number -> true };
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Bool")
      val result  = binding(state, "result")
      val integer = namedKey("Int", state)
      val boolean = namedKey("Bool", state)
      val calls   = referencesTo(result, binding(state, "chain"))
      assertEquals(calls.size, 1)
      assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(integer, boolean, boolean))
      val callbacks = TermTraversal.collect(result.value) { case lambda: Lambda => lambda }
      assertEquals(callbacks.size, 2)
      assertEquals(
        callbacks.flatMap(_.typeSpec).map(typeKey(_, state)),
        List(
          ProtocolTypeKey.Function(List(boolean), boolean),
          ProtocolTypeKey.Function(List(integer), boolean)
        )
      )
      assertEquals(
        callbacks.flatMap(_.body.typeSpec).map(typeKey(_, state)),
        List(boolean, boolean)
      )
      val finish = callbacks.head
      val parameterUses = TermTraversal.collect(finish.body) {
        case ref: Ref if ref.resolvedId == finish.params.head.id => ref
      }
      assertEquals(parameterUses.size, 1)
      assertEquals(parameterUses.head.typeSpec.map(typeKey(_, state)), Some(boolean))
    }

  test("a named generic callback retains its independent concrete substitution"):
    check(identityDefinition + applyDefinition + "let result = apply identity 1;").map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Int")
      val result  = binding(state, "result")
      val integer = namedKey("Int", state)
      val callers = referencesTo(result, binding(state, "apply"))
      val values  = referencesTo(result, binding(state, "identity"))
      assertEquals(callers.size, 1)
      assertEquals(values.size, 1)
      assertEquals(callers.head.typeArgs.map(typeKey(_, state)), List(integer, integer))
      assertEquals(values.head.typeArgs.map(typeKey(_, state)), List(integer))
    }

  test("an incompatible callback result remains a type failure"):
    check(applyDefinition + "let bad: Int = apply { x -> true } 1;").map { state =>
      assert(failures(state).exists {
        case _: TypeError.TypeMismatch => true
        case TypeError
              .GenericInferenceFailed(_, _: GenericInference.Failure.IncompatibleTypes, _) =>
          true
        case TypeError
              .GenericInferenceFailed(_, _: GenericInference.Failure.ConflictingArgument, _) =>
          true
        case _ => false
      })
    }

  test("a callback without argument or result evidence reports its missing type arguments"):
    check(applyDefinition + "let bad = apply { x -> x };").map { state =>
      val target   = binding(state, "apply").id
      val declared = function(state, "apply").typeParams.map(_.resolvedId)
      assert(target.nonEmpty)
      assertEquals(declared.size, 2)
      assert(declared.forall(_.nonEmpty))

      val callFailures = failures(state).collect {
        case TypeError.GenericInferenceFailed(ref, failure, _) if ref.resolvedId == target =>
          failure
      }

      callFailures match
        case List(GenericInference.Failure.MissingArguments(parameters)) =>
          assertEquals(parameters.map(_.resolvedId), declared)
        case other => fail(s"Expected missing type arguments for the resolved callee, got $other")
    }

  test("a conjunction reports Check when only its Echo requirement has an instance"):
    val source = echoProtocol + echoInt + """
      protocol Check 'T = fn check(value: 'T): Bool; ;
      fn both(value: 'T): 'T where 'T: Echo & Check = echo value;;
      let bad = both 1;
    """
    check(source).map { state =>
      val requirements = function(state, "both").constraints.head.constraints
      assertEquals(requirements.size, 2)
      val missing = failures(state).collect { case error: TypeError.MissingGenericRequirement =>
        error
      }
      assertEquals(missing.size, 1)
      assertEquals(missing.head.ref.resolvedId, binding(state, "both").id)
      assertEquals(missing.head.protocol.resolvedId, requirements(1).resolvedId)
      assertNotEquals(missing.head.protocol.resolvedId, requirements.head.resolvedId)
      assertEquals(typeKey(missing.head.tpe, state), namedKey("Int", state))
    }

  test("a conjunction accepts a concrete argument with both declared instances"):
    val source = echoProtocol + echoInt + """
      protocol Check 'T = fn check(value: 'T): Bool; ;
      instance Check Int = fn check(value) = true;; ;
      fn both(value: 'T): 'T where 'T: Echo & Check = echo value;;
      let result = both 1;
    """
    check(source).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assertResult(state, "result", "Int")
      val calls = referencesTo(binding(state, "result"), binding(state, "both"))
      assertEquals(calls.size, 1)
      assertEquals(calls.head.typeArgs.map(typeKey(_, state)), List(namedKey("Int", state)))
    }
