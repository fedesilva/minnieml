package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class ClosureMemoryFnGeneratorTests extends BaseEffFunSuite:

  private val synth   = SourceOrigin.Synth
  private val intType = TypeRef(synth, "Int", Some("stdlib::typealias::Int"))

  private def qualifierLambda(term: Term): Option[Lambda] =
    term match
      case Ref(_, _, _, _, _, _, Some(TermGroup(_, Expr(_, List(lambda: Lambda), _, _), _))) =>
        Some(lambda)
      case _ =>
        None

  test("tags capturing lambdas nested under selection qualifiers") {
    val capturedRef = Ref(
      source     = synth,
      name       = "a",
      typeSpec   = Some(intType),
      resolvedId = Some("Test::bnd::main::a")
    )
    val lambda = Lambda(
      source   = synth,
      params   = List(FnParam(synth, Name.synth("x"), typeAsc = Some(intType))),
      body     = Expr(synth, List(capturedRef)),
      captures = List(capturedRef)
    )
    val selectedRef = Ref(
      source    = synth,
      name      = "field",
      qualifier = Some(TermGroup(synth, Expr(synth, List(lambda))))
    )
    val binding = Bnd(
      source   = synth,
      nameNode = Name.synth("main"),
      value    = Expr(synth, List(selectedRef)),
      id       = Some("Test::bnd::main")
    )
    val module = Module(
      source     = synth,
      name       = "Test",
      visibility = Visibility.Public,
      members    = List(binding)
    )

    val rewritten = ClosureMemoryFnGenerator
      .rewriteModule(CompilerState.empty(module, SourceInfo(""), CompilerConfig.default))
      .module

    val rewrittenLambda = rewritten.members
      .collectFirst { case bnd: Bnd =>
        bnd.value.terms.collectFirst(Function.unlift(qualifierLambda))
      }
      .flatten
      .getOrElse(fail("Expected lambda nested under selection qualifier"))

    val envStructName = rewrittenLambda.meta
      .flatMap(_.envStructName)
      .getOrElse(fail("Expected envStructName on qualifier lambda"))

    assert(
      rewritten.members.exists {
        case ts: TypeStruct => ts.name == envStructName
        case _ => false
      },
      s"Expected generated env struct '$envStructName'"
    )
  }
