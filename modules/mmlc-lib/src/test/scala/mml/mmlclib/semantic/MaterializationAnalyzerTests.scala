package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*

class MaterializationAnalyzerTests extends BaseEffFunSuite:

  // ---- helpers ------------------------------------------------------------

  /** Find the top-level binding by name and return its lambda value. */
  private def topLambda(module: Module, name: String): Lambda =
    module.members
      .collectFirst {
        case b: Bnd if b.name == name =>
          b.value match
            case TXExprLambda(lambda) => lambda
            case _ => fail(s"Binding '$name' does not hold a lambda")
      }
      .getOrElse(fail(s"No binding named '$name'"))

  private def isDirect(lambda: Lambda): Boolean =
    // The assertion depends on AST metadata that the compiler does not expose. Its replacement
    // must check lowering: plain entries are an emitter optimization, not a lambda category.
    /*
    lambda.meta.exists(_.isDirect)
     */
    fail("LambdaMeta.isDirect is absent from the parent AST.")

  // Pending nullary immediate-application rejection; see unify-lambdas-ignored-tests.md.
  test("nullary lambda literal in immediate application is direct".ignore) {
    val code =
      """
        fn main(): Int = ({ 42; } ());;
      """
    semNotFailed(code).map { module =>
      val main = topLambda(module, "main")
      def findLit(term: Term): Option[Lambda] = term match
        case app: App =>
          app.fn match
            case l: Lambda => Some(l)
            case _ => None
        case group: TermGroup =>
          group.inner.terms.iterator.map(findLit).collectFirst { case Some(l) => l }
        case _ => None
      val lit = main.body.terms.iterator
        .map(findLit)
        .collectFirst { case Some(l) => l }
        .getOrElse(fail("expected immediate-application body"))
      assert(isDirect(lit), "immediately-applied nullary lambda is direct")
    }
  }
