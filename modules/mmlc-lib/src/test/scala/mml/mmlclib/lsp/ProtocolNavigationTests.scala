package mml.mmlclib.lsp

import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.{SrcPoint, SrcSpan}
import mml.mmlclib.semantic.{ProtocolError, SemanticError}
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolNavigationTests extends BaseEffFunSuite:

  private val namedSource = """protocol Inspect 'T =
  fn inspect(value: 'T): Int;
  fn rank(value: 'T): Int;
;
type Count = Int;
instance Inspect Count =
  fn inspect(value) = value;;
  fn rank(value) = value;;
;
instance Inspect Bool =
  fn inspect(value) = 0;;
  fn rank(value) = 1;;
;
let integer = inspect 1;
let boolean = inspect true;
let anotherInteger = inspect 2;
let integerRank = rank 3;
let booleanRank = rank false;
fn ordinary(value: Int): Int = value;;
let ordinaryUse = ordinary 4;"""

  private val operatorSource = """protocol Combine 'T =
  op %%(left: 'T, right: 'T): 'T 60 left;
  op %%(value: 'T): 'T 95 right;
;
instance Combine Int =
  op %%(left, right) = left;;
  op %%(value) = value;;
;
instance Combine Bool =
  op %%(left, right) = right;;
  op %%(value) = value;;
;
let integer = 1 %% 2;
let boolean = true %% false;
let unary = %% 3;"""

  /** Expected positions come from the literal fixture text, independently of its AST. */
  private def span(source: String, line: Int, col: Int, length: Int): SrcSpan =
    val offset = source.linesIterator.take(line - 1).map(_.length + 1).sum + col - 1
    SrcSpan(SrcPoint(line, col, offset), SrcPoint(line, col + length, offset + length))

  private def namedSpan(line: Int, col: Int, length: Int): SrcSpan =
    span(namedSource, line, col, length)

  test("named calls lead to their protocol signature after concrete instance selection"):
    semStateNotFailed(namedSource).map { state =>
      List((14, 15), (15, 15), (16, 22)).foreach { (line, col) =>
        assertEquals(AstLookup.findDefinitionAt(state, line, col), List(namedSpan(2, 6, 7)))
      }
      List((17, 19), (18, 19)).foreach { (line, col) =>
        assertEquals(AstLookup.findDefinitionAt(state, line, col), List(namedSpan(3, 6, 4)))
      }
    }

  test("a protocol lists its instance headers and a signature lists only its own members"):
    semStateNotFailed(namedSource).map { state =>
      assertEquals(
        AstLookup.findImplementationsAt(state, 1, 10),
        List(namedSpan(6, 10, 7), namedSpan(10, 10, 7))
      )
      assertEquals(
        AstLookup.findImplementationsAt(state, 2, 6),
        List(namedSpan(7, 6, 7), namedSpan(11, 6, 7))
      )
      assertEquals(
        AstLookup.findImplementationsAt(state, 3, 6),
        List(namedSpan(8, 6, 4), namedSpan(12, 6, 4))
      )
    }

  test("an instance header finds uses of all its members without including another instance"):
    semStateNotFailed(namedSource).map { state =>
      val integerUses = List(namedSpan(14, 15, 7), namedSpan(16, 22, 7), namedSpan(17, 19, 4))
      val booleanUses = List(namedSpan(15, 15, 7), namedSpan(18, 19, 4))

      assertEquals(
        AstLookup.findReferencesAt(state, 6, 10, includeDeclaration = false),
        integerUses
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 6, 10, includeDeclaration = true),
        namedSpan(6, 10, 7) :: integerUses
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 10, 10, includeDeclaration = false),
        booleanUses
      )
    }

  test("member and selected-call references stay concrete while signature references span types"):
    semStateNotFailed(namedSource).map { state =>
      val integerUses = List(namedSpan(14, 15, 7), namedSpan(16, 22, 7))
      val allUses     = List(namedSpan(14, 15, 7), namedSpan(15, 15, 7), namedSpan(16, 22, 7))

      List((7, 6), (14, 15)).foreach { (line, col) =>
        assertEquals(
          AstLookup.findReferencesAt(state, line, col, includeDeclaration = false),
          integerUses
        )
        assertEquals(
          AstLookup.findReferencesAt(state, line, col, includeDeclaration = true),
          namedSpan(7, 6, 7) :: integerUses
        )
      }
      assertEquals(
        AstLookup.findReferencesAt(state, 2, 6, includeDeclaration = true),
        namedSpan(2, 6, 7) :: allUses
      )
    }

  test("ordinary functions and concrete type names keep their definition and reference behavior"):
    semStateNotFailed(namedSource).map { state =>
      assertEquals(AstLookup.findDefinitionAt(state, 20, 19), List(namedSpan(19, 4, 8)))
      assertEquals(AstLookup.findImplementationsAt(state, 19, 4), Nil)
      assertEquals(
        AstLookup.findReferencesAt(state, 19, 4, includeDeclaration = false),
        List(namedSpan(20, 19, 8))
      )
      assertEquals(AstLookup.findDefinitionAt(state, 6, 18), List(namedSpan(5, 1, 17)))
      assertEquals(
        AstLookup.findReferencesAt(state, 6, 18, includeDeclaration = false),
        List(namedSpan(6, 18, 5))
      )
      assertEquals(AstLookup.findImplementationsAt(state, 6, 18), Nil)
      assertEquals(AstLookup.findImplementationsAt(state, 1, 18), Nil)
    }

  test("navigation accepts name ends without crossing whitespace or stealing adjacent tokens"):
    val source = """let left = 1;
let right = 2;
let sum = left+right;
let spaced = left  ;"""

    semStateNotFailed(source).map { state =>
      assertEquals(AstLookup.findDefinitionAt(state, 4, 18), List(span(source, 1, 5, 9)))
      assertEquals(AstLookup.findDefinitionAt(state, 4, 19), Nil)
      assertEquals(AstLookup.findDefinitionAt(state, 3, 15), Nil)
      assertEquals(AstLookup.findDefinitionAt(state, 3, 16), List(span(source, 2, 5, 10)))
      assertEquals(AstLookup.findDefinitionAt(state, 3, 1), Nil)
      assertEquals(
        AstLookup.findReferencesAt(state, 3, 16, includeDeclaration = false),
        List(span(source, 3, 16, 5))
      )
    }

  test("operator navigation distinguishes unary and binary signatures and concrete instances"):
    semStateNotFailed(operatorSource).map { state =>
      def operatorSpan(line: Int, col: Int): SrcSpan = span(operatorSource, line, col, 2)

      assertEquals(AstLookup.findDefinitionAt(state, 13, 17), List(operatorSpan(2, 6)))
      assertEquals(AstLookup.findDefinitionAt(state, 14, 20), List(operatorSpan(2, 6)))
      assertEquals(AstLookup.findDefinitionAt(state, 15, 13), List(operatorSpan(3, 6)))
      assertEquals(
        AstLookup.findImplementationsAt(state, 2, 6),
        List(operatorSpan(6, 6), operatorSpan(10, 6))
      )
      assertEquals(
        AstLookup.findImplementationsAt(state, 3, 6),
        List(operatorSpan(7, 6), operatorSpan(11, 6))
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 5, 10, includeDeclaration = false),
        List(operatorSpan(13, 17), operatorSpan(15, 13))
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 6, 6, includeDeclaration = false),
        List(operatorSpan(13, 17))
      )
      assertEquals(
        AstLookup.findReferencesAt(state, 2, 6, includeDeclaration = false),
        List(operatorSpan(13, 17), operatorSpan(14, 20))
      )
    }

  test("same-named signatures in different protocols retain their selected identities"):
    val source = """protocol IntegerInspect 'T = fn inspect(value: 'T): Int; ;
protocol BooleanInspect 'T = fn inspect(value: 'T): Int; ;
instance IntegerInspect Int = fn inspect(value) = value;; ;
instance BooleanInspect Bool = fn inspect(value) = 0;; ;
let integer = inspect 1;
let boolean = inspect true;"""

    semStateNotFailed(source).map { state =>
      assertEquals(AstLookup.findDefinitionAt(state, 5, 15), List(span(source, 1, 33, 7)))
      assertEquals(AstLookup.findDefinitionAt(state, 6, 15), List(span(source, 2, 33, 7)))
      assertEquals(AstLookup.findImplementationsAt(state, 1, 33), List(span(source, 3, 34, 7)))
      assertEquals(AstLookup.findImplementationsAt(state, 2, 33), List(span(source, 4, 35, 7)))
      assertEquals(
        AstLookup.findReferencesAt(state, 1, 33, includeDeclaration = false),
        List(span(source, 5, 15, 7))
      )
    }

  test("built-in protocol calls do not invent source destinations"):
    val source = "let sum = 1 + 2;"

    semStateNotFailed(source).map { state =>
      assertEquals(AstLookup.findDefinitionAt(state, 1, 13), Nil)
      assertEquals(AstLookup.findImplementationsAt(state, 1, 13), Nil)
      assertEquals(
        AstLookup.findReferencesAt(state, 1, 13, includeDeclaration = true),
        Nil
      )
    }

  test("typed function values preserve the written protocol reference through callable lowering"):
    val source = """protocol Identity 'T = fn identity(value: 'T): 'T; ;
instance Identity Int = fn identity(value) = value;; ;
let selected: Int -> Int = identity;"""

    semStateNotFailed(source).map { state =>
      assertEquals(AstLookup.findDefinitionAt(state, 3, 28), List(span(source, 1, 27, 8)))
      assertEquals(
        AstLookup.findReferencesAt(state, 2, 10, includeDeclaration = false),
        List(span(source, 3, 28, 8))
      )
    }

  test("an incomplete neighboring declaration preserves valid protocol navigation"):
    val source = """protocol Inspect 'T = fn inspect(value: 'T): Int; ;
instance Inspect Int = fn inspect(value) = value;; ;
let result = inspect 1;
let broken: Missing = 0;"""

    FrontEndApi.compile(source, "Recovery").value.map { compiled =>
      val state =
        compiled.fold(error => fail(s"Expected recovered compiler state: $error"), identity)
      assert(state.errors.nonEmpty)
      assertEquals(AstLookup.findDefinitionAt(state, 3, 14), List(span(source, 1, 26, 7)))
      assertEquals(AstLookup.findImplementationsAt(state, 1, 26), List(span(source, 2, 27, 7)))
      assertEquals(
        AstLookup.findReferencesAt(state, 2, 10, includeDeclaration = false),
        List(span(source, 3, 14, 7))
      )
    }

  test("implementation navigation excludes an incomplete rejected instance"):
    val source = """protocol Inspect 'T =
  fn inspect(value: 'T): Int;
  fn rank(value: 'T): Int;
;
instance Inspect Int =
  fn inspect(value) = value;;
;
instance Inspect Bool =
  fn inspect(value) = 0;;
  fn rank(value) = 1;;
;"""

    FrontEndApi.compile(source, "RejectedInstance").value.map { compiled =>
      val state =
        compiled.fold(error => fail(s"Expected recovered compiler state: $error"), identity)

      state.semanticErrors.toList match
        case List(SemanticError.ProtocolErrorFound(error: ProtocolError.MissingMember, _)) =>
          assertEquals(
            error.signature.id,
            protocolSignature(state.module, "Inspect", "rank").id
          )
          assertEquals(error.instance.protocol.nameNode.spanOpt, Some(span(source, 5, 10, 7)))
        case other => fail(s"Expected one missing-member rejection, got $other")

      assertEquals(AstLookup.findImplementationsAt(state, 1, 10), List(span(source, 8, 10, 7)))
      assertEquals(AstLookup.findImplementationsAt(state, 2, 6), List(span(source, 9, 6, 7)))
      assertEquals(AstLookup.findImplementationsAt(state, 3, 6), List(span(source, 10, 6, 4)))
    }
