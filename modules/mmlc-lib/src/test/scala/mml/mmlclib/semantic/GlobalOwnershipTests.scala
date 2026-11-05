package mml.mmlclib.semantic

import mml.mmlclib.test.BaseEffFunSuite

class GlobalOwnershipTests extends BaseEffFunSuite:

  private val prelude = """
    struct Box { text: String };
    struct Outer { box: Box };
    let global = "global";
    let aggregate = Outer (Box "field");
    fn fixed(): String = global;;
    fn field(): String = aggregate.box.text;;
    fn mixed(flag: Bool): String =
      if flag then aggregate.box.text; else "literal";;
    ;
    fn take(~text: String): Int = text.length;;
    fn staged(~text: String, n: Int): Int = text.length + n;;
  """

  private val sources = List(
    "direct" -> "global",
    "aliases" -> "let first = global; let second = first; second",
    "field" -> "aggregate.box.text",
    "field alias" -> "let box = aggregate.box; box.text",
    "helper" -> "fixed ()",
    "field helper" -> "field ()",
    "mixed static helper" -> "mixed flag",
    "mixed owned" -> "if flag then concat \"a\" \"b\"; else global;",
    "mixed literal" -> "if flag then \"literal\"; else global;"
  )

  private val sinks = List(
    "argument" -> "take value",
    "field" -> "let box = Box value; box.text.length",
    "move capture" -> "let f = ~{ n: Int -> value.length + n; }; f 1",
    "partial application" -> "let f = staged value; f 1",
    "owning return" -> "if flag then concat \"x\" \"y\"; else value;"
  )

  // NOTE:QA: I hate this. to read this you need to assemble the fixtures in your head.
  sources.foreach { (sourceName, value) =>
    sinks.foreach { (sinkName, body) =>
      val returnType = if sinkName == "owning return" then "String" else "Int"
      test(s"$sourceName requires an explicit copy at $sinkName") {
        semState(prelude + s"fn check(flag: Bool): $returnType = let value = ($value); $body;;")
          .map { state =>
            assert(
              state.errors.exists {
                case error: SemanticError.InvalidExpression =>
                  error.reason == OwnershipError.GlobalTransfer
                case _ => false
              },
              state.errors
            )
          }
      }
      test(s"an explicit $sourceName copy can enter $sinkName") {
        semNotFailed(
          prelude + s"fn check(flag: Bool): $returnType = let value = ^($value); $body;;"
        ).void
      }
    }
    test(s"$sourceName remains available for borrowing") {
      semNotFailed(
        prelude + s"fn check(flag: Bool): Int = let value = ($value); value.length;;"
      ).void
    }
  }

  test("global scalar copies and direct closure references remain valid") {
    semNotFailed(prelude + """
      let count = 7;
      fn check(): Int =
        let f = ~{ n: Int -> global.length + n; };
        f count;
      ;
    """).void
  }

  test("top-level owning initializers also require explicit copies") {
    semState(prelude + "let rejected = Box global;").map { state =>
      assert(
        state.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.reason == OwnershipError.GlobalTransfer
          case _ => false
        },
        state.errors
      )
    }
  }

  test("a global closure environment cannot become an owned payload") {
    semState("""
      fn make(): Int -> Int =
        let text = concat "a" "b";
        ~{ n: Int -> text.length + n; };
      ;
      let global = make ();
      fn take(~f: Int -> Int): Int = f 1;;
      fn check(): Int = take global;;
    """).map { state =>
      assert(
        state.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.reason == OwnershipError.GlobalTransfer
          case _ => false
        },
        state.errors
      )
    }
  }

  private val returnForms: List[(String, String => String)] = List(
    "direct" -> (value => value),
    "aliased" -> (value => s"let result = ($value); result"),
    "alias chain" -> (value => s"let first = ($value); let second = first; second")
  )

  returnForms.foreach { (form, body) =>
    test(s"$form global-field and literal alternatives can return without ownership") {
      val result = body("if flag then aggregate.box.text; else \"literal\";")
      semNotFailed(prelude + s"fn accepted(flag: Bool): String = $result;;").void
    }

    test(s"$form global-field alternative does not permit a borrowed parameter to escape") {
      val result = body("if flag then aggregate.box.text; else borrowed;")
      semState(prelude + s"""
        fn rejected(flag: Bool, borrowed: String): String = $result;;
      """).map { state =>
        val borrowedId = resolvedBindingId(state.module, "rejected", "borrowed")
        assert(
          state.errors.exists {
            case SemanticError.BorrowEscapeViaReturn(ref, _) => ref.resolvedId.contains(borrowedId)
            case _ => false
          },
          state.errors
        )
      }
    }

    test(s"$form immediate parameter retains its borrow contract for a static argument") {
      val result = body("if flag then aggregate.box.text; else borrowed;")
      semState(prelude + s"""
        fn rejected(flag: Bool): String =
          { borrowed: String -> $result; } "literal";
        ;
      """).map { state =>
        val borrowedId = resolvedBindingId(state.module, "rejected", "borrowed")
        assert(
          state.errors.exists {
            case SemanticError.BorrowEscapeViaReturn(ref, _) => ref.resolvedId.contains(borrowedId)
            case _ => false
          },
          state.errors
        )
      }
    }
  }
