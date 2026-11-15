package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class GenericNavigationTests extends BaseEffFunSuite:

  test("concrete uses navigate to the written generic declaration"):
    semStateNotFailed("""
      fn identity(~value: 'T): 'T = value;;
      fn main(): Int = identity 7;;
    """).map { state =>
      val declaration = state.module.members
        .collectFirst {
          case binding: Bnd if binding.name == "identity" => binding
        }
        .getOrElse(fail("Missing checked generic declaration"))
      val target = declaration.nameNode.source.spanOpt.getOrElse(fail("Missing declaration span"))
      val refs =
        state.module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
          TermTraversal.collect(binding.value) {
            case ref: Ref if ref.sourceId == declaration.id => ref
          }
        }
      assertEquals(refs.size, 1)
      val use = refs.head.source.spanOpt.getOrElse(fail("Missing use span"))
      assert(AstLookup.findDefinitionAt(state, use.start.line, use.start.col).contains(target))
    }

  test("an unused generic body retains parameter navigation"):
    semStateNotFailed("fn identity(~value: 'T): 'T = value;; fn main(): Int = 0;;")
      .map { state =>
        val definition = state.module.members
          .collectFirst {
            case binding: Bnd if binding.name == "identity" => binding
          }
          .getOrElse(fail("Missing unused source declaration"))
        val lambda = definition.value.terms
          .collectFirst { case lambda: Lambda => lambda }
          .getOrElse(fail("Missing source lambda"))
        val parameter = lambda.params.head
        val target    = parameter.source.spanOpt.getOrElse(fail("Missing parameter span"))
        val use = TermTraversal
          .collect(lambda.body) {
            case ref: Ref if ref.resolvedId == parameter.id => ref
          }
          .head
          .source
          .spanOpt
          .getOrElse(fail("Missing parameter use"))
        assert(AstLookup.findDefinitionAt(state, use.start.line, use.start.col).contains(target))
      }

  private def position(source: String, text: String): (Int, Int) =
    val offset = source.indexOf(text)
    assert(offset >= 0, s"Missing fixture text: $text")
    val prefix = source.take(offset)
    (prefix.count(_ == '\n') + 1, offset - prefix.lastIndexOf('\n'))

  test("references and hover use source identities across concrete specializations"):
    val source = """fn identity(~value: 'T): 'T = value;;
let number = identity 7;
let flag = identity true;"""
    semStateNotFailed(source).map { state =>
      val (line, col) = position(source, "identity(~")
      val refs        = AstLookup.findReferencesAt(state, line, col, includeDeclaration = false)
      assertEquals(refs.map(s => (s.start.line, s.start.col)), List((2, 14), (3, 12)))
      refs.foreach { use =>
        assertEquals(
          AstLookup.findReferencesAt(state, use.start.line, use.start.col, false),
          refs
        )
        val hover = AstLookup
          .findAt(state.module, use.start.line, use.start.col)
          .getOrElse(fail("Missing generic use hover"))
        assertEquals(hover.name, Some("identity"))
        assert(hover.typeSpec.exists(_.isInstanceOf[TypeFn]))
      }
      val symbols = AstLookup.collectSymbols(state.module, "file:///generic.mml")
      assertEquals(symbols.map(_.name).sorted, List("flag", "identity", "number"))
    }

  test("shadowed local templates retain separate source definitions and references"):
    val source = """fn identity(~value: 'T): 'T = value;;
fn main(): Int =
  fn identity(~inner: 'T): 'T = inner;;
  identity 7;
;
let flag = identity true;"""
    semStateNotFailed(source).map { state =>
      val templates = state.module.resolvables.resolvables.values.toList
        .collect {
          case binding: Bnd if binding.name == "identity" => binding
        }
        .sortBy(_.source.spanOpt.map(_.start.index))
      assertEquals(templates.size, 2)
      val global = templates.head
      val local  = templates(1)
      assertNotEquals(global.id, local.id)
      List(("identity 7", local), ("identity true", global)).foreach { (text, definition) =>
        val (line, col) = position(source, text)
        val target      = definition.source.spanOpt.getOrElse(fail("Missing definition span"))
        assertEquals(AstLookup.findDefinitionAt(state, line, col), List(target))
        val refs = AstLookup.findReferencesAt(state, target.start.line, target.start.col, false)
        assertEquals(refs.map(s => (s.start.line, s.start.col)), List((line, col)))
      }
      val (line, col) = position(source, "inner;;")
      val target      = AstLookup.findDefinitionAt(state, line, col)
      assertEquals(target.size, 1)
      assertEquals(target.head.start.line, 3)
      assertEquals(AstLookup.findAt(state.module, line, col).flatMap(_.name), Some("inner"))
    }

  test("unused local templates retain body hover and captured parameter navigation"):
    val source = """fn outer(prefix: String): Int =
  fn unused(value: 'T): String = prefix;;
  1;
;"""
    semStateNotFailed(source).map { state =>
      val (line, col) = position(source, "prefix;;")
      val target      = AstLookup.findDefinitionAt(state, line, col)
      assertEquals(target.size, 1)
      assertEquals(target.head.start.line, 1)
      val hover = AstLookup.findAt(state.module, line, col).getOrElse(fail("Missing captured use"))
      assertEquals(hover.name, Some("prefix"))
      assertEquals(AstLookup.formatType(hover.typeSpec), "String")
      assert(
        AstLookup.collectSymbols(state.module, "file:///generic.mml").exists(_.name == "unused")
      )
    }

  test("generic function values and partial applications retain their source use sites"):
    val source = """fn identity(~value: 'T): 'T = value;;
fn first(~left: 'T, right: 'T): 'T = left;;
let selected = identity Int;
let partial: Int -> Int = first 7;
let result = selected (partial 8);"""
    semStateNotFailed(source).map { state =>
      List("identity Int" -> "identity", "first 7" -> "first").foreach { (text, name) =>
        val (line, col) = position(source, text)
        val declaration = state.module.members
          .collectFirst {
            case binding: Bnd if binding.name == name => binding
          }
          .getOrElse(fail(s"Missing $name declaration"))
        assertEquals(
          AstLookup.findDefinitionAt(state, line, col),
          declaration.source.spanOpt.toList
        )
        assertEquals(AstLookup.findAt(state.module, line, col).flatMap(_.name), Some(name))
        val target = declaration.source.spanOpt.getOrElse(fail("Missing declaration source"))
        val refs   = AstLookup.findReferencesAt(state, target.start.line, target.start.col, false)
        assertEquals(refs.map(s => (s.start.line, s.start.col)), List((line, col)))
      }
    }
