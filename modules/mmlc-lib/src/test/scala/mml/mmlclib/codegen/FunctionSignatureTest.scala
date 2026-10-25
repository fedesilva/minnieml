package mml.mmlclib.codegen

import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.llvm.ClosureAssertions

import scala.util.matching.Regex

class FunctionSignatureTest extends BaseEffFunSuite with ClosureAssertions:

  private case class UnaryClosure(argument: String, environment: String, body: String)
  private case class ForwardedCall(result: String, entry: String)

  private def unaryClosure(ir: String, entry: String): UnaryClosure =
    val signature = uniqueMatch(
      ir,
      s"define internal i32 @${Regex.quote(entry)}\\(i32 (?<argument>%[^,]+), " +
        "ptr[^\\n]* (?<environment>%[^)]+)\\) #[0-9]+ \\{"
    )
    UnaryClosure(
      signature.group("argument"),
      signature.group("environment"),
      functionBody(ir, entry)
    )

  private def assertUnaryForwarder(ir: String, entry: String, target: String): Unit =
    val closure = unaryClosure(ir, entry)
    uniqueMatch(
      closure.body,
      s"(?<result>%[^ ]+) = call i32 @${Regex.quote(target)}" +
        s"\\(i32 ${Regex.quote(closure.argument)}\\)\\n  ret i32 \\k<result>"
    )
    assert(!closure.body.contains(closure.environment), closure.body)

  test("synthesized main passes argv as StringArray for unit-returning main(args)") {
    val source =
      """
        pub fn main(args: StringArray): Unit = println (int_to_str (ar_str_len args));;
      """
    val config =
      CompilerConfig.exe("build", targetTriple = Some("x86_64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>
      assert(llvmIr.contains("define i32 @main(i32 %0, ptr %1) #0"))
      assert(llvmIr.contains("call { i32, i64 } @mml_args_to_array(i32 %0, ptr %1)"))
      assert(llvmIr.contains("call void @test_main(%struct.StringArray %"))
      assert(llvmIr.contains("call void @__free_StringArray(i32 %"))
      assert(llvmIr.contains("call void @mml_sys_flush()"))
    }
  }

  test("synthesized main passes argv as StringArray for int-returning main(args)") {
    val source =
      """
        pub fn main(args: StringArray): Int = ar_str_len args;;
      """
    val config =
      CompilerConfig.exe("build", targetTriple = Some("x86_64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>
      assert(llvmIr.contains("define i32 @main(i32 %0, ptr %1) #0"))
      assert(llvmIr.contains("call { i32, i64 } @mml_args_to_array(i32 %0, ptr %1)"))
      assert(llvmIr.contains("call i32 @test_main(%struct.StringArray %"))
      assert(llvmIr.contains("call void @__free_StringArray(i32 %"))
      assert("ret i32 %\\d+".r.findFirstIn(llvmIr).nonEmpty)
    }
  }

  test("correctly generates signatures for native and regular functions on x86_64") {
    val source =
      """
        fn debug_print (a: String): Unit = @metal:ffi;;
        fn log_message (a: String): Unit = @metal:ffi;;
        fn join_strings(a: String, b: String): String = @metal:ffi;;
        pub fn main() = log_message "Fede";;
      """

    // Use Binary mode to generate synthesized main entry point
    val config =
      CompilerConfig.exe("build", targetTriple = Some("x86_64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>

      // Native functions keep original names (declarations)
      // String occupies two integer eightbytes on x86_64 System V.
      assert(
        llvmIr.contains("declare void @debug_print(i32, i64)"),
        s"debug_print declaration, got:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare void @log_message(i32, i64)"),
        s"log_message declaration, got:\n$llvmIr"
      )
      // join_strings: two String params (each decomposed) + struct return
      assert(
        llvmIr.contains(
          "declare { i32, i64 } @join_strings(i32, i64, i32, i64)"
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
        fn debug_print (a: String): Unit = @metal:ffi;;
        fn log_message (a: String): Unit = @metal:ffi;;
        fn join_strings(a: String, b: String): String = @metal:ffi;;
        pub fn main() = log_message "Fede";;
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
      // join_strings uses a two-word register image for its result and arguments.
      assert(
        llvmIr.contains(
          "declare [2 x i64] @join_strings([2 x i64], [2 x i64])"
        ),
        s"join_strings should return its register image on aarch64, got:\n$llvmIr"
      )
    }
  }

  test("aarch64 native calls with String struct args use flattened array") {
    val source =
      """
        fn debug_print (a: String): Unit = @metal:ffi;;
        pub fn main(): Unit = debug_print "hi";;
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
        type F32 = @metal:repr[t=float];
        type F64 = @metal:repr[t=double];

        type Vec3d = @metal:repr { x: F64, y: F64, z: F64 };
        type Vec4f = @metal:repr { x: F32, y: F32, z: F32, w: F32 };

        fn hfa_arg_d (v: Vec3d): Int = @metal:ffi;;
        fn hfa_ret_d (): Vec3d = @metal:ffi;;
        fn hfa_arg_f (v: Vec4f): Int = @metal:ffi;;
        fn hfa_ret_f (): Vec4f = @metal:ffi;;

        pub fn main(): Unit = ();;
      """

    val config =
      CompilerConfig.exe("build", targetTriple = Some("aarch64-apple-macosx"))

    compileAndGenerate(source, config = config).map { llvmIr =>
      assert(
        llvmIr.contains("declare i32 @hfa_arg_d([3 x double])"),
        s"HFA double arg should not be byval/split:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare [3 x double] @hfa_ret_d()"),
        s"HFA double return should not use sret:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare i32 @hfa_arg_f([4 x float])"),
        s"HFA float arg should not be byval/split:\n$llvmIr"
      )
      assert(
        llvmIr.contains("declare [4 x float] @hfa_ret_f()"),
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
        op ** (a: Int, b: Int) 80 left = a * b;;
        fn main(): Int = 2 ** 3;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Definition should use module-prefixed mangled name
      assert(
        llvmIr.contains("define internal i32 @test_op.star_star.2(i32 %0, i32 %1)"),
        s"operator definition should use module-prefixed mangled name, got:\n$llvmIr"
      )
      // Call should use module-prefixed mangled name
      assert(
        llvmIr.contains("call i32 @test_op.star_star.2("),
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
        op ! (a: Int) 90 left = a * a;;
        fn main(): Int = !5;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Definition should use module-prefixed mangled name
      assert(
        llvmIr.contains("define internal i32 @test_op.bang.1(i32 %0)"),
        s"unary operator definition should use module-prefixed mangled name, got:\n$llvmIr"
      )
      // Call should use module-prefixed mangled name
      assert(
        llvmIr.contains("call i32 @test_op.bang.1("),
        s"unary operator call should use module-prefixed mangled name, got:\n$llvmIr"
      )
    }
  }

  test("partial application generates a function via eta-expansion") {
    val source =
      """
        let greet = concat "Hello, ";
        fn main(): Unit = println (greet "world");;
      """

    compileAndGenerate(source).map { llvmIr =>
      // greet should be defined as a function with module prefix
      assert(
        llvmIr.contains("define internal %struct.String @test_greet(%struct.String"),
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
        fn add3(a: Int, b: Int, c: Int): Int = a + b + c;;
        let add10 = add3 10;
        fn main(): Int = add10 20 5;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // add10 should be a function with 2 params and module prefix
      assert(
        llvmIr.contains("define internal i32 @test_add10(i32 %0, i32 %1)"),
        s"partial should have 2 params with module prefix, got:\n$llvmIr"
      )
      // main should call add10 with module prefix
      assert(
        llvmIr.contains("call i32 @test_add10("),
        s"main should call test_add10, got:\n$llvmIr"
      )
    }
  }

  test("function aliasing via eta-expansion") {
    val source =
      """
        fn add(a: Int, b: Int): Int = a + b;;
        let f = add;
        fn main(): Int = f 1 2;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // f should be a wrapper function with module prefix
      assert(
        llvmIr.contains("define internal i32 @test_f(i32 %0, i32 %1)"),
        s"alias should be eta-expanded to a module-prefixed function, got:\n$llvmIr"
      )
      // alias function should call original (also module-prefixed)
      assert(
        llvmIr.contains("call i32 @test_add("),
        s"alias function should call module-prefixed original, got:\n$llvmIr"
      )
    }
  }

  test("repeated higher-order named function arguments preserve both calls") {
    val source =
      """
        fn apply(f: Int -> Int): Int = f 1;;
        fn inc(x: Int): Int = x + 1;;
        fn main(): Int = (apply inc) + (apply inc);;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val calls =
        ("(?<result>%[^ ]+) = call i32 @test_apply\\(\\{ ptr, ptr \\} " +
          s"$nullEnvironmentClosure\\)").r
          .findAllMatchIn(mainBody)
          .map(call => ForwardedCall(call.group("result"), call.group("entry")))
          .toList

      assertEquals(calls.size, 2, mainBody)
      calls.foreach(call => assertUnaryForwarder(llvmIr, call.entry, "test_inc"))
      val first  = calls.head
      val second = calls.last
      uniqueMatch(
        mainBody,
        s"(?<sum>%[^ ]+) = add i32 ${Regex.quote(first.result)}, " +
          s"${Regex.quote(second.result)}\\n  ret i32 \\k<sum>"
      )
      assert(!mainBody.contains("load { ptr, ptr }"), mainBody)
    }
  }

  test("mixed direct and higher-order top-level function uses keep both call shapes") {
    val source =
      """
        fn apply(f: Int -> Int, n: Int): Int = f n;;
        fn inc(x: Int): Int = x + 1;;
        fn main(): Int = (inc 1) + (apply inc 2);;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val direct = uniqueMatch(
        mainBody,
        "(?<result>%[^ ]+) = call i32 @test_inc\\(i32 1\\)"
      )
      val higherOrder = uniqueMatch(
        mainBody,
        "(?<result>%[^ ]+) = call i32 @test_apply\\(\\{ ptr, ptr \\} " +
          s"$nullEnvironmentClosure, i32 2\\)"
      )

      assertUnaryForwarder(llvmIr, higherOrder.group("entry"), "test_inc")
      uniqueMatch(
        mainBody,
        s"(?<sum>%[^ ]+) = add i32 ${Regex.quote(direct.group("result"))}, " +
          s"${Regex.quote(higherOrder.group("result"))}\\n  ret i32 \\k<sum>"
      )
      assert(!mainBody.contains("load { ptr, ptr }"), mainBody)
    }
  }

  test("global function-valued bindings call through the indirect fat-pointer path") {
    val source =
      """
        fn inc(x: Int): Int = x + 1;;
        let chooser = if true then inc; else { x: Int -> x + 10; }; ;
        fn main(): Int = chooser 41;;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBodyMatching(llvmIr, "test_main\\(\\) #0")

      assert(
        llvmIr.contains("@test_chooser = global { ptr, ptr }"),
        s"chooser should still lower as a fat-pointer global. IR:\n$llvmIr"
      )
      assert(
        !mainBody.contains("call i32 @test_chooser("),
        s"Global function-valued binding must not be called as a direct symbol. Body:\n$mainBody"
      )
      assert(
        mainBody.contains("load { ptr, ptr }, ptr @test_chooser") &&
          mainBody.contains("extractvalue { ptr, ptr } %") &&
          """call i32 %\d+\(i32 41, ptr %\d+\)""".r.findFirstIn(mainBody).nonEmpty,
        s"Global function-valued binding should load and call through the fat pointer. Body:\n$mainBody"
      )
    }
  }

  test("chained partial application") {
    val source =
      """
        fn add3(a: Int, b: Int, c: Int): Int = a + b + c;;
        let add10 = add3 10;
        let add10and20 = add10 20;
        fn main(): Int = add10and20 5;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // add10 should be a function with 2 params and module prefix
      assert(
        llvmIr.contains("define internal i32 @test_add10(i32 %0, i32 %1)"),
        s"add10 should have 2 params with module prefix, got:\n$llvmIr"
      )
      // add10and20 should be a function with 1 param and module prefix
      assert(
        llvmIr.contains("define internal i32 @test_add10and20(i32 %0)"),
        s"add10and20 should have 1 param with module prefix, got:\n$llvmIr"
      )
      // add10and20 should call add10 with module prefix
      assert(
        llvmIr.contains("call i32 @test_add10("),
        s"add10and20 should call test_add10, got:\n$llvmIr"
      )
    }
  }

  test("curried application - multi-param function called with all args") {
    val source =
      """
        fn add3(a: Int, b: Int, c: Int): Int = a + b + c;;
        fn main(): Int = add3 1 2 3;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // main should call add3 with module prefix and 3 arguments
      assert(
        llvmIr.contains("call i32 @test_add3(i32 1, i32 2, i32 3)"),
        s"main should call test_add3 with all args, got:\n$llvmIr"
      )
    }
  }

  test("curried application - function calling another with params") {
    val source =
      """
        fn helper(a: Int, b: Int): Int = a + b;;
        fn wrapper(x: Int): Int = helper x 10;;
        fn main(): Int = wrapper 5;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // wrapper should call helper with module prefix, x and 10
      assert(
        llvmIr.contains("call i32 @test_helper(i32 %0, i32 10)"),
        s"wrapper should call test_helper with param and literal, got:\n$llvmIr"
      )
    }
  }

  test("function with native template emits inline LLVM IR") {
    val source =
      """
        fn ctpop(x: Int): Int = @metal:tpl["call i32 @llvm.ctpop.i32(i32 %operand)"];;
        fn main(): Int = ctpop 255;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Should emit inline call to intrinsic, not a function call to ctpop
      assert(
        llvmIr.contains("call i32 @llvm.ctpop.i32(i32"),
        s"should emit inline intrinsic call, got:\n$llvmIr"
      )
      // Should NOT generate a function definition for ctpop
      assert(
        !llvmIr.contains("define i32 @test_ctpop"),
        s"should NOT define ctpop as a function, got:\n$llvmIr"
      )
    }
  }

  test("function with native template and multiple args uses operand1, operand2") {
    val source =
      """
        fn mymax(a: Int, b: Int): Int = @metal:tpl["call i32 @llvm.smax.i32(i32 %operand1, i32 %operand2)"];;
        fn main(): Int = mymax 10 20;;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Should emit inline call with both operands substituted
      assert(
        llvmIr.contains("call i32 @llvm.smax.i32(i32") &&
          llvmIr.contains("i32 10") && llvmIr.contains("i32 20"),
        s"should emit inline intrinsic call with both args, got:\n$llvmIr"
      )
    }
  }

  test("noalias on native function returning allocating NativePointer") {
    val source =
      """
        type MyPtr = @metal:repr[t=*i8, mem=heap];
        fn alloc_ptr(): MyPtr = @metal:ffi;;
        fn main(): Unit = ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("declare noalias i8* @alloc_ptr()"),
        s"allocating pointer return should have noalias, got:\n$llvmIr"
      )
    }
  }

  test("no noalias on native function returning non-allocating NativePointer") {
    val source =
      """
        type MyPtr = @metal:repr[t=*i8];
        fn get_ptr(): MyPtr = @metal:ffi;;
        fn main(): Unit = ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      // get_ptr returns a pointer but has no mem=heap, so no noalias
      assert(
        llvmIr.contains("declare i8* @get_ptr()"),
        s"non-allocating pointer return should NOT have noalias, got:\n$llvmIr"
      )
    }
  }

  test("noalias on native function returning allocating opaque ptr") {
    val source =
      """
        type MyOpaque = @metal:repr[t=ptr, mem=heap];
        fn alloc_ptr(): MyOpaque = @metal:ffi;;
        fn main(): Unit = ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("declare noalias ptr @alloc_ptr()"),
        s"allocating opaque ptr return should have noalias, got:\n$llvmIr"
      )
    }
  }

  test("no noalias on native function returning non-allocating opaque ptr") {
    val source =
      """
        type MyOpaque = @metal:repr[t=ptr];
        fn get_ptr(): MyOpaque = @metal:ffi;;
        fn main(): Unit = ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("declare ptr @get_ptr()"),
        s"non-allocating opaque ptr return should NOT have noalias, got:\n$llvmIr"
      )
    }
  }

  test("no noalias on native function returning NativeStruct with Alloc") {
    val source =
      """
        fn join_strings(a: String, b: String): String = @metal:ffi;;
        fn main(): Unit = ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      // String is NativeStruct (passed as {i32, i8*}), not a pointer — no noalias on return
      assert(
        !llvmIr.contains("noalias %struct.String @join_strings"),
        s"String return should NOT have noalias, got:\n$llvmIr"
      )
    }
  }

  test("noalias on consuming NativePointer parameter in user function") {
    val source =
      """
        type MyPtr = @metal:repr[t=*i8, mem=heap];
        fn alloc_ptr(): MyPtr = @metal:ffi[mem=alloc];;
        fn free_ptr(~p: MyPtr): Unit = @metal:ffi;;
        fn take_ptr(~p: MyPtr): Unit = free_ptr p;;
        fn main(): Unit = take_ptr (alloc_ptr ());;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("define internal void @test_take_ptr(i8* noalias %0)"),
        s"consuming pointer param should have noalias, got:\n$llvmIr"
      )
    }
  }

  test("no noalias on consuming NativeStruct parameter") {
    val source =
      """
        fn consume_str(~s: String): Unit = println s;;
        fn main(): Unit = consume_str "hello";;
      """

    compileAndGenerate(source).map { llvmIr =>
      // String is a NativeStruct ({i32, ptr}), not a pointer type
      // The user function definition should NOT have noalias on the struct param
      assert(
        llvmIr.contains("define internal void @test_consume_str(%struct.String %0)"),
        s"consuming String param should NOT have noalias, got:\n$llvmIr"
      )
    }
  }

  test("no noalias on non-consuming NativePointer parameter") {
    val source =
      """
        type MyPtr = @metal:repr[t=*i8, mem=heap];
        fn alloc_ptr(): MyPtr = @metal:ffi;;
        fn read_ptr(p: MyPtr): Unit = @metal:ffi;;
        fn main(): Unit = read_ptr (alloc_ptr ());;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Only alloc_ptr gets noalias (on return), read_ptr should not
      assert(
        llvmIr.contains("declare noalias i8* @alloc_ptr()"),
        s"alloc_ptr should have noalias return, got:\n$llvmIr"
      )
      // read_ptr declaration should NOT have noalias on param
      assert(
        llvmIr.contains("declare void @read_ptr(i8*)"),
        s"non-consuming pointer param should NOT have noalias, got:\n$llvmIr"
      )
    }
  }

  test("noalias on consuming opaque ptr parameter in user function") {
    val source =
      """
        type MyOpaque = @metal:repr[t=ptr, mem=heap];
        fn alloc_ptr(): MyOpaque = @metal:ffi[mem=alloc];;
        fn free_ptr(~p: MyOpaque): Unit = @metal:ffi;;
        fn take_ptr(~p: MyOpaque): Unit = free_ptr p;;
        fn main(): Unit = take_ptr (alloc_ptr ());;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("define internal void @test_take_ptr(ptr noalias %0)"),
        s"consuming opaque ptr param should have noalias, got:\n$llvmIr"
      )
    }
  }

  test("native fn with name attribute declares and calls the native symbol") {
    val source =
      """
        fn clone_string(s: String): String = @metal:ffi[name="__clone_String", mem=alloc];;
        fn main(): String = clone_string "hello";;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("declare") && llvmIr.contains("@__clone_String("),
        s"declaration should use native symbol __clone_String, got:\n$llvmIr"
      )
      assert(
        llvmIr.contains("call") && llvmIr.contains("@__clone_String("),
        s"call should use native symbol __clone_String, got:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("@clone_string("),
        s"should NOT contain MML name clone_string as LLVM symbol, got:\n$llvmIr"
      )
    }
  }

  test("native fn without name attribute still uses binding name") {
    val source =
      """
        fn debug_print(a: String): Unit = @metal:ffi;;
        fn main(): Unit = debug_print "hi";;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("declare void @debug_print("),
        s"declaration should use binding name debug_print, got:\n$llvmIr"
      )
      assert(
        llvmIr.contains("call void @debug_print("),
        s"call should use binding name debug_print, got:\n$llvmIr"
      )
    }
  }

  test("stdlib str_eq is declared and called with the expected native symbol") {
    val source =
      """
        fn main(): Bool = str_eq "a" "b";;
      """

    compileAndGenerate(source).map { llvmIr =>
      // Boolean return extension depends on the target C ABI; this test checks symbol routing.
      assert(
        """declare (?:zeroext )?i1 @str_eq\(""".r.findFirstIn(llvmIr).nonEmpty,
        s"declaration should use stdlib symbol str_eq, got:\n$llvmIr"
      )
      assert(
        """call (?:zeroext )?i1 @str_eq\(""".r.findFirstIn(llvmIr).nonEmpty,
        s"call should use stdlib symbol str_eq, got:\n$llvmIr"
      )
    }
  }

  test("non-pub function emits internal linkage in IR") {
    val source =
      """
        fn helper(): Int = 42;;
        fn main(): Int = helper ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("define internal i32 @test_helper()"),
        s"non-pub function should have internal linkage, got:\n$llvmIr"
      )
    }
  }

  test("nested capturing lambda forwards the captured callable environment") {
    val source =
      """
        pub fn outer(a: Int): Int =
          let f = { x: Int -> x + a; };
          let g = { y: Int -> f y; };
          g 1;
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val outerBody = functionBody(llvmIr, "test_outer")
      val outerCall = closureInvocation(outerBody, returnType = "i32", argumentPattern = "i32 1")
      val nested    = unaryClosure(llvmIr, outerCall.group("entry"))
      val callableCapture = assertCaptureField(outerBody, nested.body, "{ ptr, ptr }")
      assertEquals(callableCapture.consumerEnvironment, nested.environment)

      val capturedCall = closureInvocation(
        nested.body,
        returnType      = "i32",
        argumentPattern = s"i32 ${Regex.quote(nested.argument)}",
        pairPattern     = Regex.quote(callableCapture.loadedValue)
      )
      assertReturnedNext(nested.body, capturedCall, "i32")

      val captured      = unaryClosure(llvmIr, capturedCall.group("entry"))
      val scalarCapture = assertCaptureField(outerBody, captured.body, "i32")
      val capturedValue = closureValue(
        outerBody,
        capturedCall.group("entry"),
        scalarCapture.ownerEnvironment
      )
      assertEquals(scalarCapture.storedValue, "%0")
      assertEquals(scalarCapture.consumerEnvironment, captured.environment)
      assertEquals(capturedValue, callableCapture.storedValue)
      uniqueMatch(
        captured.body,
        s"(?<sum>%[^ ]+) = add i32 ${Regex.quote(captured.argument)}, " +
          s"${Regex.quote(scalarCapture.loadedValue)}\\n  ret i32 \\k<sum>"
      )

      val nestedValue = closureValue(
        outerBody,
        outerCall.group("entry"),
        callableCapture.ownerEnvironment
      )
      assertEquals(nestedValue, outerCall.group("pair"))
      assertCalls(outerBody, "malloc", 0)
    }
  }

  test("loopified closure calls its captured sibling with the saved environment") {
    val source =
      """
        pub fn main(): Unit =
          let base = 10;

          fn sibling(x: Int): Int =
            x + base;
          ;

          fn loop(i: Int): Int =
            if i > 0 then
              let y = sibling i;
              loop (i - 1);
            else
              0;
            ;
          ;

          println (int_to_str (loop 2));
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody  = functionBody(llvmIr, "test_main")
      val loopCall  = closureInvocation(mainBody, returnType = "i32", argumentPattern = "i32 2")
      val loopEntry = loopCall.group("entry")
      val loop      = unaryClosure(llvmIr, loopEntry)
      val siblingCapture = assertCaptureField(mainBody, loop.body, "{ ptr, ptr }")
      val loopValue      = closureValue(mainBody, loopEntry, siblingCapture.ownerEnvironment)
      assertEquals(siblingCapture.consumerEnvironment, loop.environment)
      assertEquals(loopValue, loopCall.group("pair"))

      val iteration = uniqueMatch(
        loop.body,
        "(?<current>%[^ ]+) = phi i32 \\[ " + Regex.quote(loop.argument) +
          ", %entry \\], \\[ (?<next>%[^,]+), %[^ ]+ \\]"
      )
      val siblingCall = closureInvocation(
        loop.body,
        returnType      = "i32",
        argumentPattern = s"i32 ${Regex.quote(iteration.group("current"))}",
        pairPattern     = Regex.quote(siblingCapture.loadedValue)
      )
      val sibling = unaryClosure(llvmIr, siblingCall.group("entry"))
      val scalarField = uniqueMatch(
        sibling.body,
        "(?<field>%[^ ]+) = getelementptr (?<layout>%struct\\.[^,]+), " +
          s"ptr ${Regex.quote(sibling.environment)}, i32 0, i32 (?<index>[0-9]+)\\n" +
          "  (?<value>%[^ ]+) = load i32, ptr \\k<field>"
      )
      val siblingPair = closurePair(mainBody, siblingCall.group("entry"))
      assertEquals(siblingPair.value, siblingCapture.storedValue)
      val siblingEnvironment = siblingPair.environment
      uniqueMatch(
        mainBody,
        s"(?<field>%[^ ]+) = getelementptr ${Regex.quote(scalarField.group("layout"))}, " +
          s"ptr ${Regex.quote(siblingEnvironment)}, i32 0, i32 ${scalarField.group("index")}\\n" +
          "  store i32 10, ptr \\k<field>"
      )
      uniqueMatch(
        sibling.body,
        s"(?<sum>%[^ ]+) = add i32 ${Regex.quote(sibling.argument)}, " +
          s"${Regex.quote(scalarField.group("value"))}\\n  ret i32 \\k<sum>"
      )

      assertEquals(phiCount(loop.body), 1)
      assertPhiPredecessors(loop.body)
      assertCalls(loop.body, loopEntry, 0)
      assert(
        loop.body.contains(
          s"${iteration.group("next")} = sub i32 ${iteration.group("current")}, 1"
        ),
        loop.body
      )
      assertCalls(mainBody, "malloc", 0)
    }
  }

  test("move lambda clones a heap literal into the invoked environment") {
    val source =
      """
        pub fn main(): Unit =
          let msg = "hello";
          let greet = ~{ println msg; };
          greet ();
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody  = functionBody(llvmIr, "test_main")
      val entry     = closureEntry(mainBody)
      val greetBody = functionBody(llvmIr, entry)
      val capture   = assertCaptureField(mainBody, greetBody, "%struct.String")
      val cloneCall = uniqueMatch(
        mainBody,
        "(?<result>%[^ ]+) = call %struct\\.String @(?<entry>[^ (]+)" +
          "\\(%struct\\.String (?<literal>%[^)]+)\\)"
      )

      assertEquals(capture.storedValue, cloneCall.group("result"))
      assertCalls(expandNativeAdapters(llvmIr, cloneCall.matched), "__clone_String", 1)
      assertCalls(expandNativeAdapters(llvmIr, mainBody), "__clone_String", 1)
      val printCall = uniqueMatch(
        greetBody,
        "call void @(?<entry>[^ (]+)" +
          s"\\(%struct\\.String ${Regex.quote(capture.loadedValue)}\\)"
      )
      assertCalls(expandNativeAdapters(llvmIr, printCall.matched), "println", 1)
      assertCalls(expandNativeAdapters(llvmIr, greetBody), "__clone_String", 0)
      assertCalls(expandNativeAdapters(llvmIr, greetBody), "__free_String", 0)

      val owned = assertOwnedEnvironment(llvmIr, mainBody, "%struct.String", "__free_String")
      assertCaptureLifetime(mainBody, capture, entry, owned.destructor)
    }
  }

  test("pub function emits no linkage qualifier in IR") {
    val source =
      """
        pub fn exported(): Int = 42;;
        fn main(): Int = exported ();;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(
        llvmIr.contains("define i32 @test_exported()"),
        s"pub function should have no internal linkage, got:\n$llvmIr"
      )
      assert(
        !llvmIr.contains("define internal i32 @test_exported()"),
        s"pub function must not have internal linkage, got:\n$llvmIr"
      )
    }
  }

  test(
    "higher-order named function arguments lower as function values, not symbol loads"
  ) {
    val source =
      """
        fn apply(f: Int -> Int): Int = f 1;;
        fn inc(x: Int): Int = x + 1;;
        fn main(): Int = apply inc;;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val entry = uniqueMatch(
        mainBody,
        s"call i32 @test_apply\\(\\{ ptr, ptr \\} $nullEnvironmentClosure\\)"
      ).group("entry")

      assertUnaryForwarder(llvmIr, entry, "test_inc")
      assert(!mainBody.contains("load { ptr, ptr }"), mainBody)
      val applyBody  = functionBody(llvmIr, "test_apply")
      val invocation = indirectClosureInvocation(applyBody, "%0", "i32", "i32 1")
      assertReturnedNext(applyBody, invocation, "i32")
    }
  }

  test("local direct-only call uses a plain entry") {
    val source =
      """
        fn main(): Int =
          let f = { x: Int -> x + 1; };
          f 41;
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      val mainBody = functionBody(llvmIr, "test_main")
      val target = uniqueMatch(
        mainBody,
        "(?<result>%[^ ]+) = call i32 @(?<entry>[^ (]+)\\(i32 41\\)\\n" +
          "  ret i32 \\k<result>"
      ).group("entry")
      val entryBody = functionBodyMatching(llvmIr, s"${Regex.quote(target)}\\(i32 %0\\).*")

      uniqueMatch(entryBody, "(?<result>%[^ ]+) = add i32 %0, 1\\n  ret i32 \\k<result>")
      assert(!mainBody.contains("{ ptr, ptr }"), mainBody)
      assert(!mainBody.contains("ptr null"), mainBody)
    }
  }

  test("shadowed local callable args do not eta-expand from top-level names") {
    val source =
      """
        fn apply2(f: Int -> Int -> Int): Int = f 1 2;;
        fn inc(x: Int): Int = x + 1;;
        fn add(a: Int, b: Int): Int = a + b;;
        fn forward(inc: Int -> Int -> Int): Int = apply2 inc;;
        fn main(): Int = forward add;;
      """

    compileAndGenerate(source).map { llvmIr =>
      val forwardBody = functionBody(llvmIr, "test_forward")
      val mainBody    = functionBody(llvmIr, "test_main")
      val entry = uniqueMatch(
        mainBody,
        s"call i32 @test_forward\\(\\{ ptr, ptr \\} $nullEnvironmentClosure\\)"
      ).group("entry")
      val entryBody = functionBodyMatching(
        llvmIr,
        s"${Regex.quote(entry)}\\(i32 %0, i32 %1, ptr %2\\).*"
      )

      uniqueMatch(
        forwardBody,
        "(?<result>%[^ ]+) = call i32 @test_apply2\\(\\{ ptr, ptr \\} %0\\)\\n" +
          "  ret i32 \\k<result>"
      )
      assert(!forwardBody.contains("@test_inc"), forwardBody)
      uniqueMatch(
        entryBody,
        "(?<result>%[^ ]+) = call i32 @test_add\\(i32 %0, i32 %1\\)\\n" +
          "  ret i32 \\k<result>"
      )
      assert(!entryBody.contains("%2"), entryBody)
    }
  }
