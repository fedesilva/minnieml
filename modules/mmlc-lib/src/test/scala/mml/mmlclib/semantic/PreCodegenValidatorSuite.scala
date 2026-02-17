package mml.mmlclib.semantic

import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.compiler.{CompilerConfig, PreCodegenValidator}
import mml.mmlclib.semantic.SemanticError.InvalidEntryPoint
import mml.mmlclib.test.BaseEffFunSuite

class PreCodegenValidatorSuite extends BaseEffFunSuite:

  test("binary mode requires a main function") {
    val source          = """
      fn foo() = 1;
    """
    val expectedMessage = "No entry point 'main' found for binary compilation"
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          val errorMessages = validatedState.errors.map {
            case InvalidEntryPoint(message, _) => message
            case _ => ""
          }
          assert(errorMessages.contains(expectedMessage))
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("binary mode main function with non-StringArray parameter is not a valid entry point") {
    val source          = """
      fn main(a: Int32) = 1;
    """
    val expectedMessage = "No entry point 'main' found for binary compilation"
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          val errorMessages = validatedState.errors.map {
            case InvalidEntryPoint(message, _) => message
            case _ => ""
          }
          assert(errorMessages.contains(expectedMessage))
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("binary mode main function with StringArray parameter is valid") {
    val source = """
      fn main(args: StringArray): Unit = ();
    """
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          assert(validatedState.errors.isEmpty)
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("binary mode consuming StringArray main parameter is not a valid entry point") {
    val source          = """
      fn main(~args: StringArray): Unit = ();
    """
    val expectedMessage = "No entry point 'main' found for binary compilation"
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          val errorMessages = validatedState.errors.map {
            case InvalidEntryPoint(message, _) => message
            case _ => ""
          }
          assert(errorMessages.contains(expectedMessage))
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("binary mode main function must have Unit or Int64 return type") {
    val source          = """
      fn main(): String = "hello";
    """
    val expectedMessage = "Entry point 'main' must have a return type of 'Unit' or 'Int64'"
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          val errorMessages = validatedState.errors.map {
            case InvalidEntryPoint(message, _) => message
            case _ => ""
          }
          assert(errorMessages.contains(expectedMessage))
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("binary mode main function with Unit return type is valid") {
    val source = """
      fn main(): Unit = ();
    """
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          assert(validatedState.errors.isEmpty)
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("binary mode main function with Int return type is valid") {
    val source = """
      fn main(): Int = 0;
    """
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Exe))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
          assert(validatedState.errors.isEmpty)
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("library mode does not require a main function") {
    val source = """
      fn foo() = 1;
    """
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Library))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Library)(state)
          assert(validatedState.errors.isEmpty)
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("ast mode does not require a main function") {
    val source = """
      fn foo() = 1;
    """
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Ast))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Ast)(state)
          assert(validatedState.errors.isEmpty)
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }

  test("ir mode does not require a main function") {
    val source = """
      fn foo() = 1;
    """
    FrontEndApi
      .compile(source, "Test", CompilerConfig.default.copy(mode = CompilationMode.Ir))
      .value
      .map {
        case Right(state) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Ir)(state)
          assert(validatedState.errors.isEmpty)
        case Left(error) => fail(s"Compilation failed with error: $error")
      }
  }
