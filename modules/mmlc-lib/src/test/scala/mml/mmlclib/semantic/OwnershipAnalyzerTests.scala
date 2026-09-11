package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*

class OwnershipAnalyzerTests extends BaseEffFunSuite:

  private def containsFreeOf(freeName: String)(term: Term): Boolean =
    existsTerm(term) {
      case TXCall1(TXRefResolved(id), _) if id.endsWith("::" + freeName) => true
      case TXCall1(TXRefNamed(name), _) if name == freeName => true
    }

  private def containsClosureTarget(module: Module, term: Term)(
    targetBody: PartialFunction[Term, Boolean]
  ): Boolean =
    existsTerm(term) { case d: DestroyClosure =>
      module.resolvables.lookup(d.targetId).exists {
        case b: Bnd => existsTerm(b.value)(targetBody)
        case _ => false
      }
    }

  private def containsClosureEnvFree(module: Module, term: Term): Boolean =
    containsClosureTarget(module, term) { case _: DestroyClosureEnvironment => true }

  private def containsClosureDispatch(module: Module, term: Term): Boolean =
    containsClosureTarget(module, term) { case _: DispatchClosureDestructor => true }

  private def containsClosureCleanupOf(
    module:      Module,
    owner:       String,
    bindingName: String,
    term:        Term
  ): Boolean =
    val bindingId = module.members
      .collectFirst { case b: Bnd if b.name == owner => b }
      .toList
      .flatMap(b => TermTraversal.collect(b.value) { case l: Lambda => l.params }.flatten)
      .find(_.name == bindingName)
      .flatMap(_.id)
    assert(bindingId.nonEmpty, s"Missing binding $bindingName in $owner")
    existsTerm(term) { case d: DestroyClosure =>
      d.operand.terms.exists {
        case r: Ref => r.resolvedId == bindingId
        case _ => false
      }
    }

  private def topLevelLambdaBody(module: Module, name: String): Expr =
    module.members.collectFirst {
      case b: Bnd if b.name == name =>
        b.value.terms.collectFirst { case l: Lambda => l.body }.get
    }.get

  private def containsFreeString(term: Term): Boolean =
    existsTerm(term) {
      case TXCall1(TXRefResolved(id), _) if id.endsWith("::__free_String") => true
      case TXCall1(TXRefNamed(name), _) if name == "__free_String" => true
    }

  private def countFreesOf(name: String, term: Term): Int =
    countTerms(term) {
      case TXCall1(fn, TXRefNamed(argName)) if argName == name =>
        fn match
          case TXRefResolved(id) if id.endsWith("::__free_String") => 1
          case TXRefNamed(n) if n == "__free_String" => 1
          case _ => 0
    }

  private def countFreeCallsOf(freeName: String, term: Term): Int =
    countTerms(term) {
      case TXCall1(TXRefResolved(id), _) if id.endsWith("::" + freeName) => 1
      case TXCall1(TXRefNamed(n), _) if n == freeName => 1
    }

  private def containsCloneString(term: Term): Boolean =
    existsTerm(term) {
      case TXRefResolved(id) if id.endsWith("::__clone_String") => true
      case TXRefNamed(name) if name == "__clone_String" => true
    }

  private def containsRefName(name: String)(term: Term): Boolean =
    existsTerm(term) {
      case TXRefNamed(n) if n == name => true
    }

  test("caller frees value returned by user function that allocates internally") {
    val code =
      """
        fn get_string(n: Int): String =
          int_to_str n;
        ;

        fn main(): Unit =
          let s = get_string 5;
          println s;
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

  // Pending migration: Parent omits cleanup for the allocating String alias binding.
  test("alias-typed allocating let binding is freed at scope end".ignore) {
    val code =
      """
        type Name = String;

        fn main(): Unit =
          let s: Name = int_to_str 5;
          println s;
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = topLevelLambdaBody(module, "main")
      assert(
        containsFreeString(mainBody),
        "expected alias-typed String returned from int_to_str to be freed"
      )
    }
  }

  test("use after move to consuming param") {
    val code =
      """
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
          consume s;
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
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
          println s;
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
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s1 = "hello" ++ " world";
          consume s1;
          let s2 = "goodbye" ++ " world";
          consume s2;
          println "done";
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
          println s;
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

  test("unused partial application may leave a consuming parameter unapplied") {
    val code =
      """
        fn consume(a: Int, ~s: String): Unit = println s;;

        fn main(): Unit =
          let f = consume 42;
          println "done";
        ;
      """

    semNotFailed(code).void
  }

  test("escaping PAP over borrowed heap argument is rejected") {
    val code =
      """
        fn say(msg: String, n: Int): Unit = println msg;;

        fn make(msg: String): Int -> Unit =
          say msg;
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn =>
        e
      }
      assert(errors.nonEmpty, "Expected borrowed PAP escape error")
    }
  }

  test("non-escaping PAP over borrowed heap argument is accepted") {
    val code =
      """
        fn say(msg: String, n: Int): Unit = println msg;;

        fn main(): Unit =
          let msg = int_to_str 7;
          let f: Int -> Unit = say msg;
          f 0;
        ;
      """

    semNotFailed(code).void
  }

  test("escaping Direct PAP over borrowed heap capture is rejected") {
    val code =
      """
        fn make(): Int -> Unit =
          let msg = int_to_str 7;
          let say: Int -> Int -> Unit =
            ~{ x: Int, y: Int ->
              println msg;
            }
          ;

          say 1;
        ;
      """

    semState(code).map { result =>
      val escapeErrors = result.errors.collect {
        case e: SemanticError.BorrowClosureEscapeViaReturn =>
          e
      }
      assert(escapeErrors.nonEmpty, "Expected borrowed Direct PAP escape error")
    }
  }

  test("non-escaping Direct PAP over borrowed heap capture is accepted") {
    val code =
      """
        fn main(): Unit =
          let msg = int_to_str 7;
          let say: Int -> Int -> Unit =
            ~{ x: Int, y: Int ->
              println msg;
            }
          ;

          let g: Int -> Unit = say 1;
          g 2;
        ;
      """

    semNotFailed(code).void
  }

  test("PAP over already-applied consuming heap arg moves source binding") {
    val code =
      """
        fn consume_first(~msg: String, n: Int): Unit = println msg;;

        fn main(): Unit =
          let msg = int_to_str 7;
          let f: Int -> Unit = consume_first msg;
          println msg;
          f 0;
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(errors.nonEmpty, "Expected UseAfterMove after moving heap arg into PAP")
    }
  }

  test("consuming param not last use detected") {
    val code =
      """
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
          println s;
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
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s = "hello" ++ " world";
          println s;
          consume s;
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse => e }
      assert(errors.isEmpty, s"Expected no ConsumingParamNotLastUse errors but got: $errors")
    }
  }

  test("second move closure cannot capture the same owned heap binding") {
    val code =
      """
        fn main(): Unit =
          let s = "hello" ++ " world";
          let f = ~{
            println s;
          }: Unit;
          let g = ~{
            println s;
          }: Unit;
          f ();
          g ();
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.CapturedMovedHeapBinding => e }
      assertEquals(errors.length, 1, s"Expected one duplicate capture error but got: $errors")
      assertEquals(errors.head.ref.name, "s")
    }
  }

  test("multiple borrow closures can share the same heap binding") {
    val code =
      """
        fn main(): Unit =
          let s = "hello" ++ " world";
          let f = {
            println s;
          }: Unit;
          let g = {
            println s;
          }: Unit;
          f ();
          g ();
        ;
      """

    semNotFailed(code)
  }

  test("single owning closure may pass a captured heap binding to sibling helpers by parameter") {
    val code =
      """
        fn main(): Unit =
          let s = "hello" ++ " world";

          fn show(msg: String): Unit =
            println msg;
          ;

          fn run(): Unit =
            show s;
          ;

          run ();
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect {
        case e: SemanticError.CapturedMovedHeapBinding => e
        case e: SemanticError.CapturedBorrowedHeapBinding => e
      }
      assert(errors.isEmpty, s"Expected no capture ownership errors but got: $errors")
    }
  }

  test("consuming param only use accepted") {
    val code =
      """
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume s;
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
        fn consume(~s: String): Unit = println s;;

        fn test_cond_consume(flag: Bool): Unit =
          let s = int_to_str 1;
          if flag then
            consume s;
          else
            println s;
          ;
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
        fn consume(~s: String): Unit = println s;;

        fn main(): Unit =
          let s1 = "hello" ++ " world";
          let s2 = "goodbye" ++ " world";
          consume s1;
          consume s2;
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
        fn consume(a: Int, ~s: String): Unit = println s;;

        fn main(): Unit =
          let s = "hello" ++ " world";
          consume 42 s;
          println "done";
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
        fn identity(s: String): String = s;;
        fn main(): Unit = println "ok";;
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
        fn make_str(n: Int): String = int_to_str n;;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("string literal returned from heap-returning function is accepted") {
    val code =
      """
        fn greeting(): String = "hello";;
        fn main(): Unit = println "ok";;
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
          if n > 0 then
            int_to_str n;
          else
            "none";
          ;
        ;
        fn main(): Unit = println "ok";;
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
            if flag2 then
              int_to_str n;
            else
              "none";
            ;
          else
            int_to_str (n + 1);
          ;
        ;
        fn main(): Unit = println "ok";;
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
            if false then
              int_to_str 1;
            else
              "none";
            ;
          else
            int_to_str 2;
          ;
          println s;
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
          if flag then
            a;
          else
            b;
          ;
        ;
        fn main(): Unit = println "ok";;
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
        fn id(n: Int): Int = n;;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("inner fn returning scalar param does not trigger borrow escape") {
    val code =
      """
        fn main(): Int =
          fn id(count: Int): Int = count;;
          id 1;
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("let-bound lambda returning scalar param does not trigger borrow escape") {
    val code =
      """
        fn main(): Int =
          let id = { count: Int -> count; };
          id 1;
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn errors but got: $errors")
    }
  }

  test("borrowed param returned through let-binding wrapper is rejected") {
    val code =
      """
        fn echo(s: String): String =
          let x = s;
          x;
        ;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.nonEmpty, "Expected BorrowEscapeViaReturn for borrowed param via let wrapper")
    }
  }

  test("borrowed param returned through let-bound conditional is rejected") {
    val code =
      """
        fn pick(s: String, b: Bool): String =
          let x = s;
          if b then
            x;
          else
            "default";
          ;
        ;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(
        errors.nonEmpty,
        "Expected BorrowEscapeViaReturn for borrowed param via let + Cond return"
      )
    }
  }

  test("borrowed param returned through two nested let-bindings is rejected") {
    val code =
      """
        fn echo(s: String): String =
          let x = s;
          let y = x;
          y;
        ;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(
        errors.nonEmpty,
        "Expected BorrowEscapeViaReturn for borrowed param via two nested let wrappers"
      )
    }
  }

  test("let-binding shadowing a borrowed outer param does not trigger borrow escape") {
    val code =
      """
        fn f(s: String): String =
          let s = "static";
          s;
        ;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(
        errors.isEmpty,
        s"Expected no BorrowEscapeViaReturn (inner 's' shadows outer); got: $errors"
      )
    }
  }

  test("let-binding wrapper whose body returns a static value is accepted") {
    val code =
      """
        fn echo(s: String): String =
          let x = s;
          "static";
        ;
        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case e: SemanticError.BorrowEscapeViaReturn => e }
      assert(errors.isEmpty, s"Expected no BorrowEscapeViaReturn but got: $errors")
    }
  }

  test("borrowed return retains its parameter identity after the name is shadowed") {
    val code =
      """
        fn echo(s: String): String =
          let alias = s;
          let s = "static";
          alias;
        ;
      """

    semState(code).map { result =>
      val parameterId = result.module.members.collectFirst {
        case bnd: Bnd if bnd.name == "echo" =>
          bnd.value match
            case TXExprLambda(lambda) => lambda.params.headOption.flatMap(_.id)
            case _ => None
      }.flatten
      val errors = result.errors.collect { case error: SemanticError.BorrowEscapeViaReturn =>
        error
      }
      assert(parameterId.nonEmpty, "expected the indexed function parameter")
      assertEquals(errors.map(_.ref.resolvedId).toSet, Set(parameterId))
    }
  }

  test("allocating return branch does not make a borrowed alias return valid") {
    val code =
      """
        fn choose(s: String, take_borrow: Bool): String =
          if take_borrow then
            let alias = s;
            alias;
          else
            int_to_str 1;
          ;
        ;
      """

    semState(code).map { result =>
      val errors = result.errors.collect { case error: SemanticError.BorrowEscapeViaReturn =>
        error
      }
      assertEquals(errors.size, 1, "the borrowed branch must be rejected before clone promotion")
    }
  }

  test("consuming parameter returned through two aliases remains owned by the caller") {
    val code =
      """
        fn echo(~s: String): String =
          let x = s;
          let y = x;
          y;
        ;
      """

    semNotFailed(code).map { module =>
      val body = topLevelLambdaBody(module, "echo")
      assert(!containsFreeString(body), "returned ownership must not be freed in the callee")
      assert(!containsCloneString(body), "returning the moved value must not clone it")
    }
  }

  test("borrow-capturing lambda returned directly is rejected") {
    val code =
      """
        fn makeAdder(a: Int): Int -> Int =
          { x: Int -> x + a; };
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
      assert(errors.nonEmpty, "Expected BorrowClosureEscapeViaReturn error")
    }
  }

  test("borrow-capturing lambda returned through let binding is rejected") {
    val code =
      """
        fn makeAdder(a: Int): Int -> Int =
          let f = { x: Int -> x + a; };
          f;
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
      assert(errors.nonEmpty, "Expected BorrowClosureEscapeViaReturn error")
    }
  }

  test("borrow-capturing lambda returned through two nested let-bindings is rejected") {
    val code =
      """
        fn makeAdder(a: Int): Int -> Int =
          let f = { x: Int -> x + a; };
          let g = f;
          g;
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
      assert(
        errors.nonEmpty,
        "Expected BorrowClosureEscapeViaReturn for borrow closure via two nested let wrappers"
      )
    }
  }

  test("borrow-capturing inner fn returned through local binding is rejected") {
    val code =
      """
        fn makeAdder(a: Int): Int -> Int =
          fn addA(x: Int): Int = x + a;;
          addA;
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
      assert(errors.nonEmpty, "Expected BorrowClosureEscapeViaReturn error")
    }
  }

  test("move-capturing lambda returned directly is accepted") {
    val code =
      """
        fn makeAdder(a: Int): Int -> Int =
          ~{ x: Int -> x + a; };
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
      assert(
        errors.isEmpty,
        s"Expected no BorrowClosureEscapeViaReturn errors but got: $errors"
      )
    }
  }

  test("non-capturing borrow lambda returned directly is accepted") {
    val code =
      """
        fn makeInc(): Int -> Int =
          { x: Int -> x + 1; };
        ;
      """

    semState(code).map { result =>
      val errors =
        result.errors.collect { case e: SemanticError.BorrowClosureEscapeViaReturn => e }
      assert(
        errors.isEmpty,
        s"Expected no BorrowClosureEscapeViaReturn errors but got: $errors"
      )
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
          println u.name;
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
          println u.name;
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
          User n r;
        ;

        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val consumeErrors =
        result.errors.collect { case e: SemanticError.BorrowedValuePassedToConsumingParam =>
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
          Wrapper u;
        ;

        fn main(): Unit = println "ok";;
      """

    semState(code).map { result =>
      val consumeErrors =
        result.errors.collect { case e: SemanticError.BorrowedValuePassedToConsumingParam =>
          e
        }
      assert(
        consumeErrors.exists(_.ref.name == "u"),
        s"Expected borrowed user-struct arg 'u' to be rejected, got: ${result.errors}"
      )
    }
  }

  // Pending migration: Parent accepts a borrowed closure in an owning struct field.
  test(
    "struct field is an ownership sink: borrow-capturing closure into a field is rejected".ignore
  ) {
    val code =
      """
        struct Holder { f: Int -> Int };

        fn main(seed: Int): Int =
          let add_seed = { x: Int -> x + seed; };
          let h = Holder add_seed;
          h.f 10;
        ;
      """

    semState(code).map { result =>
      val errs = result.errors.collect {
        case e: SemanticError.BorrowedValuePassedToConsumingParam => e
      }
      assert(
        errs.exists(_.ref.name == "add_seed"),
        s"Expected borrow closure laundered through a struct field to be rejected, got: ${result.errors}"
      )
    }
  }

  // Pending migration: Parent omits the expected struct cleanup at the binder scope.
  test(
    "struct field is an ownership sink: move-capturing closure moves in and is freed once".ignore
  ) {
    val code =
      """
        struct Holder { f: Int -> Int };

        fn build(seed: Int): Int =
          let add_seed = ~{ x: Int -> x + seed; };
          let h = Holder add_seed;
          h.f 10;
        ;

        fn main(): Unit = println (int_to_str (build 5));;
      """

    semNotFailed(code).map { module =>
      val buildBody = topLevelLambdaBody(module, "build")
      assertEquals(
        countFreeCallsOf("__free_Holder", buildBody),
        1,
        "expected the moved-in struct to be freed exactly once at binder scope"
      )
    }
  }

  test("struct field is an ownership sink: non-capturing function value is accepted") {
    val code =
      """
        struct Holder { f: Int -> Int };

        fn inc(x: Int): Int = x + 1;;

        fn build(): Int =
          let h = Holder inc;
          h.f 10;
        ;

        fn main(): Unit = println (int_to_str (build ()));;
      """

    semState(code).map { result =>
      val errs = result.errors.collect {
        case e: SemanticError.BorrowedValuePassedToConsumingParam => e
      }
      assert(
        errs.isEmpty,
        s"Non-capturing function value into a struct field should be accepted, got: ${result.errors}"
      )
    }
  }

  // Pending migration: Parent has no inner struct destructor for this function-bearing field.
  test(
    "struct holding a function-bearing struct compiles and frees through the nested destructor".ignore
  ) {
    val code =
      """
        struct Inner { f: Int -> Int };
        struct Outer { inner: Inner };

        fn use_outer(o: Outer, x: Int): Int =
          o.inner.f x;
        ;

        fn build(seed: Int): Int =
          let add_seed = ~{ x: Int -> x + seed; };
          let inner = Inner add_seed;
          let o = Outer inner;
          use_outer o 1;
        ;

        fn main(): Unit = println (int_to_str (build 5));;
      """

    semNotFailed(code).map { module =>
      val innerFreeBody = topLevelLambdaBody(module, "__free_Inner")
      assert(
        containsClosureDispatch(module, innerFreeBody),
        "nested struct's destructor should free the closure field through __free_closure"
      )
      // A struct holding a function value transitively is not clonable.
      assert(
        !module.members.exists {
          case b: Bnd => b.name == "__clone_Outer"
          case _ => false
        },
        "a struct transitively holding a function value must not get a clone function"
      )
    }
  }

  // Pending migration: Parent inserts struct cleanup for a scalar return at the caller.
  test(
    "scalar-returning function that owns a local struct is not treated as returning the struct".ignore
  ) {
    val code =
      """
        struct Box { name: String };

        fn call_box(b: Box, x: Int): Int = x + 1;;

        fn build(): Int =
          let b = Box "hi";
          call_box b 10;
        ;

        fn main(): Unit = println (int_to_str (build ()));;
      """

    semNotFailed(code).map { module =>
      val buildBody = topLevelLambdaBody(module, "build")
      val mainBody  = topLevelLambdaBody(module, "main")
      assertEquals(
        countFreeCallsOf("__free_Box", buildBody),
        1,
        "build should free its local Box exactly once"
      )
      assert(
        !containsFreeOf("__free_Box")(mainBody),
        "caller must not free a scalar return value as a struct"
      )
    }
  }

  test("constructor with non-heap fields not consumed") {
    val code =
      """
        struct Point { x: Int, y: Int };

        fn make_point(a: Int, b: Int): Point =
          Point a b;
        ;

        fn main(): Unit = println "ok";;
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
          println b.name;
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
          println u.name;
        ;

        fn main(): Unit =
          let n = "Alice" ++ " Smith";
          let r = "Admin" ++ " Role";
          let a = User n r;
          let b = a;
          // Quack! not possible, mem is not owned anymore by a.
          print_user a;
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
          println a.name;
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
          println (int_to_str b.x);
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.isEmpty, s"Non-heap struct should not move: $moveErrors")
    }
  }

  test("string rebinding moves ownership") {
    val code =
      """
        fn main(): Unit =
          let a = "hello" ++ " world";
          let b = a;
          println a; // Error, `a`` was moved.
          println b;
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.nonEmpty, "String rebinding should move — use after move expected")
    }
  }

  test("string rebinding without use-after-move is valid") {
    val code =
      """
        fn main(): Unit =
          let a = "hello" ++ " world";
          let b = a;
          println b;
        ;
      """

    semState(code).map { result =>
      val moveErrors = result.errors.collect { case e: SemanticError.UseAfterMove => e }
      assert(moveErrors.isEmpty, s"No use-after-move when original is not used: $moveErrors")
    }
  }

  test("string rebinding target gets freed") {
    val code =
      """
        fn main(): Unit =
          let a = "hello" ++ " world";
          let b = a;
          println b;
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = module.members.collectFirst {
        case b: Bnd if b.name == "main" =>
          b.value.terms.collectFirst { case l: Lambda => l.body }.get
      }.get

      assert(
        containsFreeString(mainBody.terms.last),
        "moved string target should be freed"
      )
    }
  }

  test("borrowed struct rebinding stays borrowed") {
    val code =
      """
        struct User { name: String, role: String };

        fn use_user(u: User): Unit =
          let b = u;
          println b.name;
        ;

        fn main(): Unit = println "ok";;
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
          println o.data;
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
          println (ar_str_get arr 0);
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

        fn freeNamedValue(~n: NamedValue): Unit = ();;

        fn main(): Unit =
          let nv = NamedValue "hello" 42;
          ();
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

  test("global bindings are auto-cloned when passed to consuming parameter") {
    val code =
      """
        struct Person { name: String, age: Int };
        let name = "fede";
        let p1 = Person name 25;
        let p2 = Person (name) 25;

        fn main() =
          println name;
        ;
      """

    semNotFailed(code).map { module =>
      def member(n: String) = module.members.collectFirst {
        case b: Bnd if b.name == n => b
      }.get

      val p1Body = member("p1").value
      assert(
        containsCloneString(p1Body),
        "expected global 'name' to be cloned when passed to Person constructor"
      )

      val p2Body = member("p2").value
      assert(
        containsCloneString(p2Body),
        "expected global 'name' inside TermGroup to be cloned when passed to Person constructor"
      )
    }
  }

  test("borrowed capture passed to consuming param reports borrowed-value error") {
    val code =
      """
        fn consume_len(~s: String): Int = 1;;

        fn main(): Int =
          let s = "hello" ++ " world";
          fn call(): Int = consume_len s;;
          call ();
        ;
      """

    semState(code).map { result =>
      val borrowedErrors =
        result.errors.collect { case e: SemanticError.BorrowedValuePassedToConsumingParam =>
          e
        }
      val lastUseErrors =
        result.errors.collect { case e: SemanticError.ConsumingParamNotLastUse => e }

      assertEquals(
        borrowedErrors.length,
        1,
        s"Expected one BorrowedValuePassedToConsumingParam error but got: $borrowedErrors"
      )
      assertEquals(borrowedErrors.head.ref.name, "s")
      assertEquals(borrowedErrors.head.param.name, "s")
      assert(
        lastUseErrors.isEmpty,
        s"Expected borrowed-value error instead of ConsumingParamNotLastUse, got: $lastUseErrors"
      )
    }
  }

  // ---- Function-value ownership regression guards --------------------------------------
  //
  // Non-capturing function values have no closure environment to clean up. Passing them as
  // higher-order arguments must not schedule universal or env-specific closure frees.
  test("top-level non-capturing function passed as HO arg schedules no __free_closure") {
    val code =
      """
        fn inc(x: Int): Int = x + 1;;
        fn apply(g: Int -> Int, n: Int): Int = g n;;
        fn main(): Int =
          apply inc 5;
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = topLevelLambdaBody(module, "main")
      assert(
        !containsClosureDispatch(module, mainBody),
        "top-level function value must not be freed by the caller scope"
      )
      assert(
        !containsClosureEnvFree(module, mainBody),
        "top-level function value must not schedule an env-specific free"
      )
    }
  }

  test("inline non-capturing lambda passed as HO arg schedules no __free_closure") {
    val code =
      """
        fn apply(g: Int -> Int, n: Int): Int = g n;;
        fn main(): Int =
          apply { x: Int -> x + 1 } 5;
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = topLevelLambdaBody(module, "main")
      assert(
        !containsClosureDispatch(module, mainBody),
        "inline non-capturing lambda must not be freed by the caller scope"
      )
      assert(
        !containsClosureEnvFree(module, mainBody),
        "inline non-capturing lambda must not schedule an env-specific free"
      )
    }
  }

  test("let-bound non-capturing lambda passed as HO arg schedules no __free_closure") {
    val code =
      """
        fn apply(g: Int -> Int, n: Int): Int = g n;;
        fn main(): Int =
          let f = { x: Int -> x + 1 };
          apply f 5;
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = topLevelLambdaBody(module, "main")
      assert(
        !containsClosureDispatch(module, mainBody),
        "non-capturing closure bound to a let must not be freed at scope end"
      )
      assert(
        !containsClosureEnvFree(module, mainBody),
        "non-capturing closure bound to a let must not schedule an env-specific free"
      )
    }
  }

  test("top-level non-capturing function passed to consuming HO param schedules no caller free") {
    val code =
      """
        fn inc(x: Int): Int = x + 1;;
        fn consume(~g: Int -> Int): Int = g 5;;
        fn main(): Int =
          consume inc;
        ;
      """

    semNotFailed(code).map { module =>
      val consumeBody = topLevelLambdaBody(module, "consume")
      val mainBody    = topLevelLambdaBody(module, "main")
      assert(
        containsClosureCleanupOf(module, "consume", "g", consumeBody) &&
          containsClosureDispatch(module, consumeBody),
        "consuming TypeFn param cleanup must stay in the callee"
      )
      assert(
        !containsClosureDispatch(module, mainBody),
        "top-level function value must not be freed by the caller scope"
      )
      assert(
        !containsClosureEnvFree(module, mainBody),
        "top-level function value must not schedule an env-specific free"
      )
    }
  }

  test("inline non-capturing lambda passed to consuming HO param schedules no caller free") {
    val code =
      """
        fn consume(~g: Int -> Int): Int = g 5;;
        fn main(): Int =
          consume { x: Int -> x + 1 };
        ;
      """

    semNotFailed(code).map { module =>
      val consumeBody = topLevelLambdaBody(module, "consume")
      val mainBody    = topLevelLambdaBody(module, "main")
      assert(
        containsClosureCleanupOf(module, "consume", "g", consumeBody) &&
          containsClosureDispatch(module, consumeBody),
        "consuming TypeFn param cleanup must stay in the callee"
      )
      assert(
        !containsClosureDispatch(module, mainBody),
        "inline non-capturing lambda must not be freed by the caller scope"
      )
      assert(
        !containsClosureEnvFree(module, mainBody),
        "inline non-capturing lambda must not schedule an env-specific free"
      )
    }
  }

  test("move-capturing lambda value still schedules env cleanup") {
    val code =
      """
        fn apply(g: Int -> Int, n: Int): Int = g n;;
        fn main(): Int =
          let a = 1;
          let f = ~{ x: Int -> x + a };
          apply f 5;
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = topLevelLambdaBody(module, "main")
      assert(
        containsClosureCleanupOf(module, "main", "f", mainBody) &&
          (containsClosureEnvFree(module, mainBody) || containsClosureDispatch(module, mainBody)),
        "materialized move-capturing closure must still be cleaned up"
      )
    }
  }
