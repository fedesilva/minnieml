package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class CallStageOwnershipTests extends BaseEffFunSuite:

  private val consuming = """
    fn take(~payload: String, n: Int): Int = payload.length + n;;
  """

  private def semChecked(source: String): IO[SemanticResult] =
    parseNotFailed(source).flatMap(_ => semState(source))

  private def member(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected member $name"))

  private def assertOnlyMove(
    source: String,
    result: SemanticResult,
    owner:  String,
    name:   String,
    use:    String
  ): Unit =
    val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
    assertEquals(errors.size, 1, result.errors.toString)
    assertEquals(result.errors.size, 1, result.errors.toString)
    assertEquals(errors.head.ref.resolvedId, Some(resolvedBindingId(result.module, owner, name)))
    val expected = source.indexOf(use)
    assert(expected >= 0, s"Missing use marker: $use")
    assertEquals(errors.head.ref.spanOpt.map(_.start.index), Some(expected))
    assert(errors.head.movedAt.spanOpt.exists(_.start.index < expected))

  private def assertTransferred(module: Module, name: String): Unit =
    val id = resolvedBindingId(module, "example", name)
    val lambdas = TermTraversal.collect(member(module, "example").value) {
      case lambda: Lambda
          if lambda.isMove && !lambda.meta.exists(_.isPartialApplication) &&
            lambda.captures.exists(_.ref.resolvedId.contains(id)) =>
        lambda
    }
    assertEquals(lambdas.size, 1)
    assertEquals(lambdas.head.meta.map(_.transferredCaptures), Some(Set(id)))
    assert(!lambdas.head.meta.exists(_.borrowedCaptures.contains(id)))

  private def destructorCalls(module: Module, owner: String, typeName: String): List[App] =
    val destructorId = TypeUtils
      .freeFnFor(typeName, module.resolvables)
      .flatMap(DestructionTargets.named(_, module.resolvables))
      .getOrElse(fail(s"Expected destructor for $typeName"))
    TermTraversal
      .collect(member(module, owner).value) { case app: App =>
        app.fn match
          case ref: Ref if ref.resolvedId.contains(destructorId) => List(app)
          case _ => Nil
      }
      .flatten

  List(
    "chained" -> "{ u: Unit -> take text; } () 2",
    "bound" -> "let selected = { u: Unit -> take text; } (); selected 2"
  ).foreach { (form, invocation) =>
    val declarations = """
      fn take(~payload: String): Int -> Int =
        println payload;
        { n: Int -> n; };
      ;
    """

    test(s"$form evaluated callee transfers the enclosing move lambda's capture") {
      semNotFailed(s"""
        $declarations
        fn example(): Int =
          let text = int_to_str 42;
          let outer = ~{ $invocation; };
          outer ();
        ;
      """).map(assertTransferred(_, "text"))
    }

    test(s"$form evaluated callee makes the enclosing move lambda call-once") {
      val source = s"""
        $declarations
        fn example(): Int =
          let text = int_to_str 42;
          let outer = ~{ $invocation; };
          let first = outer ();
          outer () + first;
        ;
      """
      semChecked(source).map(assertOnlyMove(source, _, "example", "outer", "outer () + first"))
    }
  }

  test("evaluated field qualifier transfers the enclosing move lambda's capture") {
    semNotFailed("""
      struct Holder { call: Int -> Int };
      fn take(~payload: String): Holder =
        println payload;
        Holder { n: Int -> n; };
      ;
      fn example(): Int =
        let text = int_to_str 42;
        let outer = ~{ (take text).call 2; };
        outer ();
      ;
    """).map(assertTransferred(_, "text"))
  }

  test("an evaluated immediate body moves its caller's binding") {
    val source = """
      fn take(~payload: String): Int -> Int =
        println payload;
        { n: Int -> n; };
      ;
      fn example(): Int =
        let text = int_to_str 42;
        let first = { u: Unit -> take text; } () 1;
        println text;
        first;
      ;
    """
    semChecked(source).map { result =>
      val use    = source.indexOf("text;", source.indexOf("println text"))
      val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
      assertEquals(errors.size, 1, result.errors.toString)
      assertEquals(result.errors.size, 1, result.errors.toString)
      assertEquals(
        errors.head.ref.resolvedId,
        Some(resolvedBindingId(result.module, "example", "text"))
      )
      assertEquals(errors.head.ref.spanOpt.map(_.start.index), Some(use))
      assert(errors.head.movedAt.spanOpt.exists(_.start.index < use))
    }
  }

  test("an evaluated immediate body cleans up its nonescaping local allocation once") {
    semNotFailed("""
      fn example(): Int =
        { u: Unit ->
          let text = int_to_str 42;
          println text;
          { n: Int -> n; };
        } () 1;
      ;
    """).map { module =>
      assertEquals(destructorCalls(module, "example", "String").size, 1)
    }
  }

  List(
    "chained" -> "{ text: String -> println text; { n: Int -> n; }; } (int_to_str 42) 0",
    "bound" ->
      "let next = { text: String -> println text; { n: Int -> n; }; } (int_to_str 42); next 0"
  ).foreach { (form, invocation) =>
    test(s"$form immediate stage retains its allocating argument's sole cleanup") {
      semNotFailed(s"fn example(): Int = $invocation;;").map { module =>
        val cleanup = destructorCalls(module, "example", "String")
        assertEquals(cleanup.size, 1)
        val destroyedIds = cleanup.flatMap { app =>
          TermTraversal.collect(app.arg) { case ref: Ref => ref.resolvedId }.flatten
        }
        val allocatorId = member(module, "int_to_str").id
        val allocationOwners = TermTraversal
          .collect(member(module, "example").value) { case app: App =>
            app.fn match
              case lambda: Lambda =>
                val allocates = TermTraversal
                  .collect(app.arg) { case allocation: App =>
                    allocation.fn match
                      case ref: Ref => ref.resolvedId == allocatorId
                      case _ => false
                  }
                  .contains(true)
                if allocates then
                  lambda.params
                    .filter(_.typeSpec.flatMap(TypeUtils.getTypeName).contains("String"))
                    .flatMap(_.id)
                else Nil
              case _ => Nil
          }
          .flatten
        assertEquals(destroyedIds, allocationOwners)
      }
    }
  }

  test("constructing an uncalled borrow lambda does not execute its consuming body") {
    semChecked("""
      fn take(~payload: String): Int = payload.length;;
      fn example(): Int =
        let text = int_to_str 42;
        let outer = ~{
          let unused = { u: Unit -> take text; };
          1;
        };
        let first = outer ();
        outer () + first;
      ;
    """).map { result =>
      val id = resolvedBindingId(result.module, "example", "text")
      val outer = TermTraversal.collect(member(result.module, "example").value) {
        case lambda: Lambda if lambda.isMove => lambda
      }
      assertEquals(outer.size, 1)
      assertEquals(outer.head.meta.map(_.transferredCaptures), Some(Set.empty[String]))
      val borrowed = result.errors.collect {
        case error: SemanticError.BorrowedValuePassedToConsumingParam => error
      }
      assertEquals(borrowed.map(_.ref.resolvedId), List(Some(id)))
      assertEquals(result.errors.size, borrowed.size)
    }
  }

  List(
    "direct" -> "p",
    "local alias" -> "let alias = p; alias"
  ).foreach { (resultForm, returned) =>
    List(
      "chained" -> s"let first = { u: Unit -> $returned; } () 1;",
      "bound" -> s"let selected = { u: Unit -> $returned; } (); let first = selected 1;"
    ).foreach { (callForm, invocation) =>
      test(s"$callForm invocation of a returned $resultForm callable consumes its source") {
        val source = s"""
          $consuming
          fn example(): Int =
            let text = int_to_str 42;
            let p = take text;
            $invocation
            p 2 + first;
          ;
        """
        semChecked(source).map { result =>
          assertOnlyMove(source, result, "example", "p", "p 2 + first")
        }
      }
    }
  }

  List(
    "chained" -> "let first = { u: Unit -> if flag then p; else p; ; } () 1;",
    "bound" ->
      "let selected = { u: Unit -> if flag then p; else p; ; } (); let first = selected 1;"
  ).foreach { (form, invocation) =>
    test(s"$form conditional selection of an existing call-once value retains its borrow") {
      semChecked(s"""
        $consuming
        fn example(flag: Bool): Int =
          let p = take (int_to_str 42);
          $invocation
          p 2 + first;
        ;
      """).map { result =>
        val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
        assertEquals(
          errors.map(_.msg),
          List("Calling this function consumes its environment and requires ownership")
        )
        assertEquals(result.errors.size, 1, result.errors.toString)
      }
    }
  }

  test("a conditional callable selected from an immediate body's local owners can be invoked") {
    semNotFailed(s"""
      $consuming
      fn example(flag: Bool): Int =
        { u: Unit ->
          let first = take (int_to_str 1);
          let second = take (int_to_str 2);
          if flag then first; else second; ;
        } () 1;
      ;
    """)
  }

  test("a returned reusable callable remains reusable after chained invocation") {
    semNotFailed("""
      fn read(payload: String, n: Int): Int = payload.length + n;;
      fn example(): Int =
        let text = int_to_str 42;
        let p = read text;
        let first = { u: Unit -> p; } () 1;
        p 2 + first;
      ;
    """)
  }

  test("a returned call-once callable transfers its enclosing move capture") {
    semNotFailed(s"""
      $consuming
      fn example(): Int =
        let p = take (int_to_str 42);
        let outer = ~{ { u: Unit -> p; } () 1; };
        outer ();
      ;
    """).map(assertTransferred(_, "p"))
  }

  test("an evaluated callee cannot consume a borrowed caller parameter") {
    val source = """
      fn take(~payload: String): Int -> Int =
        println payload;
        { n: Int -> n; };
      ;
      fn example(text: String): Int = { u: Unit -> take text; } () 1;;
    """
    semChecked(source).map { result =>
      val errors = result.errors.collect {
        case error: SemanticError.BorrowedValuePassedToConsumingParam => error
      }
      assertEquals(errors.size, 1, result.errors.toString)
      assertEquals(result.errors.size, 1, result.errors.toString)
      assertEquals(
        errors.head.ref.resolvedId,
        Some(resolvedBindingId(result.module, "example", "text"))
      )
      assertEquals(errors.head.param.id, Some(resolvedBindingId(result.module, "take", "payload")))
      assertEquals(errors.head.ref.spanOpt.map(_.start.index), Some(source.indexOf("text; }")))
    }
  }

  List(
    "chained" -> "make (int_to_str 42) 1",
    "bound" -> "let p = make (int_to_str 42); p 1"
  ).foreach { (form, invocation) =>
    List("static" -> "\"literal\"", "allocated" -> "int_to_str n").foreach {
      (resultForm, returned) =>
        test(s"$form call takes its $resultForm String cleanup from the final stage") {
          semNotFailed(s"""
            fn take(~payload: String, n: Int): String = println payload; $returned;;
            fn make(~payload: String): Int -> String = take payload;;
            fn example(): Unit =
              let result = ($invocation);
              println result;
            ;
          """).map { module =>
            assertEquals(
              destructorCalls(module, "example", "String").size,
              if resultForm == "allocated" then 1 else 0
            )
          }
        }
    }
  }

  List(
    "chained" -> "choose () p 1",
    "bound" -> "let run = choose (); run p 1",
    "partially supplied" -> "let run = choose () p; run 1"
  ).foreach { (form, invocation) =>
    test(s"$form later-stage consuming callable parameter accepts one invocation") {
      semNotFailed(s"""
        $consuming
        fn once(~f: Int -> Int, n: Int): Int = f n;;
        fn choose(): (Int -> Int) -> Int -> Int = once;;
        fn example(): Int =
          let p = take (int_to_str 42);
          $invocation;
        ;
      """)
    }

    test(s"$form later-stage callable argument reaches its resolved consuming parameter") {
      val source = s"""
        $consuming
        fn twice(~f: Int -> Int, n: Int): Int =
          let first = f n;
          first + f n;
        ;
        fn choose(): (Int -> Int) -> Int -> Int = twice;;
        fn example(): Int =
          let p = take (int_to_str 42);
          $invocation;
        ;
      """
      val use = source.indexOf("first + f n") + "first + ".length
      semChecked(source).map { result =>
        val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
        assertEquals(errors.size, 1, result.errors.toString)
        assertEquals(result.errors.size, 1, result.errors.toString)
        assertEquals(
          errors.head.ref.resolvedId,
          Some(resolvedBindingId(result.module, "twice", "f"))
        )
        assertEquals(errors.head.ref.spanOpt.map(_.start.index), Some(use))
        assert(errors.head.movedAt.spanOpt.exists(_.start.index < use))
      }
    }
  }

  test("a later-stage borrowing callable parameter reports the original call-once argument") {
    val source = s"""
      $consuming
      fn choose(): (Int -> Int) -> Int -> Int = { f: Int -> Int, n: Int -> n; };;
      fn example(): Int =
        let p = take (int_to_str 42);
        choose () p 1;
      ;
    """
    for
      parsed <- parseNotFailed(source)
      result <- semState(source)
    yield
      val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
      assertEquals(errors.map(_.msg), List("A call-once function requires a consuming parameter"))
      assertEquals(result.errors.size, 1, result.errors.toString)
      val argument = errors.head.expr
      val expected = source.indexOf("p 1;")
      val originalSources = TermTraversal.collect(member(parsed, "example").value) {
        case ref: Ref if ref.spanOpt.exists(_.start.index == expected) => ref.source
      }
      assertEquals(originalSources.size, 1)
      assertEquals(argument.spanOpt.map(_.start.index), Some(expected))
      assertEquals(argument.source, originalSources.head)
      val ids = TermTraversal.collect(argument) { case ref: Ref => ref.resolvedId }.flatten
      assertEquals(ids, List(resolvedBindingId(result.module, "example", "p")))
  }

  test("a later-stage argument cannot move an earlier stage's borrowed operand") {
    val source = """
      fn identity(n: Int): Int = n;;
      fn read(payload: String): Int -> Int = println payload; identity;;
      fn consume(~payload: String): Int = payload.length;;
      fn example(): Int =
        let text = int_to_str 42;
        read text (consume text);
      ;
    """
    semChecked(source).map { result =>
      val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
      assertEquals(errors.size, 1, result.errors.toString)
      assertEquals(result.errors.size, 1, result.errors.toString)
      assertEquals(
        errors.head.ref.resolvedId,
        Some(resolvedBindingId(result.module, "example", "text"))
      )
      val borrowed = source.indexOf("text (consume")
      val moved    = source.lastIndexOf("text")
      assertEquals(errors.head.ref.spanOpt.map(_.start.index), Some(borrowed))
      assertEquals(errors.head.movedAt.spanOpt.map(_.start.index), Some(moved))
    }
  }

  List(
    "chained" -> "choose false text",
    "bound" -> "let run = choose false; run text"
  ).foreach { (form, invocation) =>
    List("consuming" -> "~", "borrowing" -> "").foreach { (contract, marker) =>
      test(s"$form later-stage alternatives accept matching $contract parameters") {
        semNotFailed(s"""
          fn first(${marker}payload: String): Int = payload.length;;
          fn second(${marker}payload: String): Int = payload.length + 1;;
          fn choose(flag: Bool): String -> Int =
            if flag then first; else second; ;
          ;
          fn example(): Int =
            let text = int_to_str 42;
            $invocation;
          ;
        """).map { module =>
          assertEquals(
            destructorCalls(module, "example", "String").size,
            if contract == "borrowing" then 1 else 0
          )
        }
      }
    }

    test(s"$form later-stage alternatives must agree on consuming parameters") {
      semChecked(s"""
        fn consume(~payload: String): Int = payload.length;;
        fn borrow(payload: String): Int = payload.length;;
        fn choose(flag: Bool): String -> Int =
          if flag then consume; else borrow; ;
        ;
        fn example(): Int =
          let text = int_to_str 42;
          $invocation;
        ;
      """).map { result =>
        val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
        assertEquals(
          errors.map(_.msg),
          List("Callable alternatives must agree on consuming parameters")
        )
        assertEquals(result.errors.size, 1, result.errors.toString)
      }
    }
  }

  List(
    "chained" -> "choose () text",
    "bound" -> "let run = choose (); run text"
  ).foreach { (form, invocation) =>
    test(s"$form later-stage consuming parameter rejects the borrowed source identity") {
      val source = s"""
        fn consume(~payload: String): Int = payload.length;;
        fn choose(): String -> Int = { ~selected: String -> consume selected; };;
        fn example(text: String): Int = $invocation;;
      """
      semChecked(source).map { result =>
        val errors = result.errors.collect {
          case error: SemanticError.BorrowedValuePassedToConsumingParam => error
        }
        assertEquals(errors.size, 1, result.errors.toString)
        assertEquals(result.errors.size, 1, result.errors.toString)
        assertEquals(
          errors.head.ref.resolvedId,
          Some(resolvedBindingId(result.module, "example", "text"))
        )
        assertEquals(
          errors.head.param.id,
          Some(resolvedBindingId(result.module, "choose", "selected"))
        )
        assertEquals(errors.head.ref.spanOpt.map(_.start.index), Some(source.lastIndexOf("text")))
      }
    }
  }

  List(
    "chained" -> "choose false unknown (int_to_str 42)",
    "bound" -> "let run = choose false unknown; run (int_to_str 42)"
  ).foreach { (form, invocation) =>
    List("consuming" -> "~", "borrowing" -> "").foreach { (contract, marker) =>
      test(s"$form known $contract alternative cannot establish an unresolved parameter contract") {
        val source = s"""
          fn known(${marker}text: String): Int = text.length;;
          fn choose(flag: Bool, ~unknown: String -> Int): String -> Int =
            if flag then known; else unknown; ;
          ;
          fn example(~unknown: String -> Int): Int = $invocation;;
        """
        semChecked(source).map { result =>
          val errors = result.errors.collect { case error: SemanticError.InvalidExpression =>
            error
          }
          assertEquals(
            errors.map(_.msg),
            List("Callable alternatives must agree on consuming parameters")
          )
          assertEquals(result.errors.size, 1, result.errors.toString)
          val call =
            if form == "bound" then "run (int_to_str 42)"
            else invocation
          assertEquals(errors.head.expr.spanOpt.map(_.start.index), Some(source.indexOf(call)))
          val targets = TermTraversal.collect(errors.head.expr) { case ref: Ref => ref }
          assertEquals(targets.size, 1)
          assert(targets.head.resolvedId.isDefined)
          if form == "bound" then
            assertEquals(
              targets.head.resolvedId,
              Some(resolvedBindingId(result.module, "example", "run"))
            )
        }
      }
    }
  }

  List(
    "chained" -> "choose unknown (int_to_str 42)",
    "bound" -> "let run = choose unknown; run (int_to_str 42)"
  ).foreach { (form, invocation) =>
    test(s"$form entirely unresolved callback retains its allocating argument cleanup") {
      semNotFailed(s"""
        fn choose(~unknown: String -> Int): String -> Int = unknown;;
        fn example(~unknown: String -> Int): Int = $invocation;;
      """).map { module =>
        assertEquals(destructorCalls(module, "example", "String").size, 1)
      }
    }
  }

  List(
    "chained" -> "choose false 1",
    "bound" -> "let run = choose false; run 1"
  ).foreach { (form, invocation) =>
    test(s"$form later-stage alternatives must agree on result ownership") {
      semChecked(s"""
        fn constant(n: Int): String = "literal";;
        fn choose(flag: Bool): Int -> String =
          if flag then constant; else int_to_str; ;
        ;
        fn example(): Unit =
          let text = ($invocation);
          println text;
        ;
      """).map { result =>
        val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
        assertEquals(
          errors.map(_.msg),
          List("Callable alternatives must agree on result ownership")
        )
        assertEquals(result.errors.size, 1, result.errors.toString)
      }
    }
  }

  private val handles = """
    type Handle = @metal:repr[t=ptr, mem=heap, free=release];
    fn allocate(): Handle = @metal:ffi[mem=alloc];;
    fn release(~p: Handle): Unit = @metal:ffi;;
    fn inspect(p: Handle): Int = @metal:ffi;;
    fn use(~p: Handle, n: Int): Int = inspect p + n;;
  """

  List(
    "direct sink" -> "release (allocate ())",
    "local sink" -> "let handle = allocate (); release handle",
    "move capture" -> "let handle = allocate (); let f = ~{ release handle; }; f ()",
    "invoked partial application" -> "let p = use (allocate ()); let ignored = p 1; ()",
    "uncalled partial application" -> "let p = use (allocate ()); ()"
  ).foreach { (form, body) =>
    test(s"nullary native allocation retains ownership through $form") {
      semNotFailed(s"$handles fn example(): Unit = $body;;")
    }
  }

  test("a nullary owned callable result rejects only its second invocation") {
    val source = s"""
      $consuming
      fn make(): Unit -> Int =
        let p = take (int_to_str 42);
        ~{ { u: Unit -> p; } () 1; };
      ;
      fn example(): Int =
        let outer = make ();
        let first = outer ();
        outer () + first;
      ;
    """
    semChecked(source).map(assertOnlyMove(source, _, "example", "outer", "outer () + first"))
  }

  test("a nullary ordinary allocating function retains caller cleanup") {
    semNotFailed("""
      fn make(): String = int_to_str 42;;
      fn example(): Unit = let text = make (); println text;;
    """).map { module =>
      assertEquals(destructorCalls(module, "example", "String").size, 1)
      assertEquals(destructorCalls(module, "make", "String").size, 0)
    }
  }
