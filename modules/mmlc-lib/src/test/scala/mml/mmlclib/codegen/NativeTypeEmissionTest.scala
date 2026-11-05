package mml.mmlclib.codegen

import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.test.BaseEffFunSuite

class NativeTypeEmissionTest extends BaseEffFunSuite:

  test("emits LLVM type definition for native struct") {
    val source =
      """
      type MySize = @metal:repr[t=i64];
      type MyCharPtr = @metal:repr[t=*i8];
      type MyString = @metal:repr { length: MySize, data: MyCharPtr };
      """

    compileAndGenerate(source).map { llvmIr =>
      // Fields are in declaration order: length, data
      assert(llvmIr.contains("%struct.MyString = type { i64, i8* }"))
    }
  }

  test("fails when struct field references undefined type") {
    val source = """
      type MyStruct = @metal:repr {
        field: UndefinedType
      };
    """

    FrontEndApi.compile(source, "Test").value.map {
      case Right(state) =>
        val errors = state.visibleErrors.collect {
          case error: mml.mmlclib.semantic.SemanticError.UndefinedTypeRef => error
        }
        assertEquals(errors.size, 1)
        assertEquals(errors.head.typeRef.name, "UndefinedType")
        assertEquals(state.visibleErrors.size, 1)
        assertEquals(state.llvmIr, None)
      case Left(error) => fail(s"Expected retained frontend state, got $error")
    }
  }

  test("handles type alias to native type correctly") {
    val source = """
      type BaseInt = @metal:repr[t=i32];
      type MyInt = BaseInt;

      type MyStruct = @metal:repr {
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
      type Opaque = @metal:repr[t=ptr];
      fn mk_opaque(): Opaque = @metal:ffi;;
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
