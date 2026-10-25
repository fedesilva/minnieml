package mml.mmlclib.grammar

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, IngestStage, ParsingErrorChecker}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.lsp.AstLookup
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.collectModuleTerms
import mml.mmlclib.util.prettyprint.ast.prettyPrintTerm

class ImplementationAnnotationTests extends BaseEffFunSuite:

  private def implementation(module: Module): NativeImpl =

    val implementations = collectModuleTerms(module) { case native: NativeImpl => native }
    assertEquals(implementations.size, 1)
    implementations.head

  private def rejectsImplementationAndPreservesNext(member: String): IO[Unit] =
    val source          = s"$member\nlet after = 42;"
    val annotationStart = source.indexOf('@')
    val errorStart =
      if source.startsWith("@metal:ffi[", annotationStart) then source.indexOf('[', annotationStart)
      else annotationStart
    val errorEnd = source.indexOf(";;")
    val info     = SourceInfo(source)

    parseNotFailed(source).map { module =>
      val state = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, info, CompilerConfig.default)
      )
      val bindings = state.module.members.collect { case binding: Bnd => binding }
      assertEquals(bindings.map(_.name), List("invalid", "after"), source)
      val functions = TermTraversal.collect(bindings.head.value) { case lambda: Lambda => lambda }
      assertEquals(functions.size, 1)
      assertEquals(functions.head.params.map(_.name), List("x"))
      val errors = TermTraversal.collect(functions.head.body) { case error: ParseTermError =>
        error
      }
      assertEquals(errors.size, 1, source)
      val error = errors.head
      assertEquals(error.message, "Invalid expression syntax")
      assertEquals(error.failedCode, Some(source.substring(errorStart, errorEnd)))
      assertEquals(error.spanOpt, Some(SrcSpan(info.pointAt(errorStart), info.pointAt(errorEnd))))
      assertEquals(state.diagnostics.records.size, 1)
      val diagnostic = state.diagnostics.records.head
      assert(diagnostic.error.isInstanceOf[SemanticError.ParseTermErrorFound])
      assertEquals(diagnostic.relation, DiagnosticRelation.Primary)
      assertEquals(error.causes, Vector(diagnostic.id))
      assertEquals(bindings(1).spanOpt.map(_.start.line), Some(2), source)
      assertEquals(
        TermTraversal.collect(bindings(1).value) { case value: LiteralInt =>
          value.value
        },
        List(42L)
      )
    }

  private def rejectsTypeAndPreservesNext(annotation: String): IO[Unit] =
    val source = s"type Invalid = $annotation;\nlet after = 42;"
    val start  = source.indexOf(annotation)
    val info   = SourceInfo(source)
    val span   = SrcSpan(info.pointAt(start), info.pointAt(start + annotation.length))

    IO.blocking(IngestStage.fromSource(source, "InvalidTypeAnnotation")).map { state =>
      val aliases = state.module.members.collect { case alias: TypeAlias => alias }
      assertEquals(aliases.size, 1, source)
      val error = aliases.head.typeRef match
        case error: ParseTypeError => error
        case other => fail(s"Expected rejected type annotation, got $other")

      assertEquals(error.failedCode, Some(annotation), source)
      assertEquals(error.spanOpt, Some(span), source)
      assertEquals(state.diagnostics.records.size, 1, source)
      val diagnostic = state.diagnostics.records.head
      assert(diagnostic.error.isInstanceOf[SemanticError.ParseTypeErrorFound], source)
      assertEquals(diagnostic.relation, DiagnosticRelation.Primary, source)
      assertEquals(error.causes, Vector(diagnostic.id), source)
      assertEquals(
        state.visibleErrors.map(_.message),
        Vector("Parse error in type: Invalid type syntax")
      )

      val bindings = state.module.members.collect { case binding: Bnd => binding }
      assertEquals(bindings.map(_.name), List("after"), source)
      assertEquals(bindings.head.spanOpt.map(_.start.line), Some(2), source)
    }

  test("bare ffi is a foreign declaration"):
    val source = "fn external(): Unit = @metal:ffi;;"

    parseNotFailed(source).map { module =>
      val native = implementation(module)
      assertEquals(native.nativeTpl, None)
      assertEquals(native.nativeSymbol, None)
      assertEquals(native.memEffect, None)
      assertEquals(
        AstLookup.findAt(module, 1, source.indexOf('@') + 1).flatMap(_.name),
        Some("@metal:ffi")
      )
      assertEquals(prettyPrintTerm(native, indent = 0).trim, "@metal:ffi")
    }

  test("template retains its positional string, symbol, and static memory effect"):
    val template = "add i32 %operand, 1"
    val source =
      s"""fn increment(x: Int): Int = @metal:tpl["$template", mem=static, name="increment"];;"""

    parseNotFailed(source).map { module =>
      val native = implementation(module)
      assertEquals(native.nativeTpl, Some(template))
      assertEquals(native.nativeSymbol, Some("increment"))
      assertEquals(native.memEffect, Some(MemEffect.Static))
      assertEquals(
        AstLookup.findAt(module, 1, source.indexOf('@') + 1).flatMap(_.name),
        Some("@metal:tpl")
      )
      assertEquals(
        prettyPrintTerm(native, indent = 0).trim,
        s"""@metal:tpl["$template", mem=static, name="increment"]"""
      )
    }

  test("ffi prints memory and symbol attributes together"):
    val source = """fn allocate(): Int = @metal:ffi[name="host_allocate", mem=alloc];;"""

    parseNotFailed(source).map { module =>
      val native = implementation(module)
      assertEquals(native.nativeTpl, None)
      assertEquals(native.nativeSymbol, Some("host_allocate"))
      assertEquals(native.memEffect, Some(MemEffect.Alloc))
      assertEquals(
        prettyPrintTerm(native, indent = 0).trim,
        """@metal:ffi[mem=alloc, name="host_allocate"]"""
      )
    }

  test("unterminated template preserves the following declaration"):
    rejectsImplementationAndPreservesNext(
      """fn invalid(x: Int): Int = @metal:tpl["add i32 %operand, 1];;"""
    )

  test("multiline template retains its string and following declaration"):
    val template = "add i32 %operand,\n1"
    val source = s"""fn increment(x: Int): Int = @metal:tpl["$template"];;
                    |let after = 42;
                    |""".stripMargin

    parseNotFailed(source).map { module =>
      assertEquals(implementation(module).nativeTpl, Some(template))
      assertEquals(
        module.members.collect { case binding: Bnd => binding.name },
        List("increment", "after")
      )
    }

  List(
    "@native",
    "@ffi",
    "@llvm[tpl=\"add i32 %operand, 1\"]",
    "@metal:repr[t=i32]",
    "@metal:tpl[tpl=\"add i32 %operand, 1\"]",
    "@metal:tpl[mem=static, \"add i32 %operand, 1\"]",
    "@metal:tpl[\"add i32 %operand, 1\", unknown]",
    "@metal:tpl[\"add i32 %operand, 1\", b=llvm]",
    "@metal:tpl[\"add i32 %operand, 1\", \"second\"]",
    "@metal:ffi[\"add i32 %operand, 1\"]",
    "@native[name=\"external\"]",
    "@native[tpl=\"add i32 %operand, 1\"]",
    "@metal:ffi[tpl=\"add i32 %operand, 1\"]",
    "@metal:ffi[mem=alloc, tpl=\"add i32 %operand, 1\"]",
    "@metal:tpl",
    "@metal:tpl[mem=alloc]",
    "@metal:tpl[name=\"external\"]",
    "@metal:tpl[]",
    "@metal:ffi[]",
    "@metal:ffi_extra",
    "@metal:tpl_extra[\"add i32 %operand, 1\"]"
  ).foreach { annotation =>
    test(s"reject invalid implementation annotation $annotation"):
      rejectsImplementationAndPreservesNext(s"fn invalid(x: Int): Int = $annotation;;")
  }

  List(
    "@ffi[t=i32]",
    "@metal:ffi[t=i32]",
    "@metal:ffi { value: Int }",
    "@metal:repr_extra[t=i32]",
    "@native[t=i32]",
    "@native { value: Int }",
    "@metal:tpl[t=i32]",
    "@metal:tpl { value: Int }",
    "@metal:ffi[tpl=\"i32\"]"
  ).foreach { annotation =>
    test(s"reject invalid type annotation $annotation"):
      rejectsTypeAndPreservesNext(annotation)
  }
