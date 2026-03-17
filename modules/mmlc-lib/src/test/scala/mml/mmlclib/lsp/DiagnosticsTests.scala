package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

class DiagnosticsTests extends BaseEffFunSuite:

  test("duplicate diagnostic prefers the first real source span when synthetic entries exist"):
    val code =
      """
      fn print(a: String): Unit = ();
      """

    semState(code).map { result =>
      val duplicateError =
        result.errors.collectFirst { case e: SemanticError.DuplicateName => e }.getOrElse {
          fail("Expected DuplicateName error from redefining stdlib print")
        }

      val diagnostic = Diagnostics.fromError(duplicateError).getOrElse {
        fail("Expected diagnostic for duplicate name with a real user declaration")
      }

      val expectedSpan = duplicateError.duplicates.iterator
        .collect { case fs: FromSource => fs.source.spanOpt }
        .collectFirst { case Some(span) => span }
        .getOrElse(fail("Expected at least one real source span in duplicate group"))
      assertEquals(diagnostic.range, Range.fromSrcSpan(expectedSpan))
    }

  test("duplicate diagnostics are omitted when all duplicates are synthetic"):
    val duplicateError = SemanticError.DuplicateName(
      name       = "x",
      duplicates = List(mkSyntheticBinding("x", 1), mkSyntheticBinding("x", 2)),
      phase      = "test"
    )

    assertEquals(Diagnostics.fromError(duplicateError), None)

  test("duplicate diagnostics emit one item per real duplicate span"):
    val code =
      """
      let a = 1;
      let a = 2;
      let a = 3;
      """

    semState(code).map { result =>
      val duplicateError =
        result.errors
          .collectFirst { case e: SemanticError.DuplicateName if e.name == "a" => e }
          .getOrElse(fail("Expected DuplicateName error for 'a'"))

      val expectedRanges = duplicateError.duplicates.iterator
        .collect { case fs: FromSource => fs.source.spanOpt }
        .collect { case Some(span) => Range.fromSrcSpan(span) }
        .toList

      val actualRanges = Diagnostics
        .fromErrorAll(duplicateError)
        .filter(_.message == "Duplicate definition: a")
        .map(_.range)

      assertEquals(actualRanges, expectedRanges)
    }

  private def mkSyntheticBinding(name: String, value: Int): Bnd =
    val synthSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))
    Bnd(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth(name),
      value    = Expr(synthSpan, List(LiteralInt(synthSpan, value)))
    )
