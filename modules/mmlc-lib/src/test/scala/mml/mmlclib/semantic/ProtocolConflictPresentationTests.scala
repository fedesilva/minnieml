package mml.mmlclib.semantic

import mml.mmlclib.ast.SrcSpan
import mml.mmlclib.lsp.{Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.{ErrorPrinter, SemanticErrorPrinter, diagnosticMessage}

class ProtocolConflictPresentationTests extends BaseEffFunSuite:

  List(("true", "Bool", false), ("1.5", "Float", true)).foreach {
    (argument, typeName, missingInstance) =>
      test(s"CLI and LSP explain Int/$typeName uses at both argument locations"):
        val source = s"""
          protocol Render 'T = fn render(label: String, value: 'T): Unit; ;
          instance Render Int = fn render(label, value) = ();; ;
          instance Render Bool = fn render(label, value) = ();; ;
          fn example(): Unit =
            let render_again = render "partial: ";
            render_again 15;
            render_again $argument;
          ;
        """
        parseNotFailed(source).map { parsed =>
          val initial = prepareForTypeChecking(source, parsed)
          assertEquals(initial.errors, Vector.empty)

          val result = TypeChecker.rewriteModule(initial)
          val conflicts = result.visibleErrors.collect {
            case error @ SemanticError.ProtocolErrorFound(_: ProtocolError.ConflictingUses, _) =>
              error
          }
          conflicts match
            case Vector(SemanticError.ProtocolErrorFound(conflict, _)) =>
              val info     = SourceInfo(source)
              val first    = info.pointAt(source.indexOf("15;"))
              val second   = info.pointAt(source.lastIndexOf(s"$argument;"))
              val headline = "Cannot infer type of 'render_again': conflicting uses"
              val expected = List(
                headline,
                s"  ${first.line}:${first.col}: requires Render Int",
                s"  ${second.line}:${second.col}: requires Render $typeName"
              ) :+ "This binding must select one implementation for all uses."
              val message = diagnosticMessage(conflict)
              assertEquals(message.linesIterator.toList, expected)

              val span = conflict.source.spanOpt
                .getOrElse(fail("Expected initializer reference span"))
              assertEquals(source.substring(span.start.index, span.end.index), "render")

              val diagnostics = Diagnostics
                .fromCompilerState(result)
                .sortBy(diagnostic => diagnostic.range.start.line)
              assertEquals(diagnostics.size, 3)
              assertEquals(diagnostics.head.message, message)
              assertEquals(diagnostics.head.range, Range.fromSrcSpan(span))
              val firstUse  = source.indexOf("render_again 15")
              val secondUse = source.indexOf(s"render_again $argument")
              val unknown   = "Cannot resolve type for 'render_again'"
              val secondMessage =
                if missingInstance then
                  "No instance of protocol 'Render' for Float provides 'render'"
                else unknown
              assertEquals(diagnostics.map(_.message), List(message, unknown, secondMessage))
              val firstSpan =
                SrcSpan(info.pointAt(firstUse), info.pointAt(firstUse + "render_again".length))
              val secondLength =
                if missingInstance then s"render_again $argument".length else "render_again".length
              val secondSpan =
                SrcSpan(info.pointAt(secondUse), info.pointAt(secondUse + secondLength))
              assertEquals(diagnostics(1).range, Range.fromSrcSpan(firstSpan))
              assertEquals(diagnostics(2).range, Range.fromSrcSpan(secondSpan))

              val errors = result.visibleErrors.collect { case error: SemanticError =>
                error
              }.toList
              val cli                = ErrorPrinter.prettyPrintSemanticErrors(errors, Some(info))
              val semanticCli        = SemanticErrorPrinter.prettyPrint(errors, Some(info))
              val positionedHeadline = s"$headline at ${ErrorPrinter.formatLocation(span)}"
              List(cli, semanticCli).foreach { printed =>
                assert(printed.contains(positionedHeadline), printed)
                expected.tail.foreach(detail => assert(printed.contains(detail), printed))
                assert(
                  printed.contains(s"$unknown at ${ErrorPrinter.formatLocation(firstSpan)}"),
                  printed
                )
                assert(
                  printed.contains(s"$secondMessage at ${ErrorPrinter.formatLocation(secondSpan)}"),
                  printed
                )
              }
            case other => fail(s"Expected one conflicting-use diagnostic, got $other")
        }
  }
