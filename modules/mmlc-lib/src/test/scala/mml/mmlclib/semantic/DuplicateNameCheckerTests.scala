package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class DuplicateNameCheckerTests extends BaseEffFunSuite:

  test("duplicate members parsed from source preserve real source origin"):
    val code =
      """
      let value = 1;
      let value = 2;
      """

    justParse(code).map { module =>
      val sourceState = CompilerState.empty(module, SourceInfo(code), CompilerConfig.default)
      val rewritten   = DuplicateNameChecker.rewriteModule(sourceState)

      val duplicateMember =
        rewritten.module.members.collectFirst { case dm: DuplicateMember => dm }.getOrElse {
          fail("Expected a DuplicateMember for duplicate let bindings")
        }

      val secondDecl = module.members.collect { case b: Bnd => b }.lift(1).getOrElse {
        fail("Expected two bindings in parsed module")
      }

      assertEquals(duplicateMember.source, secondDecl.source)
      assert(duplicateMember.source.spanOpt.exists(_.start.line > 0))
    }

  test("synthetic duplicates stay synthetic and never fabricate source spans"):
    val module = Module(
      source     = SourceOrigin.Synth,
      name       = "SyntheticDuplicateModule",
      visibility = Visibility.Public,
      members    = List(mkSyntheticBinding("x", 1), mkSyntheticBinding("x", 2))
    )

    val state     = CompilerState.empty(module, SourceInfo(""), CompilerConfig.default)
    val rewritten = DuplicateNameChecker.rewriteModule(state)
    val duplicateMember =
      rewritten.module.members.collectFirst { case dm: DuplicateMember => dm }.getOrElse {
        fail("Expected DuplicateMember for synthetic duplicate bindings")
      }

    assertEquals(duplicateMember.source, SourceOrigin.Synth)
    assertEquals(duplicateMember.source.spanOpt, None)

  private def mkSyntheticBinding(name: String, value: Int): Bnd =
    val synthSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))
    Bnd(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth(name),
      value    = Expr(synthSpan, List(LiteralInt(synthSpan, value)))
    )
