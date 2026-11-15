package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class GenericCallableOwnershipTests extends BaseEffFunSuite:

  private val identityDefinition = "fn identity(~value: 'T): 'T = value;;"
  private val applyDefinition    = "fn apply(f: ('A -> 'B), ~value: 'A): 'B = f value;;"

  test("concrete generic function values preserve consuming parameters"):
    semStateNotFailed(identityDefinition + """
      fn main(): Int =
        let keep: String -> String = identity;
        let text = keep (int_to_str 42);
        text.length;
      ;
    """).map { state =>
      val flow = CallableValues.fromModule(state.module)
      val main = state.module.members
        .collectFirst { case b: Bnd if b.name == "main" => b }
        .getOrElse(fail("Missing main"))
      val refs = TermTraversal.collect(main.value) { case ref: Ref if ref.name == "keep" => ref }
      assert(refs.nonEmpty)
      assertEquals(flow.parameters(refs.head).map(_.consuming), List(true))
    }

  test("a generic higher-order call accepts a consuming concrete callback"):
    semNotFailed(applyDefinition + """
      fn length(~text: String): Int = text.length;;
      fn main(): Int = apply length (int_to_str 42);;
    """)

  test("a generic higher-order call rejects conflicting callback ownership"):
    semState(applyDefinition + """
      fn borrow(text: String): Int = text.length;;
      fn consume(~text: String): Int = text.length;;
      fn main(flag: Bool): Int =
        let read = if flag then borrow; else consume;;
        apply read (int_to_str 42);
      ;
    """).map { state =>
      assert(state.errors.exists {
        case SemanticError.InvalidExpression(_, OwnershipError.ParameterOwnershipMismatch, _) =>
          true
        case _ => false
      })
    }

  test("a transfer through generic identity rejects subsequent use"):
    semState(identityDefinition + """
      fn main(): Int =
        let text = int_to_str 42;
        let moved = identity text;
        text.length + moved.length;
      ;
    """).map { state =>
      assert(state.errors.exists {
        case _: SemanticError.UseAfterMove => true
        case _ => false
      })
    }

  test("generic outer functions preserve local recursive identities"):
    semNotFailed("""
      fn outer(value: 'T): Int =
        fn count(n: Int): Int = if n == 0 then 0; else count (n - 1); ;;
        count 3;
      ;
      fn main(): Int = outer 7 + outer true;;
    """)

  test("local generic functions retain enclosing substitutions and captures"):
    semNotFailed("""
      fn outer(value: 'T): 'T =
        fn local(other: 'U): 'T = value;;
        local true;
      ;
      fn main(): Int = outer 7;;
    """)

  test("expected function types instantiate partial generic calls"):
    semNotFailed("""
      fn first(left: 'A, right: 'B): 'A = left;;
      fn main(): Int =
        let choose: Bool -> Int = first 7;
        choose true;
      ;
    """)

  test("generic partial applications reject reuse after transferring a captured value"):
    semState("""
      fn first(~left: 'A, right: 'B): 'A = left;;
      fn main(): Int =
        let choose: Bool -> String = first (int_to_str 42);
        let one = choose true;
        let two = choose false;
        one.length + two.length;
      ;
    """).map { state =>
      assert(state.errors.exists {
        case _: SemanticError.UseAfterMove => true
        case _ => false
      })
    }

  test("local generic shadowing resolves distinct definitions"):
    semNotFailed("""
      fn keep(value: 'T): 'T = value;;
      fn main(): Int =
        let before = keep 7;
        fn keep(left: 'A, right: 'B): 'B = right;;
        keep true before;
      ;
    """)

  for optimization <- List(0, 3) do
    test(s"generic transfers and callbacks execute with sanitizers at O$optimization"):
      runSanitizedProgram(
        identityDefinition + applyDefinition + """
        fn length(~text: String): Int = text.length;;
        pub fn main(): Unit =
          let text = identity ("hello" ++ " world");
          println (to_str (apply length text));
          let keep: String -> String = identity;
          println (keep ("generic" ++ " value"));
        ;
      """,
        "generic_ownership",
        optimization,
        List("11", "generic value")
      )

    test(s"generic aggregates and escaping closures execute with sanitizers at O$optimization"):
      runSanitizedProgram(
        identityDefinition + """
        struct Box { text: String };
        fn capture(~value: 'T): Unit -> 'T =
          fn keep(~item: 'U): 'U = item;;
          ~{ keep value; };
        ;
        pub fn main(): Unit =
          let box = identity (Box ("hello" ++ " box"));
          println box.text;
          let text = capture ("captured" ++ " text");
          let number = capture 7;
          println (text ());
          println (to_str (number ()));
        ;
      """,
        "generic_captures",
        optimization,
        List("hello box", "captured text", "7")
      )

    test(s"generic partial calls preserve evaluation order at O$optimization"):
      runSanitizedProgram(
        """
        fn first(~left: 'A, right: 'B): 'A = left;;
        fn mark(value: Int): Int = println (to_str value); value;;
        pub fn main(): Unit =
          let choose: Int -> Int = first (mark 1);
          println (to_str (choose (mark 2)));
          let text: Bool -> String = first ("partial" ++ " value");
          println (text true);
        ;
      """,
        "generic_partial",
        optimization,
        List("1", "2", "1", "partial value")
      )

    test(s"recursive generic locals capture enclosing values at O$optimization"):
      runSanitizedProgram(
        """
        fn captured(value: 'T): 'T =
          fn local(other: 'U, n: Int): 'T =
            if n == 0 then value; else local other (n - 1); ;
          ;
          local true 2;
        ;
        pub fn main(): Unit =
          println (to_str (captured 7));
          println (to_str (captured 42));
          if captured true then println "true"; else println "false"; ;
        ;
      """,
        "generic_local_captures",
        optimization,
        List("7", "42", "true")
      )

    test(s"type prefixes and explicit constructor values execute at O$optimization"):
      runSanitizedProgram(
        identityDefinition + """
        struct Person { age: Int };
        pub fn main(): Unit =
          let keep_string = identity String;
          let keep_person = identity Person;
          let make_person = identity (Person: Int -> Person);
          println (keep_string ("hello" ++ " generic"));
          let person = keep_person (make_person 7);
          println (to_str person.age);
        ;
      """,
        "generic_type_prefix",
        optimization,
        List("hello generic", "7")
      )
