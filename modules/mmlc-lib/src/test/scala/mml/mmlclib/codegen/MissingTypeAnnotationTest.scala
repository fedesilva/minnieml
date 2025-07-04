package mml.mmlclib.codegen

import mml.mmlclib.api.{CodeGenApi, CompilerApi}
import mml.mmlclib.test.BaseEffFunSuite

class MissingTypeAnnotationTest extends BaseEffFunSuite {

  test("should fail with clear error when function return type annotation is missing") {
    val source = """
      fn main() = 42;
    """

    CompilerApi.compileString(source, Some("Test")).value.flatMap {
      case Right(compiled) =>
        CodeGenApi.generateFromModule(compiled).value.map {
          case Left(error) =>
            val errorMsg = error.toString
            assert(errorMsg.contains("Missing return type annotation for function 'main'"), s"Expected error message but got: $errorMsg")
          case Right(_) =>
            fail("Expected codegen to fail with missing type annotation error")
        }
      case Left(error) =>
        fail(s"Compilation should succeed but codegen should fail: $error")
    }
  }

  test("should fail with clear error when function parameter type annotation is missing") {
    val source = """
      fn test(x): () = 42;
    """

    CompilerApi.compileString(source, Some("Test")).value.flatMap {
      case Right(compiled) =>
        CodeGenApi.generateFromModule(compiled).value.map {
          case Left(error) =>
            val errorMsg = error.toString
            assert(errorMsg.contains("Missing type for param 'x' in fn 'test'"), s"Expected error message but got: $errorMsg")
          case Right(_) =>
            fail("Expected codegen to fail with missing type annotation error")
        }
      case Left(error) =>
        fail(s"Compilation should succeed but codegen should fail: $error")
    }
  }

  test("should succeed when all type annotations are present") {
    val source = """      
      fn add(x: Int32  y: Int32): Int32 = x + y;
    """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("define i32 @add(i32 %0, i32 %1)"), "Function signature should be correctly generated")
    }
  }
}
