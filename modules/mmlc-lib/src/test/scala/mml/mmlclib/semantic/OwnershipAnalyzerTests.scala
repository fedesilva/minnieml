package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class OwnershipAnalyzerTests extends BaseEffFunSuite:

  private def containsFreeOf(freeName: String)(term: Term): Boolean =
    term match
      case Ref(_, name, _, _, _, _, _) => name == freeName
      case App(_, fn, arg, _, _) =>
        containsFreeOf(freeName)(fn) || containsFreeOf(freeName)(arg)
      case Expr(_, terms, _, _) => terms.exists(containsFreeOf(freeName))
      case Lambda(_, _, body, _, _, _, _) => containsFreeOf(freeName)(body)
      case TermGroup(_, inner, _) => containsFreeOf(freeName)(inner)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        containsFreeOf(freeName)(cond) || containsFreeOf(freeName)(ifTrue) ||
        containsFreeOf(freeName)(ifFalse)
      case Tuple(_, elements, _, _) => elements.exists(containsFreeOf(freeName))
      case _ => false

  private def containsFreeString(term: Term): Boolean = containsFreeOf("__free_String")(term)

  private def countFreesOf(name: String, term: Term): Int =
    term match
      // FIXME:QA: General, matching with so many _,_ is an antipattern.
      //       -- stable references are best: ref: Ref, then ref.argname)
      //          not sure if this is possible.
      //       -- another posibility, since it seems we have a patten (no pun intended)
      //          in the pattern matches below, we can use custom extractors like expression rewriter uses,
      //          but defined for this module
      case App(_, fn: Ref, Expr(_, List(Ref(_, argName, _, _, _, _, _)), _, _), _, _)
          // FIXME:QA: brittle string match.
          if fn.name == "__free_String" && argName == name =>
        1
      case App(_, fn, arg, _, _) => countFreesOf(name, fn) + countFreesOf(name, arg)
      case Expr(_, terms, _, _) => terms.map(countFreesOf(name, _)).sum
      case Lambda(_, _, body, _, _, _, _) => countFreesOf(name, body)
      case TermGroup(_, inner, _) => countFreesOf(name, inner)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        countFreesOf(name, cond) + countFreesOf(name, ifTrue) + countFreesOf(name, ifFalse)
      case Tuple(_, elements, _, _) => elements.toList.map(countFreesOf(name, _)).sum
      case _ => 0

  private def containsCloneString(term: Term): Boolean =
    term match
      // FIXME:QA: brittle string match.
      case Ref(_, name, _, _, _, _, _) => name == "__clone_String"
      case App(_, fn, arg, _, _) => containsCloneString(fn) || containsCloneString(arg)
      case Expr(_, terms, _, _) => terms.exists(containsCloneString)
      case Lambda(_, _, body, _, _, _, _) => containsCloneString(body)
      case TermGroup(_, inner, _) => containsCloneString(inner)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        containsCloneString(cond) || containsCloneString(ifTrue) || containsCloneString(ifFalse)
      case Tuple(_, elements, _, _) => elements.exists(containsCloneString)
      case _ => false

  private def containsRefName(name: String)(term: Term): Boolean =
    term match
      case Ref(_, n, _, _, _, _, _) => n == name
      case App(_, fn, arg, _, _) =>
        containsRefName(name)(fn) || containsRefName(name)(arg)
      case Expr(_, terms, _, _) => terms.exists(containsRefName(name))
      case Lambda(_, _, body, _, _, _, _) => containsRefName(name)(body)
      case TermGroup(_, inner, _) => containsRefName(name)(inner)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        containsRefName(name)(cond) ||
        containsRefName(name)(ifTrue) ||
        containsRefName(name)(ifFalse)
      case Tuple(_, elements, _, _) => elements.exists(containsRefName(name))
      case _ => false

  test("caller frees value returned by user function that allocates internally") {
    val code =
      """
        fn get_string(n: Int): String =
          int_to_str n
        ;

        fn main(): Unit =
          let s = get_string 5;
          println s
        ;
      """

    semNotFailed(code).map { module =>
      def member(name: String) = module.members.collectFirst {
        case b: Bnd if b.name == name => b
      }.get

      val mainBody = member("main").value.terms.collectFirst { case l: Lambda => l.body }.get
      val getBody  = member("get_string").value.terms.collectFirst { case l: Lambda => l.body }.get

      assert(
        containsFreeString(mainBody),
        "expected caller to free String returned from get_string"
      )

      assert(
        !containsFreeString(getBody),
        "callee should not free the returned String"
      )
    }
  }

  test("use after move to consuming param") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
          consume s
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.nonEmpty, "Expected UseAfterMove error for double move")
    }
  }

  test("use after move in expression") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
          println s
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.nonEmpty, "Expected UseAfterMove error when reading moved binding")
    }
  }

  test("no error when each binding moved once") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s1 = "hello" ++ " world";
          consume s1;
          let s2 = "goodbye" ++ " world";
          consume s2;
          println "done"
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.isEmpty, s"Expected no UseAfterMove errors but got: $moveErrors")
    }
  }

  test("right-assoc ++ chain frees each binding exactly once") {
    val code =
      """
        fn main(): Unit =
          let s0 = int_to_str 0;
          let s  = "Zero: " ++ s0 ++ ", " ++ (int_to_str 1);
          println s
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      val freesS0 = countFreesOf("s0", mainBody)
      assertEquals(freesS0, 1, "s0 should be freed exactly once, at scope end")
    }
  }

  test("partial application of function with consuming param is rejected") {
    val code =
      """
        fn consume(a: Int, ~s: String): Unit = println s;

        fn main(): Unit =
          let f = consume 42;
          println "done"
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.PartialApplicationWithConsuming =>
        e
      }
      assert(errors.nonEmpty, "Expected PartialApplicationWithConsuming error")
    }
  }

  test("consuming param not last use detected") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
          println s
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse => e }
      assert(errors.nonEmpty, "Expected ConsumingParamNotLastUse error")
    }
  }

  test("consuming param as last use accepted") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s = "hello" ++ " world";
          println s;
          consume s
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse => e }
      assert(errors.isEmpty, s"Expected no ConsumingParamNotLastUse errors but got: $errors")
    }
  }

  test("consuming param only use accepted") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse => e }
      assert(errors.isEmpty, s"Expected no ConsumingParamNotLastUse errors but got: $errors")
    }
  }

  test("conditional consume in one branch frees in the other branch") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn test_cond_consume(flag: Bool): Unit =
          let s = int_to_str 1;
          if flag then
            consume s
          else
            println s
          end
        ;
      """

    semNotFailed(code).map { module =>
      val testBody = module.members.collectFirst {
        case b: Bnd if b.name == "test_cond_consume" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assertEquals(
        countFreesOf("s", testBody),
        1,
        "expected one branch-local free of s when only one branch consumes it"
      )
    }
  }

  test("independent bindings each consumed once no error") {
    val code =
      """
        fn consume(~s: String): Unit = println s;

        fn main(): Unit =
          let s1 = "hello" ++ " world";
          let s2 = "goodbye" ++ " world";
          consume s1;
          consume s2
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse => e }
      assert(errors.isEmpty, s"Expected no ConsumingParamNotLastUse errors but got: $errors")
    }
  }

  test("saturated call to function with consuming param is accepted") {
    val code =
      """
        fn consume(a: Int, ~s: String): Unit = println s;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume 42 s;
          println "done"
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.PartialApplicationWithConsuming =>
        e
      }
      assert(errors.isEmpty, s"Expected no PartialApplicationWithConsuming errors but got: $errors")
    }
  }

  test("borrowed param returned from heap-returning function is rejected") {
    val code =
      """
        fn identity(s: String): String = s;
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.nonEmpty, "Expected BorrowEscapeViaReturn error")
      assertEquals(errors.head.ref.name, "s")
    }
  }

  test("allocating call returned from heap-returning function is accepted") {
    val code =
      """
        fn make_str(n: Int): String = int_to_str n;
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("string literal returned from heap-returning function is accepted") {
    val code =
      """
        fn greeting(): String = "hello";
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("mixed conditional with clone promotion is accepted") {
    val code =
      """
        fn maybe_str(n: Int): String =
          if n > 0 then int_to_str n else "none" end
        ;
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("nested mixed conditional in heap-returning function is accepted") {
    val code =
      """
        fn nested_maybe(flag1: Bool, flag2: Bool, n: Int): String =
          if flag1 then
            if flag2 then int_to_str n else "none" end
          else
            int_to_str (n + 1)
          end
        ;
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("nested mixed conditional let-binding creates ownership witness") {
    val code =
      """
        fn main(): Unit =
          let s = if true then
            if false then int_to_str 1 else "none" end
          else
            int_to_str 2
          end;
          println s
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        containsRefName("__owns_s")(mainBody),
        "expected mixed nested conditional let-binding to generate __owns_s witness"
      )
    }
  }

  test("conditional with both branches borrowed in heap-returning function is rejected") {
    val code =
      """
        fn pick(flag: Bool, a: String, b: String): String =
          if flag then a else b end
        ;
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.nonEmpty, "Expected BorrowEscapeViaReturn error for borrowed branches")
      val names = errors.map(_.ref.name).toSet
      assert(names.contains("a"), "Expected error for borrowed 'a'")
      assert(names.contains("b"), "Expected error for borrowed 'b'")
    }
  }

  test("non-heap return type with borrowed param is accepted") {
    val code =
      """
        fn id(n: Int): Int = n;
        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("constructor consumes owned args without cloning") {
    val code =
      """
        struct User { name: String, role: String };

        fn main(): Unit =
          let n = "Alice" ++ " Smith";
          let r = "Admin" ++ " Role";
          let u = User n r;
          println u.name
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        !containsCloneString(mainBody),
        "owned args to constructor should be moved, not cloned"
      )
    }
  }

  test("constructor auto-clones literal string args") {
    val code =
      """
        struct User { name: String, role: String };

        fn main(): Unit =
          let u = User "Alice" "Admin";
          println u.name
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        containsCloneString(mainBody),
        "literal args to constructor should be auto-cloned"
      )
    }
  }

  test("constructor rejects borrowed args for consuming params") {
    val code =
      """
        struct User { name: String, role: String };

        fn make_user(n: String, r: String): User =
          User n r
        ;

        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val consumeErrors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse =>
        e
      }
      assert(
        consumeErrors.nonEmpty,
        s"Expected consuming-param ownership error for borrowed constructor args, got: ${result.errors}"
      )
      val names = consumeErrors.map(_.ref.name).toSet
      assert(names.contains("n"), s"Expected borrowed arg 'n' to be rejected, got: $names")
      assert(names.contains("r"), s"Expected borrowed arg 'r' to be rejected, got: $names")
    }
  }

  test("constructor rejects borrowed user-struct args") {
    val code =
      """
        struct User { name: String, role: String };
        struct Wrapper { user: User };

        fn wrap(u: User): Wrapper =
          Wrapper u
        ;

        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val consumeErrors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse =>
        e
      }
      assert(
        consumeErrors.exists(_.ref.name == "u"),
        s"Expected borrowed user-struct arg 'u' to be rejected, got: ${result.errors}"
      )
    }
  }

  test("constructor with non-heap fields not consumed") {
    val code =
      """
        struct Point { x: Int, y: Int };

        fn make_point(a: Int, b: Int): Point =
          Point a b
        ;

        fn main(): Unit = println "ok";
      """

    semNotFailed(code).map { module =>
      // Point has no heap fields, so no consuming params, no clones
      val mkPointBody = module.members.collectFirst {
        case b: Bnd if b.name == "make_point" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        !containsCloneString(mkPointBody),
        "non-heap fields should not trigger cloning"
      )

      assert(
        !containsFreeString(mkPointBody),
        "non-heap fields should not trigger freeing"
      )
    }
  }

  test("struct rebinding moves ownership") {
    val code =
      """
        struct User { name: String, role: String };

        fn main(): Unit =
          let n = "Alice" ++ " Smith";
          let r = "Admin" ++ " Role";
          let a = User n r;
          let b = a;
          println b.name
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        containsFreeOf("__free_User")(mainBody),
        "moved struct target should be freed"
      )
    }
  }

  test("use after struct move detected") {
    val code =
      """      
        struct User { name: String, role: String };

        fn print_user(u: User): Unit =
          println u.name
        ;

        fn main(): Unit =
          let n = "Alice" ++ " Smith";
          let r = "Admin" ++ " Role";
          let a = User n r;
          let b = a;
          // Quack! not possible, mem is not owned anymore by a.
          print_user a
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.nonEmpty, "Expected UseAfterMove error for struct use after move")
    }
  }

  test("struct use field access after rejected") {
    val code =
      """
        struct User { name: String, role: String };

        fn main(): Unit =
          let n = "Alice" ++ " Smith";
          let r = "Admin" ++ " Role";
          let a = User n r;
          let b = a;
          println a.name
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.nonEmpty, "Expected UseAfterMove error for field access after move")
    }
  }

  test("non-heap struct rebinding borrows") {
    val code =
      """
        struct Point { x: Int, y: Int };

        fn main(): Unit =
          let a = Point 1 2;
          let b = a;
          println (int_to_str a.x);
          println (int_to_str b.x)
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.isEmpty, s"Non-heap struct should not move: $moveErrors")
    }
  }

  test("string rebinding still borrows") {
    val code =
      """
        fn main(): Unit =
          let a = "hello" ++ " world";
          let b = a;
          println a;
          println b
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.isEmpty, s"String rebinding should borrow, not move: $moveErrors")
    }
  }

  test("borrowed struct rebinding stays borrowed") {
    val code =
      """
        struct User { name: String, role: String };

        fn use_user(u: User): Unit =
          let b = u;
          println b.name
        ;

        fn main(): Unit = println "ok";
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.isEmpty, s"Borrowed param rebinding should stay borrowed: $moveErrors")
    }
  }

  test("nested struct with heap fields has correct free calls") {
    val code =
      """
        struct Inner { name: String };
        struct Outer { inner: Inner, data: String };

        fn main(): Unit =
          let i = Inner ("hello" ++ " world");
          let o = Outer i ("foo" ++ " bar");
          println o.data
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        containsFreeOf("__free_Outer")(mainBody),
        "Outer struct should be freed via __free_Outer"
      )
    }
  }

  test("same string in two array slots rejected") {
    val code =
      """
        fn main(): Unit =
          let arr = ar_str_new 2;
          let s = "hello" ++ " world";
          ar_str_set arr 0 s;
          ar_str_set arr 1 s;
          println (ar_str_get arr 0)
        ;
      """

    semState(code).map { result =>
      val consumeErrors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse =>
        e
      }
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(
        consumeErrors.nonEmpty || moveErrors.nonEmpty,
        s"Expected ownership error for double use of moved string, got: ${result.errors}"
      )
    }
  }

  test("native struct constructor heap params marked consuming") {
    val code =
      """
        type NamedValue = @native[mem=heap, free=freeNamedValue] {
          name: String,
          value: Int
        };

        fn freeNamedValue(~n: NamedValue): Unit = ();

        fn main(): Unit =
          let nv = NamedValue "hello" 42;
          ()
        ;
      """

    semNotFailed(code).map { module =>
      val ctor = module.members.collectFirst {
        case b: Bnd if b.name == "__mk_NamedValue" => b
      }.get
      val lambda     = ctor.value.terms.collectFirst { case l: Lambda => l }.get
      val nameParam  = lambda.params.find(_.name == "name").get
      val valueParam = lambda.params.find(_.name == "value").get
      assert(nameParam.consuming, "heap-typed 'name' param should be consuming")
      assert(!valueParam.consuming, "non-heap 'value' param should not be consuming")
    }
  }
