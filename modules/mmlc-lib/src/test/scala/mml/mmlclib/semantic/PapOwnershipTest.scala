package mml.mmlclib.semantic

import mml.mmlclib.test.BaseEffFunSuite

class PapOwnershipTest extends BaseEffFunSuite:

  test("higher-order PAP argument stays live through later argument evaluation") {
    semState("""
      fn read(text: String, n: Int): Int = println text; text.length + n;;
      fn consume(~text: String): Int = text.length;;
      fn invoke(f: Int -> Int, n: Int): Int = f n;;
      fn main(): Int =
        let text = int_to_str 123;
        let p = read text;
        invoke p (consume text);
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("inline PAP preserves enclosing borrow captures") {
    semNotFailed("""
      fn main(): Int =
        let text = int_to_str 123;
        let p = { n: Int, x: Int -> println text; n + x; } 1;
        println text;
        p 2;
      ;
    """).void
  }

  test("inline PAP cannot return an enclosing borrow capture") {
    semState("""
      fn make(): Int -> Int =
        let text = int_to_str 123;
        { n: Int, x: Int -> println text; n + x; } 1;
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowClosureEscapeViaReturn]),
        result.errors
      )
    }
  }

  private val consumingPrelude = """
    fn take(~text: String, n: Int): Int = text.length + n;;
    fn invoke(~f: Int -> Int): Int = f 1;;
    fn borrow(f: Int -> Int): Int = f 1;;
  """

  List(
    "second call" -> "let p = take (int_to_str 123); let a = p 1; p 2;",
    "original after alias move" -> "let p = take (int_to_str 123); let q = p; let a = q 1; p 2;",
    "payload after creation" -> "let s = int_to_str 123; let p = take s; println s; p 1;",
    "second higher-order call" -> "let p = take (int_to_str 123); let a = invoke p; invoke p;"
  ).foreach { (name, body) =>
    test(s"consuming PAP rejects $name") {
      semState(consumingPrelude + s"fn main(): Int = $body ;").map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("consuming PAP requires ownership through a higher-order argument") {
    semFailed(consumingPrelude + "fn main(): Int = borrow (take (int_to_str 123));;").void
  }

  test("consuming PAP can return its owned payload") {
    semNotFailed("""
      fn keep(~text: String, n: Int): String = text;;
      fn main(): Int =
        let p = keep (int_to_str 123);
        let text = p 0;
        text.length;
      ;
    """).void
  }

  test("consuming PAP can pass through a consuming higher-order parameter") {
    semNotFailed(consumingPrelude + "fn main(): Int = invoke (take (int_to_str 123));;").void
  }

  List(
    "temporary consuming argument" -> "consume (read text)",
    "aliased consuming argument" -> "let p = read text; consume p",
    "struct field" -> "let p = read text; let holder = Holder p; holder.f 0"
  ).foreach { (name, body) =>
    test(s"borrowed PAP cannot escape through $name") {
      semFailed(s"""
        struct Holder { f: Int -> Int };
        fn read(text: String, n: Int): Int = text.length + n;;
        fn consume(~f: Int -> Int): Int = f 0;;
        fn main(): Int =
          let text = int_to_str 123;
          $body;
        ;
      """).void
    }
  }

  test("inline consuming PAP moves its applied parameter") {
    semNotFailed("""
      fn main(): Int =
        let p = { ~text: String, n: Int -> text.length + n; } (int_to_str 123);
        p 1;
      ;
    """).void
  }

  test("chained PAP creation moves a consuming callee") {
    semNotFailed("""
      fn take(~text: String, a: Int, b: Int): Int = text.length + a + b;;
      fn main(): Int =
        let p = take (int_to_str 123);
        let q = p 1;
        q 2;
      ;
    """).void
  }

  test("consuming PAP accepts an allocating remaining argument") {
    semNotFailed("""
      fn two(~left: String, right: String): Int = left.length + right.length;;
      fn main(): Int =
        let p = two (int_to_str 123);
        p (int_to_str 456);
      ;
    """).void
  }

  test("inline PAP accepts remaining consuming parameters") {
    semNotFailed("""
      fn main(): Int =
        let p = { n: Int, ~text: String -> text.length + n; } 1;
        p (int_to_str 123);
      ;
    """).void
  }

  test("PAP use is rejected after its borrowed heap payload moves") {
    val source =
      """
        fn read(text: String, n: Int): Int = text.length + n;;
        fn consume(~text: String): Unit = println text;;

        fn main(): Int =
          let text = int_to_str 123;
          let p = read text;
          consume text;
          p 0;
        ;
      """

    semState(source).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("PAP aliases retain borrowed payload identity through source-name shadowing") {
    val source =
      """
        fn read(text: String, n: Int): Int = text.length + n;;
        fn consume(~text: String): Unit = println text;;

        fn main(): Int =
          let text = int_to_str 123;
          let p = read text;
          let alias = p;
          consume text;
          fn call(text: String): Int = alias text.length;;
          call "456";
        ;
      """

    semState(source).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("borrowed PAP may be called before its payload moves") {
    semNotFailed(
      """
        fn read(text: String, n: Int): Int = text.length + n;;
        fn consume(~text: String): Unit = println text;;

        fn main(): Int =
          let text = int_to_str 123;
          let p = read text;
          let result = p 0;
          consume text;
          result;
        ;
      """
    ).void
  }

  test("argument ownership follows source evaluation order") {
    semNotFailed("""
      fn read(text: String, n: Int): Int = text.length + n;;
      fn combine(a: Int, ~text: String): Int = a + text.length;;
      fn main(): Int =
        let text = int_to_str 123;
        let p = read text;
        combine (p 0) text;
      ;
    """).void
  }

  test("borrowed PAP callee stays live throughout argument evaluation") {
    semState("""
      fn read(text: String, n: Int): Int = text.length + n;;
      fn consume(~text: String): Int = text.length;;
      fn main(): Int =
        let text = int_to_str 123;
        let p = read text;
        p (consume text);
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("PAP borrowed from a struct field depends on the aggregate owner") {
    semState("""
      struct Holder { text: String };
      fn read(text: String, n: Int): Int = println text; text.length + n;;
      fn drop(~h: Holder): Int = h.text.length;;
      fn main(): Int =
        let h = Holder (int_to_str 123);
        let p = read h.text;
        let n = drop h;
        p n;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("owned PAP moves into a function field") {
    semNotFailed("""
      struct Holder { f: Int -> Int };
      fn add(a: Int, b: Int): Int = a + b;;
      fn main(): Int =
        let p = add 1;
        let holder = Holder p;
        holder.f 2;
      ;
    """).void
  }
