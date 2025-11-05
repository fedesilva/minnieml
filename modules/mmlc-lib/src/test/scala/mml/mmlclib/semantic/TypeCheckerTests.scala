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
