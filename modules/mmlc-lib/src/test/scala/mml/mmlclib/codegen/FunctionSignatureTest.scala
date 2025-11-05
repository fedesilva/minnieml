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
      assert(llvmIr.contains("define void @main()"), "main definition")
      assert(llvmIr.contains("ret void"), "main return")
    }
  }
