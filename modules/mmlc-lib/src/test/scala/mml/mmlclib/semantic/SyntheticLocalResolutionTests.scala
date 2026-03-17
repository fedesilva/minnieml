package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class SyntheticLocalResolutionTests extends BaseEffFunSuite:

  private def bindingBody(module: Module, name: String): Expr =
    bindingValue(module, name).terms
      .collectFirst { case lambda: Lambda => lambda.body }
      .getOrElse(fail(s"Expected binding body for '$name'"))

  private def bindingValue(module: Module, name: String): Expr =
    module.members
      .collectFirst { case bnd: Bnd if bnd.name == name => bnd }
      .map(_.value)
      .getOrElse(fail(s"Expected binding body for '$name'"))

  private def collectParams(term: Term): List[FnParam] =
    term match
      case Expr(_, terms, _, _) => terms.flatMap(collectParams)
      case Lambda(_, params, body, _, _, _, _) => params ++ collectParams(body)
      case App(_, fn, arg, _, _) =>
        collectParamsFromAppFn(fn) ++ collectParams(arg)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        collectParams(cond) ++ collectParams(ifTrue) ++ collectParams(ifFalse)
      case TermGroup(_, inner, _) => collectParams(inner)
      case Tuple(_, elements, _, _) => elements.toList.flatMap(collectParams)
      case InvalidExpression(_, originalExpr, _, _) => collectParams(originalExpr)
      case ref: Ref => ref.qualifier.toList.flatMap(collectParams)
      case _ => Nil

  private def collectParamsFromAppFn(fn: Ref | App | Lambda): List[FnParam] =
    fn match
      case _:      Ref => Nil
      case app:    App => collectParamsFromAppFn(app.fn) ++ collectParams(app.arg)
      case lambda: Lambda => collectParams(lambda)

  private def collectRefs(term: Term): List[Ref] =
    term match
      case expr:   Expr => expr.terms.flatMap(collectRefs)
      case lambda: Lambda => lambda.captures ++ collectRefs(lambda.body)
      case App(_, fn, arg, _, _) =>
        collectRefsFromAppFn(fn) ++ collectRefs(arg)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        collectRefs(cond) ++ collectRefs(ifTrue) ++ collectRefs(ifFalse)
      case TermGroup(_, inner, _) => collectRefs(inner)
      case Tuple(_, elements, _, _) => elements.toList.flatMap(collectRefs)
      case InvalidExpression(_, originalExpr, _, _) => collectRefs(originalExpr)
      case ref: Ref => ref :: ref.qualifier.toList.flatMap(collectRefs)
      case _ => Nil

  private def collectRefsFromAppFn(fn: Ref | App | Lambda): List[Ref] =
    fn match
      case ref:    Ref => collectRefs(ref)
      case app:    App => collectRefs(app)
      case lambda: Lambda => collectRefs(lambda)

  private def assertIndexedParam(module: Module, param: FnParam): String =
    val id = clue(param.id).getOrElse(fail(s"Expected id for synthetic param '${param.name}'"))
    assertEquals(module.resolvables.lookup(id), Some(param))
    id

  private def assertResolvedRefs(module: Module, term: Term, param: FnParam): Unit =
    val id   = assertIndexedParam(module, param)
    val refs = collectRefs(term).filter(_.resolvedId.contains(id))
    assert(refs.nonEmpty, s"Expected ref resolved to synthetic param '${param.name}'")
    refs.foreach { ref =>
      assertEquals(ref.candidateIds, List(id))
    }

  test("partial application synthetic params are resolved and indexed") {
    val code =
      """
        fn mult(a: Int, b: Int): Int = a * b;
        let partial = mult 1;
      """

    semNotFailed(code).map { module =>
      val partialValue = bindingValue(module, "partial")
      val etaParam =
        collectParams(partialValue)
          .find(_.name.startsWith("$p"))
          .getOrElse(fail("Expected eta-expanded synthetic param"))

      assertResolvedRefs(module, partialValue, etaParam)
    }
  }

  test("ownership temp wrappers resolve synthetic locals and index discard params") {
    val code =
      """
        fn print_it(s: String): Unit = println s;

        fn main(): Unit =
          print_it (int_to_str 1)
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody      = bindingBody(module, "main")
      val syntheticTemp = collectParams(mainBody).filter(_.name.startsWith("__tmp_"))
      val discardParams = collectParams(mainBody).filter(_.name == "_")

      assert(syntheticTemp.nonEmpty, "Expected ownership temp wrapper params")
      syntheticTemp.foreach(assertResolvedRefs(module, mainBody, _))

      assert(discardParams.nonEmpty, "Expected discard params in ownership wrapper")
      discardParams.foreach(assertIndexedParam(module, _))
    }
  }

  test("terminal free wrappers resolve __ownership_result and index discard params") {
    val code =
      """
        fn main(): Unit =
          let s = int_to_str 1;
          println "done"
        ;
      """

    semNotFailed(code).map { module =>
      val mainBody = bindingBody(module, "main")
      val ownershipResult =
        collectParams(mainBody)
          .find(_.name == "__ownership_result")
          .getOrElse(fail("Expected __ownership_result wrapper param"))

      assertResolvedRefs(module, mainBody, ownershipResult)
      collectParams(mainBody).filter(_.name == "_").foreach(assertIndexedParam(module, _))
    }
  }
