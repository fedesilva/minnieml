package mml.mmlclib.semantic

import mml.mmlclib.errors.{DiagnosticRelation, diagnosticSources}
import mml.mmlclib.lsp.{Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.diagnosticMessage

class DiagnosticCascadeTests extends BaseEffFunSuite:

  test("a conflicting binding and each affected call retain a useful diagnostic"):
    val source = """
      protocol Render 'T = fn render(label: String, value: 'T): Unit; ;
      instance Render Int = fn render(label, value) = ();; ;
      instance Render Bool = fn render(label, value) = ();; ;
      fn example(): Unit =
        let selected = render "prefix";
        selected 15;
        selected 1.5;
        selected true;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val state = TypeChecker.rewriteModule(initial)
      val located = state.visibleErrors
        .map { error =>
          val span = diagnosticSources(error)
            .flatMap(_.spanOpt)
            .headOption
            .getOrElse(fail("Expected a written diagnostic location"))
          (
            span.start.index,
            source.slice(span.start.index, span.end.index),
            diagnosticMessage(error)
          )
        }
        .sortBy(_._1)
      assertEquals(located.size, 4)
      assertEquals(
        located.map { (index, text, _) => index -> text },
        Vector(
          source.indexOf("render \"prefix\"") -> "render",
          source.indexOf("selected 15") -> "selected",
          source.indexOf("selected 1.5") -> "selected 1.5",
          source.indexOf("selected true") -> "selected"
        )
      )
      assert(located.head._3.startsWith("Cannot infer type of 'selected': conflicting uses"))
      assertEquals(located(1)._3, "Cannot resolve type for 'selected'")
      assertEquals(located(2)._3, "No instance of protocol 'Render' for Float provides 'render'")
      assertEquals(located(3)._3, "Cannot resolve type for 'selected'")

      val primary = state.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
      assertEquals(primary.size, 2)
      val visibleConsequences = state.diagnostics.visibleRecords.filter(_.causes.nonEmpty)
      assertEquals(visibleConsequences.size, 2)
      assert(visibleConsequences.forall(_.causes == primary.map(_.id)))
      assert(state.errors.size > state.visibleErrors.size)
    }

  test("repeated missing implementations retain each call location through a local alias"):
    val source = """
      protocol Render 'T = fn render(label: String, value: 'T): Unit; ;
      instance Render Int = fn render(label, value) = ();; ;
      fn example(): Unit =
        let selected = render "prefix";
        let alias = selected;
        selected 15;
        alias 1.5;
        alias 2.5;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val state = TypeChecker.rewriteModule(initial)
      val missing = state.visibleErrors.collect {
        case SemanticError.ProtocolErrorFound(error: ProtocolError.MissingInstance, _) => error
      }
      assertEquals(missing.size, 2)
      val spans = missing.flatMap(_.source.spanOpt).sortBy(_.start.index)
      assertEquals(
        spans.map(span => span.start.index -> source.slice(span.start.index, span.end.index)),
        Vector(
          source.indexOf("alias 1.5") -> "alias 1.5",
          source.indexOf("alias 2.5") -> "alias 2.5"
        )
      )
      val index    = state.module.resolvables
      val expected = ProtocolRegistry.typeKey(typeReference("Float", index), index)
      assertEquals(
        missing.map(error => ProtocolRegistry.typeKey(error.concreteType, index)),
        Vector(expected, expected)
      )
    }

  test("a nested missing implementation keeps its inner location and the outer unresolved use"):
    val source = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      fn example(): Int =
        let selected = identity;
        let first = selected 15;
        selected (selected 1.5);
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val state = TypeChecker.rewriteModule(initial)
      val diagnostics = Diagnostics.fromCompilerState(state).sortBy { diagnostic =>
        (diagnostic.range.start.line, diagnostic.range.start.character)
      }
      val info = SourceInfo(source)
      val expectedUses = List(
        "identity;" -> "identity",
        "selected 15" -> "selected",
        "selected (" -> "selected",
        "selected 1.5" -> "selected 1.5"
      ).map { (fragment, highlighted) =>
        val start = source.indexOf(fragment)
        Range.fromSrcSpan(
          mml.mmlclib.ast.SrcSpan(info.pointAt(start), info.pointAt(start + highlighted.length))
        )
      }
      assertEquals(diagnostics.map(_.range), expectedUses)
      assert(
        diagnostics.head.message.startsWith("Cannot infer type of 'selected': conflicting uses")
      )
      assertEquals(
        diagnostics.tail.map(_.message),
        List(
          "Cannot resolve type for 'selected'",
          "Cannot resolve type for 'selected'",
          "No instance of protocol 'Identity' for Float provides 'identity'"
        )
      )
    }
