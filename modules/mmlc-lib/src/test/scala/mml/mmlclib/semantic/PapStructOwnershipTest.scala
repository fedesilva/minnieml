package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class PapStructOwnershipTest extends BaseEffFunSuite:
  test("a staged constructor preserves consuming parameters of an earlier function field") {
    semNotFailed("""
      struct Holder { f: Int -> String -> Int, n: Int };
      fn take(n: Int, ~text: String): Int = n + text.length;;
      fn main(): Int =
        let constructor = Holder take;
        let holder = constructor 0;
        let p = holder.f 1;
        p (int_to_str 123);
      ;
    """).void
  }

  test("a conditional aggregate alias cannot invoke a field after its owner moves") {
    semState("""
      struct Holder { f: Int -> Int };
      struct Outer { inner: Holder, text: String };
      fn add(a: Int, b: Int): Int = a + b;;
      fn make(n: Int): Holder = Holder (add n);;
      fn drop_outer(~o: Outer): Int = 0;;
      fn main(seed: Int, flag: Bool): Int =
        let outer = Outer (make seed) (int_to_str 0);
        let selected = if flag then outer.inner; else outer.inner;;
        let ignored = drop_outer outer;
        selected.f 2;
      ;
    """).map { state =>
      assert(state.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), state.errors)
    }
  }

  List(
    "consuming argument" -> "fn drop(~h: Holder): Int = 0;; fn main(): Int = drop global;;",
    "mixed return" -> "fn main(flag: Bool): Holder = if flag then Holder inc; else global; ;;"
  ).foreach { (name, body) =>
    test(s"a function-bearing global cannot be cloned at a $name") {
      semState("""
        struct Holder { f: Int -> Int };
        fn inc(n: Int): Int = n + 1;;
        let global = Holder inc;
      """ + body).map { state =>
        assert(
          state.errors.exists {
            case error: SemanticError.InvalidExpression =>
              error.message == "This type cannot be cloned"
            case _ => false
          },
          state.errors
        )
      }
    }
  }

  private val prelude = """
    struct Holder { f: Int -> Int };
    struct Outer { inner: Holder, text: String };
    fn add(a: Int, b: Int): Int = a + b;;
    fn take(~text: String, n: Int): Int = text.length + n;;
    fn drop(~h: Holder): Int = 0;;
    fn make(n: Int): Holder = let p = add n; Holder p;;
    fn make_once(~text: String): Holder = let p = take text; Holder p;;
    fn invoke(~h: Holder): Int = h.f 2;;
  """

  List(
    "reusable scalar PAP" -> "let p = add 1; let h = Holder p; let a = h.f 2; h.f a;",
    "inline scalar PAP" -> "let h = Holder (add 1); h.f 2;",
    "returned holder" -> "let h = make 1; let q = h.f; let a = q 2; h.f a;",
    "moved holder" -> "let h = make 1; let moved = h; moved.f 2;",
    "consuming PAP field" -> "let h = make_once (int_to_str 123); h.f 2;",
    "borrowed field alias invocation" ->
      "let h = make_once (int_to_str 123); let q = h.f; q 2;",
    "consuming holder parameter" -> "let h = make_once (int_to_str 123); invoke h;",
    "unused holder" -> "let h = make_once (int_to_str 123); 0;",
    "nested holder" ->
      "let h = make 1; let o = Outer h (int_to_str 123); o.inner.f 2;",
    "nested consuming field" ->
      "let h = make_once (int_to_str 123); let o = Outer h (int_to_str 456); o.inner.f 2;"
  ).foreach { (name, body) =>
    test(s"PAP struct supports $name") {
      semNotFailed(prelude + s"fn main(): Int = $body ;").void
    }
  }

  List(
    "PAP after construction" -> "let p = add 1; let h = Holder p; p 2;",
    "holder after move" -> "let h = make 1; let moved = h; h.f 2;",
    "nested field after owner move" ->
      "let h = make 1; let o = Outer h (int_to_str 123); let moved = o; o.inner.f 2;",
    "second consuming field call" ->
      "let h = make_once (int_to_str 123); let a = h.f 2; h.f 3;",
    "second call through field alias" ->
      "let h = make_once (int_to_str 123); let q = h.f; let a = q 2; h.f 3;",
    "second call through original field" ->
      "let h = make_once (int_to_str 123); let q = h.f; let a = h.f 2; q 3;",
    "move after field consumption" ->
      "let h = make_once (int_to_str 123); let a = h.f 2; drop h;",
    "field alias after owner destruction" ->
      "let h = make 1; let q = h.f; let a = drop h; q 2;",
    "conditional second call" ->
      "let h = make_once (int_to_str 123); let a = if true then h.f 2; else 0;; h.f 3;"
  ).foreach { (name, body) =>
    test(s"PAP struct rejects $name") {
      semState(prelude + s"fn main(): Int = $body ;").map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("a borrowed holder cannot supply a consuming field invocation") {
    semFailed(
      prelude + "fn bad(h: Holder): Int = h.f 2;; fn main(): Int = bad (make_once (int_to_str 123));;"
    ).void
  }

  test("an owned field cannot be duplicated into a second holder") {
    semFailed(prelude + "fn bad(): Holder = let h = make 1; Holder h.f;;").void
  }

  test("a borrowed function field cannot escape by return") {
    semFailed(prelude + "fn bad(): Int -> Int = let h = make 1; h.f;;").void
  }

  test("function-bearing aggregates do not get clone helpers") {
    semNotFailed(prelude).map { module =>
      val structs = module.members.collect { case s: TypeStruct if s.name == "Outer" => s }
      assertEquals(structs.size, 1)
      assert(TypeUtils.containsFunction(structs.head, module.resolvables))
      assertEquals(TypeUtils.cloneFnFor("Outer", module.resolvables), None)
    }
  }

  test("an inline borrow closure cannot enter an owning function field") {
    semFailed("""
      struct Holder { f: Int -> Int };
      fn main(seed: Int): Holder = Holder { x: Int -> x + seed; };;
    """).void
  }

  test("a returned PAP keeps deferred consuming arguments through a field") {
    semState("""
      struct Holder { f: String -> Int };
      fn measure(n: Int, ~s: String): Int = n + s.length;;
      fn make(n: Int): Holder = Holder (measure n);;
      fn main(): Int =
        let h = make 1;
        let text = int_to_str 123;
        let result = h.f text;
        text.length;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("a function field is selected independently of a same-named local") {
    compileAndGenerate("""
      struct Holder { f: Int -> Int };
      fn inc(x: Int): Int = x + 1;;
      fn main(): Int = let f = 10; let h = Holder inc; h.f 2;;
    """).map { ir =>
      val body = functionBodyMatching(ir, "[^\\n]*main\\(\\)[^\\n]*")
      assert(body.contains("extractvalue %struct.Holder"), body)
    }
  }

  test("function and aggregate aliases preserve ownership and destruction") {
    semNotFailed("""
      type Callback = Int -> Int;
      struct Holder { f: Callback };
      type Box = Holder;
      struct Outer { box: Box };
      fn add(a: Int, b: Int): Int = a + b;;
      fn make(n: Int): Outer = let p = add n; let h = Holder p; Outer h;;
      fn main(): Int = let o = make 1; o.box.f 2;;
    """).void
  }

  test("field consumption is conditional while sibling fields remain usable") {
    semNotFailed("""
      struct Pair { f: Int -> Int, g: Int -> Int };
      fn take(~s: String, n: Int): Int = s.length + n;;
      fn main(flag: Bool): Int =
        let left = take (int_to_str 123);
        let right = take (int_to_str 456);
        let pair = Pair left right;
        let a = if flag then pair.f 1; else 0;;
        pair.g a;
      ;
    """).void
  }

  test("a function-valued field is resolved to its declared field identity") {
    semNotFailed(prelude + "fn main(): Int = let h = make 1; h.f 2;;").map { module =>
      val fields = module.members.collect {
        case struct: TypeStruct if struct.name == "Holder" =>
          struct.fields.toList
      }.flatten
      val main = module.members.collect { case binding: Bnd if binding.name == "main" => binding }
      val selections = main.flatMap(binding =>
        TermTraversal.collect(binding.value) {
          case ref: Ref if ref.qualifier.isDefined => ref
        }
      )
      assert(selections.nonEmpty)
      assert(selections.forall(ref => fields.exists(_.id == ref.resolvedId)))
    }
  }

  test("shadowed holder ownership follows binding identity") {
    semNotFailed(prelude + """
      fn main(~h: Holder): Int =
        let moved = h;
        let h = make 2;
        let result = drop h;
        moved.f result;
      ;
    """).void
  }

  test("a field name does not count as a use of its moved constructor argument") {
    semNotFailed("""
      struct Holder { f: Int -> Int };
      fn add(a: Int, b: Int): Int = a + b;;
      fn main(n: Int): Int = let f = add n; let h = Holder f; h.f 2;;
    """).void
  }

  test("a borrow closure capturing a field alias cannot consume the field") {
    semFailed(prelude + """
      fn main(): Int =
        let h = make_once (int_to_str 123);
        let f = h.f;
        let call = { n: Int -> f n; };
        let a = call 1;
        h.f a;
      ;
    """).void
  }

  test("a borrow closure can invoke a reusable field alias") {
    semNotFailed(prelude + """
      fn main(): Int =
        let h = make 1;
        let f = h.f;
        let call = { n: Int -> f n; };
        let a = call 1;
        h.f a;
      ;
    """).void
  }

  test("field callable alternatives must agree on returned ownership") {
    semState("""
      struct TextHolder { f: Int -> String };
      fn constant(n: Int): String = "hello";;
      fn use(h: TextHolder): String = h.f 1;;
      fn main(): Int =
        let a = use (TextHolder constant);
        let b = use (TextHolder int_to_str);
        a.length + b.length;
      ;
    """).map { state =>
      assert(
        state.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.toString.contains("Callable alternatives must agree on result ownership")
          case _ => false
        },
        state.errors
      )
    }
  }

  private def alternativeFields(first: String, second: String): String = s"""
    struct Holder { f: String -> Int };
    fn borrow(n: Int, text: String): Int = n + text.length;;
    fn take(n: Int, ~text: String): Int = n + text.length;;
    fn use(h: Holder, ~text: String): Int = h.f text;;
    fn main(): Int =
      let a = Holder ($first 1);
      let b = Holder ($second 2);
      let x = use a (int_to_str 123);
      let y = use b (int_to_str 456);
      x + y;
    ;
  """

  test("field callable alternatives cannot mix borrowing and consuming parameters") {
    semState(alternativeFields("borrow", "take")).map { state =>
      assert(
        state.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.toString.contains("Callable alternatives must agree on consuming parameters")
          case _ => false
        },
        state.errors
      )
    }
  }

  List("borrow", "take").foreach { function =>
    test(s"field callable alternatives accept consistent $function parameters") {
      semNotFailed(alternativeFields(function, function)).void
    }
  }

  List(
    "conditional fields" -> "Outer (if flag then outer.inner; else outer.inner;) (int_to_str 0);",
    "conditional field alias" -> "let selected = if flag then outer.inner; else outer.inner;; Outer selected (int_to_str 0);",
    "mixed borrowed and owned branches" -> "Outer (if flag then outer.inner; else make 2;) (int_to_str 0);",
    "consuming argument" -> "let ignored = drop (if flag then outer.inner; else outer.inner;); outer;"
  ).foreach { (name, body) =>
    test(s"a borrowed aggregate cannot reach an ownership sink through $name") {
      semState(prelude + s"""
        fn bad(flag: Bool): Outer =
          let outer = Outer (make 1) (int_to_str 0);
          $body
        ;
      """).map { state =>
        assert(
          state.errors.exists {
            case error: SemanticError.InvalidExpression =>
              error.toString.contains(
                "An ownership sink cannot receive a value with borrowed ownership"
              )
            case _ => false
          },
          state.errors
        )
      }
    }
  }

  test("conditional owned aggregates can enter an owning field") {
    semNotFailed(prelude + """
      fn make_outer(flag: Bool): Outer =
        Outer (if flag then make 1; else make 2;) (int_to_str 0);
      ;
    """).void
  }

  test("a conditional field alias remains available for borrowed calls") {
    semNotFailed(prelude + """
      fn main(flag: Bool): Int =
        let outer = Outer (make 1) (int_to_str 0);
        let selected = if flag then outer.inner; else outer.inner;;
        selected.f 2;
      ;
    """).void
  }
