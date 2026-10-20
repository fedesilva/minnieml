package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class LambdaLiteralBorrowingTests extends BaseEffFunSuite:

  private def semChecked(source: String): IO[(Module, SemanticResult)] =
    for
      parsed <- parseNotFailed(source)
      result <- semState(source)
    yield (parsed, result)

  private def member(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected member $name"))

  private def originalRef(parsed: Module, owner: String, index: Int): Ref =
    assert(index >= 0, "Expected a source-reference location")
    val refs = TermTraversal.collect(member(parsed, owner).value) {
      case ref: Ref if ref.spanOpt.exists(_.start.index == index) => ref
    }
    assertEquals(refs.size, 1, s"Expected one source reference at $index")
    refs.head

  private def originalExpression(parsed: Module, owner: String, index: Int): SourceOrigin =
    val sources = TermTraversal
      .collect(member(parsed, owner).value) {
        case expr: Expr if expr.spanOpt.exists(_.start.index == index) => expr.source
      }
      .distinct
    assertEquals(sources.size, 1, s"Expected one source expression at $index")
    sources.head

  private def assertOnlyMove(
    parsed:  Module,
    result:  SemanticResult,
    owner:   String,
    name:    String,
    usedAt:  Int,
    movedAt: SourceOrigin
  ): Unit =
    val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
    assertEquals(errors.size, 1, result.errors.toString)
    assertEquals(result.errors.size, 1, result.errors.toString)
    assertEquals(errors.head.ref.resolvedId, Some(resolvedBindingId(result.module, owner, name)))
    assertEquals(errors.head.ref.source, originalRef(parsed, owner, usedAt).source)
    assertEquals(errors.head.movedAt, movedAt)

  private def stringCleanup(module: Module, owner: String): List[App] =
    val destructorId = TypeUtils
      .freeFnFor("String", module.resolvables)
      .flatMap(DestructionTargets.named(_, module.resolvables))
      .getOrElse(fail("Expected String destructor"))
    TermTraversal
      .collect(member(module, owner).value) { case app: App =>
        app.fn match
          case ref: Ref if ref.resolvedId.contains(destructorId) => List(app)
          case _ => Nil
      }
      .flatten

  private def moveLambda(module: Module, owner: String): Lambda =
    val lambdas = TermTraversal.collect(member(module, owner).value) {
      case lambda: Lambda if lambda.isMove && !lambda.meta.exists(_.isPartialApplication) =>
        lambda
    }
    assertEquals(lambdas.size, 1)
    lambdas.head

  List(
    "unary" -> "{ value: String -> value.length; } text",
    "multiple parameters" -> "{ value: String, n: Int -> value.length + n; } text 1"
  ).foreach { (form, invocation) =>
    test(s"$form literal borrows owned and borrowed arguments without moving them") {
      semNotFailed(s"""
        fn owned(): Int =
          let text = int_to_str 42;
          let first = $invocation;
          first + text.length;
        ;
        fn borrowed(text: String): Int =
          let first = $invocation;
          first + text.length;
        ;
      """).map { module =>
        val cleanup = stringCleanup(module, "owned")
        assertEquals(cleanup.size, 1)
        val destroyedIds = TermTraversal
          .collect(cleanup.head.arg) { case ref: Ref =>
            ref.resolvedId
          }
          .flatten
        assertEquals(destroyedIds, List(resolvedBindingId(module, "owned", "text")))
        assertEquals(stringCleanup(module, "borrowed"), Nil)
      }
    }
  }

  test("named borrowing calls retain the same owned and borrowed argument contracts") {
    semNotFailed("""
      fn read(value: String, n: Int): Int = value.length + n;;
      fn owned(): Int =
        let text = int_to_str 42;
        let first = read text 1;
        first + text.length;
      ;
      fn borrowed(text: String): Int =
        let first = read text 1;
        first + text.length;
      ;
    """).map { module =>
      assertEquals(stringCleanup(module, "read"), Nil)
      assertEquals(stringCleanup(module, "owned").size, 1)
      assertEquals(stringCleanup(module, "borrowed"), Nil)
    }
  }

  List(
    "unary" -> "{ ~value: String -> value.length; } text",
    "multiple parameters" -> "{ ~value: String, n: Int -> value.length + n; } text 1"
  ).foreach { (form, invocation) =>
    test(s"$form consuming literal moves the original argument at its source reference") {
      val source = s"""
        fn length(value: String): Int = value.length;;
        fn example(): Int =
          let text = int_to_str 42;
          let first = $invocation;
          first + length text;
        ;
      """
      semChecked(source).map { (parsed, result) =>
        assertOnlyMove(
          parsed,
          result,
          "example",
          "text",
          source.lastIndexOf("text;"),
          originalRef(parsed, "example", source.indexOf("} text") + 2).source
        )
      }
    }
  }

  test("ordinary local binding still transfers ownership") {
    val source = """
      fn length(value: String): Int = value.length;;
      fn example(): Int =
        let text = int_to_str 42;
        let alias = text;
        alias.length + length text;
      ;
    """
    semChecked(source).map { (parsed, result) =>
      assertOnlyMove(
        parsed,
        result,
        "example",
        "text",
        source.lastIndexOf("text;"),
        originalRef(parsed, "example", source.indexOf("= text") + 2).source
      )
    }
  }

  List("owned reference" -> "text", "allocating temporary" -> "(int_to_str 7)").foreach {
    (argumentKind, argument) =>
      List("literal", "named").foreach { form =>
        test(s"$form borrowed parameter cannot forward an $argumentKind to a consuming call") {
          val declaration =
            if form == "named" then "fn borrow(value: String): Int = take value;;" else ""
          val callee = if form == "named" then "borrow" else "{ value: String -> take value; }"
          val source = s"""
            fn take(~payload: String): Int = payload.length;;
            $declaration
            fn example(): Int =
              let text = int_to_str 42;
              $callee $argument;
            ;
          """
          semChecked(source).map { (parsed, result) =>
            val errors = result.errors.collect {
              case error: SemanticError.BorrowedValuePassedToConsumingParam => error
            }
            val sinks = result.errors.collect { case error: SemanticError.InvalidExpression =>
              error
            }
            val expectedSinkCount = if form == "literal" && argument == "text" then 1 else 0
            assertEquals(errors.size, 1, result.errors.toString)
            assertEquals(sinks.size, expectedSinkCount, result.errors.toString)
            assertEquals(result.errors.size, errors.size + sinks.size, result.errors.toString)
            val owner = if form == "named" then "borrow" else "example"
            assertEquals(
              errors.head.ref.resolvedId,
              Some(resolvedBindingId(result.module, owner, "value"))
            )
            assertEquals(
              errors.head.param.id,
              Some(resolvedBindingId(result.module, "take", "payload"))
            )
            val argumentSource = originalRef(parsed, owner, source.indexOf("take value") + 5)
            assertEquals(errors.head.ref.source, argumentSource.source)
            sinks.foreach { error =>
              assertEquals(
                error.msg,
                "An ownership sink cannot receive a value with borrowed ownership"
              )
              assertEquals(error.expr.source, argumentSource.source)
              val ids = TermTraversal
                .collect(error.expr) { case ref: Ref =>
                  ref.resolvedId
                }
                .flatten
              assertEquals(ids, List(resolvedBindingId(result.module, owner, "value")))
            }
          }
        }
      }
  }

  List("literal", "named").foreach { form =>
    test(s"$form borrowed parameter cannot escape through a heap return") {
      val declaration =
        if form == "named" then "fn borrow(value: String): String = value;;" else ""
      val callee = if form == "named" then "borrow" else "{ value: String -> value; }"
      val source = s"""
        $declaration
        fn example(text: String): String = $callee text;;
      """
      semChecked(source).map { (parsed, result) =>
        val errors = result.errors.collect { case error: SemanticError.BorrowEscapeViaReturn =>
          error
        }
        assertEquals(errors.size, 1, result.errors.toString)
        assertEquals(result.errors.size, 1, result.errors.toString)
        val owner = if form == "named" then "borrow" else "example"
        assertEquals(
          errors.head.ref.resolvedId,
          Some(resolvedBindingId(result.module, owner, "value"))
        )
        val returnedAt = source.indexOf("value;")
        assertEquals(errors.head.ref.source, originalRef(parsed, owner, returnedAt).source)
      }
    }
  }

  List(
    ("allocated", "(int_to_str 42)", 1),
    ("static", "\"hello\"", 0),
    (
      "mixed allocated, static, and borrowed",
      "(if allocated then int_to_str 42; elif literal then \"hello\"; else text;)",
      1
    )
  ).foreach { (kind, argument, cleanupCount) =>
    test(s"a literal borrows its $kind temporary and retains only the argument's cleanup") {
      semNotFailed(s"""
        fn example(text: String, allocated: Bool, literal: Bool): Int =
          let first = { value: String -> value.length; } $argument;
          first + text.length;
        ;
      """).map { module =>
        val cleanup = stringCleanup(module, "example")
        assertEquals(cleanup.size, cleanupCount)
        val destroyedIds = cleanup.flatMap { call =>
          TermTraversal.collect(call.arg) { case ref: Ref => ref.resolvedId }.flatten
        }
        assert(!destroyedIds.contains(resolvedBindingId(module, "example", "text")))
      }
    }
  }

  test("a move closure remains reusable when a direct literal only borrows its capture") {
    semNotFailed("""
      fn example(): Int =
        let text = int_to_str 42;
        let outer = ~{ { value: String, n: Int -> value.length + n; } text 1; };
        let first = outer ();
        outer () + first;
      ;
    """).map { module =>
      val textId = resolvedBindingId(module, "example", "text")
      val outer  = moveLambda(module, "example")
      assert(!outer.meta.exists(_.transferredCaptures.contains(textId)))
      assert(outer.captures.exists(_.ref.resolvedId.contains(textId)))
    }
  }

  test("an immediate body that consumes an outer capture still makes its move closure call-once") {
    val source = """
      fn take(~payload: String): Int = payload.length;;
      fn example(): Int =
        let text = int_to_str 42;
        let outer = ~{ { n: Int -> take text + n; } 1; };
        let first = outer ();
        outer () + first;
      ;
    """
    semChecked(source).map { (parsed, result) =>
      assertOnlyMove(
        parsed,
        result,
        "example",
        "outer",
        source.lastIndexOf("outer ()"),
        originalExpression(parsed, "example", source.indexOf("outer ()"))
      )
      val textId = resolvedBindingId(result.module, "example", "text")
      val outer  = moveLambda(result.module, "example")
      assertEquals(outer.meta.map(_.transferredCaptures), Some(Set(textId)))
    }
  }

  test("a consuming partial prefix still moves the supplied source binding") {
    val source = """
      fn length(value: String): Int = value.length;;
      fn example(): Int =
        let text = int_to_str 42;
        let partial = { ~value: String, n: Int -> value.length + n; } text;
        let first = partial 1;
        first + length text;
      ;
    """
    semChecked(source).map { (parsed, result) =>
      assertOnlyMove(
        parsed,
        result,
        "example",
        "text",
        source.lastIndexOf("text;"),
        originalRef(parsed, "example", source.indexOf("} text") + 2).source
      )
    }
  }

  test("an owned result moved inside an argument can be borrowed without reusing its source") {
    semNotFailed("""
      fn example(): Int =
        let text = int_to_str 42;
        { value: String -> value.length; } (let moved = text; moved);
      ;
    """).map { module =>
      val cleanup = stringCleanup(module, "example")
      assertEquals(cleanup.size, 1)
      val destroyedIds = TermTraversal
        .collect(cleanup.head.arg) { case ref: Ref => ref.resolvedId }
        .flatten
      assertEquals(destroyedIds, List(resolvedBindingId(module, "example", "value")))
    }
  }

  test("a later consuming argument invalidates an earlier borrow even when the body ignores it") {
    val source = """
      fn example(): Int =
        let text = int_to_str 42;
        { borrowed: String, ~taken: String -> taken.length; } text text;
      ;
    """
    semChecked(source).map { (parsed, result) =>
      assertOnlyMove(
        parsed,
        result,
        "example",
        "text",
        source.indexOf("text text"),
        originalRef(parsed, "example", source.lastIndexOf("text;")).source
      )
    }
  }
