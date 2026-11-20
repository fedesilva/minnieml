package mml.mmlclib.lsp

import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

class ProtocolConstraintNavigationTests extends BaseEffFunSuite:

  private val source = """protocol Leaf 'T where 'T: Middle & Root =
  fn leaf(value: 'T): Int;
;
protocol Middle 'T where 'T: Root =
  fn middle(value: 'T): Int;
;
protocol Root 'T =
  fn root(value: 'T): Int;
;
instance Root Int = fn root(value) = value;;;
instance Root Bool = fn root(value) = 0;;;
instance Middle Int = fn middle(value) = root value;;;
instance Middle Bool = fn middle(value) = root value;;;
instance Leaf Int = fn leaf(value) = middle value;;;
instance Leaf Bool = fn leaf(value) = middle value;;;
fn generic(value: 'T): Int where 'T: Leaf = root value;;
fn outer(value: 'T): Int where 'T: Leaf =
  fn local(inner: 'U): Int where 'U: Root = root inner;;
  local value;
;
let first = generic 1;
let second = generic true;
let third = outer 2;
let fourth = outer false;"""

  /** Locations come from fixture text, independently of compiler source metadata. */
  private def spanIn(text: String, context: String, name: String): SrcSpan =
    val start = text.indexOf(context)
    assert(start >= 0, s"Missing fixture context: $context")
    val relative = context.indexOf(name)
    assert(relative >= 0, s"Missing name in fixture context: $name")
    val index  = start + relative
    val prefix = text.take(index)
    val line   = prefix.count(_ == '\n') + 1
    val col    = index - prefix.lastIndexOf('\n')
    SrcSpan(SrcPoint(line, col, index), SrcPoint(line, col + name.length, index + name.length))

  private def span(context: String, name: String): SrcSpan = spanIn(source, context, name)

  test("written prerequisites support hover, definition, implementations, and references"):
    semStateNotFailed(source).map { state =>
      val rootDeclaration = span("protocol Root", "Root")
      val rootInstances =
        List(span("instance Root Int", "Root"), span("instance Root Bool", "Root"))
      val rootUses = List(
        span("Middle & Root", "Root"),
        span("protocol Middle 'T where 'T: Root", "Root")
      ) ++ rootInstances :+ span("fn local(inner: 'U): Int where 'U: Root", "Root")

      rootUses.filterNot(rootInstances.contains).foreach { use =>
        val hover = AstLookup.findAt(state.module, use.start.line, use.start.col)
        assertEquals(hover, Some(LookupResult(None, Some("Root"), Some(use))))
        List(use.start.col, use.end.col).foreach { col =>
          assertEquals(
            AstLookup.findDefinitionAt(state, use.start.line, col),
            List(rootDeclaration)
          )
          assertEquals(AstLookup.findImplementationsAt(state, use.start.line, col), rootInstances)
          assertEquals(AstLookup.findReferencesAt(state, use.start.line, col, false), rootUses)
        }
      }
      assertEquals(
        AstLookup
          .findReferencesAt(state, rootDeclaration.start.line, rootDeclaration.start.col, true),
        (rootDeclaration :: rootUses).sortBy(_.start.index)
      )
      val middleUse = span("'T: Middle", "Middle")
      assertEquals(
        AstLookup.findDefinitionAt(state, middleUse.start.line, middleUse.start.col),
        List(span("protocol Middle", "Middle"))
      )
    }

  test("protocol and function constraints keep written locations across specialization"):
    semStateNotFailed(source).map { state =>
      val declaration = span("protocol Leaf", "Leaf")
      val uses = List(
        span("instance Leaf Int", "Leaf"),
        span("instance Leaf Bool", "Leaf"),
        span("fn generic(value: 'T): Int where 'T: Leaf", "Leaf"),
        span("fn outer(value: 'T): Int where 'T: Leaf", "Leaf")
      )
      assertEquals(
        AstLookup.findReferencesAt(state, declaration.start.line, declaration.start.col, false),
        uses
      )
      uses.takeRight(2).foreach { use =>
        assertEquals(
          AstLookup.findDefinitionAt(state, use.start.line, use.start.col),
          List(declaration)
        )
      }
      val protocol = state.module.members
        .collectFirst {
          case protocol: Protocol if protocol.name == "Leaf" => protocol
        }
        .getOrElse(fail("Missing Leaf declaration"))
      assertEquals(
        protocol.constraints.flatMap(_.constraints).flatMap(_.nameNode.spanOpt),
        List(span("'T: Middle", "Middle"), span("Middle & Root", "Root"))
      )
    }

  test("inherited member calls in generic bodies navigate to the prerequisite signature"):
    semStateNotFailed(source).map { state =>
      val use = span("= root value;;\nfn outer", "root")
      assertEquals(
        AstLookup.findDefinitionAt(state, use.start.line, use.start.col),
        List(span("fn root(value: 'T)", "root"))
      )
      assertEquals(
        AstLookup.findImplementationsAt(state, use.start.line, use.start.col),
        List(
          span("instance Root Int = fn root", "root"),
          span("instance Root Bool = fn root", "root")
        )
      )
    }

  test("an unresolved prerequisite does not hide navigation to a valid sibling"):
    val broken = """protocol Broken 'T where 'T: Missing & Root = fn broken(value: 'T): Int;;
protocol Root 'T = fn root(value: 'T): Int;;
instance Root Int = fn root(value) = value;;;"""
    FrontEndApi.compile(broken, "UnresolvedPrerequisite").value.map { result =>
      val state = result.fold(error => fail(s"Expected recovered compiler state: $error"), identity)
      assert(state.errors.nonEmpty)
      val missing = spanIn(broken, "Missing &", "Missing")
      assertEquals(
        AstLookup.findAt(state.module, missing.start.line, missing.start.col),
        Some(LookupResult(None, Some("Missing"), Some(missing)))
      )
      assertEquals(AstLookup.findDefinitionAt(state, missing.start.line, missing.start.col), Nil)
      assertEquals(
        AstLookup.findImplementationsAt(state, missing.start.line, missing.start.col),
        Nil
      )
      assertEquals(
        AstLookup.findReferencesAt(state, missing.start.line, missing.start.col, false),
        Nil
      )
      val valid = spanIn(broken, "Missing & Root", "Root")
      assertEquals(
        AstLookup.findDefinitionAt(state, valid.start.line, valid.start.col),
        List(spanIn(broken, "protocol Root", "Root"))
      )
    }

  test("AST printing retains written prerequisite order without adding transitive constraints"):
    semStateNotFailed(source).map { state =>
      val printed = prettyPrintAst(state.module).linesIterator.map(_.trim).mkString("\n")
      assert(printed.contains("Protocol Leaf 'T\nwhere 'T: Middle & Root"))
      assert(printed.contains("Protocol Middle 'T\nwhere 'T: Root"))
      assert(printed.contains("where 'T: Leaf"))
      assert(!printed.contains("where 'T: Leaf &"))
    }

  private val memberSource = """protocol Read 'T = fn read(x: 'T): Int;;
instance Read Int = fn read(x) = x;;;
protocol Apply 'T =
  fn apply(key: 'T, f: 'T -> 'U): 'U where 'U: Read;
  op ==>(arg: 'T, g: 'T -> 'U): 'U 40 left where 'U: Read;
;
instance Apply Int =
  fn apply(key, f) = keep 'U (f key);;
  op ==>(arg, g: Int -> 'U): 'U = g arg;;
;
fn keep(x: 'A): 'A = x;;
fn identity(x: Int): Int = x;;
let first = apply 3 identity;
let second = 4 ==> identity;"""

  test("member type variables navigate by binder identity across signatures and implementations"):
    semStateNotFailed(memberSource).map { state =>
      def at(context: String, name: String): SrcSpan = spanIn(memberSource, context, name)
      val function = at("f: 'T -> 'U", "'U")
      val operator = at("g: 'T -> 'U", "'U")
      val functionUses =
        List(at("): 'U where", "'U"), at("where 'U: Read;", "'U"), at("keep 'U", "'U"))
      val operatorUses = List(
        at("): 'U 40", "'U"),
        at("left where 'U", "'U"),
        at("g: Int -> 'U", "'U"),
        at("): 'U = g", "'U")
      )
      for (binder, uses) <- List(function -> functionUses, operator -> operatorUses) do
        assertEquals(
          AstLookup.findReferencesAt(state, binder.end.line, binder.end.col, false),
          uses
        )
        for occurrence <- binder :: uses do
          val pos = occurrence.start
          assertEquals(
            AstLookup.findAt(state.module, pos.line, pos.col).flatMap(_.span),
            Some(occurrence)
          )
          assertEquals(AstLookup.findDefinitionAt(state, pos.line, pos.col), List(binder))
          assertEquals(AstLookup.findReferencesAt(state, pos.line, pos.col, false), uses)
          assertEquals(AstLookup.findReferencesAt(state, pos.line, pos.col, true), binder :: uses)
      val header = at("protocol Apply 'T", "'T")
      val key    = at("key: 'T", "'T")
      assertEquals(AstLookup.findDefinitionAt(state, key.start.line, key.start.col), List(header))
    }

  test(
    "an explicit variable on a member call selects its caller binder and preserves the name range"
  ):
    val text = """protocol Keep 'T = fn keep(key: 'T, value: 'U): 'U;;
instance Keep Int = fn keep(key, value) = value;;;
fn forward(key: Int, value: 'A): 'A = keep 'A key value;;
let result = forward 1 true;"""
    semStateNotFailed(text).map { state =>
      val binder   = spanIn(text, "value: 'A", "'A")
      val argument = spanIn(text, "keep 'A key", "'A")
      val call     = spanIn(text, "keep 'A key", "keep")
      val member   = spanIn(text, "fn keep(key:", "keep")
      for col <- argument.start.col until argument.end.col do
        assertEquals(AstLookup.findDefinitionAt(state, argument.start.line, col), List(binder))
      assertEquals(AstLookup.findDefinitionAt(state, call.start.line, call.start.col), List(member))
      assertEquals(
        AstLookup.findReferencesAt(state, member.start.line, member.start.col, false),
        List(call)
      )
    }

  test(
    "generic member uses and requirements lead to their written declarations after specialization"
  ):
    semStateNotFailed(memberSource).map { state =>
      def at(context: String, name: String): SrcSpan = spanIn(memberSource, context, name)
      val required = List(at("'U where 'U: Read", "Read"), at("left where 'U: Read", "Read"))
      val impl = at("instance Read Int", "Read")
      for occurrence <- required do
        val pos = occurrence.start
        assertEquals(
          AstLookup.findAt(state.module, pos.line, pos.col).flatMap(_.name),
          Some("Read")
        )
        assertEquals(
          AstLookup.findDefinitionAt(state, pos.line, pos.col),
          List(at("protocol Read", "Read"))
        )
        assertEquals(AstLookup.findImplementationsAt(state, pos.line, pos.col), List(impl))
        assertEquals(AstLookup.findReferencesAt(state, pos.line, pos.col, false), impl :: required)
      for (call, signature, body) <- List(
          (at("apply 3", "apply"), at("fn apply(key: ", "apply"), at("fn apply(key,", "apply")),
          (at("4 ==> identity", "==>"), at("op ==>(arg:", "==>"), at("op ==>(arg,", "==>"))
        )
      do
        assertEquals(
          AstLookup.findDefinitionAt(state, call.start.line, call.start.col),
          List(signature)
        )
        assertEquals(
          AstLookup.findImplementationsAt(state, call.start.line, call.start.col),
          List(body)
        )
        assertEquals(
          AstLookup.findReferencesAt(state, signature.start.line, signature.start.col, false),
          List(call)
        )
      val printed = prettyPrintAst(state.module).linesIterator.map(_.trim).mkString("\n")
      assertEquals(
        printed.linesIterator.sliding(3).count {
          case Seq(signature, "typeParams: 'U", "where 'U: Read") =>
            signature.startsWith("Signature ")
          case _ => false
        },
        2
      )
    }
