package mml.mmlclib.util.error.print

import cats.syntax.option.*
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

class ParsingErrorPrinterTests extends BaseEffFunSuite:

  test("nested parser errors retain their message, location, and recovery text in CLI output") {
    val code = "let x = let 123invalid = 5; 6;"

    semState(code).map { result =>
      val diagnostic = result.errors match
        case List(error: SemanticError.TermErrorFound) => error
        case other => fail(s"Expected only the nested parse error, got $other")
      val error = diagnostic.error
      val span  = error.spanOpt.getOrElse(fail("Expected nested parse error source span"))
      assertEquals(diagnostic.message, "Parse error in expression: Invalid identifier '123invalid'")

      val sourceInfo = SourceInfo(code)
      val output     = ErrorPrinter.prettyPrintSemanticErrors(result.errors, sourceInfo.some)
      assert(output.contains(error.message), output)
      assert(output.contains(ErrorPrinter.formatLocation(span)), output)
      assert(output.contains(sourceInfo.slice(span.start.index, span.end.index)), output)

      val withoutSource = SemanticErrorPrinter.prettyPrint(result.errors)
      assert(withoutSource.contains(error.message), withoutSource)
      assert(withoutSource.contains(LocationPrinter.printSpan(span)), withoutSource)
      assert(withoutSource.contains("\n123invalid"), withoutSource)
    }
  }
