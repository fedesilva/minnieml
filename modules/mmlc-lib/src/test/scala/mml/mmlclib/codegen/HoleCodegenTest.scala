package mml.mmlclib.codegen

import mml.mmlclib.test.BaseEffFunSuite

class HoleCodegenTest extends BaseEffFunSuite:

  test("emits runtime hole call") {
    val source =
      """
        fn main(): Int =
          ???
        ;
      """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("declare void @__mml_sys_hole(i64, i64, i64, i64)"))
      assert(llvmIr.contains("call void @__mml_sys_hole"))
    }
  }
