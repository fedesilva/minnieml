package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class FindReferencesTests extends BaseEffFunSuite:

  test("a protocol signature finds calls to every selected instance") {
    val code = """protocol Inspect 'T =
  fn inspect(value: 'T): Int;
;
instance Inspect Int = fn inspect(value) = value;; ;
instance Inspect Bool = fn inspect(value) = 0;; ;
let integer = inspect 1;
let boolean = inspect true;
let anotherInteger = inspect 2;"""

    semStateNotFailed(code).map { state =>
      val signature     = SrcSpan(SrcPoint(2, 6, 27), SrcPoint(2, 13, 34))
      val integerUse    = SrcSpan(SrcPoint(6, 15, 171), SrcPoint(6, 22, 178))
      val booleanUse    = SrcSpan(SrcPoint(7, 15, 196), SrcPoint(7, 22, 203))
      val anotherIntUse = SrcSpan(SrcPoint(8, 22, 231), SrcPoint(8, 29, 238))

      assertEquals(
        AstLookup.findReferencesAt(state, 2, 6, includeDeclaration = false),
        List(integerUse, booleanUse, anotherIntUse)
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 2, 6, includeDeclaration = true),
        List(signature, integerUse, booleanUse, anotherIntUse)
      )
    }
  }

  test("a concrete protocol member and its call keep references within that instance") {
    val code = """protocol Inspect 'T =
  fn inspect(value: 'T): Int;
;
instance Inspect Int = fn inspect(value) = value;; ;
instance Inspect Bool = fn inspect(value) = 0;; ;
let integer = inspect 1;
let boolean = inspect true;
let anotherInteger = inspect 2;"""

    semStateNotFailed(code).map { state =>
      val integerUse         = SrcSpan(SrcPoint(6, 15, 171), SrcPoint(6, 22, 178))
      val booleanUse         = SrcSpan(SrcPoint(7, 15, 196), SrcPoint(7, 22, 203))
      val anotherIntUse      = SrcSpan(SrcPoint(8, 22, 231), SrcPoint(8, 29, 238))
      val integerDeclaration = AstLookup.findDefinitionAt(state.module, 6, 15)
      assertEquals(integerDeclaration.size, 1)

      assertEquals(
        AstLookup.findReferencesAt(state, 4, 27, includeDeclaration = false),
        List(integerUse, anotherIntUse)
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 6, 15, includeDeclaration = false),
        List(integerUse, anotherIntUse)
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 6, 15, includeDeclaration = true).toSet,
        (integerDeclaration ++ List(integerUse, anotherIntUse)).toSet
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 7, 15, includeDeclaration = false),
        List(booleanUse)
      )
    }
  }

  test("grouped callee annotations retain type references and definitions") {
    val code = """type Number = Int;
fn identity(value: Int): Int = value;;
let result = ((identity): Number -> Number) 1;"""
    semStateNotFailed(code).map { state =>
      val module = state.module
      val alias = module.members
        .collectFirst {
          case alias: TypeAlias if alias.name == "Number" => alias
        }
        .getOrElse(fail("Expected Number alias"))
      val parameterType = SrcSpan(SrcPoint(3, 27, 84), SrcPoint(3, 33, 90))
      val resultType    = SrcSpan(SrcPoint(3, 37, 94), SrcPoint(3, 43, 100))

      assertEquals(
        AstLookup.findReferencesAt(state, 1, 6, includeDeclaration = false),
        List(parameterType, resultType)
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 3, 27, includeDeclaration = false),
        List(parameterType, resultType)
      )
      assertEquals(
        AstLookup.findDefinitionAt(module, 3, 37),
        List(spanOrFail(alias, "Number alias"))
      )
    }
  }

  private def spanOrFail(node: FromSource, label: String): SrcSpan =
    node.source.spanOpt.getOrElse(fail(s"Missing source span for $label"))

  test("find references from a constructor annotation without widening its source span") {
    val code =
      "struct Box { value: Int }; let constructor = Box: Int -> Box 1; // trailing comment"

    semStateNotFailed(code).map { state =>
      val module          = state.module
      val declarationSpan = SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 27, 26))
      val annotationSpan  = SrcSpan(SrcPoint(1, 58, 57), SrcPoint(1, 61, 60))
      val declarationRefs = AstLookup.findReferencesAt(state, 1, 8, includeDeclaration = true)
      val annotationRefs  = AstLookup.findReferencesAt(state, 1, 58, includeDeclaration = true)

      assertEquals(declarationRefs, List(declarationSpan, annotationSpan))
      assertEquals(annotationRefs, declarationRefs)
      assertEquals(
        AstLookup.findReferencesAt(state, 1, 58, includeDeclaration = false),
        AstLookup.findReferencesAt(state, 1, 8, includeDeclaration  = false)
      )

      val constructor = AstLookup
        .findAt(module, 1, 46)
        .getOrElse(fail("Expected constructor reference"))
      assertEquals(constructor.span, Some(SrcSpan(SrcPoint(1, 46, 45), SrcPoint(1, 49, 48))))
      assertEquals(AstLookup.findAt(module, 1, 58), None)

      val punctuationAndTrivia = List(49, 50, 54, 55, 57, 61, 64, 65)
      punctuationAndTrivia.foreach { col =>
        assertEquals(
          AstLookup.findReferencesAt(state, 1, col, includeDeclaration = true),
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

    semStateNotFailed(code).map { state =>
      val m = state.module
      // Find the add function Bnd and use its span
      val addBnd = m.members.collectFirst { case bnd: Bnd if bnd.name == "add" => bnd }
      assert(addBnd.isDefined, "Could not find 'add' binding")
      val bnd     = addBnd.get
      val bndSpan = spanOrFail(bnd, "binding 'add'")
      val fnLine  = bndSpan.start.line
      val fnCol   = bndSpan.start.col

      val refs = AstLookup.findReferencesAt(state, fnLine, fnCol, includeDeclaration = true)

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

    semStateNotFailed(code).map { state =>
      val m = state.module
      // Find the double function and get the first param
      val doubleBnd = m.members.collectFirst { case bnd: Bnd if bnd.name == "double" => bnd }
      assert(doubleBnd.isDefined, "Could not find 'double' binding")
      val lambda    = doubleBnd.get.value.terms.head.asInstanceOf[Lambda]
      val param     = lambda.params.head
      val paramSpan = spanOrFail(param, "param 'x'")
      val line      = paramSpan.start.line
      val col       = paramSpan.start.col

      val refs = AstLookup.findReferencesAt(state, line, col, includeDeclaration = true)

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

    semStateNotFailed(code).map { state =>
      // Find references at position of `x` declaration (line 2, col ~11)
      val letLine = 2
      val letCol  = 11

      val refs = AstLookup.findReferencesAt(state, letLine, letCol, includeDeclaration = true)

      // Should find: declaration + 2 usages = 3 spans
      assert(
        clue(refs.size) >= 3,
        s"Expected at least 3 references (1 decl + 2 uses), got ${refs.size}: $refs"
      )
    }
  }
