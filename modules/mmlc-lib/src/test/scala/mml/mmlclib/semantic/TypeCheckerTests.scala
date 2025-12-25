package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeCheckerTests extends BaseEffFunSuite:

  test("should correctly type a multi-argument function application") {
    val code =
      """
        fn mult(a: Int, b: Int): Int = ???;
        let x = mult 2 2;
      """
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should fail when a later argument has the wrong type") {
    val code =
      """
        fn mult(a: Int, b: Int): Int = ???;
        let bad = mult 1 "oops";
      """
    semFailed(code)
  }

  test("should type nested application with grouped arguments") {
    val code =
      """
        fn double(a: Int): Int = a * 2;
        fn sum(f: Int, x: Int): Int = f + x;
        let a = sum (double 1) 2;
      """
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "a" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should type partial application with the remaining function type") {
    val code =
      """
        fn mult(a: Int, b: Int): Int = ???;
        let partial = mult 1;
      """
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "partial" => b }.get
      bnd.typeSpec match
        case Some(TypeFn(_, List(TypeRef(_, "Int", _)), TypeRef(_, "Int", _))) => // pass
        case other =>
          fail(s"Expected TypeFn(Int -> Int), got $other")
    }
  }

  test("should fail when a nullary function is used where a value is expected") {
    val code =
      """
        fn func(): Int = ???;
        let a: Int = func;
      """
    semFailed(code)
  }

  test("should infer the return type of a function") {
    val code =
      """
        fn identity(x: Int) = x;
        let y = identity 1;
      """
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "y" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should correctly type a simple let binding") {
    val code = "let x = 1;"
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should fail on missing function parameter type".pending) {}

  test("should fail on type mismatch in let binding".pending) {}

  test("should fail on type mismatch in function application") {
    // FIXME see BaseFunSuite for instructions on how to improve
    //       test tooling.
    semFailed("""
      fn main() = println (5 + 3);
    """)

  }

  test("should correctly type a conditional expression") {
    val code = "let x = if true then 1 else 2;"
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  // test("should fail on mismatched types in conditional branches") {
  //   val code = "let x = if true then 1 else \"hello\""
  //   semFailed(code).map { errors =>
  //     assert(errors.exists(_.isInstanceOf[SemanticError.TypeCheckingError]))
  //   }
  // }

  // Expression-level let binding tests (#149)

  test("expression-level let: single binding") {
    val code =
      """
        fn main(): Int =
          let a = 1;
          a
        ;
      """
    semNotFailed(code).map { module =>
      val fn = module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
      fn.typeSpec match
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _))) => // pass
        case other => fail(s"Expected fn returning Int, got $other")
    }
  }

  test("expression-level let: nested bindings") {
    val code =
      """
        fn main(): Int =
          let a = 1;
          let b = 2;
          b
        ;
      """
    semNotFailed(code).map { module =>
      val fn = module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
      fn.typeSpec match
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _))) => // pass
        case other => fail(s"Expected fn returning Int, got $other")
    }
  }

  test("expression-level let: with type ascription") {
    val code =
      """
        fn main(): Int =
          let a: Int = 1;
          a
        ;
      """
    semNotFailed(code).map { module =>
      val fn = module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
      fn.typeSpec match
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _))) => // pass
        case other => fail(s"Expected fn returning Int, got $other")
    }
  }

  test("expression-level let: in conditional branches") {
    val code =
      """
        fn main(): Int =
          if true then
            let x = 1;
            x
          else
            let y = 2;
            y
        ;
      """
    semNotFailed(code).map { module =>
      val fn = module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
      fn.typeSpec match
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _))) => // pass
        case other => fail(s"Expected fn returning Int, got $other")
    }
  }

  test("expression-level let: binding references outer scope") {
    val code =
      """
        fn foo(x: Int): Int =
          let y = x;
          y
        ;
      """
    semNotFailed(code).map { module =>
      val fn = module.members.collectFirst { case b: Bnd if b.name == "foo" => b }.get
      fn.typeSpec match
        case Some(TypeFn(_, List(TypeRef(_, "Int", _)), TypeRef(_, "Int", _))) => // pass
        case other => fail(s"Expected Int -> Int, got $other")
    }
  }

  test("nested let in member binding") {
    val code =
      """
        let x = let y = 1; y + 1;
      """
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Int, got $other")
    }
  }

  test("statement expressions require Unit") {
    val code =
      """
        fn main(): Unit =
          1;
          println "ok"
        ;
      """
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.TypeMismatch(_, TypeRef(_, "Unit", _), TypeRef(_, "Int", _), _, _) =>
            true
          case _ => false
        },
        "Expected a Unit vs Int type mismatch for statement expression"
      )
    }
  }

  test("statement expressions allow Unit") {
    val code =
      """
        fn main(): Unit =
          println "ok";
          println "done"
        ;
      """
    semNotFailed(code).map { _ => () }
  }

  test("recursive function without return type emits RecursiveFunctionMissingReturnType") {
    val code =
      """
        fn loop() = loop();
      """
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.RecursiveFunctionMissingReturnType(bnd, _) => bnd.name == "loop"
          case _ => false
        },
        s"Expected RecursiveFunctionMissingReturnType for 'loop', got: $typeErrors"
      )
    }
  }
