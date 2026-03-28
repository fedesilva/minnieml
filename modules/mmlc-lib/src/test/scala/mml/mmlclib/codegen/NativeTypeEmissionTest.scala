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
          val validated = CodegenStage.validate(state)
          if validated.hasErrors then
            IO(
              assert(
                validated.errors.exists(_.toString.contains("Unresolved type reference")) ||
                  validated.errors.exists(_.toString.contains("UndefinedType"))
              )
            )
          else
            CodegenStage.emitIrOnly(validated).map { codegenState =>
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

  test("opaque ptr globals lower without ptr* loads or stores") {
    val source = """
      type Opaque = @native[t=ptr];
      fn mk_opaque(): Opaque = @native;;
      let handle = mk_opaque ();
      fn read_handle(): Opaque = handle;;
    """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("declare ptr @mk_opaque()"), s"missing ptr return:\n$llvmIr")
      assert(
        llvmIr.contains("@test_handle = global ptr zeroinitializer"),
        s"missing ptr global:\n$llvmIr"
      )
      assert(
        llvmIr.contains("store ptr %0, ptr @test_handle"),
        s"missing ptr global store:\n$llvmIr"
      )
      assert(
        llvmIr.contains("load ptr, ptr @test_handle"),
        s"missing ptr global load:\n$llvmIr"
      )
      assert(!llvmIr.contains("ptr*"), s"opaque ptr lowering must not emit ptr*:\n$llvmIr")
    }
  }
