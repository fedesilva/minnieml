package mml.mmlclib.util.error.print

import mml.mmlclib.ast.*
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.{SemanticError, TypeError}
import mml.mmlclib.test.BaseEffFunSuite

class TypeErrorPrinterTests extends BaseEffFunSuite:

  test("UnresolvableType prints its shared wording and highlights the node") {
    val code =
      """
        fn main() =
          ???;
        ;
      """
    val holeSpan = SrcSpan(
      SrcPoint(2, 11, 0),
      SrcPoint(2, 14, 0)
    )
    val hole = Hole(holeSpan)
    val err = SemanticError.TypeCheckingError(
      TypeError.UnresolvableType(hole, None, "mml.mmlclib.semantic.TypeChecker")
    )

    val output = ErrorPrinter.prettyPrintSemanticErrors(List(err), Some(SourceInfo(code)))
    assert(output.contains("Cannot resolve type"))
    assert(output.contains("???"))
  }

  test("invalid application should not render value name as type") {
    val code =
      """
        fn main() =
          let f = 1;
          f 2;
        ;
      """
    semState(code).map { result =>
      val output = ErrorPrinter.prettyPrintSemanticErrors(result.errors, Some(SourceInfo(code)))
      assert(output.contains("Cannot apply Int to Int"))
      assert(!output.contains("function of type 'f'"))
    }
  }
