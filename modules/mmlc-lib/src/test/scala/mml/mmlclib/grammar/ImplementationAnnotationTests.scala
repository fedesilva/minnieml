package mml.mmlclib.grammar

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.lsp.AstLookup
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.collectModuleTerms
import mml.mmlclib.util.prettyprint.ast.prettyPrintTerm

class ImplementationAnnotationTests extends BaseEffFunSuite:

  private def implementation(module: Module): NativeImpl =

    val implementations = collectModuleTerms(module) { case native: NativeImpl => native }
    assertEquals(implementations.size, 1)
    implementations.head

  private def rejectsMemberAndPreservesNext(member: String): IO[Unit] =
    val source = s"$member\nlet after = 42;"

    justParse(source).map { module =>
      val errors = module.members.collect { case error: Error => error }
      assertEquals(errors.size, 1, source)
      errors.head match
        case error: ParsingMemberError =>
          assertEquals(error.spanOpt.map(_.start.line), Some(1), source)
          assertEquals(error.failedCode, Some(member), source)
        case other => fail(s"Expected a member parsing error, got $other")

      val bindings = module.members.collect { case binding: Bnd => binding }
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
    rejectsMemberAndPreservesNext(
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
      rejectsMemberAndPreservesNext(s"fn invalid(x: Int): Int = $annotation;;")
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
      rejectsMemberAndPreservesNext(s"type Invalid = $annotation;")
  }
