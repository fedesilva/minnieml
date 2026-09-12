package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class PapOwnershipTest extends BaseEffFunSuite:

  test("scoped call arguments preserve generated-local counters") {
    semNotFailed("""
      fn lengths(a: String, b: String): Int = a.length + b.length;;
      fn single(text: String): Bool = text.length == 1;;
      fn total(a: Int, b: Int): Int = a + b;;
      fn example(): Int =
        total (
          let value = if single (int_to_str 1) then int_to_str 123; else "abc"; ;
          lengths value (int_to_str 456)
        ) (
          let value = if single (int_to_str 2) then int_to_str 789; else "def"; ;
          lengths value (int_to_str 123)
        );
      ;
    """).map { module =>
      val body = module.members.collectFirst {
        case binding: Bnd if binding.name == "example" => binding.value
      }.get
      val parameters = TermTraversal
        .collect(body) { case lambda: Lambda =>
          lambda.params
        }
        .flatten
      val conditions  = parameters.filter(_.name.startsWith("__condition_"))
      val temporaries = parameters.filter(_.name.startsWith("__tmp_"))
      assertEquals(conditions.size, 2)
      assert(temporaries.nonEmpty)
      val generated = conditions ++ temporaries
      assert(generated.forall(_.id.nonEmpty))
      assertEquals(generated.flatMap(_.id).distinct.size, generated.size)
      // Counter continuity is observable in generated names, independently of fresh binding IDs.
      assertEquals(generated.map(_.name).distinct.size, generated.size)
    }
  }

  List(
    ("named call", "take text", 1),
    ("inline call", "{ ~owned: String -> owned.length; } text", 1),
    ("scoped conditional", "take (if flag then let value = \"abc\"; value; else text;)", 2)
  ).foreach { (name, call, expectedClones) =>
    test(s"consuming $name clones literal bindings") {
      semNotFailed(s"""
        fn take(~text: String): Int = text.length;;
        fn example(flag: Bool): Int =
          let text = "def";
          $call;
        ;
      """).map { module =>
        val cloneName = TypeUtils.cloneFnFor("String", module.resolvables)
        val cloneId = module.members.collectFirst {
          case binding: Bnd if cloneName.contains(binding.name) => binding.id
        }.flatten
        assert(cloneId.nonEmpty)
        val body = module.members.collectFirst {
          case binding: Bnd if binding.name == "example" => binding.value
        }.get
        val clones = TermTraversal.collect(body) {
          case ref: Ref if ref.resolvedId == cloneId => ref
        }
        assertEquals(clones.size, expectedClones)
      }
    }
  }

  test("inline consuming function parameter rejects a borrow-capturing closure") {
    semState("""
      fn bad(text: String): Int =
        { ~f: Int -> Int, x: Int -> f x; } { n: Int -> text.length + n; } 0;
      ;
    """).map { result =>
      assert(
        result.errors.exists {
          case error: SemanticError.InvalidExpression =>
            error.msg == "An ownership sink cannot receive a value with borrowed ownership"
          case _ => false
        },
        result.errors
      )
    }
  }

  test("consuming conditional argument rejects a borrowed result through a scoped alias") {
    semState("""
      fn take(~text: String): Int = text.length;;
      fn bad(text: String): Int =
        take (if false then int_to_str 456; else let alias = text; alias;);
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowedValuePassedToConsumingParam]),
        result.errors
      )
    }
  }

  test("parentheses preserve owned closure allocation classification") {
    semNotFailed("""
      fn make(~text: String): Int -> Int = ~{ n: Int -> text.length + n; };;
    """).map { module =>
      val closures = module.members
        .collect { case binding: Bnd => binding }
        .flatMap(binding =>
          TermTraversal.collect(binding.value) {
            case lambda: Lambda if lambda.isMove && lambda.captures.nonEmpty => lambda
          }
        )
      assert(closures.nonEmpty)
      val scope = OwnershipScope(
        syntheticOwner = SyntheticOwner.binding(module.name, "make"),
        resolvables    = module.resolvables
      )
      closures.foreach { closure =>
        val expression = Expr(closure.source, List(closure), typeSpec = closure.typeSpec)
        val grouped    = TermGroup(closure.source, expression)
        assertEquals(OwnershipAnalyzer.termAllocates(closure, scope), closure.typeSpec)
        assertEquals(OwnershipAnalyzer.termAllocates(grouped, scope), closure.typeSpec)
      }
    }
  }

  test("consuming conditional argument moves the selected owned source") {
    semState("""
      fn take(~text: String): Int = text.length;;
      fn main(): Unit =
        let text = int_to_str 123;
        let result = take (if false then int_to_str 456; else text;);
        println text;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("consuming conditional argument rejects a borrowed source branch") {
    semState("""
      fn take(~text: String): Int = text.length;;
      fn bad(text: String): Int = take (if false then int_to_str 456; else text;);;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowedValuePassedToConsumingParam]),
        result.errors
      )
    }
  }

  test("inline consuming parameter rejects a borrowed conditional branch") {
    semState("""
      fn bad(text: String): Int =
        { ~s: String, n: Int -> s.length + n; }
          (if false then int_to_str 456; else text;) 1;
      ;
    """).map { result =>
      assert(
        result.errors.exists(_.isInstanceOf[SemanticError.BorrowedValuePassedToConsumingParam]),
        result.errors
      )
    }
  }

  List("scalar" -> "1", "allocating" -> "(int_to_str 1).length").foreach { (name, extra) =>
    test(s"inline consuming callee records argument moves with $name operands") {
      semState(s"""
        fn main(): Unit =
          let text = int_to_str 123;
          let result = { ~s: String, n: Int -> s.length + n; } text ($extra);
          println text;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("inline consuming callee with an allocating argument rejects source reuse") {
    semState("""
      fn main(): Unit =
        let text = int_to_str 123;
        let result = { ~s: String, other: String -> s.length + other.length; }
          text (int_to_str 456);
        println text;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("inline move callee cannot capture an owner consumed by its arguments") {
    semState("""
      fn take(~s: String): Int = s.length;;
      fn main(): Int =
        let text = int_to_str 123;
        ~{ n: Int, other: String -> text.length + n + other.length; }
          (take text) (int_to_str 456);
      ;
    """).map { result =>
      assert(
        result.errors.exists(error =>
          error.isInstanceOf[SemanticError.UseAfterMove] ||
            error.isInstanceOf[SemanticError.ConsumingParamNotLastUse]
        ),
        result.errors
      )
    }
  }

  test("inline move callee captures after an earlier scalar read") {
    semNotFailed("""
      fn main(): Int =
        let text = int_to_str 123;
        ~{ n: Int, other: String -> text.length + n + other.length; }
          text.length (int_to_str 456);
      ;
    """).void
  }

  test("staged consuming PAP with an allocating argument moves its source") {
    semState("""
      fn take(~s: String, other: String, n: Int): Int = s.length + other.length + n;;
      fn main(): Int =
        let p = take (int_to_str 123);
        let q = p (int_to_str 456);
        p "again" 0;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

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

  test("consuming PAP call can be nested inside an allocating argument") {
    semNotFailed(consumingPrelude + """
      fn main(): Unit =
        let p = take (int_to_str 123);
        println (int_to_str (p 0));
      ;
    """).void
  }

  List(
    "later statement" -> "println (int_to_str (p 0)); p 1",
    "later allocating argument" -> "size (int_to_str (p 0)) (int_to_str (p 1))",
    "later scalar argument" -> "size_extra (int_to_str (p 0)) (p 1)",
    "earlier scalar argument" -> "extra_size (p 0) (int_to_str (p 1))",
    "callee after its argument" -> "p (p 0)"
  ).foreach { (name, body) =>
    test(s"nested consuming PAP rejects reuse in $name") {
      semState(consumingPrelude + s"""
        fn size(a: String, b: String): Int = a.length + b.length;;
        fn size_extra(a: String, b: Int): Int = a.length + b;;
        fn extra_size(a: Int, b: String): Int = a + b.length;;
        fn main(): Int =
          let p = take (int_to_str 123);
          $body;
        ;
      """).map { result =>
        assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
      }
    }
  }

  test("allocating arguments do not grant ownership to a borrowed consuming PAP") {
    semFailed(consumingPrelude + """
      fn bad(f: Int -> Int): Unit = println (int_to_str (f 0));;
      fn main(): Unit = bad (take (int_to_str 123));;
    """).void
  }

  test("nested consuming field call prevents a later call through an alias") {
    semState(consumingPrelude + """
      struct Holder { f: Int -> Int };
      fn main(): Int =
        let holder = Holder (take (int_to_str 123));
        let alias = holder.f;
        println (int_to_str (alias 0));
        holder.f 1;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("allocating later argument cannot consume an earlier argument's borrowed owner") {
    semState("""
      fn take(~text: String): Int = text.length;;
      fn size(a: String, b: String): Int = a.length + b.length;;
      fn main(): Int =
        let text = int_to_str 123;
        size text (int_to_str (take text));
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
  }

  test("inline move closure argument keeps its captured source moved") {
    semState("""
      fn apply(f: Int -> Int, text: String): Int = f 1 + text.length;;
      fn main(): Unit =
        let text = int_to_str 123;
        let result = apply ~{ n: Int -> text.length + n; } (int_to_str 2);
        println text;
      ;
    """).map { result =>
      assert(result.errors.exists(_.isInstanceOf[SemanticError.UseAfterMove]), result.errors)
    }
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
