package mml.mmlclib.util.error.print

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.*
import mml.mmlclib.codegen.{LlvmCompilationError, ToolOperation}
import mml.mmlclib.lsp.Diagnostics
import mml.mmlclib.semantic.{OwnershipError, SemanticError, TypeError}
import mml.mmlclib.test.BaseEffFunSuite

class SharedDiagnosticMessageTests extends BaseEffFunSuite:

  test("type, parser, and ownership carriers share their wording across presentation boundaries") {
    parseNotFailed("let value = 1;").map { module =>
      val binding = module.members
        .collectFirst { case binding: Bnd => binding }
        .getOrElse(fail("Expected fixture binding"))
      val syntax = ParseTermError(binding.value.source, SyntaxError.ExpectedToken("then"), None)
      val fixtures = List(
        SemanticError.TypeCheckingError(TypeError.MissingReturnType(binding, "fixture")) ->
          "Missing return type for 'value'",
        SemanticError
          .ParseTermErrorFound(syntax, "fixture") -> "Parse error in expression: Expected 'then'",
        SemanticError.InvalidExpression(binding.value, OwnershipError.GlobalTransfer, "fixture") ->
          "A global value cannot transfer ownership; use an explicit ^ copy"
      )
      fixtures.foreach { (error, expected) =>
        assertEquals(diagnosticMessage(error), expected)
        val core = error match
          case SemanticError.ParseTermErrorFound(_, _) => "Expected 'then'"
          case _ => expected
        assert(ErrorPrinter.prettyPrint(error, None).contains(core))
        assert(SemanticErrorPrinter.prettyPrint(List(error)).contains(core))
        val editor = Diagnostics.fromErrorAll(error)
        assertEquals(editor.size, 1)
        assert(editor.head.message.contains(core))
      }
    }
  }

  test("unknown fields retain the resolved struct name in plain, CLI, and LSP output") {
    val source = """struct Person { name: Int };
                   |fn make(): Person = Person 1;;
                   |let value = (make ()).age;
                   |""".stripMargin
    semVisibleState(source).map { result =>
      val error = result.errors match
        case List(SemanticError.TypeCheckingError(error: TypeError.UnknownField)) => error
        case other => fail(s"Expected one unknown-field error, got $other")
      val struct = result.module.members.collect {
        case struct: TypeStruct if struct.source.isFromSource => struct
      } match
        case List(struct) => struct
        case other => fail(s"Expected the source struct, got $other")
      assert(struct.id.nonEmpty)
      assertEquals(error.struct.id, struct.id)
      assertEquals(error.ref.name, "age")

      val wrapped  = SemanticError.TypeCheckingError(error)
      val expected = "Unknown field 'age' in struct Person"
      assertEquals(diagnosticMessage(error), expected)
      assertEquals(diagnosticMessage(wrapped), expected)
      assert(ErrorPrinter.prettyPrint(wrapped, None).contains(expected))
      assert(SemanticErrorPrinter.prettyPrint(List(wrapped)).contains(expected))
      assertEquals(Diagnostics.fromErrorAll(error).map(_.message), List(expected))
      assertEquals(Diagnostics.fromErrorAll(wrapped).map(_.message), List(expected))
    }
  }

  test("native struct failures retain every original field error before rendering") {
    val first  = TypeRef(SourceOrigin.Synth, "First")
    val second = TypeRef(SourceOrigin.Synth, "Second")
    val native = NativeStruct(SourceOrigin.Synth, List("left" -> first, "right" -> second))
    val result = nativeTypeToLlvmDef("Broken", native, CodeGenState())
    val nested = result match
      case Left(CodeGenError(CodeGenReason.StructFieldErrors(errors), _)) => errors
      case other => fail(s"Expected accumulated field errors, got $other")
    assertEquals(
      nested.map(_.reason),
      List(CodeGenReason.UnresolvedType("First"), CodeGenReason.UnresolvedType("Second"))
    )
    assertEquals(
      nested,
      List(first, second).map(tpe =>
        getLlvmType(tpe, CodeGenState()).swap.toOption.getOrElse(fail("Expected missing type"))
      )
    )
    val rendered = diagnosticMessage(CodeGenError(CodeGenReason.StructFieldErrors(nested)))
    assert(rendered.indexOf("First") < rendered.indexOf("Second"))
  }

  test("toolchain operation context and opaque stderr reach both CLI renderers") {
    val error = LlvmCompilationError.CommandExecutionError(
      List("llvm-as", "input with spaces.ll"),
      ToolOperation.Assemble,
      "external diagnostic",
      7
    )
    val expected =
      "Command 'llvm-as input with spaces.ll' failed (exit 7): Failed to convert IR to Bitcode\nexternal diagnostic"
    assertEquals(diagnosticMessage(error), expected)
    assert(ErrorPrinter.prettyPrint(error, None).contains(expected))
    assert(LlvmErrorPrinter.prettyPrint(List(error)).contains(expected))
    assertEquals(Diagnostics.fromErrorAll(error), Nil)
  }
