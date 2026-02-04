package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite

class FunctionSignatureTest extends BaseEffFunSuite:

  test("correctly generates signatures for native and regular functions on x86_64") {
    val source =
      """
        fn debug_print (a: String): Unit = @native;
        fn log_message (a: String): Unit = @native;
        fn join_strings(a: String, b: String): String = @native;
        fn main() = log_message "Fede";
      """

    // Use Binary mode to generate synthesized main entry point
    val config =
      CompilerConfig.exe("build", targetTriple = Some("x86_64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>

      // Native functions keep original names (declarations)
      // String is now 16 bytes (2 fields: length, data), decomposed to (i64, i8*) on x86_64 SysV ABI
      assert(
        llvmIr.contains("declare void @debug_print(i64, i8*)"),
        s"debug_print declaration, got:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare void @log_message(i64, i8*)"),
        s"log_message declaration, got:\n$llvmIr"
      )
      // join_strings: two String params (each decomposed) + struct return
      assert(
        llvmIr.contains(
          "declare %struct.String @join_strings(i64, i8*, i64, i8*)"
        ),
        s"join_strings declaration, got:\n$llvmIr"
      )
      // User main is mangled with module prefix
      assert(llvmIr.contains("define void @test_main()"), "user main definition")
      // Synthesized C main calls user's mangled main
      assert(llvmIr.contains("define i32 @main("), "synthesized main definition")
      assert(llvmIr.contains("call void @test_main()"), "synthesized main calls user main")
      assert(llvmIr.contains("ret i32 0"), "synthesized main return")
    }
  }

  test("correctly generates native signatures on aarch64") {
    val source =
      """
        fn debug_print (a: String): Unit = @native;
        fn log_message (a: String): Unit = @native;
        fn join_strings(a: String, b: String): String = @native;
        fn main() = log_message "Fede";
      """

    val config =
      CompilerConfig.exe("build", targetTriple = Some("aarch64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>
      // String is 16 bytes (2 fields: length, data), lowered to [2 x i64] for register passing
      // (AAPCS64: composites <=16 bytes can be passed in registers)
      assert(
        llvmIr.contains("declare void @debug_print([2 x i64])"),
        s"debug_print should use [2 x i64] param on aarch64, got:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare void @log_message([2 x i64])"),
        s"log_message should use [2 x i64] param on aarch64, got:\n$llvmIr"
      )
      // join_strings returns String (16 bytes) - returned as struct, params as [2 x i64]
      assert(
        llvmIr.contains(
          "declare %struct.String @join_strings([2 x i64], [2 x i64])"
        ),
        s"join_strings should return struct directly on aarch64, got:\n$llvmIr"
      )
    }
  }

  test("aarch64 native calls with String struct args use flattened array") {
    val source =
      """
        fn debug_print (a: String): Unit = @native;
        fn main(): Unit = debug_print "hi";
      """

    val config =
      CompilerConfig.exe("build", targetTriple = Some("aarch64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>
      // 16-byte struct is lowered to [2 x i64] for register passing on aarch64
      assert(
        llvmIr.contains("call void @debug_print([2 x i64] %"),
        s"expected [2 x i64] call site on aarch64 in:\n$llvmIr"
      )
    }
  }

  test("aarch64 HFAs stay in registers (no byval/sret) for float/double aggregates") {
    val source =
      """
        type F32 = @native[t=float];
        type F64 = @native[t=double];

        type Vec3d = @native { x: F64, y: F64, z: F64 };
        type Vec4f = @native { x: F32, y: F32, z: F32, w: F32 };

        fn hfa_arg_d (v: Vec3d): Int = @native;
        fn hfa_ret_d (): Vec3d = @native;
        fn hfa_arg_f (v: Vec4f): Int = @native;
        fn hfa_ret_f (): Vec4f = @native;

        fn main(): Unit = ();
      """

    val config =
      CompilerConfig.exe("build", targetTriple = Some("aarch64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>
      assert(
        llvmIr.contains("declare i64 @hfa_arg_d(%struct.Vec3d)"),
        s"HFA double arg should not be byval/split:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare %struct.Vec3d @hfa_ret_d()"),
        s"HFA double return should not use sret:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare i64 @hfa_arg_f(%struct.Vec4f)"),
        s"HFA float arg should not be byval/split:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare %struct.Vec4f @hfa_ret_f()"),
        s"HFA float return should not use sret:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("byval(%struct.Vec3d)") && !llvmIr.contains("byval(%struct.Vec4f)"),
        s"HFA params must not be lowered to byval pointers:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("sret(%struct.Vec3d)") && !llvmIr.contains("sret(%struct.Vec4f)"),
        s"HFA returns must not use sret:\n$llvmIr"
      )
    }
  }

  test("custom binary operator uses mangled name in definition and call") {
    val source =
      """
        op ** (a: Int, b: Int) 80 left = a * b;
        fn main(): Int = 2 ** 3;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Definition should use module-prefixed mangled name
      assert(
        llvmIr.contains("define i64 @test_op.star_star.2(i64 %0, i64 %1)"),
        s"operator definition should use module-prefixed mangled name, got:\n$llvmIr"
      )
      // Call should use module-prefixed mangled name
      assert(
        llvmIr.contains("call i64 @test_op.star_star.2("),
        s"operator call should use module-prefixed mangled name, got:\n$llvmIr"
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
      // Definition should use module-prefixed mangled name
      assert(
        llvmIr.contains("define i64 @test_op.bang.1(i64 %0)"),
        s"unary operator definition should use module-prefixed mangled name, got:\n$llvmIr"
      )
      // Call should use module-prefixed mangled name
      assert(
        llvmIr.contains("call i64 @test_op.bang.1("),
        s"unary operator call should use module-prefixed mangled name, got:\n$llvmIr"
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
      // greet should be defined as a function with module prefix
      assert(
        llvmIr.contains("define %struct.String @test_greet(%struct.String"),
        s"partial application should generate a module-prefixed function, got:\n$llvmIr"
      )
      // main should call greet with module prefix
      assert(
        llvmIr.contains("call %struct.String @test_greet("),
        s"main should call the module-prefixed partial application function, got:\n$llvmIr"
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
      // add10 should be a function with 2 params and module prefix
      assert(
        llvmIr.contains("define i64 @test_add10(i64 %0, i64 %1)"),
        s"partial should have 2 params with module prefix, got:\n$llvmIr"
      )
      // main should call add10 with module prefix
      assert(
        llvmIr.contains("call i64 @test_add10("),
        s"main should call test_add10, got:\n$llvmIr"
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
      // f should be a wrapper function with module prefix
      assert(
        llvmIr.contains("define i64 @test_f(i64 %0, i64 %1)"),
        s"alias should be eta-expanded to a module-prefixed function, got:\n$llvmIr"
      )
      // alias function should call original (also module-prefixed)
      assert(
        llvmIr.contains("call i64 @test_add("),
        s"alias function should call module-prefixed original, got:\n$llvmIr"
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
      // add10 should be a function with 2 params and module prefix
      assert(
        llvmIr.contains("define i64 @test_add10(i64 %0, i64 %1)"),
        s"add10 should have 2 params with module prefix, got:\n$llvmIr"
      )
      // add10and20 should be a function with 1 param and module prefix
      assert(
        llvmIr.contains("define i64 @test_add10and20(i64 %0)"),
        s"add10and20 should have 1 param with module prefix, got:\n$llvmIr"
      )
      // add10and20 should call add10 with module prefix
      assert(
        llvmIr.contains("call i64 @test_add10("),
        s"add10and20 should call test_add10, got:\n$llvmIr"
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
      // main should call add3 with module prefix and 3 arguments
      assert(
        llvmIr.contains("call i64 @test_add3(i64 1, i64 2, i64 3)"),
        s"main should call test_add3 with all args, got:\n$llvmIr"
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
      // wrapper should call helper with module prefix, x and 10
      assert(
        llvmIr.contains("call i64 @test_helper(i64 %0, i64 10)"),
        s"wrapper should call test_helper with param and literal, got:\n$llvmIr"
      )
    }
  }

  test("function with native template emits inline LLVM IR") {
    val source =
      """
        fn ctpop(x: Int): Int = @native[tpl="call i64 @llvm.ctpop.i64(i64 %operand)"];
        fn main(): Int = ctpop 255;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Should emit inline call to intrinsic, not a function call to ctpop
      assert(
        llvmIr.contains("call i64 @llvm.ctpop.i64(i64"),
        s"should emit inline intrinsic call, got:\n$llvmIr"
      )
      // Should NOT generate a function definition for ctpop
      assert(
        !llvmIr.contains("define i64 @test_ctpop"),
        s"should NOT define ctpop as a function, got:\n$llvmIr"
      )
    }
  }

  test("function with native template and multiple args uses operand1, operand2") {
    val source =
      """
        fn mymax(a: Int, b: Int): Int = @native[tpl="call i64 @llvm.smax.i64(i64 %operand1, i64 %operand2)"];
        fn main(): Int = mymax 10 20;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Should emit inline call with both operands substituted
      assert(
        llvmIr.contains("call i64 @llvm.smax.i64(i64") &&
          llvmIr.contains("i64 10") && llvmIr.contains("i64 20"),
        s"should emit inline intrinsic call with both args, got:\n$llvmIr"
      )
    }
  }
