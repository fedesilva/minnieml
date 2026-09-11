package mml.mmlclib.semantic

import mml.mmlclib.test.BaseEffFunSuite

class DeferredPapOwnershipTest extends BaseEffFunSuite:

  private val prelude = """
    fn measure(extra: Int, ~text: String): Int = text.length + extra;;
    fn make(extra: Int): String -> Int = measure extra;;
    fn invoke(f: String -> Int, ~text: String): Int = f text;;
    fn staged(a: Int, b: Int, ~text: String): Int = a + b + text.length;;
  """

  private val forms = List(
    "named" -> "let p = measure 10;",
    "inline" -> "let p = { n: Int, ~s: String -> n + s.length; } 10;",
    "alias" -> "let original = measure 10; let p = original;",
    "returned" -> "let p = make 10;",
    "staged" -> "let first = staged 4; let p = first 6;"
  )

  forms.foreach { (name, creation) =>
    test(s"$name PAP retains captures across fresh consuming arguments") {
      semNotFailed(prelude + s"""
        fn main(): Int =
          $creation
          let text = int_to_str 123;
          println text;
          let a = p text;
          let b = p (int_to_str 456);
          a + b;
        ;
      """).void
    }

    test(s"$name PAP rejects a borrowed deferred argument") {
      semState(prelude + s"""
        fn use(text: String): Int =
          $creation
          p text;
        ;
      """).map { result =>
        assert(
          result.errors.exists(_.isInstanceOf[SemanticError.BorrowedValuePassedToConsumingParam]),
          result.errors
        )
      }
    }

    test(s"$name PAP moves its deferred argument at invocation") {
      semState(prelude + s"""
        fn main(): Int =
          $creation
          let text = int_to_str 123;
          let result = p text;
          println text;
          result;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("higher-order invocation preserves a deferred consuming argument") {
    semNotFailed(prelude + """
      fn main(): Int =
        let p = measure 10;
        let a = invoke p (int_to_str 123);
        let b = invoke p (int_to_str 456);
        a + b;
      ;
    """).void
  }

  test("higher-order forwarding cannot borrow an argument consumed by its PAP") {
    semState(prelude + """
      fn forward(f: String -> Int, text: String): Int = f text;;
      fn main(): Int = forward (measure 10) (int_to_str 123);;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowedValuePassedToConsumingParam]),
        result.errors
      )
    }
  }

  test("mixed stored and deferred consuming arguments may transfer once") {
    semNotFailed("""
      fn both(~left: String, ~right: String): Int = left.length + right.length;;
      fn main(): Int =
        let p = both (int_to_str 123);
        p (int_to_str 456);
      ;
    """).void
  }

  test("mixed consuming PAP cannot transfer its stored payload twice") {
    semState("""
      fn both(~left: String, ~right: String): Int = left.length + right.length;;
      fn main(): Int =
        let p = both (int_to_str 123);
        let a = p (int_to_str 456);
        p (int_to_str 789);
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("a function argument retains its consuming contract when partially applied") {
    semNotFailed(prelude + """
      fn use(f: Int -> String -> Int): Int =
        let p = f 10;
        p (int_to_str 123);
      ;
      fn main(): Int = use measure;;
    """).void
  }

  test("staged application can store one consuming argument and defer another") {
    semNotFailed("""
      fn sum(n: Int, ~left: String, ~right: String): Int = n + left.length + right.length;;
      fn main(): Int =
        let first = sum 1;
        let second = first (int_to_str 123);
        second (int_to_str 456);
      ;
    """).void
  }

  test("staged application moves the consuming argument when it is supplied") {
    semState("""
      fn sum(n: Int, ~left: String, ~right: String): Int = n + left.length + right.length;;
      fn main(): Int =
        let first = sum 1;
        let text = int_to_str 123;
        let second = first text;
        println text;
        second (int_to_str 456);
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("a deferred consuming function argument transfers closure ownership") {
    semNotFailed("""
      fn call(n: Int, ~f: Int -> Int): Int = f n;;
      fn main(): Int =
        let p = call 10;
        let seed = int_to_str 123;
        let f = ~{ x: Int -> x + seed.length; };
        p f;
      ;
    """).void
  }

  private val builderPrelude = prelude + """
    fn build(f: Int -> String -> Int, n: Int, ~text: String): Int =
      let p = f n;
      p text;
    ;
  """

  test("a staged higher-order PAP preserves the supplied callable's consuming contract") {
    semNotFailed(builderPrelude + """
      fn main(): Int =
        let builder = build measure;
        let p = builder 10;
        p (int_to_str 123);
      ;
    """).void
  }

  test("a staged higher-order PAP rejects a borrowed deferred argument") {
    semState(builderPrelude + """
      fn use(text: String): Int =
        let builder = build measure;
        let p = builder 10;
        p text;
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowedValuePassedToConsumingParam]),
        result.errors
      )
    }
  }

  test("partial higher-order application resolves consuming parameters of an inner PAP") {
    semNotFailed(prelude + """
      fn use(f: Int -> String -> Int, n: Int): Int =
        let p = f n;
        p (int_to_str 123);
      ;
      fn main(): Int =
        let builder = use measure;
        builder 10;
      ;
    """).void
  }
