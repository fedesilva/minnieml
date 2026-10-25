package mml.mmlclib.parser

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class MemberSourceSpanTests extends BaseEffFunSuite:

  private def bindings(module: Module): List[Bnd] =
    module.members.collect { case binding: Bnd => binding }

  private def lambda(binding: Bnd): Lambda =
    binding.value.terms match
      case (value: Lambda) :: Nil => value
      case other => fail(s"Expected one lambda, found $other")

  private def literal(binding: Bnd): LiteralValue =
    binding.value.terms match
      case (value: LiteralValue) :: Nil => value
      case other => fail(s"Expected one literal, found $other")

  private def assertSpan(node: FromSource, source: String, start: Int, syntax: String): Unit =
    val location = node.spanOpt.getOrElse(fail("Expected a written source span"))
    assertEquals((location.start.index, location.end.index), (start, start + syntax.length))
    assertEquals(source.substring(location.start.index, location.end.index), syntax)

  private def assertFollowingMember(module: Module, source: String): Unit =
    val values = bindings(module)
    assertEquals(values.size, 1)
    assertSpan(values.head, source, source.indexOf("after ="), "after = 1;")
    assertSpan(literal(values.head), source, source.lastIndexOf('1'), "1")

  private val endings = List(
    "EOF" -> "",
    "spaces and tabs" -> " \t ",
    "LF comments" -> " // trailing comment\n\n",
    "CRLF comments" -> "\r\n// trailing comment\r\n \t"
  )

  List("42", "3.14", ".5", "true", "false", "()", "\"a // b \"").foreach { syntax =>
    test(s"literal $syntax ends before an intervening line comment") {
      val source = s"let value = $syntax // before terminator\r\n ;"
      parseNotFailed(source).map { module =>
        val binding = bindings(module).head
        val value   = literal(binding)
        assertSpan(value, source, 12, syntax)
        assertSpan(value.typeSpec.getOrElse(fail("Expected a literal type")), source, 12, syntax)
        assertSpan(binding.value, source, 12, syntax)
        assertSpan(binding, source, 4, source.drop(4))
      }
    }

    endings.foreach { case (endingName, ending) =>
      test(s"literal and binding boundaries exclude $endingName after $syntax") {
        val source = s"let value = $syntax;$ending"
        parseNotFailed(source).map { module =>
          val binding = bindings(module).head
          val value   = literal(binding)
          assertSpan(value, source, 12, syntax)
          assertSpan(value.typeSpec.getOrElse(fail("Expected a literal type")), source, 12, syntax)
          assertSpan(binding.nameNode, source, 4, "value")
          assertSpan(binding, source, 4, s"value = $syntax;")
          assertSpan(module, source, 0, source)
        }
      }
    }
  }

  test("a declaration's trailing comment does not extend its value, name, or binding") {
    val source = "let b: Int = \"2\";\r\n\r\n// belongs to the next declaration\r\nlet c = b;"
    parseNotFailed(source).map { module =>
      val values = bindings(module)
      assertEquals(values.size, 2)
      assertSpan(values.head, source, 4, "b: Int = \"2\";")
      assertSpan(values.head.nameNode, source, 4, "b")
      assertSpan(values.head.value, source, 13, "\"2\"")
      assertEquals(values.head.spanOpt.map(_.end), Some(SrcPoint(1, 18, 17)))
      assertSpan(values(1), source, source.indexOf("c ="), "c = b;")
    }
  }

  test("the module owns whole-file trivia while its member owns only declaration syntax") {
    val source = "\t// introduction\r\n\r\nlet value = 1;\r\n// ending\r\n"
    parseNotFailed(source).map { module =>
      assertSpan(module, source, 0, source)
      assertSpan(bindings(module).head, source, source.indexOf("value"), "value = 1;")
    }
  }

  test("parameter documentation and annotations are owned without following comments") {
    val source = "/* declaration */\r\nfn choose (/* parameter */ ~item: Int // annotation\r\n" +
      ", fallback // name\r\n): Int = item; // body\r\n; // declaration\r\n"
    parseNotFailed(source).map { module =>
      val binding  = bindings(module).head
      val function = lambda(binding)
      assertSpan(binding, source, source.indexOf("choose"), "choose")
      assertSpan(binding.nameNode, source, source.indexOf("choose"), "choose")
      assertSpan(
        binding.docComment.getOrElse(fail("Expected declaration comment")),
        source,
        0,
        "/* declaration */"
      )
      assertEquals(function.params.size, 2)
      val first = function.params.head
      assert(first.consuming)
      assertSpan(first, source, source.indexOf("/* parameter */"), "/* parameter */ ~item: Int")
      assertSpan(first.nameNode, source, source.indexOf("item:"), "item")
      assertSpan(
        first.docComment.getOrElse(fail("Expected parameter comment")),
        source,
        source.indexOf("/* parameter */"),
        "/* parameter */"
      )
      assertSpan(function.params(1), source, source.indexOf("fallback"), "fallback")
      assertSpan(function.params(1).nameNode, source, source.indexOf("fallback"), "fallback")
      assertSpan(function.body, source, source.indexOf("item;"), "item")
      val lambdaStart = source.indexOf('(')
      val lambdaEnd   = source.indexOf(';') + 1
      assertSpan(function, source, lambdaStart, source.substring(lambdaStart, lambdaEnd))
    }
  }

  List(
    "op + (left: Int, right: Int): Int 60 left = left; ;" -> "+",
    "op ! (value: Bool): Bool 90 right = value; ;" -> "!"
  ).foreach { case (declaration, name) =>
    test(s"operator $name keeps name-only binding and exact body terminator") {
      val source = declaration + " // declaration\nlet next = 1;"
      parseNotFailed(source).map { module =>
        val values = bindings(module)
        assertEquals(values.size, 2)
        val function = lambda(values.head)
        assertSpan(values.head, source, 3, name)
        assertSpan(values.head.nameNode, source, 3, name)
        val lambdaStart = source.indexOf('(')
        val lambdaEnd   = source.indexOf(';') + 1
        assertSpan(function, source, lambdaStart, source.substring(lambdaStart, lambdaEnd))
      }
    }
  }

  endings.foreach { case (endingName, ending) =>
    test(s"link directive and entry boundaries exclude $endingName") {
      val source = "@link[\"ssl\" // first library\r\n, \"crypto\" ];" + ending
      parseNotFailed(source).map { module =>
        val directive = module.linkDirective.getOrElse(fail("Expected link directive"))
        assertEquals(directive.entries.size, 2)
        assertSpan(directive.entries.head, source, 6, "\"ssl\"")
        assertSpan(directive.entries(1), source, source.indexOf("\"crypto\""), "\"crypto\"")
        assertSpan(directive, source, 0, source.take(source.indexOf(';') + 1))
        assertSpan(module, source, 0, source)
      }
    }
  }

  test("invalid link names keep the directive's exact closing semicolon") {
    val declaration = "@link[\"../ssl\"];"
    val source      = declaration + " // header\nlet next = 1;"
    parseFailedWithErrors(source).map { errors =>
      assertEquals(errors.size, 1)
      assertSpan(errors.head, source, 0, declaration)
      assertEquals(errors.head.failedCode, Some(declaration))
    }
  }

  test("failed-member recovery owns the malformed line but excludes following line comments") {
    val recovered = "@ bad  // same line\r"
    val source    = recovered + "\n // next line\r\nlet after = 1;"
    justParse(source).map { module =>
      val errors = module.members.collect { case error: Error => error }
      assertEquals(errors.size, 1)
      assertSpan(errors.head, source, 0, recovered)
      assertEquals(errors.head.failedCode, Some("@ bad  // same line"))
      assertFollowingMember(module, source)
    }
  }

  test("malformed link recovery ends at its semicolon before a following comment") {
    val recovered = "@link[\"ssl\";"
    val source    = recovered + " // outside the header\nlet after = 1;"
    justParse(source).map { module =>
      val errors = module.members.collect { case error: Error => error }
      assertEquals(errors.size, 1)
      assertSpan(errors.head, source, 0, recovered)
      assertEquals(errors.head.failedCode, Some(recovered))
      assertFollowingMember(module, source)
    }
  }

  test("unterminated link recovery owns trivia up to the next declaration anchor") {
    val recovered = "@link[\"ssl\" // inside the malformed header\n"
    val source    = recovered + "let after = 1;"
    justParse(source).map { module =>
      val errors = module.members.collect { case error: Error => error }
      assertEquals(errors.size, 1)
      assertSpan(errors.head, source, 0, recovered)
      assertEquals(errors.head.failedCode, Some(recovered))
      assertFollowingMember(module, source)
    }
  }

  test("an invalid operator name ends at its declaration terminator") {
    val source = "op Bad (value: Int): Int = value; ; // declaration\nlet next = 1;"
    parseFailedWithErrors(source).map { errors =>
      assertEquals(errors.size, 1)
      assertSpan(errors.head, source, 3, "Bad (value: Int): Int = value; ;")
    }
  }

  test("nested doc comments retain their own delimiter and attach across trivia") {
    val source = "/* outer /* inner */ text */ // comment\r\nlet result = 1;"
    parseNotFailed(source).map { module =>
      val binding = bindings(module).head
      val comment = binding.docComment.getOrElse(fail("Expected declaration comment"))
      assertSpan(comment, source, 0, "/* outer /* inner */ text */")
      assertEquals(comment.text, "outer  inner  text")
      assertSpan(binding, source, source.indexOf("result"), "result = 1;")
    }
  }
