package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class FieldQualifierOwnershipTests extends BaseEffFunSuite:

  private val values = """
    struct Box { value: Int, text: String };
    struct Outer { inner: Box };
    fn make(n: Int): Box = Box n (int_to_str n);;
    fn make_outer(n: Int): Outer = Outer (make n);;
    fn read(text: String): Int = text.length;;
    fn take(~text: String): Int = text.length;;
    fn drop_box(~box: Box): Int = box.value;;
    fn lengths(a: String, b: Int): Int = a.length + b;;
  """

  private val callables = """
    struct Holder { call: Int -> Int };
    struct TextHolder { call: Int -> String };
    fn add(a: Int, b: Int): Int = a + b;;
    fn take_text(~text: String, n: Int): Int = text.length + n;;
    fn return_text(~text: String, n: Int): String = text;;
    fn static_text(n: Int): String = "static";;
    fn make_holder(n: Int): Holder = Holder (add n);;
    fn make_once(n: Int): Holder = Holder (take_text (int_to_str n));;
    fn make_returning(n: Int): TextHolder = TextHolder (return_text (int_to_str n));;
    fn make_allocating(): TextHolder = TextHolder int_to_str;;
    fn make_static(): TextHolder = TextHolder static_text;;
    fn use(call: Int -> Int): Int = call 2;;
    fn consume(~call: Int -> Int): Int = call 2;;
  """

  private val binaryCallables = """
    struct Binary { call: Int -> Int -> Int };
    fn add(a: Int, b: Int): Int = a + b;;
    fn make_binary(): Binary = Binary add;;
  """

  private def body(module: Module): Expr =
    module.members
      .collectFirst { case binding: Bnd if binding.name == "example" => binding }
      .flatMap(_.value.terms.collectFirst { case lambda: Lambda => lambda.body })
      .getOrElse(fail("Expected example function body"))

  private def destructorCalls(module: Module, typeName: String): List[Ref] =
    val target = TypeUtils
      .freeFnFor(typeName, module.resolvables)
      .flatMap(DestructionTargets.named(_, module.resolvables))
      .getOrElse(fail(s"Expected destructor for $typeName"))
    TermTraversal.collect(body(module)) {
      case ref: Ref if ref.resolvedId.contains(target) => ref
    }

  private def assertOwnershipError(errors: List[SemanticError]): Unit =
    val ownershipErrors = errors.collect {
      case error: SemanticError.InvalidExpression if error.phase == "ownership-analyzer" => error
      case error: SemanticError.BorrowEscapeViaReturn => error
      case error: SemanticError.BorrowedValuePassedToConsumingParam => error
      case error: SemanticError.UseAfterMove => error
      case error: SemanticError.ConsumingParamNotLastUse => error
      case error: SemanticError.CapturedBorrowedHeapBinding => error
      case error: SemanticError.BorrowClosureEscapeViaReturn => error
    }
    assert(errors.nonEmpty, "Expected an ownership error")
    assertEquals(ownershipErrors, errors)

  test("a value-type qualifier needs no ownership cleanup") {
    semNotFailed("""
      struct Point { x: Int, y: Int };
      fn make_point(): Point = Point 1 2;;
      fn example(): Int = (make_point ()).x;;
    """).void
  }

  List(
    ("primitive projection", "(make 123).value", "Box"),
    ("String borrow", "read (make 123).text", "Box"),
    ("nested String projection", "(make 123).text.length", "Box"),
    ("unused String projection", "let ignored = (make 123).text; 0", "Box"),
    ("local String alias", "let text = (make 123).text; read text + text.length", "Box"),
    ("nested aggregate projection", "(make_outer 123).inner.value", "Outer"),
    ("nested aggregate alias", "let inner = (make_outer 123).inner; inner.value", "Outer"),
    ("nested String alias", "let text = (make_outer 123).inner.text; read text", "Outer")
  ).foreach { (name, expression, ownerType) =>
    test(s"temporary qualifier retains its owner for $name") {
      semNotFailed(values + s"fn example(): Int = $expression;;").map { module =>
        assertEquals(destructorCalls(module, ownerType).size, 1)
        assertEquals(destructorCalls(module, "String").size, 0)
        if ownerType == "Outer" then assertEquals(destructorCalls(module, "Box").size, 0)
      }
    }
  }

  List(
    "two allocating branches" -> "if flag then make 123; else make 456;",
    "allocating and borrowed branches" -> "if flag then make 123; else borrowed;"
  ).foreach { (name, qualifier) =>
    test(s"conditional qualifier supports borrowed aliases from $name") {
      semNotFailed(values + s"""
        fn example(flag: Bool, borrowed: Box): Int =
          let text = ($qualifier).text;
          read text + text.length;
        ;
      """).map { module =>
        assertEquals(destructorCalls(module, "Box").size, 1)
        assertEquals(destructorCalls(module, "String").size, 0)
      }
    }
  }

  test("a conditional qualifier borrowing both branches adds no owner cleanup") {
    semNotFailed(values + """
      fn example(flag: Bool, borrowed: Box): Int =
        read (if flag then borrowed; else borrowed;).text;
      ;
    """).map { module =>
      assertEquals(destructorCalls(module, "Box").size, 0)
      assertEquals(destructorCalls(module, "String").size, 0)
    }
  }

  List(
    "reusable field call" -> "(make_holder 1).call 2",
    "reusable field alias" -> "let call = (make_holder 1).call; call 2 + call 3",
    "higher-order borrowed field" -> "use (make_holder 1).call",
    "consuming field call" -> "(make_once 123).call 2",
    "consuming field alias" -> "let call = (make_once 123).call; call 2"
  ).foreach { (name, expression) =>
    test(s"temporary callable qualifier supports $name") {
      semNotFailed(callables + s"fn example(): Int = $expression;;").map { module =>
        assertEquals(destructorCalls(module, "Holder").size, 1)
        assertEquals(destructorCalls(module, "String").size, 0)
      }
    }
  }

  List(
    ("allocated result", "make_allocating ()", 1),
    ("static result", "make_static ()", 0),
    ("transferred PAP payload", "make_returning 123", 1)
  ).foreach { (name, qualifier, expectedStringFrees) =>
    test(s"temporary callable qualifier preserves $name ownership") {
      semNotFailed(callables + s"""
        fn example(): Int =
          let text = ($qualifier).call 123;
          text.length;
        ;
      """).map { module =>
        assertEquals(destructorCalls(module, "TextHolder").size, 1)
        assertEquals(destructorCalls(module, "String").size, expectedStringFrees)
      }
    }
  }

  test("temporary callable qualifier can return its transferred payload") {
    semNotFailed(callables + """
      fn example(): String = (make_returning 123).call 0;;
    """).map { module =>
      assertEquals(destructorCalls(module, "TextHolder").size, 1)
      assertEquals(destructorCalls(module, "String").size, 0)
    }
  }

  List(
    ("String field", "String", "(make 123).text"),
    ("String alias", "String", "let text = (make 123).text; text"),
    ("aggregate field", "Box", "(make_outer 123).inner"),
    ("aggregate alias", "Box", "let inner = (make_outer 123).inner; inner"),
    ("callable field", "Int -> Int", "(make_holder 1).call"),
    ("callable alias", "Int -> Int", "let call = (make_holder 1).call; call")
  ).foreach { (name, resultType, expression) =>
    test(s"temporary qualifier rejects returning a borrowed $name") {
      semState(values + callables + s"fn example(): $resultType = $expression;;").map { result =>
        assertOwnershipError(result.errors)
        assert(result.errors.exists(_.isInstanceOf[SemanticError.BorrowEscapeViaReturn]))
      }
    }
  }

  List(
    "consuming String argument" -> "take (make 123).text",
    "owning String field" -> "let box = Box 0 (make 123).text; box.value",
    "consuming aggregate argument" -> "drop_box (make_outer 123).inner",
    "owning aggregate field" -> "let outer = Outer (make_outer 123).inner; outer.inner.value",
    "consuming callable argument" -> "consume (make_holder 1).call",
    "owning callable field" -> "let holder = Holder (make_holder 1).call; holder.call 2"
  ).foreach { (name, expression) =>
    test(s"temporary qualifier rejects its borrowed field at a $name") {
      semState(values + callables + s"fn example(): Int = $expression;;").map { result =>
        assertOwnershipError(result.errors)
      }
    }
  }

  test("a consuming temporary field alias cannot be called twice") {
    semState(callables + """
      fn example(): Int =
        let call = (make_once 123).call;
        let first = call 1;
        call first;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("a mixed qualifier cannot lend ownership to a borrowed consuming field") {
    semState(callables + """
      fn example(flag: Bool, borrowed: Holder): Int =
        (if flag then make_once 123; else borrowed;).call 2;
      ;
      fn main(): Int = example false (make_once 456);;
    """).map { result =>
      assertOwnershipError(result.errors)
    }
  }

  List(
    "temporary String owner" -> "(make 123).text",
    "explicit String owner" -> "let box = make 123; box.text"
  ).foreach { (name, branch) =>
    test(s"a conditional branch cannot export a borrow from its $name") {
      semState(values + s"""
        fn example(flag: Bool): Int =
          let text = if flag then $branch; else "static";;
          read text;
        ;
      """).map { result =>
        assertOwnershipError(result.errors)
      }
    }
  }

  test("a conditional branch cannot export a callable borrow from its temporary owner") {
    semState(callables + """
      fn example(flag: Bool): Int =
        let call = if flag then (make_holder 1).call; else (make_holder 2).call;;
        call 3;
      ;
    """).map { result =>
      assertOwnershipError(result.errors)
    }
  }

  test("a conditional branch cannot export a String through a nested aggregate alias") {
    semState(values + """
      fn example(flag: Bool): Int =
        let text = if flag then
          let inner = (make_outer 123).inner;
          inner.text;
        else "static";
        ;
        read text;
      ;
    """).map { result =>
      assertOwnershipError(result.errors)
    }
  }

  List(
    "qualifier" -> "(let ignored = (); box).value",
    "local binding" -> "let selected = (let ignored = (); box); selected.value"
  ).foreach { (name, expression) =>
    test(s"an owned result through a scope transfers cleanup to its $name") {
      semNotFailed(values + s"""
        fn example(): Int =
          let box = make 123;
          $expression;
        ;
      """).map { module =>
        assertEquals(destructorCalls(module, "Box").size, 1)
      }
    }
  }

  test("an owner transferred through a scoped qualifier cannot be used afterward") {
    semState(values + """
      fn example(): Int =
        let box = make 123;
        let value = (let ignored = (); box).value;
        box.value + value;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("a conditional branch can finish borrowing its temporary before leaving") {
    semNotFailed(values + """
      fn example(flag: Bool): Int =
        if flag then read (make 123).text; else read (make 456).text;
        ;
      ;
    """).void
  }

  test("qualifier evaluation observes ownership moved by its call argument") {
    semState(values + callables + """
      fn example(): Int =
        let box = make 123;
        (make_holder box.value).call (drop_box box);
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("call arguments can read an owner before its qualifier consumes it") {
    semNotFailed(values + callables + """
      fn take_box(~box: Box): Holder = make_holder box.value;;
      fn example(): Int =
        let box = make 123;
        (take_box box).call box.value;
      ;
    """).void
  }

  test("a later argument cannot move the owner of an earlier projected borrow") {
    semState(values + """
      fn example(): Int =
        let box = make 123;
        lengths (if true then box; else box;).text (drop_box box);
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("a temporary callable field can be partially applied within its owner lifetime") {
    semNotFailed(binaryCallables + """
      fn example(): Int =
        let call = (make_binary ()).call 1;
        call 2 + call 3;
      ;
    """).map { module =>
      assertEquals(destructorCalls(module, "Binary").size, 1)
    }
  }

  test("a temporary callable field alias can be partially applied within its owner lifetime") {
    semNotFailed(binaryCallables + """
      fn example(): Int =
        let field = (make_binary ()).call;
        let call = field 1;
        call 2 + call 3;
      ;
    """).map { module =>
      assertEquals(destructorCalls(module, "Binary").size, 1)
    }
  }

  List(
    "direct PAP" -> "(make_binary ()).call 1",
    "PAP alias" -> "let call = (make_binary ()).call 1; call",
    "field alias PAP" -> "let field = (make_binary ()).call; field 1"
  ).foreach { (name, expression) =>
    test(s"a $name borrowing a temporary callable field cannot escape") {
      semState(binaryCallables + s"fn example(): Int -> Int = $expression;;").map { result =>
        assertOwnershipError(result.errors)
      }
    }
  }

  test("a PAP of a borrowed parameter's field alias cannot escape") {
    semState(binaryCallables + """
      fn example(borrowed: Binary): Int -> Int =
        let field = borrowed.call;
        field 1;
      ;
    """).map { result =>
      assertOwnershipError(result.errors)
    }
  }

  List("borrow" -> "", "move" -> "~").foreach { (name, marker) =>
    test(s"an escaping $name closure cannot capture a temporary qualifier's borrowed String") {
      semState(values + s"""
        fn example(): Int -> Int =
          let text = (make 123).text;
          $marker{ n: Int -> text.length + n; };
        ;
      """).map { result =>
        assertOwnershipError(result.errors)
      }
    }
  }
