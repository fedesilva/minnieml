package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class FunctionSignatureTest extends BaseEffFunSuite:

  test("correctly generates signatures for native and regular functions") {
    val source =
      """
        fn debug_print (a: String): Unit = @native;
        fn log_message (a: String): Unit = @native;
        fn join_strings(a: String, b: String): String = @native;
        fn main() = log_message "Fede";
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("declare void @debug_print(%String)"), "debug_print declaration")
      assert(llvmIr.contains("declare void @log_message(%String)"), "log_message declaration")
      assert(
        llvmIr.contains("declare %String @join_strings(%String, %String)"),
        "join_strings declaration"
      )
      assert(llvmIr.contains("define i64 @main()"), "main definition")
      assert(llvmIr.contains("ret i64 0"), "main return")
    }
  }

  test("custom binary operator uses mangled name in definition and call") {
    val source =
      """
        op ** (a: Int, b: Int) 80 left = a * b;
        fn main(): Int = 2 ** 3;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Definition should use mangled name
      assert(
        llvmIr.contains("define i64 @op.star_star.2(i64 %0, i64 %1)"),
        s"operator definition should use mangled name, got:\n$llvmIr"
      )
      // Call should use mangled name (not @**)
      assert(
        llvmIr.contains("call i64 @op.star_star.2("),
        s"operator call should use mangled name, got:\n$llvmIr"
      )
      // Should NOT contain unmangled operator name
      assert(
        !llvmIr.contains("@**("),
        s"should not contain unmangled operator name @**, got:\n$llvmIr"
      )
    }
  }

  test("custom unary operator uses mangled name in definition and call") {
    val source =
      """
        op ! (a: Int) 90 left = a * a;
        fn main(): Int = !5;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Definition should use mangled name
      assert(
        llvmIr.contains("define i64 @op.bang.1(i64 %0)"),
        s"unary operator definition should use mangled name, got:\n$llvmIr"
      )
      // Call should use mangled name
      assert(
        llvmIr.contains("call i64 @op.bang.1("),
        s"unary operator call should use mangled name, got:\n$llvmIr"
      )
    }
  }

  test("partial application generates a function via eta-expansion") {
    val source =
      """
        let greet = concat "Hello, ";
        fn main(): Unit = println (greet "world");
      """

    compileAndGenerate(source).map { llvmIr =>
      // greet should be defined as a function taking one String arg
      assert(
        llvmIr.contains("define %String @greet(%String"),
        s"partial application should generate a function, got:\n$llvmIr"
      )
      // main should call greet
      assert(
        llvmIr.contains("call %String @greet("),
        s"main should call the partial application function, got:\n$llvmIr"
      )
    }
  }

  test("partial application of multi-param function") {
    val source =
      """
        fn add3(a: Int, b: Int, c: Int): Int = a + b + c;
        let add10 = add3 10;
        fn main(): Int = add10 20 5;
      """

    compileAndGenerate(source).map { llvmIr =>
      // add10 should be a function with 2 params (eta-expanded from add3 10)
      assert(
        llvmIr.contains("define i64 @add10(i64 %0, i64 %1)"),
        s"partial should have 2 params, got:\n$llvmIr"
      )
      // main should call add10 with two args
      assert(
        llvmIr.contains("call i64 @add10("),
        s"main should call add10, got:\n$llvmIr"
      )
    }
  }

  test("function aliasing via eta-expansion") {
    val source =
      """
        fn add(a: Int, b: Int): Int = a + b;
        let f = add;
        fn main(): Int = f 1 2;
      """

    compileAndGenerate(source).map { llvmIr =>
      // f should be a wrapper function calling add
      assert(
        llvmIr.contains("define i64 @f(i64 %0, i64 %1)"),
        s"alias should be eta-expanded to a function, got:\n$llvmIr"
      )
      assert(
        llvmIr.contains("call i64 @add("),
        s"alias function should call original, got:\n$llvmIr"
      )
    }
  }

  test("chained partial application") {
    val source =
      """
        fn add3(a: Int, b: Int, c: Int): Int = a + b + c;
        let add10 = add3 10;
        let add10and20 = add10 20;
        fn main(): Int = add10and20 5;
      """

    compileAndGenerate(source).map { llvmIr =>
      // add10 should be a function with 2 params
      assert(
        llvmIr.contains("define i64 @add10(i64 %0, i64 %1)"),
        s"add10 should have 2 params, got:\n$llvmIr"
      )
      // add10and20 should be a function with 1 param (chained partial)
      assert(
        llvmIr.contains("define i64 @add10and20(i64 %0)"),
        s"add10and20 should have 1 param (chained partial), got:\n$llvmIr"
      )
      // add10and20 should call add10
      assert(
        llvmIr.contains("call i64 @add10("),
        s"add10and20 should call add10, got:\n$llvmIr"
      )
    }
  }

  test("curried application - multi-param function called with all args") {
    val source =
      """
        fn add3(a: Int, b: Int, c: Int): Int = a + b + c;
        fn main(): Int = add3 1 2 3;
      """

    compileAndGenerate(source).map { llvmIr =>
      // main should call add3 with 3 arguments
      assert(
        llvmIr.contains("call i64 @add3(i64 1, i64 2, i64 3)"),
        s"main should call add3 with all args, got:\n$llvmIr"
      )
    }
  }

  test("curried application - function calling another with params") {
    val source =
      """
        fn helper(a: Int, b: Int): Int = a + b;
        fn wrapper(x: Int): Int = helper x 10;
        fn main(): Int = wrapper 5;
      """

    compileAndGenerate(source).map { llvmIr =>
      // wrapper should call helper with x and 10
      assert(
        llvmIr.contains("call i64 @helper(i64 %0, i64 10)"),
        s"wrapper should call helper with param and literal, got:\n$llvmIr"
      )
    }
  }
