package mml.mmlclib.semantic

import mml.mmlclib.test.BaseEffFunSuite

class MoveCaptureConsumptionTest extends BaseEffFunSuite:

  private val prelude = """
    type Counter = Int -> Int;
    fn take(~text: String, extra: Int): Int = text.length + extra;;
    fn invoke(~f: Int -> Int): Int = f 1;;
    fn borrow(f: Int -> Int): Int = f 1;;
    fn make_pap(~text: String): Counter = take text;;
    fn make_pap_local(~text: String): Counter =
      let p = take text;
      p;
    ;
    fn make_pap_allocated(): Counter =
      let text = int_to_str 123;
      let p = take text;
      p;
    ;
    fn make(~text: String): Int -> Int =
      let p = take text;
      ~{ extra: Int -> p extra; };
    ;
    fn make_returner(~text: String): Unit -> Counter =
      let p = take text;
      ~{ p; };
    ;
    fn make_returner_local(~text: String): Unit -> Counter =
      let p = take text;
      ~{ let local = p; local; };
    ;
    fn make_builder(~text: String): Unit -> Counter = ~{ take text; };;
  """

  List(
    "direct" -> "p extra",
    "local move" -> "let local = p; local extra",
    "conditional" -> "if extra > 0 then p extra; else 0;",
    "higher-order" -> "invoke p",
    "nested move lambda" -> "let inner = ~{ n: Int -> p n; }; inner extra"
  ).foreach { (name, body) =>
    test(s"returned move lambda consumes its PAP through $name") {
      semNotFailed(s"""
        $prelude
        fn make_case(~text: String): Int -> Int =
          let p = take text;
          ~{ extra: Int -> $body; };
        ;
        fn main(): Int =
          let f = make_case (int_to_str 123);
          f 1;
        ;
      """)
    }

    test(s"transferring a PAP through $name makes the returned move lambda call-once") {
      semState(s"""
        $prelude
        fn make_case(~text: String): Counter =
          let p = take text;
          ~{ extra: Int -> $body; };
        ;
        fn main(): Int =
          let f = make_case (int_to_str 123);
          let first = f 1;
          f first;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  List(
    "second call" -> "let first = f 1; f first",
    "alias after call" -> "let first = f 1; let alias = f; alias first",
    "source after alias move" -> "let alias = f; let first = alias 1; f first",
    "conditional call" -> "let first = if flag then f 1; else 0; ; f first"
  ).foreach { (name, body) =>
    test(s"consuming move lambda rejects $name") {
      semState(s"""
        $prelude
        fn example(flag: Bool): Int =
          let f = make (int_to_str 123);
          $body;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("consuming move lambda requires a consuming higher-order parameter") {
    semState(s"""
      $prelude
      fn main(): Int = borrow (make (int_to_str 123));;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.InvalidExpression]), result.errors)
    }
  }

  test("consuming move lambda passes through a consuming higher-order parameter") {
    semNotFailed(s"""
      $prelude
      fn main(): Int = invoke (make (int_to_str 123));;
    """)
  }

  test("borrow lambda cannot consume or return its captured PAP") {
    semState(s"""
      $prelude
      fn invalid(~text: String): Int -> Int =
        let p = take text;
        { extra: Int -> p extra; };
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowClosureEscapeViaReturn]),
        result.errors
      )
      assert(result.errors.exists(_.isInstanceOf[SemanticError.InvalidExpression]), result.errors)
    }
  }

  test("move lambda cannot reuse a captured PAP after moving it locally") {
    semState(s"""
      $prelude
      fn invalid(~text: String): Int -> Int =
        let p = take text;
        ~{ extra: Int -> let local = p; let first = local extra; p first; };
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("move lambda that borrows its captures remains reusable") {
    semNotFailed("""
      fn read(text: String, extra: Int): Int = text.length + extra;;
      fn make(~text: String): Int -> Int = ~{ extra: Int -> read text extra; };;
      fn main(): Int =
        let f = make (int_to_str 123);
        let first = f 1;
        f first;
      ;
    """)
  }

  test("rebinding a captured noncapturing function keeps the outer lambda reusable") {
    semNotFailed("""
      fn inc(n: Int): Int = n + 1;;
      fn make(): Int -> Int =
        let p = inc;
        ~{ n: Int -> let local = p; local n; };
      ;
      fn main(): Int =
        let f = make ();
        let first = f 1;
        f first;
      ;
    """)
  }

  test("moving a captured scalar PAP still consumes the outer lambda") {
    semState("""
      fn add(a: Int, b: Int): Int = a + b;;
      fn make(): Int -> Int =
        let p = add 1;
        ~{ n: Int -> let local = p; local n; };
      ;
      fn main(): Int =
        let f = make ();
        let first = f 1;
        f first;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("parameter shadowing does not consume an outer capture") {
    semNotFailed("""
      fn take(~text: String): Int = text.length;;
      fn make(~text: String): Int -> Int =
        ~{ extra: Int ->
          let first = text.length;
          let text = int_to_str extra;
          first + take text;
        };
      ;
      fn main(): Int =
        let f = make (int_to_str 123);
        let first = f 1;
        f first;
      ;
    """)
  }

  test("move lambda can return a locally allocated capture") {
    semNotFailed("""
      fn make(): Unit -> String =
        let text = int_to_str 123;
        ~{ text; };
      ;
      fn main(): Int =
        let f = make ();
        let result = f ();
        result.length;
      ;
    """)
  }

  test("consuming a cloned literal capture is accepted") {
    semNotFailed("""
      fn take(~text: String): Int = text.length;;
      fn main(): Int =
        let text = "abc";
        let f = ~{ take text; };
        f ();
      ;
    """)
  }

  List(
    "returned PAP" -> "let p = f (); let first = p 1; p first",
    "returned PAP alias" -> "let p = f (); let alias = p; let first = alias 1; alias first",
    "outer lambda" -> "let p = f (); let q = f (); p 1 + q 1"
  ).foreach { (name, body) =>
    test(s"returning an owned PAP rejects reuse of the $name") {
      semState(s"""
        $prelude
        fn main(): Int =
          let f = make_returner (int_to_str 123);
          $body;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("returned owned PAP preserves its consuming higher-order contract") {
    semState(s"""
      $prelude
      fn main(): Int =
        let f = make_returner (int_to_str 123);
        let p = f ();
        borrow p;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.InvalidExpression]), result.errors)
    }
  }

  List(
    "plain function" -> "make_pap (int_to_str 123)",
    "plain function through a local" -> "make_pap_local (int_to_str 123)",
    "plain function with a local allocation" -> "make_pap_allocated ()",
    "returned lambda" -> "let f = make_returner (int_to_str 123); f ()",
    "returned lambda through a local" -> "let f = make_returner_local (int_to_str 123); f ()",
    "returned lambda creating a PAP" -> "let f = make_builder (int_to_str 123); f ()"
  ).foreach { (name, factory) =>
    test(s"owning PAP returned by $name can be consumed by its caller") {
      semNotFailed(s"""
        $prelude
        fn main(): Int =
          let p = ($factory);
          invoke p;
        ;
      """)
    }

    test(s"owning PAP returned by $name rejects a second call") {
      semState(s"""
        $prelude
        fn main(): Int =
          let p = ($factory);
          let first = p 1;
          p first;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }

    test(s"owning PAP returned by $name retains call-once through an alias") {
      semState(s"""
        $prelude
        fn main(): Int =
          let p = ($factory);
          let alias = p;
          let first = alias 1;
          alias first;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }

    test(s"owning PAP returned by $name rejects alias reuse after a higher-order transfer") {
      semState(s"""
        $prelude
        fn main(): Int =
          let p = ($factory);
          let alias = p;
          let first = invoke alias;
          alias first;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }

    test(s"owning PAP returned by $name cannot be passed to a borrowing parameter") {
      semState(s"""
        $prelude
        fn main(): Int =
          let p = ($factory);
          borrow p;
        ;
      """).map { result =>
        assert(
          result.errors.exists {
            case error: SemanticError.InvalidExpression =>
              error.msg == "A call-once function requires a consuming parameter"
            case _ => false
          },
          result.errors
        )
      }
    }
  }

  List(
    "direct return" -> "take text",
    "local return" -> "let p = take text; p",
    "move lambda call" -> "let p = take text; ~{ extra: Int -> p extra; }"
  ).foreach { (name, body) =>
    test(s"borrowed input cannot supply an owning PAP for $name") {
      semState(s"""
        $prelude
        fn invalid(text: String): Counter = $body ;;
      """).map { result =>
        assert(
          result.errors.exists(_.isInstanceOf[SemanticError.CapturedBorrowedHeapBinding]),
          result.errors
        )
      }
    }
  }

  test("borrow lambda cannot return its owned PAP capture") {
    semState(s"""
      $prelude
      fn invalid(~text: String): Unit -> Counter =
        let p = take text;
        { p; };
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowClosureEscapeViaReturn]),
        result.errors
      )
    }
  }

  List("make_returner_local", "make_builder").foreach { factory =>
    test(s"returning a PAP from $factory consumes the outer lambda") {
      semState(s"""
        $prelude
        fn main(): Int =
          let f = $factory (int_to_str 123);
          let p = f ();
          let q = f ();
          p 1 + q 1;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }
