package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class FindDefinitionTests extends BaseEffFunSuite:

  test("find definition for local param and let binding") {
    val code =
      """
      fn main(x: Int): Int =
        let y = x + 1;
        y
      ;
      """

    semNotFailed(code).map { m =>
      val mainBnd = m.members
        .collectFirst { case b: Bnd if b.name == "main" => b }
        .getOrElse(fail("Could not find 'main'"))
      val params = collectParamsFromMember(mainBnd)
      val refs   = collectRefsFromMember(mainBnd)

      val xParamOpt = params.find(_.name == "x")
      assert(xParamOpt.isDefined, "Could not find param 'x'")
      val yParamOpt = params.find(_.name == "y")
      assert(yParamOpt.isDefined, "Could not find param 'y'")

      val xRefOpt = refs.find(ref => ref.name == "x" && ref.qualifier.isEmpty)
      assert(xRefOpt.isDefined, "Could not find ref 'x'")
      val yRefOpt = refs.find(ref => ref.name == "y" && ref.qualifier.isEmpty)
      assert(yRefOpt.isDefined, "Could not find ref 'y'")

      val xRef   = xRefOpt.get
      val xDefs  = AstLookup.findDefinitionAt(m, xRef.span.start.line, xRef.span.start.col)
      val xParam = xParamOpt.get
      assert(xDefs.contains(xParam.span), s"Missing param span for 'x': $xDefs")

      val yRef   = yRefOpt.get
      val yDefs  = AstLookup.findDefinitionAt(m, yRef.span.start.line, yRef.span.start.col)
      val yParam = yParamOpt.get
      assert(yDefs.contains(yParam.span), s"Missing param span for 'y': $yDefs")
    }
  }

  private def collectParamsFromMember(member: Member): List[FnParam] =
    member match
      case bnd: Bnd =>
        collectParamsFromExpr(bnd.value)
      case dm: DuplicateMember =>
        collectParamsFromMember(dm.originalMember)
      case im: InvalidMember =>
        collectParamsFromMember(im.originalMember)
      case _ => Nil

  private def collectParamsFromExpr(expr: Expr): List[FnParam] =
    expr.terms.flatMap(collectParamsFromTerm)

  private def collectParamsFromTerm(term: Term): List[FnParam] =
    term match
      case lambda: Lambda =>
        lambda.params ++ collectParamsFromExpr(lambda.body)
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case cond: Cond =>
        collectParamsFromExpr(cond.cond) ++
          collectParamsFromExpr(cond.ifTrue) ++
          collectParamsFromExpr(cond.ifFalse)
      case group: TermGroup =>
        collectParamsFromExpr(group.inner)
      case tuple: Tuple =>
        tuple.elements.toList.flatMap(collectParamsFromExpr)
      case expr: Expr =>
        collectParamsFromExpr(expr)
      case inv: InvalidExpression =>
        collectParamsFromExpr(inv.originalExpr)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectParamsFromTerm)
      case _ => Nil

  private def collectParamsFromAppFn(fn: Ref | App | Lambda): List[FnParam] =
    fn match
      case lambda: Lambda =>
        lambda.params ++ collectParamsFromExpr(lambda.body)
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectParamsFromTerm)

  private def collectRefsFromMember(member: Member): List[Ref] =
    member match
      case bnd: Bnd =>
        collectRefsFromExpr(bnd.value)
      case dm: DuplicateMember =>
        collectRefsFromMember(dm.originalMember)
      case im: InvalidMember =>
        collectRefsFromMember(im.originalMember)
      case _ => Nil

  private def collectRefsFromExpr(expr: Expr): List[Ref] =
    expr.terms.flatMap(collectRefsFromTerm)

  private def collectRefsFromTerm(term: Term): List[Ref] =
    term match
      case ref: Ref =>
        ref :: ref.qualifier.toList.flatMap(collectRefsFromTerm)
      case app: App =>
        collectRefsFromAppFn(app.fn) ++ collectRefsFromExpr(app.arg)
      case lambda: Lambda =>
        collectRefsFromExpr(lambda.body)
      case cond: Cond =>
        collectRefsFromExpr(cond.cond) ++
          collectRefsFromExpr(cond.ifTrue) ++
          collectRefsFromExpr(cond.ifFalse)
      case group: TermGroup =>
        collectRefsFromExpr(group.inner)
      case tuple: Tuple =>
        tuple.elements.toList.flatMap(collectRefsFromExpr)
      case expr: Expr =>
        collectRefsFromExpr(expr)
      case inv: InvalidExpression =>
        collectRefsFromExpr(inv.originalExpr)
      case _ => Nil

  test("go-to-definition on struct constructor resolves to struct declaration") {
    val code =
      """
      struct Pair { fst: Int, snd: Int };
      fn make(a: Int, b: Int): Pair = Pair a b;
      """

    semNotFailed(code).map { m =>
      val structOpt = m.members.collectFirst { case ts: TypeStruct => ts }
      assert(structOpt.isDefined, "Could not find struct 'Pair'")
      val struct = structOpt.get

      val makeBnd = m.members
        .collectFirst { case b: Bnd if b.meta.exists(_.originalName == "make") => b }
        .getOrElse(fail("Could not find 'make'"))
      val refs = collectRefsFromMember(makeBnd)

      val ctorRef = refs.find(_.name == "Pair")
      assert(ctorRef.isDefined, "Could not find ref 'Pair' in make body")

      val ref  = ctorRef.get
      val defs = AstLookup.findDefinitionAt(m, ref.span.start.line, ref.span.start.col)
      assert(
        defs.contains(struct.nameNode.span),
        s"Expected struct nameNode span ${struct.nameNode.span}, got: $defs"
      )
    }
  }

  // TODO: fix-ctor-gotodef: this test documents the real bug — see tracking.md
  // The simple case (Pair test above) passes because non-heap struct constructors
  // don't get ownership temp wrappers. Heap-struct constructors inside let bindings
  // fail because OwnershipAnalyzer wraps the arg with syntheticSpan nodes,
  // causing findDefinitionInApp to skip the arg entirely.
  test("go-to-definition on constructor inside let binding resolves to struct") {
    val code =
      """
      struct Address { city: String, street: String };
      fn main() =
        let addr = Address "SF" "Main St";
        println addr.city
      ;
      """

    semNotFailed(code).map { m =>
      val structOpt = m.members.collectFirst { case ts: TypeStruct if ts.name == "Address" => ts }
      assert(structOpt.isDefined, "Could not find struct 'Address'")
      val struct = structOpt.get

      val mainBnd = m.members
        .collectFirst { case b: Bnd if b.meta.exists(_.originalName == "main") => b }
        .getOrElse(fail("Could not find 'main'"))

      val refs    = collectRefsFromMember(mainBnd)
      val ctorRef = refs.find(r => r.name == "Address" && r.source.isFromSource)
      assert(ctorRef.isDefined, "Could not find source ref 'Address'")

      val ref  = ctorRef.get
      val defs = AstLookup.findDefinitionAt(m, ref.span.start.line, ref.span.start.col)
      assert(
        defs.contains(struct.nameNode.span),
        s"Expected struct nameNode span ${struct.nameNode.span}, got: $defs"
      )
    }
  }

  private def collectRefsFromAppFn(fn: Ref | App | Lambda): List[Ref] =
    fn match
      case ref: Ref =>
        ref :: ref.qualifier.toList.flatMap(collectRefsFromTerm)
      case app: App =>
        collectRefsFromAppFn(app.fn) ++ collectRefsFromExpr(app.arg)
      case lambda: Lambda =>
        collectRefsFromExpr(lambda.body)
