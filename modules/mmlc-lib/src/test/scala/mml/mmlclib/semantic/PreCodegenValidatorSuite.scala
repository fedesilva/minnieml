package mml.mmlclib.semantic

import mml.mmlclib.api.CompilerApi
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.semantic.SemanticError.InvalidEntryPoint
import mml.mmlclib.test.BaseEffFunSuite

class PreCodegenValidatorSuite extends BaseEffFunSuite:

  test("binary mode requires a main function") {
    val source          = """
      fn foo() = 1;
    """
    val expectedMessage = "No entry point 'main' found for binary compilation"
    CompilerApi.compileState(source, "Test").value.map {
      case Right(state) =>
        val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(state)
        val errorMessages = validatedState.errors.map {
          case InvalidEntryPoint(message, _) => message
          case _ => ""
        }
        assert(errorMessages.contains(expectedMessage))
      case Left(error) => fail(s"Compilation failed with error: $error")
    }
  }

  test("binary mode main function must have no parameters") {
    val source          = """
      fn main(a: Int32) = 1;
    """
    val expectedMessage = "Entry point 'main' must have no parameters"
    CompilerApi.compileState(source, "Test").value.map {
      case Right(state) =>
        val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(state)
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
    CompilerApi.compileState(source, "Test").value.map {
      case Right(state) =>
        val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(state)
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
    CompilerApi.compileState(source, "Test").value.map {
      case Right(state) =>
        val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(state)
        assert(validatedState.errors.isEmpty)
      case Left(error) => fail(s"Compilation failed with error: $error")
    }
  }

  test("binary mode main function with Int return type is valid") {
    val source = """
      fn main(): Int = 0;
    """
    CompilerApi.compileState(source, "Test").value.map {
      case Right(state) =>
        val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(state)
        assert(validatedState.errors.isEmpty)
      case Left(error) => fail(s"Compilation failed with error: $error")
    }
  }

  test("library mode does not require a main function") {
    val source = """
      fn foo() = 1;
    """
    CompilerApi.compileState(source, "Test").value.map {
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
    CompilerApi.compileState(source, "Test").value.map {
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
    CompilerApi.compileState(source, "Test").value.map {
      case Right(state) =>
        val validatedState = PreCodegenValidator.validate(CompilationMode.Ir)(state)
        assert(validatedState.errors.isEmpty)
      case Left(error) => fail(s"Compilation failed with error: $error")
    }
  }
