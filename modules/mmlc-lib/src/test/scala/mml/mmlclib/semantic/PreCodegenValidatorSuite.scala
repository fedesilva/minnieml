package mml.mmlclib.semantic

import mml.mmlclib.ast.queries.DeclarationQueries
import mml.mmlclib.ast.{Bnd, Visibility}
import mml.mmlclib.codegen.{CompilationMode, EntryPoint}
import mml.mmlclib.compiler.{CompilerState, PreCodegenValidator}
import mml.mmlclib.semantic.SemanticError.InvalidEntryPoint
import mml.mmlclib.test.BaseEffFunSuite

class PreCodegenValidatorSuite extends BaseEffFunSuite:

  private def mainBinding(state: CompilerState): Bnd =
    EntryPoint.find(state.module).map(_._1).getOrElse(fail("Expected an entry-point binding"))

  test("a protocol member named main does not supply the program entry point") {
    val source = """
      protocol Run 'T = fn main(): 'T; ;
      instance Run Unit = fn main() = ();; ;
    """

    semStateNotFailed(source).map { state =>
      val validated = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validated.errors,
        Vector(InvalidEntryPoint(EntryPointError.NotFound(state.module.source)))
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
      val memberIds = DeclarationQueries.loweredMemberIds(state.module)
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
    val source = """
      fn foo() = 1;;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validatedState.errors,
        Vector(InvalidEntryPoint(EntryPointError.NotFound(state.module.source)))
      )
    }
  }

  test("binary mode main function with non-StringArray parameter is not a valid entry point") {
    val source = """
      fn main(a: Int32) = 1;;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validatedState.errors,
        Vector(InvalidEntryPoint(EntryPointError.NotFound(state.module.source)))
      )
    }
  }

  test("binary mode main function with StringArray parameter is valid") {
    val source = """
      pub fn main(args: StringArray): Unit = ();;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assert(validatedState.errors.isEmpty)
    }
  }

  test("binary mode consuming StringArray main parameter is not a valid entry point") {
    val source = """
      fn main(~args: StringArray): Unit = ();;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validatedState.errors,
        Vector(InvalidEntryPoint(EntryPointError.NotFound(state.module.source)))
      )
    }
  }

  test("binary mode main function must have Unit, Int32 or Int64 return type") {
    val source = """
      pub fn main(): String = "hello";;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validatedState.errors,
        Vector(InvalidEntryPoint(EntryPointError.InvalidReturnType(mainBinding(state))))
      )
    }
  }

  test("binary mode main function with Unit return type is valid") {
    val source = """
      pub fn main(): Unit = ();;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assert(validatedState.errors.isEmpty)
    }
  }

  test("an entry point without a function type retains its binding in the failure") {
    semStateNotFailed("pub fn main(): Int = 0;;").map { state =>
      val binding = mainBinding(state)
      val untyped = binding.copy(typeSpec = None)
      val module = state.module.copy(members = state.module.members.map {
        case candidate: Bnd if candidate.id == binding.id => untyped
        case member => member
      })
      val validated = PreCodegenValidator.validate(CompilationMode.Exe)(state.copy(module = module))

      assertEquals(
        validated.errors,
        Vector(InvalidEntryPoint(EntryPointError.InvalidReturnType(untyped)))
      )
      assertEquals(validated.entryPoint, None)
    }
  }

  test("binary mode main function with Int return type is valid") {
    val source = """
      pub fn main(): Int = 0;;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assert(validatedState.errors.isEmpty)
    }
  }

  test("library mode does not require a main function") {
    val source = """
      fn foo() = 1;;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Library)(state)
      assert(validatedState.errors.isEmpty)
    }
  }

  test("ast mode does not require a main function") {
    val source = """
      fn foo() = 1;;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Ast)(state)
      assert(validatedState.errors.isEmpty)
    }
  }

  test("ir mode does not require a main function") {
    val source = """
      fn foo() = 1;;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Ir)(state)
      assert(validatedState.errors.isEmpty)
    }
  }

  test("binary mode: fn main without pub emits InvalidEntryPoint error") {
    val source = """
      fn main(): Unit = ();;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assertEquals(
        validatedState.errors,
        Vector(InvalidEntryPoint(EntryPointError.NotPublic(mainBinding(state))))
      )
    }
  }

  test("binary mode: pub fn main passes visibility check") {
    val source = """
      pub fn main(): Unit = ();;
    """
    semStateNotFailed(source).map { state =>
      val validatedState = PreCodegenValidator.validate(CompilationMode.Exe)(state)
      assert(validatedState.errors.isEmpty)
    }
  }
