package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.{callRootReference, termApplication}

class ProtocolMemberCallTests extends BaseEffFunSuite:

  private val apply = """
    protocol Apply 'T =
      fn apply_to(value: 'T, f: 'T -> 'U): 'U;
      op ==>(value: 'T, f: 'T -> 'U): 'U 40 left;
    ;
    instance Apply Int =
      fn apply_to(value, f) = f value;;
      op ==>(value, f) = f value;;
    ;
    fn positive(value: Int): Bool = value > 0;;
  """

  List("apply_to", "(==>)").foreach { member =>
    test(s"$member infers a single specialization from the later uses of a partial"):
      semNotFailed(apply + s"""
        fn later(): Bool =
          let pending = $member 3;
          pending positive;
        ;
      """)

    test(s"$member accepts an expected callable type and a grouped explicit function type"):
      semNotFailed(apply + s"""
        fn identity(~x: 'T): 'T = x;;
        fn inferred(): Bool =
          let chosen: Int -> (Int -> Bool) -> Bool = $member;
          chosen 7 positive;
        ;
        fn returned(): Int -> Int =
          $member (Int -> Int) 1 { n -> identity Int; };
        ;
      """)

    test(
      s"$member forwards an explicit rigid result type and specializes the chosen implementation"
    ):
      semNotFailed(apply + s"""
        fn forward(x: 'K, f: 'K -> 'R): 'R where 'K: Apply = $member 'R x f;;
        fn use(): Bool = forward 1 positive;;
      """)

    test(s"$member obtains an unannotated callback's input from its first argument"):
      semNotFailed(apply + s"fn use(): Bool = $member 3 { x -> positive x; };;")

    test(s"$member rejects inconsistent later uses of the same stored callable"):
      semFailed(apply + s"""
        fn text(x: Int): String = "int";;
        fn bad(): String =
          let pending = $member 3;
          let flag = pending positive;
          pending text;
        ;
      """)
  }

  test("a member used as a callback is selected from the receiving function's parameter type"):
    semNotFailed(apply + """
      fn consume(f: Int -> (Int -> Bool) -> Bool): Bool = f 7 positive;;
      let by_name = consume apply_to;
      let by_operator = consume (==>);
    """)

  test("argument evidence in a member call determines an enclosing lambda parameter"):
    semNotFailed(apply + """
      fn use(): Bool =
        let check = { x -> x ==> positive; };
        check 7;
      ;
    """)

  private val produce = """
    protocol Make 'T = fn make(): 'T; ;
    instance Make Int = fn make() = 42;; ;
    protocol Produce 'K = fn produce(key: 'K): 'R where 'R: Make; ;
    instance Produce Bool = fn produce(key) = make ();; ;
  """

  test("expected result or explicit arguments select a result-only member variable"):
    semNotFailed(produce + "let inferred: Int = produce true; let explicit = produce Int true;")

  test("having a single instance does not infer a missing result-only argument"):
    semState(produce + "let unknown = produce true;").map { state =>
      val missing = state.errors.collect {
        case SemanticError.TypeCheckingError(
              TypeError.GenericInferenceFailed(_, GenericInference.Failure.MissingArguments(vs), _)
            ) =>
          vs.map(_.name)
      }
      assertEquals(missing, List(List("'R")))
    }

  private val ordering = """
    protocol Reorder 'K =
      fn reorder(key: 'K, x: 'B, y: 'A, again: 'B): 'A;
    ;
    instance Reorder Int = fn reorder(key, x, y, again) = y;; ;
  """

  test("explicit arguments bind all local variables in signature order"):
    semNotFailed(ordering + "fn ok(): Bool = reorder String Bool 1 \"a\" true \"b\";;")

  test("the protocol parameter is inferred independently of explicit member arguments"):
    semState(ordering + "let bad = reorder Int String Bool 1 \"a\" true \"b\";").map { state =>
      val counts = state.errors.collect {
        case SemanticError.TypeCheckingError(
              TypeError.GenericInferenceFailed(
                _,
                GenericInference.Failure.TypeArgumentCount(expected, actual),
                _
              )
            ) =>
          expected -> actual
      }
      assertEquals(counts, List(2 -> 3))
    }

  test("repeated member-variable occurrences must agree"):
    semState(ordering + "let bad = reorder 1 true 7 9;").map { state =>
      val conflicts = state.errors.collect {
        case SemanticError.TypeCheckingError(
              TypeError.GenericInferenceFailed(
                _,
                GenericInference.Failure.ConflictingArgument(v, _, _),
                _
              )
            ) =>
          v.name
      }
      assertEquals(conflicts, List("'B"))
    }

  private val evidence = """
    protocol Printable 'T = ;
    protocol Tagged 'K = fn tag(x: 'K, y: 'U): Bool where 'U: Printable; ;
    instance Tagged Int = fn tag(x, y) = true;; ;
  """

  List("let bad = tag 7 true;", "fn bad(x: 'K, y: 'U): Bool where 'K: Tagged = tag x y;;")
    .foreach { body =>
      test(s"missing member constraint is diagnosed at its call in $body"):
        semState(evidence + body).map { state =>
          val missing = state.errors.collect {
            case SemanticError.TypeCheckingError(
                  TypeError.MissingGenericRequirement(ref, protocol, _, _)
                ) =>
              (ref.name, protocol.nameNode.value)
          }
          assertEquals(missing, List("tag" -> "Printable"))
        }
    }

  test("a lexical binding shadows a generic protocol member"):
    semNotFailed(apply + """
      fn local(apply_to: Bool -> Bool): Bool = apply_to true;;
    """)

  test("generic function arguments can be passed to generic and monomorphic members"):
    semNotFailed(apply + """
      protocol ApplySame 'T = fn apply_same(x: 'T, f: 'T -> 'T): 'T; ;
      instance ApplySame Int = fn apply_same(x, f) = f x;; ;
      fn identity(~x: 'A): 'A = x;;
      let generic_result: Int = 7 ==> identity;
      let fixed_result = apply_same 7 identity;
    """)

  test("an infix operator receives a constructor as its right operand"):
    semNotFailed(apply + """
      struct Record { number: Int };
      let made: Record = 7 ==> Record;
    """)

  test("explicit member arguments exclude an equally shaped free monomorphic operator"):
    semStateNotFailed(apply + """
      op ==>(x: Int, f: Int -> Bool): Bool 40 left = false;;
      let flag = (==>) Bool 7 positive;
    """).map { state =>
      val member = protocolImplementation(state, "Apply", "Int", "==>")
      val roots = state.module.members
        .collect { case b: Bnd if b.name == "flag" => b }
        .flatMap(b => termApplication(b.value).flatMap(callRootReference))
        .map(ref => ref.sourceId.orElse(ref.resolvedId))
      assertEquals(roots, List(member.id))
    }

  private val constructorOverloads = """
    struct Box { value: Int };
    protocol Select 'T = fn make_with(f: 'T -> 'R, x: 'T): 'R; ;
    instance Select Int = fn make_with(f, x) = f x;; ;
    protocol Second 'T = fn make_with(k: 'T, v: 'A, w: 'B): 'A; ;
    instance Second Bool = fn make_with(k, v, w) = v;; ;
  """

  test("each overloaded member reads its own explicit types before constructor operands"):
    semNotFailed(constructorOverloads + """
      let made: Box = make_with Box Box 42;
      let other: Box = make_with Box Int true (Box 4) 3;
      fn forward(x: 'A): 'A = make_with 'A Int true x 3;;
    """)

  test("an overloaded explicit prefix preserves operand positions for lambda inference"):
    val source = """
      struct Box { value: Int };
      protocol Select 'T = fn make_with(f: 'T -> 'R, x: 'T, flag: Bool): 'R; ;
      instance Select Int = fn make_with(f, x, flag) = f x;; ;
      protocol Second 'T = fn make_with(k: 'T, v: 'A, w: 'B): 'A; ;
      instance Second Bool = fn make_with(k, v, w) = v;; ;
      fn example(): Box = let make = { x -> make_with Box Box x false; }; make 42;;
    """
    semNotFailed(source)

  test("a grouped operator keeps the constructor after its own explicit type prefix"):
    val source = """
      struct Box { value: Int };
      protocol First 'T = op ==>(f: 'T -> 'R, x: 'T): 'R 40 left; ;
      instance First Int = op ==>(f, x) = f x;; ;
      protocol Second 'T = op ==>(key: 'T, v: 'A): 'B 40 left; ;
      instance Second Bool = op ==>(key, v) = ???;; ;
      let made: Box = (==>) Box Box 42;
    """
    semNotFailed(source)

  test("different explicit prefix lengths may both select applicable implementations"):
    val source = """
      struct Box { value: Int };
      protocol First 'T = fn choose(f: Int -> 'R, key: 'T): 'R; ;
      instance First Int = fn choose(f, key) = f key;; ;
      protocol Second 'T = fn choose(key: 'T): 'A -> 'B; ;
      instance Second Int = fn choose(key) = ???;; ;
      let selected = choose Box Box 1;
    """
    semVisibleState(source).map { state =>
      val index = state.module.resolvables
      def key(tpe: Type) =
        ProtocolRegistry.typeKey(tpe, index).fold(e => fail(e.toString), identity)
      val int = key(typeReference("Int", index))
      val box = key(typeReference("Box", index))
      val choices = state.errors.collect {
        case SemanticError.ProtocolErrorFound(
              ProtocolError.AmbiguousSelection(_, alternatives),
              _
            ) =>
          alternatives.map(a => (a.protocolName, a.name, key(a.functionType)))
      }
      val first = ProtocolTypeKey.Function(List(ProtocolTypeKey.Function(List(int), box), int), box)
      val second = ProtocolTypeKey.Function(List(int), ProtocolTypeKey.Function(List(box), box))
      assertEquals(choices.map(_.size).toList, List(2))
      assertEquals(
        choices.flatten.toSet,
        Set((Option("First"), "choose", first), (Option("Second"), "choose", second))
      )
    }

  for member <- List("apply_to", "(==>)") do
    test(s"a stored $member infers its result from a later generic callback"):
      semNotFailed(apply + s"""
        fn identity(~x: 'A): 'A = x;;
        fn run() = let pending = $member 1; pending identity;;
      """)

  test("two matching generic protocol members remain ambiguous"):
    semState(apply + """
      protocol Another 'K = fn apply_to(v: 'K, f: 'K -> 'U): 'U; ;
      instance Another Int = fn apply_to(v, f) = f v;; ;
      let ambiguous = apply_to 1 positive;
    """).map { state =>
      val ambiguous = state.errors.collect {
        case SemanticError.ProtocolErrorFound(
              ProtocolError.AmbiguousSelection(_, alternatives),
              _
            ) =>
          alternatives.flatMap(_.protocolName).toSet
      }
      assertEquals(ambiguous, List(Set("Apply", "Another")))
    }
