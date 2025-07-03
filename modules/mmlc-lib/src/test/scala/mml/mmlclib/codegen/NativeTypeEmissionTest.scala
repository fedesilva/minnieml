package mml.mmlclib.codegen

import cats.effect.IO
import mml.mmlclib.api.{CodeGenApi, CompilerApi}
import mml.mmlclib.test.BaseEffFunSuite

class NativeTypeEmissionTest extends BaseEffFunSuite:

  // Test helper to compile source and generate LLVM IR
  private def compileAndGenerate(source: String): IO[String] =
    CompilerApi.compileString(source, Some("Test")).value.flatMap {
      case Right(compiled) =>
        CodeGenApi.generateFromModule(compiled).value.map {
          case Right(llvmIr) => llvmIr
          case Left(error) => fail(s"CodeGen failed: $error")
        }
      case Left(error) =>
        fail(s"Compilation failed: $error")
    }

  test("emits LLVM type definition for native primitive type") {
    val source = """
      type MySize = @native:i64;
    """

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("%MySize = type i64"))
    }
  }

  test("emits LLVM type definition for native pointer type") {
    val source = """
      type MyCharPtr = @native:*i8;
    """

    compileAndGenerate(source).map { llvmIr =>
      // Debug: print to see what's generated
      println(s"Generated LLVM IR:\n$llvmIr")
      assert(llvmIr.contains("%MyCharPtr = type i8*"))
    }
  }

  test("emits LLVM type definition for native struct") {
    val source =
      """
      type MySize = @native:i64;
      type MyCharPtr = @native:*i8;
      type MyString = @native:{ length: MySize, data: MyCharPtr };
      """

    compileAndGenerate(source).map { llvmIr =>
      println(s"Generated LLVM IR for struct test:\n$llvmIr")
      assert(llvmIr.contains("%MySize = type i64"))
      assert(llvmIr.contains("%MyCharPtr = type i8*"))
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

    CompilerApi.compileString(source, Some("Test")).value.flatMap {
      case Right(module) =>
        // If compilation succeeded, let's try codegen to see if it fails there
        CodeGenApi.generateFromModule(module).value.map {
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

  test("handles type alias to native type correctly".only) {
    val source = """
      type MyInt64 = @native:i64;
      type MyInt = MyInt64;
      type MyStruct = @native:{ value: MyInt };
    """

    // Debug: inspect the AST at each phase
    println("=== Inspecting AST transformations ===")
    mml.mmlclib.util.yolo.rewrite(source, showTypes = true)

    compileAndGenerate(source).map { llvmIr =>
      assert(llvmIr.contains("%MyInt64 = type i64"))
      assert(llvmIr.contains("%MyStruct = type { i64 }"))
    }
  }

  test("no longer emits hardcoded String type definition for simple function") {
    val source = """
      fn main() = 42;
    """

    compileAndGenerate(source).map { llvmIr =>
      // The predefined String type should still be emitted
      // but not from hardcoded logic in CodeGenState
      assert(llvmIr.contains("%String = type { i64, i8* }"))
    }
  }

  // TODO: this is redunculous, we should not emit this crap
  //      when we have a `type X = @native:i8;` what we need to emit
  //      is just the i8. why in hell are we using thins
  test("emits predefined native types from semantic phase") {
    val source = """
      fn dummy() = 1;
    """

    compileAndGenerate(source).map { llvmIr =>
      // These should be emitted from the TypeDefs injected by injectBasicTypes
      assert(llvmIr.contains("%Int64 = type i64"))
      assert(llvmIr.contains("%Float = type float"))
      assert(llvmIr.contains("%Double = type double"))
      assert(llvmIr.contains("%Bool = type i1"))
      assert(llvmIr.contains("%CharPtr = type i8*"))
      assert(llvmIr.contains("%String = type { i64, i8* }"))
      assert(llvmIr.contains("%SizeT = type i64"))
      assert(llvmIr.contains("%Char = type i8"))
    }
  }
