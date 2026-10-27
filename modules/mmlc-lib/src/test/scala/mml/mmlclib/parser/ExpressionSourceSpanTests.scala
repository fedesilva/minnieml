package mml.mmlclib.parser

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.test.BaseEffFunSuite

class ExpressionSourceSpanTests extends BaseEffFunSuite:

  private def assertSpan(
    source:   String,
    node:     FromSource,
    start:    Int,
    end:      Int,
    expected: String
  ): Unit =
    val location = node.spanOpt.getOrElse(fail("Expected written source span"))
    val info     = SourceInfo(source)
    assertEquals(location, SrcSpan(info.pointAt(start), info.pointAt(end)))
    assertEquals(source.substring(location.start.index, location.end.index), expected)

  private def value(module: Module): Expr =
    module.members match
      case (binding: Bnd) :: Nil => binding.value
      case other => fail(s"Expected one binding, found $other")

  private def onlyTerm(expression: Expr): Term =
    expression.terms match
      case term :: Nil => term
      case other => fail(s"Expected one term, found $other")

  private def lambda(expression: Expr): Lambda =
    onlyTerm(expression) match
      case value: Lambda => value
      case other => fail(s"Expected a lambda, found $other")

  private def conditional(expression: Expr): Cond =
    onlyTerm(expression) match
      case value: Cond => value
      case other => fail(s"Expected a conditional, found $other")

  private def native(expression: Expr): NativeImpl =
    onlyTerm(expression) match
      case value: NativeImpl => value
      case other => fail(s"Expected a native implementation, found $other")

  private def scopedApplication(expression: Expr): (App, Lambda) =
    onlyTerm(expression) match
      case application: App =>
        application.fn match
          case scope: Lambda => (application, scope)
          case other => fail(s"Expected a scope lambda, found $other")
      case other => fail(s"Expected a scoped application, found $other")

  private def parseChecked(source: String, expectedErrors: Int = 0): IO[Module] =
    parseNotFailed(source).map { module =>
      val checked = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(checked.errors.size, expectedErrors)
      module
    }

  test("an annotated reference keeps its name span while the expression includes the type") {
    val source = "let value = item: Int // comment\n ;"

    parseChecked(source).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 21, "item: Int")
      onlyTerm(expression) match
        case reference: Ref =>
          assertSpan(source, reference, 12, 16, "item")
          val annotation = reference.typeAsc.getOrElse(fail("Expected the written annotation"))
          assertSpan(source, annotation, 18, 21, "Int")
        case other => fail(s"Expected an annotated reference, found $other")
    }
  }

  test("a sole lambda owns its return annotation without copying it onto the expression"):
    val source = "let value = { x: Int -> x; }: Int // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      val function   = lambda(expression)
      val annotation = function.typeAsc.getOrElse(fail("Expected the lambda return annotation"))

      assertEquals(expression.typeAsc, None)
      assertEquals(function.body.typeAsc, None)
      assertEquals(expression.typeSpec, function.typeSpec)
      assertSpan(source, expression, 12, 33, "{ x: Int -> x; }: Int")
      assertSpan(source, function, 12, 28, "{ x: Int -> x; }")
      assertSpan(source, annotation, 30, 33, "Int")
    }

  test("a sole group owns its annotation while its computed type passes through the expression"):
    val source = "let value = (1): Int // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      val group = onlyTerm(expression) match
        case group: TermGroup => group
        case other => fail(s"Expected an annotated group, found $other")
      val literal    = onlyTerm(group.inner)
      val annotation = group.typeAsc.getOrElse(fail("Expected the group annotation"))

      assertEquals(expression.typeAsc, None)
      assertEquals(group.inner.typeAsc, None)
      assertEquals(literal.typeAsc, None)
      assert(literal.typeSpec.nonEmpty)
      assertEquals(group.typeSpec, literal.typeSpec)
      assertEquals(expression.typeSpec, literal.typeSpec)
      assertSpan(source, expression, 12, 20, "(1): Int")
      assertSpan(source, group, 12, 15, "(1)")
      assertSpan(source, literal, 13, 14, "1")
      assertSpan(source, annotation, 17, 20, "Int")
    }

  test("an empty tuple error retains the expression endpoint of a discarded annotation") {
    val source = "let value = ( ): Int; // next\n"

    parseChecked(source, expectedErrors = 1).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 20, "( ): Int")
      onlyTerm(expression) match
        case error: ParseTermError =>
          assertSpan(source, error, 12, 15, "( )")
          assertEquals(error.typeAsc, None)
          assertEquals(error.failedCode, Some("( )"))
          assertEquals(error.message, "Tuple must have at least one element")
        case other => fail(s"Expected an empty tuple error, found $other")
    }
  }

  test("a selection keeps field spans while its expression includes the annotation") {
    val source = "let value = person.address.city: String // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 39, "person.address.city: String")
      onlyTerm(expression) match
        case city: Ref =>
          assertSpan(source, city, 12, 31, "person.address.city")
          val annotation = city.typeAsc.getOrElse(fail("Expected the selection annotation"))
          assertSpan(source, annotation, 33, 39, "String")
          city.qualifier match
            case Some(address: Ref) =>
              assertSpan(source, address, 12, 26, "person.address")
              val base = address.qualifier.getOrElse(fail("Expected the original qualifier"))
              assertSpan(source, base, 12, 18, "person")
              assertEquals(address.typeAsc, None)
              assertEquals(base.typeAsc, None)
            case other => fail(s"Expected the intermediate field selection, found $other")
        case other => fail(s"Expected an annotated selection, found $other")
    }
  }

  test("a placeholder retains its own span inside an annotated expression") {
    val source = "let value = _: Int // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 18, "_: Int")
      onlyTerm(expression) match
        case placeholder: Placeholder =>
          assertSpan(source, placeholder, 12, 13, "_")
          val annotation = placeholder.typeAsc.getOrElse(fail("Expected a placeholder annotation"))
          assertSpan(source, annotation, 15, 18, "Int")
        case other => fail(s"Expected a placeholder, found $other")
    }
  }

  test("a typed hole retains its own span inside an annotated expression") {
    val source = "let value = ???: Int // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 20, "???: Int")
      onlyTerm(expression) match
        case hole: Hole =>
          assertSpan(source, hole, 12, 15, "???")
          val annotation = hole.typeAsc.getOrElse(fail("Expected the hole annotation"))
          assertSpan(source, annotation, 17, 20, "Int")
        case other => fail(s"Expected a typed hole, found $other")
    }
  }

  test("a foreign implementation without attributes ends at its keyword") {
    val source = "let value = @metal:ffi // trailing\n;"

    parseChecked(source).map { module =>
      val expression     = value(module)
      val implementation = native(expression)
      assertSpan(source, expression, 12, 22, "@metal:ffi")
      assertSpan(source, implementation, 12, 22, "@metal:ffi")
      assertEquals(implementation.nativeTpl, None)
      assertEquals(implementation.nativeSymbol, None)
      assertEquals(implementation.memEffect, None)
    }
  }

  test("a foreign implementation with attributes ends immediately after its closing bracket") {
    val syntax = """@metal:ffi[name="host", mem=alloc]"""
    val source = s"let value = $syntax // trailing\n;"

    parseChecked(source).map { module =>
      val expression     = value(module)
      val implementation = native(expression)
      assertSpan(source, expression, 12, 46, syntax)
      assertSpan(source, implementation, 12, 46, syntax)
      assertEquals(implementation.nativeTpl, None)
      assertEquals(implementation.nativeSymbol, Some("host"))
      assertEquals(implementation.memEffect, Some(MemEffect.Alloc))
    }
  }

  test("a native template ends at its closing bracket before a trailing comment") {
    val syntax = """@metal:tpl["add i32 %operand, 1", mem=static]"""
    val source = s"let value = $syntax // trailing\n;"

    parseChecked(source).map { module =>
      val expression     = value(module)
      val implementation = native(expression)
      assertSpan(source, expression, 12, 57, syntax)
      assertSpan(source, implementation, 12, 57, syntax)
      assertEquals(implementation.nativeTpl, Some("add i32 %operand, 1"))
      assertEquals(implementation.memEffect, Some(MemEffect.Static))
    }
  }

  test("recovery starts after annotation trivia and preserves malformed trailing spaces") {
    val source = "let value = item: Int // comment\n @ bad  ;"

    parseChecked(source, expectedErrors = 1).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 41, "item: Int // comment\n @ bad  ")
      expression.terms match
        case (prefix: Expr) :: (error: ParseTermError) :: Nil =>
          assertSpan(source, prefix, 12, 21, "item: Int")
          assertSpan(source, onlyTerm(prefix), 12, 16, "item")
          assertSpan(source, error, 34, 41, "@ bad  ")
          assertEquals(error.failedCode, Some("@ bad  "))
          assertEquals(error.message, "Invalid expression syntax")
        case other => fail(s"Expected a preserved prefix and one recovered suffix, found $other")
    }
  }

  test("a group ends at its closing parenthesis before a trailing CRLF comment") {
    val source = "let value = (item) // trailing\r\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 18, "(item)")
      onlyTerm(expression) match
        case group: TermGroup =>
          assertSpan(source, group, 12, 18, "(item)")
          assertSpan(source, group.inner, 13, 17, "item")
        case other => fail(s"Expected a group, found $other")
    }
  }

  test("a tuple owns its closing parenthesis while each element owns only its value") {
    val source = "let value = (1, 2) // trailing\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      assertSpan(source, expression, 12, 18, "(1, 2)")
      onlyTerm(expression) match
        case tuple: Tuple =>
          assertSpan(source, tuple, 12, 18, "(1, 2)")
          assertEquals(tuple.elements.size, 2)
          assertSpan(source, tuple.elements.head, 13, 14, "1")
          assertSpan(source, tuple.elements.last, 16, 17, "2")
        case other => fail(s"Expected a tuple, found $other")
    }
  }

  test("a lambda owns its closing brace and excludes the comment after it") {
    val source = "let value = { x -> x } // trailing\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      val function   = lambda(expression)
      assertSpan(source, expression, 12, 22, "{ x -> x }")
      assertSpan(source, function, 12, 22, "{ x -> x }")
      assertSpan(source, function.body, 19, 20, "x")
      assertEquals(function.params.size, 1)
      assertSpan(source, function.params.head, 14, 15, "x")
    }
  }

  test("an explicit else sets the conditional endpoint after its owned semicolon") {
    val source = "let value = if true then 1; else 2; // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      val result     = conditional(expression)
      assertSpan(source, expression, 12, 35, "if true then 1; else 2;")
      assertSpan(source, result, 12, 35, "if true then 1; else 2;")
      assertSpan(source, result.cond, 15, 19, "true")
      assertSpan(source, result.ifTrue, 25, 26, "1")
      assertSpan(source, result.ifFalse, 33, 34, "2")
      assertEquals(result.typeAsc, None)
    }
  }

  test("the final elif supplies the endpoint and a zero-width implicit false branch") {
    val source = "let value = if true then 1; elif false then 2; // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      val result     = conditional(expression)
      val last       = conditional(result.ifFalse)
      assertSpan(source, expression, 12, 46, "if true then 1; elif false then 2;")
      assertSpan(source, result, 12, 46, "if true then 1; elif false then 2;")
      assertSpan(source, last, 33, 46, "false then 2;")
      assertSpan(source, last.ifTrue, 44, 45, "2")
      assertSpan(source, last.ifFalse, 46, 46, "")
      onlyTerm(last.ifFalse) match
        case unit: LiteralUnit => assertSpan(source, unit, 46, 46, "")
        case other => fail(s"Expected the implicit Unit result, found $other")
    }
  }

  test("a conditional without elif or else puts implicit Unit after the initial terminator") {
    val source = "let value = if true then (); // tail\n;"

    parseChecked(source).map { module =>
      val expression = value(module)
      val result     = conditional(expression)
      assertSpan(source, expression, 12, 28, "if true then ();")
      assertSpan(source, result, 12, 28, "if true then ();")
      assertSpan(source, result.ifTrue, 25, 27, "()")
      assertSpan(source, result.ifFalse, 28, 28, "")
      onlyTerm(result.ifFalse) match
        case unit: LiteralUnit => assertSpan(source, unit, 28, 28, "")
        case other => fail(s"Expected the implicit Unit result, found $other")
    }
  }

  test("a lowered local let ends at its continuation before the enclosing lambda brace") {
    val source = "let value = { let x = 1; x // tail\n };"

    parseChecked(source).map { module =>
      val function             = lambda(value(module))
      val (application, scope) = scopedApplication(function.body)
      assertSpan(source, function, 12, 37, "{ let x = 1; x // tail\n }")
      assertSpan(source, function.body, 14, 26, "let x = 1; x")
      assertSpan(source, application, 14, 26, "let x = 1; x")
      assertSpan(source, scope, 14, 26, "let x = 1; x")
      assertSpan(source, application.arg, 22, 23, "1")
      assertSpan(source, scope.body, 25, 26, "x")
    }
  }

  test("a lowered local function ends at the continuation and retains both body terminators") {
    val source = "let value = { fn local(x: Int): Int = x;; local 1 // tail\n };"

    parseChecked(source).map { module =>
      val function             = lambda(value(module))
      val (application, scope) = scopedApplication(function.body)
      assertSpan(source, function.body, 14, 49, "fn local(x: Int): Int = x;; local 1")
      assertSpan(source, application, 14, 49, "fn local(x: Int): Int = x;; local 1")
      assertSpan(source, scope, 14, 49, "fn local(x: Int): Int = x;; local 1")
      assertSpan(source, scope.body, 42, 49, "local 1")
      val local = lambda(application.arg)
      assertSpan(source, local.body, 38, 39, "x")
      assertEquals(local.params.size, 1)
      assertSpan(source, local.params.head.nameNode, 23, 24, "x")
    }
  }
