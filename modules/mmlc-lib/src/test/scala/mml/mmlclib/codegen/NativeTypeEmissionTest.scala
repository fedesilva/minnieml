package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.compiler.CodegenStage
import mml.mmlclib.test.BaseEffFunSuite

class NativeTypeEmissionTest extends BaseEffFunSuite:

  test("emits LLVM type definition for native struct") {
    val source =
      """
      type MySize = @native[t=i64];
      type MyCharPtr = @native[t=*i8];
      type MyString = @native { length: MySize, data: MyCharPtr };
      """

    compileAndGenerate(source).map { llvmIr =>
      // Fields are in declaration order: length, data
      assert(llvmIr.contains("%struct.MyString = type { i64, i8* }"))
    }
  }

  test("fails when struct field references undefined type") {
    val source = """
      type MyStruct = @native {
        field: UndefinedType
      };
    """

    FrontEndApi.compile(source, "Test").value.flatMap {
      case Right(state) =>
        if state.errors.nonEmpty then
          IO(
            assert(
              state.errors.exists(_.toString.contains("UndefinedTypeRef")) ||
                state.errors.exists(_.toString.contains("UndefinedType"))
            )
          )
        else
          // If compilation succeeded, let's try codegen to see if it fails there
          val validated = CodegenStage.process(state)
          if validated.hasErrors then
            IO(
              assert(
                validated.errors.exists(_.toString.contains("Unresolved type reference")) ||
                  validated.errors.exists(_.toString.contains("UndefinedType"))
              )
            )
          else
            CodegenStage.processIrOnly(validated).map { codegenState =>
              codegenState.llvmIr match
                case Some(_) =>
                  fail("Expected codegen to fail with undefined type reference")
                case None =>
                  assert(
                    codegenState.errors.exists(_.toString.contains("Unresolved type reference")) ||
                      codegenState.errors.exists(_.toString.contains("UndefinedType"))
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
      type BaseInt = @native[t=i32];
      type MyInt = BaseInt;

      type MyStruct = @native {
        value: MyInt
      };
    """

    compileAndGenerate(source).map { llvmIr =>
      // Should emit the struct with the resolved native type (i32, not MyInt)
      assert(llvmIr.contains("%struct.MyStruct = type { i32 }"))
    }
  }
