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
        case Some(TypeRef(_, "Int", _, _)) => // pass
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
        case Some(TypeRef(_, "Int", _, _)) => // pass
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
        case Some(TypeFn(_, List(TypeRef(_, "Int", _, _)), TypeRef(_, "Int", _, _))) => // pass
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
        case Some(TypeRef(_, "Int", _, _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should correctly type a simple let binding") {
    val code = "let x = 1;"
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _, _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should fail on missing function parameter type") {
    val code =
      """
        fn add(a, b): Int = a + b;
      """
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.MissingParameterType(param, _, _) => param.name == "a" || param.name == "b"
          case _ => false
        },
        s"Expected MissingParameterType for 'a' or 'b', got: $typeErrors"
      )
    }
  }

  test("should fail on type mismatch in let binding") {
    val code =
      """
        let x: String = 42;
      """
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError
                .TypeMismatch(_, TypeRef(_, "String", _, _), TypeRef(_, "Int", _, _), _, _) =>
            true
          case _ => false
        },
        s"Expected TypeMismatch (String vs Int), got: $typeErrors"
      )
    }
  }

  test("should fail on type mismatch in function application") {
    // FIXME see BaseFunSuite for instructions on how to improve
    //       test tooling.
    semFailed("""
      fn main() = println (5 + 3);
    """)

  }

  test("should correctly type a conditional expression") {
    val code = "let x = if true then 1 else 2 end;"
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _, _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should type holes using conditional branch type") {
    val code = "let x = if true then 1 else ??? end;"
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _, _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("should type holes using binding ascription") {
    val code = "let x: Int = ???;"
    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "x" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "Int", _, _)) => // pass
        case other => fail(s"Expected Some(TypeRef(\"Int\")), got $other")
    }
  }

  test("untyped hole error references binding name") {
    val code = "let x = ???;"
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.UntypedHoleInBinding("x", _, _) => true
          case _ => false
        },
        "Expected UntypedHoleInBinding to reference binding name 'x'"
      )
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
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _, _))) => // pass
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
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _, _))) => // pass
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
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _, _))) => // pass
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
          end;
      """
    semNotFailed(code).map { module =>
      val fn = module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
      fn.typeSpec match
        case Some(TypeFn(_, Nil, TypeRef(_, "Int", _, _))) => // pass
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
        case Some(TypeFn(_, List(TypeRef(_, "Int", _, _)), TypeRef(_, "Int", _, _))) => // pass
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
        case Some(TypeRef(_, "Int", _, _)) => // pass
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
          case TypeError.TypeMismatch(_, TypeRef(_, "Unit", _, _), TypeRef(_, "Int", _, _), _, _) =>
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

  test("@native function without return type emits MissingReturnType") {
    val code =
      """
        fn nativeNoReturn() = @native;
      """
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.MissingReturnType(bnd: Bnd, _) => bnd.name == "nativeNoReturn"
          case _ => false
        },
        s"Expected MissingReturnType for 'nativeNoReturn', got: $typeErrors"
      )
    }
  }

  test("@native operator without return type emits MissingOperatorReturnType") {
    val code =
      """
        op ***(a: Int, b: Int) 70 left = @native[tpl="mul %type %operand1, %operand2"];
      """
    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.MissingOperatorReturnType(bnd: Bnd, _) =>
            bnd.meta.exists(_.originalName == "***")
          case _ => false
        },
        s"Expected MissingOperatorReturnType for '***', got: $typeErrors"
      )
    }
  }

  test("@native function with return type passes") {
    val code =
      """
        fn nativeWithReturn(): Int = @native;
      """
    semNotFailed(code).map { _ => () }
  }

  test("@native operator with return type passes") {
    val code =
      """
        op ***(a: Int, b: Int): Int 70 left = @native[tpl="mul %type %operand1, %operand2"];
      """
    semNotFailed(code).map { _ => () }
  }

  test("struct selection yields field type") {
    val code =
      """
        struct Person {
          name: String
        };
        let p: Person = ???;
        let n = p.name;
      """

    semNotFailed(code).map { module =>
      val bnd = module.members.collectFirst { case b: Bnd if b.name == "n" => b }.get
      bnd.typeSpec match
        case Some(TypeRef(_, "String", _, _)) => ()
        case other =>
          fail(s"Expected Some(TypeRef(\"String\")), got $other")
    }
  }

  test("struct selection on non-struct emits InvalidSelection") {
    val code =
      """
        let p: Int = 1;
        let n = p.name;
      """

    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.InvalidSelection(ref, TypeRef(_, "Int", _, _), _) =>
            ref.name == "name"
          case _ => false
        },
        s"Expected InvalidSelection for p.name, got: $typeErrors"
      )
    }
  }

  test("struct selection with unknown field emits UnknownField") {
    val code =
      """
        struct Person {
          name: String
        };
        let p: Person = ???;
        let n = p.age;
      """

    semState(code).map { result =>
      val typeErrors = result.errors.collect { case SemanticError.TypeCheckingError(err) => err }
      assert(
        typeErrors.exists {
          case TypeError.UnknownField(ref, struct, _) =>
            ref.name == "age" && struct.name == "Person"
          case _ => false
        },
        s"Expected UnknownField for p.age, got: $typeErrors"
      )
    }
  }
