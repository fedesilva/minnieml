package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class FunctionSignatureTest extends BaseEffFunSuite:

  test("correctly generates signatures for native and regular functions") {
    val source =
      """
        fn print (a: String): () = @native;
        fn println (a: String): () = @native;
        fn concat(a: String, b: String): String = @native;
        fn main(): () = println "Fede";
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("declare void @print(%String)"), "print declaration")
      assert(llvmIr.contains("declare void @println(%String)"), "println declaration")
      assert(llvmIr.contains("declare %String @concat(%String, %String)"), "concat declaration")
      assert(llvmIr.contains("define void @main()"), "main definition")
      assert(llvmIr.contains("ret void"), "main return")
    }
  }
