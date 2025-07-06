package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeCheckerTests extends BaseEffFunSuite:

  test("should correctly type a multi-argument function application") {
    val code =
      """
        fn mult(a: Int b: Int): Int = ???;
        let x = mult 2 2;
      """
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  // test("should correctly type a simple let binding") {
  //   val code = "let x = 1"
  //   semNotFailed(code).map { module =>
  //     val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
  //     bnd.typeSpec match
  //       case Some(TypeRef(_, "Int", _)) => // pass
  //       case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
  //   }
  // }

  // test("should fail on missing function return type") {
  //   val code = "fn foo(x: Int) = x"
  //   semFailed(code).map { errors =>
  //     assert(errors.exists(_.isInstanceOf[SemanticError.TypeCheckingError]))
  //   }
  // }

  // test("should fail on missing function parameter type") {
  //   val code = "fn foo(x): Int = x"
  //   semFailed(code).map { errors =>
  //     assert(errors.exists(_.isInstanceOf[SemanticError.TypeCheckingError]))
  //   }
  // }

  // test("should correctly type a simple function application") {
  //   val code =
  //     """
  //       |fn identity(x: Int): Int = x
  //       |let y = identity 1
  //     """.stripMargin
  //   semNotFailed(code).map { module =>
  //     val bnd = module.members.collectFirst { case b: Bnd if b.name == "y" => b }.get
  //     bnd.typeSpec match
  //       case Some(TypeRef(_, "Int", _)) => // pass
  //       case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
  //   }
  // }

  // test("should fail on type mismatch in function application") {
  //   val code =
  //     """
  //       |fn identity(x: Int): Int = x
  //       |let y = identity "hello"
  //     """.stripMargin
  //   semFailed(code).map { errors =>
  //     assert(errors.exists(_.isInstanceOf[SemanticError.TypeCheckingError]))
  //   }
  // }

  // test("should fail on type mismatch in let binding") {
  //   val code = "let x: String = 1"
  //   semFailed(code).map { errors =>
  //     assert(errors.exists(_.isInstanceOf[SemanticError.TypeCheckingError]))
  //   }
  // }

  // test("should correctly type a conditional expression") {
  //   val code = "let x = if true then 1 else 2"
  //   semNotFailed(code).map { module =>
  //     val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
  //     bnd.typeSpec match
  //       case Some(TypeRef(_, "Int", _)) => // pass
  //       case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
  //   }
  // }

  // test("should fail on mismatched types in conditional branches") {
  //   val code = "let x = if true then 1 else \"hello\""
  //   semFailed(code).map { errors =>
  //     assert(errors.exists(_.isInstanceOf[SemanticError.TypeCheckingError]))
  //   }
  // }
