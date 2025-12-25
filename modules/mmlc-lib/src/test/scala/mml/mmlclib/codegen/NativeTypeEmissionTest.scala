package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.api.{CodeGenApi, CompilerApi}
import mml.mmlclib.test.BaseEffFunSuite

class NativeTypeEmissionTest extends BaseEffFunSuite:

  test("emits LLVM type definition for native struct") {
    val source =
      """
      type MySize = @native:i64;
      type MyCharPtr = @native:*i8;
      type MyString = @native:{ length: MySize, data: MyCharPtr };
      """

    compileAndGenerate(source).map { llvmIr =>
      // Fields are in declaration order: length, data
      assert(llvmIr.contains("%MyString = type { i64, i8* }"))
    }
  }

  test("fails when struct field references undefined type") {
    val source = """
      type MyStruct = @native {
        field: UndefinedType
      };
    """

    CompilerApi.compileString(source, "Test").value.flatMap {
      case Right(state) =>
        // If compilation succeeded, let's try codegen to see if it fails there
        CodeGenApi.generateFromModule(state.module).value.map {
          case Right(_) =>
            fail("Expected codegen to fail with undefined type reference")
          case Left(error) =>
            assert(
              error.toString.contains("Unresolved type reference") ||
                error.toString.contains("UndefinedType")
            )
        }
      case Left(error) =>
        // Expected - the semantic phase should catch undefined types
        IO(
          assert(
            error.toString.contains("UndefinedTypeRef") ||
              error.toString.contains("UndefinedType")
          )
        )
    }
  }

  test("handles type alias to native type correctly") {
    val source = """
      type BaseInt = @native:i32;
      type MyInt = BaseInt;
      
      type MyStruct = @native:{
        value: MyInt
      };
    """

    compileAndGenerate(source).map { llvmIr =>
      // Should emit the struct with the resolved native type (i32, not MyInt)
      assert(llvmIr.contains("%MyStruct = type { i32 }"))
    }
  }
