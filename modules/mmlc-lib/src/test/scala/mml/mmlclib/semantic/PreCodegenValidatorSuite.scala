package mml.mmlclib.semantic

import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.{Bnd, Visibility}
import mml.mmlclib.codegen.{CompilationMode, EntryPoint}
import mml.mmlclib.compiler.{CompilerConfig, PreCodegenValidator}
import mml.mmlclib.semantic.SemanticError.InvalidEntryPoint
import mml.mmlclib.test.BaseEffFunSuite

class PreCodegenValidatorSuite extends BaseEffFunSuite:

  test("a protocol member named main does not supply the program entry point") {
    val source = """
      protocol Run 'T = fn main(): 'T; ;
      instance Run Unit = fn main() = ();; ;
    """

    semStateNotFailed(source).map { state =>
      val validated = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validated.errors.map(_.message),
        Vector("No entry point 'main' found for binary compilation")
      )
      assertEquals(validated.entryPoint, None)
      assertEquals(EntryPoint.find(state.module), None)
    }
  }

  test("the source main remains the entry point when instance functions occur first") {
    val source = """
      protocol Run 'T = fn main(): 'T; ;
      instance Run Unit = fn main() = ();; ;
      pub fn main(): Int = 37;;
    """

    semStateNotFailed(source).map { state =>
      val memberIds = ProtocolRegistry.loweredMemberIds(state.module)
      val sourceMain = state.module.members
        .collectFirst {
          case binding: Bnd if binding.name == "main" && binding.visibility == Visibility.Public =>
            binding
        }
        .getOrElse(fail("Expected source main"))
      val (implementations, otherMembers) = state.module.members.partition {
        case binding: Bnd => binding.id.exists(memberIds.contains)
        case _ => false
      }
      assert(implementations.nonEmpty)
      val reordered = state.copy(
        module = state.module.copy(members = implementations ++ otherMembers)
      )
      val validated = PreCodegenValidator.validate(CompilationMode.Exe)(reordered)

      assertEquals(validated.errors, Vector.empty)
      assertEquals(validated.entryPoint, Some("test_main"))
      assertEquals(EntryPoint.find(reordered.module).map(_._1.id), Some(sourceMain.id))
    }
  }

  test("binary mode requires a main function") {
    val source          = """
      fn foo() = 1;;
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
      fn main(a: Int32) = 1;;
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
      pub fn main(args: StringArray): Unit = ();;
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
      fn main(~args: StringArray): Unit = ();;
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

  test("binary mode main function must have Unit, Int32 or Int64 return type") {
    val source = """
      pub fn main(): String = "hello";;
    """
    val expectedMessage =
      "Entry point 'main' must have a return type of 'Unit', 'Int32', or 'Int64'"
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
      pub fn main(): Unit = ();;
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
      pub fn main(): Int = 0;;
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
      fn foo() = 1;;
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
      fn foo() = 1;;
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
      fn foo() = 1;;
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

  test("binary mode: fn main without pub emits InvalidEntryPoint error") {
    val source          = """
      fn main(): Unit = ();;
    """
    val expectedMessage = "Entry point 'main' must be declared 'pub'"
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

  test("binary mode: pub fn main passes visibility check") {
    val source = """
      pub fn main(): Unit = ();;
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
