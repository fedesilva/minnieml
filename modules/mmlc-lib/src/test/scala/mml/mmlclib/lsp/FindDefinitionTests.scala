package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class FindDefinitionTests extends BaseEffFunSuite:

  private def spanOrFail(node: FromSource, label: String): SrcSpan =
    node.source.spanOpt.getOrElse(fail(s"Missing source span for $label"))

  test("find definition for local param and let binding") {
    val code =
      """
      fn main(x: Int): Int =
        let y = x + 1;
        y;
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

      val xRef       = xRefOpt.get
      val xRefSpan   = spanOrFail(xRef, "ref 'x'")
      val xDefs      = AstLookup.findDefinitionAt(m, xRefSpan.start.line, xRefSpan.start.col)
      val xParam     = xParamOpt.get
      val xParamSpan = spanOrFail(xParam, "param 'x'")
      assert(xDefs.contains(xParamSpan), s"Missing param span for 'x': $xDefs")

      val yRef       = yRefOpt.get
      val yRefSpan   = spanOrFail(yRef, "ref 'y'")
      val yDefs      = AstLookup.findDefinitionAt(m, yRefSpan.start.line, yRefSpan.start.col)
      val yParam     = yParamOpt.get
      val yParamSpan = spanOrFail(yParam, "param 'y'")
      assert(yDefs.contains(yParamSpan), s"Missing param span for 'y': $yDefs")
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

  private def collectParamsFromAppFn(fn: Callee): List[FnParam] =
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
      fn make(a: Int, b: Int): Pair = Pair a b;;
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

      val ref            = ctorRef.get
      val refSpan        = spanOrFail(ref, "constructor ref 'Pair'")
      val defs           = AstLookup.findDefinitionAt(m, refSpan.start.line, refSpan.start.col)
      val structNameSpan = spanOrFail(struct.nameNode, "struct 'Pair' name")
      assert(
        defs.contains(structNameSpan),
        s"Expected struct name span $structNameSpan, got: $defs"
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
        println addr.city;
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

      val ref            = ctorRef.get
      val refSpan        = spanOrFail(ref, "constructor ref 'Address'")
      val defs           = AstLookup.findDefinitionAt(m, refSpan.start.line, refSpan.start.col)
      val structNameSpan = spanOrFail(struct.nameNode, "struct 'Address' name")
      assert(
        defs.contains(structNameSpan),
        s"Expected struct name span $structNameSpan, got: $defs"
      )
    }
  }

  test("go-to-definition on non-source symbol returns no definition") {
    val code =
      """
      struct Person {
        name: String,
        age: Int
      };

      fn main() =
        let p = Person "fede" 25;
        println ("Age: " ++ int_to_str p.age);
      ;
      """

    semNotFailed(code).map { m =>
      val mainBnd = m.members
        .collectFirst { case b: Bnd if b.meta.exists(_.originalName == "main") => b }
        .getOrElse(fail("Could not find 'main'"))

      val refs = collectRefsFromMember(mainBnd)
      val stdlibRef =
        refs.find(r => r.name == "int_to_str" && r.source.isFromSource && r.qualifier.isEmpty)
      assert(stdlibRef.isDefined, "Could not find source ref 'int_to_str'")

      val ref     = stdlibRef.get
      val refSpan = spanOrFail(ref, "ref 'int_to_str'")
      val defs    = AstLookup.findDefinitionAt(m, refSpan.start.line, refSpan.start.col)
      assertEquals(
        defs,
        Nil,
        s"Expected no definition for non-source symbol at $refSpan, got: $defs"
      )
    }
  }

  private def collectRefsFromAppFn(fn: Callee): List[Ref] =
    fn match
      case ref: Ref =>
        ref :: ref.qualifier.toList.flatMap(collectRefsFromTerm)
      case app: App =>
        collectRefsFromAppFn(app.fn) ++ collectRefsFromExpr(app.arg)
      case lambda: Lambda =>
        collectRefsFromExpr(lambda.body)

  test("definition fallbacks stay on written names and preserve valid type and value targets") {
    val code =
      """
      type Value = Int;
      struct Box { field: Value };
      fn example(param: Value): Value = param;;
      let stored = 1;
      """

    semNotFailed(code).map { module =>
      val alias = module.members
        .collectFirst {
          case value: TypeAlias if value.source.isFromSource =>
            value
        }
        .getOrElse(fail("Expected source type alias"))
      val struct = module.members
        .collectFirst { case value: TypeStruct => value }
        .getOrElse(fail("Expected source struct"))
      val bindings = module.members.collect {
        case value: Bnd if value.source.isFromSource => value
      }
      val params = bindings.flatMap(collectParamsFromMember)
      assertEquals(params.size, 1)
      val parameter = params.head
      val field     = struct.fields.head
      val declarations: List[Resolvable & FromSource] =
        List(alias, struct, parameter, field) ++ bindings

      declarations.foreach { declaration =>
        val name = spanOrFail(declaration.nameNode, "written declaration name")
        assertEquals(
          AstLookup.findDefinitionAt(module, name.start.line, name.start.col),
          List(spanOrFail(declaration, "declaration"))
        )
      }

      val typeReferences = parameter.typeAsc.toList :+ field.typeSpec
      typeReferences.foreach { reference =>
        val span = spanOrFail(reference, "written type reference")
        assertEquals(
          AstLookup.findDefinitionAt(module, span.start.line, span.start.col),
          List(spanOrFail(alias, "resolved type declaration"))
        )
      }
      val valueReferences = bindings
        .flatMap(collectRefsFromMember)
        .filter(_.resolvedId == parameter.id)
      assertEquals(valueReferences.size, 1)
      val value = spanOrFail(valueReferences.head, "parameter use")
      assertEquals(
        AstLookup.findDefinitionAt(module, value.start.line, value.start.col),
        List(spanOrFail(parameter, "resolved parameter"))
      )
    }
  }

  test("malformed alias targets and struct fields do not resolve to enclosing declarations") {
    val code = "type Value = Int; struct Box { field: Int };"
    parseNotFailed(code).map { module =>
      val alias = module.members
        .collectFirst { case value: TypeAlias => value }
        .getOrElse(fail("Expected alias"))
      val struct = module.members
        .collectFirst { case value: TypeStruct => value }
        .getOrElse(fail("Expected struct"))
      val field       = struct.fields.head
      val failedAlias = ParseTypeError(alias.typeRef.source, "Malformed alias type", None)
      val failedField = ParseTypeError(field.typeSpec.source, "Malformed field type", None)
      val recovered = module.copy(members =
        List(
          alias.copy(typeRef = failedAlias),
          struct.copy(fields = Vector(field.copy(typeSpec = failedField)))
        )
      )

      List(failedAlias, failedField).foreach { error =>
        val span = spanOrFail(error, "malformed type")
        assertEquals(AstLookup.findDefinitionAt(recovered, span.start.line, span.start.col), Nil)
      }
    }
  }
