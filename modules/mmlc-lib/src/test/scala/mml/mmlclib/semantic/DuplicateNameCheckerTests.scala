package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class DuplicateNameCheckerTests extends BaseEffFunSuite:

  List(
    "inline" -> "fn main(): Int = { x: Int, x: Int -> x + x } 1 2;;",
    "top-level value" -> "let duplicate = { x: Int, x: Int -> x + x };",
    "local value" -> """
      fn main(): Int =
        let duplicate = { x: Int, x: Int -> x + x };
        duplicate 1 2;
      ;
    """,
    "argument" -> """
      fn apply(f: Int -> Int -> Int): Int = f 1 2;;
      fn main(): Int = apply { x: Int, x: Int -> x + x };;
    """,
    "named function" -> "fn duplicate(x: Int, x: Int): Int = x + x;;"
  ).foreach { (position, source) =>
    test(s"duplicate parameters in a $position lambda are rejected explicitly") {
      semState(source).map { result =>
        val duplicates = result.errors.collect { case error: SemanticError.DuplicateName => error }
        assertEquals(duplicates.size, 1)
        val params = duplicates.head.duplicates.collect { case param: FnParam => param }
        assertEquals(params.size, 2)
        assert(params.forall(_.source.isFromSource))
      }
    }
  }

  test("duplicate parameter errors accumulate across sibling lambdas") {
    semState("""
      fn main(): Int =
        let first = { x: Int, x: Int -> x + x };
        let second = { y: Int, y: Int -> y + y };
        0;
      ;
    """).map { result =>
      val duplicates = result.errors.collect { case error: SemanticError.DuplicateName => error }
      assertEquals(duplicates.size, 2)
    }
  }

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

  test("sequential local let rebindings are rejected as duplicate names"):
    val code =
      """
      fn main(): Int =
        let value = 1;
        let value = 2;
        value;
      ;
      """

    semState(code).map { result =>
      val duplicateError =
        result.errors
          .collectFirst {
            case e: SemanticError.DuplicateName if e.name == "value" => e
          }
          .getOrElse(fail("Expected DuplicateName error for local rebindings"))

      val duplicateParams = duplicateError.duplicates.collect { case param: FnParam => param }
      assertEquals(duplicateParams.length, 2)
      assert(duplicateParams.forall(_.source.isFromSource))
    }

  test("statement wrappers do not hide duplicate local rebindings"):
    val code =
      """
      fn main(): Int =
        let value = 1;
        println "";
        let value = 2;
        value;
      ;
      """

    semState(code).map { result =>
      val duplicateError =
        result.errors
          .collectFirst {
            case e: SemanticError.DuplicateName if e.name == "value" => e
          }
          .getOrElse(fail("Expected DuplicateName error across statement chain"))

      val duplicateParams = duplicateError.duplicates.collect { case param: FnParam => param }
      assertEquals(duplicateParams.length, 2)
    }

  test("nested lambda scopes may still shadow outer local names"):
    val code =
      """
      fn main(): Int =
        let value = 1;
        let keep = {
          x: Int ->
            let value = x;
            value;
        };
        value;
      ;
      """

    semNotFailed(code).map { module =>
      val duplicateErrors =
        module.members.collect { case _: DuplicateMember => () }
      assertEquals(duplicateErrors.length, 0)
    }

  private def mkSyntheticBinding(name: String, value: Int): Bnd =
    val synthSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))
    Bnd(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth(name),
      value    = Expr(synthSpan, List(LiteralInt(synthSpan, value)))
    )
