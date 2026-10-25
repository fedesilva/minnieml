package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class FindReferencesTests extends BaseEffFunSuite:

  private def spanOrFail(node: FromSource, label: String): SrcSpan =
    node.source.spanOpt.getOrElse(fail(s"Missing source span for $label"))

  test("find references from a constructor annotation without widening its source span") {
    val code =
      "struct Box { value: Int }; let constructor = Box: Int -> Box 1; // trailing comment"

    semNotFailed(code).map { module =>
      val declarationSpan = SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 27, 26))
      val annotationSpan  = SrcSpan(SrcPoint(1, 58, 57), SrcPoint(1, 61, 60))
      val declarationRefs = AstLookup.findReferencesAt(module, 1, 8, includeDeclaration = true)
      val annotationRefs  = AstLookup.findReferencesAt(module, 1, 58, includeDeclaration = true)

      assertEquals(declarationRefs, List(declarationSpan, annotationSpan))
      assertEquals(annotationRefs, declarationRefs)
      assertEquals(
        AstLookup.findReferencesAt(module, 1, 58, includeDeclaration = false),
        AstLookup.findReferencesAt(module, 1, 8, includeDeclaration  = false)
      )

      val constructor = AstLookup
        .findAt(module, 1, 46)
        .getOrElse(fail("Expected constructor reference"))
      assertEquals(constructor.span, Some(SrcSpan(SrcPoint(1, 46, 45), SrcPoint(1, 49, 48))))
      assertEquals(AstLookup.findAt(module, 1, 58), None)

      val punctuationAndTrivia = List(49, 50, 54, 55, 57, 61, 64, 65)
      punctuationAndTrivia.foreach { col =>
        assertEquals(
          AstLookup.findReferencesAt(module, 1, col, includeDeclaration = true),
          Nil,
          s"References at column $col"
        )
        assertEquals(AstLookup.findAt(module, 1, col), None, s"Lookup at column $col")
      }
    }
  }

  test("find references to function") {
    val code =
      """
      fn add(x: Int, y: Int): Int = x + y;;
      let result = add 1 2;
      let result2 = add 3 4;
      """

    semNotFailed(code).map { m =>
      // Find the add function Bnd and use its span
      val addBnd = m.members.collectFirst { case bnd: Bnd if bnd.name == "add" => bnd }
      assert(addBnd.isDefined, "Could not find 'add' binding")
      val bnd     = addBnd.get
      val bndSpan = spanOrFail(bnd, "binding 'add'")
      val fnLine  = bndSpan.start.line
      val fnCol   = bndSpan.start.col

      val refs = AstLookup.findReferencesAt(m, fnLine, fnCol, includeDeclaration = true)

      // Should find: declaration + 2 usages = 3 spans
      assert(
        clue(refs.size) >= 3,
        s"Expected at least 3 references (1 decl + 2 uses), got ${refs.size}: $refs"
      )
    }
  }

  test("find references to parameter") {
    val code =
      """
      fn double(x: Int): Int = x + x;;
      """

    semNotFailed(code).map { m =>
      // Find the double function and get the first param
      val doubleBnd = m.members.collectFirst { case bnd: Bnd if bnd.name == "double" => bnd }
      assert(doubleBnd.isDefined, "Could not find 'double' binding")
      val lambda    = doubleBnd.get.value.terms.head.asInstanceOf[Lambda]
      val param     = lambda.params.head
      val paramSpan = spanOrFail(param, "param 'x'")
      val line      = paramSpan.start.line
      val col       = paramSpan.start.col

      val refs = AstLookup.findReferencesAt(m, line, col, includeDeclaration = true)

      // Should find: declaration + 2 usages in body = 3 spans
      assert(
        clue(refs.size) >= 3,
        s"Expected at least 3 references (1 decl + 2 uses), got ${refs.size}: $refs"
      )
    }
  }

  test("find references to let binding") {
    val code =
      """
      let x = 42;
      let y = x + x;
      """

    semNotFailed(code).map { m =>
      // Find references at position of `x` declaration (line 2, col ~11)
      val letLine = 2
      val letCol  = 11

      val refs = AstLookup.findReferencesAt(m, letLine, letCol, includeDeclaration = true)

      // Should find: declaration + 2 usages = 3 spans
      assert(
        clue(refs.size) >= 3,
        s"Expected at least 3 references (1 decl + 2 uses), got ${refs.size}: $refs"
      )
    }
  }
