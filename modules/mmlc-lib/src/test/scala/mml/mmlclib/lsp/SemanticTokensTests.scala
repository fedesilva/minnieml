package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

import scala.collection.mutable.ListBuffer

class SemanticTokensTests extends BaseEffFunSuite:

  test("semantic tokens color println calls consistently") {
    val code =
      """
      fn hola(a: String) = concat "Hola, " a;;

      let name = "fede";

      fn main() =
        let pepe = "pepe";
        println (hola name);
        println name;
        println pepe;
      ;
      """

    semNotFailed(code).map { module =>
      val mainBnd = module.members.collectFirst { case bnd: Bnd if bnd.name == "main" => bnd }
      assert(mainBnd.isDefined, "Expected to find main binding")

      val refs =
        collectRefs(module.members)
          .filter(ref => ref.name == "println" && ref.qualifier.isEmpty)
      assert(
        refs.length == 3,
        s"Expected 3 println refs, got ${refs.length}.\n${debugForRefs(refs, Nil, module)}"
      )

      val decodedTokens = decodeTokens(SemanticTokens.compute(module))

      val missingTokens = refs.filter(ref => tokenAt(decodedTokens, ref).isEmpty)
      assert(
        missingTokens.isEmpty,
        s"Missing semantic tokens for println refs.\n${debugForRefs(refs, decodedTokens, module)}"
      )

      val wrongTokens = refs.filter { ref =>
        tokenAt(decodedTokens, ref).exists(_.tokenType != TokenType.Function)
      }
      assert(
        wrongTokens.isEmpty,
        s"Unexpected semantic token types for println refs.\n${debugForRefs(refs, decodedTokens, module)}"
      )
    }
  }

  test("statement chain params do not produce semantic tokens") {
    val code =
      """
      fn main() =
        println "a";
        println "b";
        println "c";
      ;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))
      val paramTokens   = decodedTokens.filter(_.tokenType == TokenType.Parameter)
      assert(
        paramTokens.isEmpty,
        s"Synthesized __stmt params should not produce tokens, got: " +
          paramTokens.map(t => s"${t.line}:${t.col}:${t.length}").mkString(", ")
      )
    }
  }

  test("inner function declarations and refs use function tokens") {
    val code =
      """
      fn main() =
        fn helper(x: Int): Int = x + 1;
        ;
        helper 41;
      ;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))
      val mainBody      = bindingBody(module, "main")
      val helperParam   = findParamByName(mainBody, "helper")
      val helperRefs    = refsForParam(mainBody, helperParam)

      assertTokenType(decodedTokens, helperParam, TokenType.Function, "inner fn declaration")
      assert(helperRefs.nonEmpty, "Expected refs resolved to inner fn helper")
      helperRefs.foreach(assertTokenType(decodedTokens, _, TokenType.Function, "inner fn ref"))
    }
  }

  test("let-bound callable values use function tokens") {
    val code =
      """
      fn add(a: Int, b: Int): Int = a + b;;

      fn main() =
        let add1 = add 1;
        add1 2;
      ;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))
      val mainBody      = bindingBody(module, "main")
      val add1Param     = findParamByName(mainBody, "add1")
      val add1Refs      = refsForParam(mainBody, add1Param)

      assertTokenType(decodedTokens, add1Param, TokenType.Function, "callable let declaration")
      assert(add1Refs.nonEmpty, "Expected refs resolved to callable let binding")
      add1Refs.foreach(assertTokenType(decodedTokens, _, TokenType.Function, "callable let ref"))
    }
  }

  test("callable params use function tokens while ordinary params stay parameter tokens") {
    val code =
      """
      fn apply(f: Int -> Int, x: Int): Int =
        f x
      ;;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))
      val applyLambda   = bindingLambda(module, "apply")
      val fParam        = applyLambda.params.find(_.name == "f").getOrElse(fail("Missing f param"))
      val xParam        = applyLambda.params.find(_.name == "x").getOrElse(fail("Missing x param"))
      val fRefs         = refsForParam(applyLambda.body, fParam)
      val xRefs         = refsForParam(applyLambda.body, xParam)

      assertTokenType(decodedTokens, fParam, TokenType.Function, "callable param declaration")
      assertTokenType(decodedTokens, xParam, TokenType.Parameter, "ordinary param declaration")
      assert(fRefs.nonEmpty, "Expected refs resolved to callable param f")
      assert(xRefs.nonEmpty, "Expected refs resolved to ordinary param x")
      fRefs.foreach(assertTokenType(decodedTokens, _, TokenType.Function, "callable param ref"))
      xRefs.foreach(assertTokenType(decodedTokens, _, TokenType.Parameter, "ordinary param ref"))
    }
  }

  test("elif does not color the first two chars of the following term as keyword") {
    val code =
      """
      fn main(cell: Int): String =
        if cell == 1 then
          "#";
        elif cell == 2 then
          "*";
        else
          ".";
      ;;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))
      val mainLambda    = bindingLambda(module, "main")
      val cellParam =
        mainLambda.params.find(_.name == "cell").getOrElse(fail("Missing cell param"))
      val cellRefs = refsForParam(mainLambda.body, cellParam)

      assertEquals(cellRefs.length, 2, s"Expected two refs to cell, got ${cellRefs.length}")
      cellRefs.foreach(assertTokenType(decodedTokens, _, TokenType.Parameter, "cell ref in cond"))
    }
  }

  test("string literal in function argument gets string token") {
    val code =
      """
      struct Pair { name: String, value: String };

      fn show(p: Pair): String = "Name: " ++ p.name;;

      fn main() =
        let p = Pair "x" "y";
        println ("Name: " ++ p.name);
        println (show p);
      ;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))
      val allTokens = decodedTokens
        .map(t => s"${t.line}:${t.col}:${t.length}:${t.tokenType.name}")
        .mkString("\n")
      // Find all string tokens
      val stringTokens = decodedTokens.filter(_.tokenType == TokenType.String)
      assert(
        stringTokens.nonEmpty,
        s"Expected at least one string token.\nAll tokens:\n$allTokens"
      )
      // Line with println ("Name: " ++ p.name) should have a string token for "Name: "
      val printlnLine =
        decodedTokens.find(t => t.tokenType == TokenType.Function && t.length == 7)
      printlnLine match
        case Some(pl) =>
          val lineTokens      = decodedTokens.filter(_.line == pl.line)
          val hasStringOnLine = lineTokens.exists(_.tokenType == TokenType.String)
          assert(
            hasStringOnLine,
            s"Line ${pl.line} should have a string token.\nLine tokens: " +
              lineTokens.map(t => s"${t.col}:${t.length}:${t.tokenType.name}").mkString(", ") +
              s"\nAll tokens:\n$allTokens"
          )
        case None =>
          fail(s"Could not find println function token.\nAll tokens:\n$allTokens")
    }
  }

  test("no phantom tokens from ownership free wrappers") {
    val code =
      """
      struct Person { name: String, age: Int };

      fn main() =
        let p = Person "fede" 25;
        println ("Name: " ++ p.name);
        println "---";
      ;
      """

    semNotFailed(code).map { module =>
      val decodedTokens = decodeTokens(SemanticTokens.compute(module))

      // No two tokens on the same line should have overlapping ranges
      val byLine = decodedTokens.groupBy(_.line)
      byLine.foreach { (line, tokens) =>
        val sorted = tokens.sortBy(_.col)
        sorted.sliding(2).foreach {
          case List(a, b) =>
            assert(
              a.col + a.length <= b.col,
              s"Overlapping tokens on line $line: " +
                s"${a.col}:${a.length}:${a.tokenType.name} overlaps ${b.col}:${b.length}:${b.tokenType.name}"
            )
          case _ => ()
        }
      }

      // String literal "Name: " should have TokenType.String, not Function/Parameter
      val nameStringTokens =
        decodedTokens.filter(t => t.tokenType == TokenType.String && t.length == 8)
      assert(
        nameStringTokens.nonEmpty,
        s"Expected string token for \"Name: \".\nAll tokens:\n" +
          decodedTokens
            .map(t => s"${t.line}:${t.col}:${t.length}:${t.tokenType.name}")
            .mkString("\n")
      )
    }
  }

  test("synthesized constructor and destructor bindings excluded from workspace symbols") {
    val code =
      """
      struct User { name: String };

      fn main(): Int = 0;;
      """

    semNotFailed(code).map { module =>
      val symbols = AstLookup.collectSymbols(module, "file:///test.mml")
      val names   = symbols.map(_.name)
      assert(names.contains("main"), s"Expected 'main' in symbols: $names")
      assert(names.contains("User"), s"Expected 'User' in symbols: $names")
      assert(
        !names.exists(_.startsWith("__mk_")),
        s"Synthesized constructors should be excluded: $names"
      )
      assert(
        !names.exists(_.startsWith("__free_")),
        s"Synthesized destructors should be excluded: $names"
      )
      assert(
        !names.exists(_.startsWith("__clone_")),
        s"Synthesized clone fns should be excluded: $names"
      )
    }
  }

  private case class DecodedToken(
    line:      Int,
    col:       Int,
    length:    Int,
    tokenType: TokenType
  )

  private def spanOrFail(node: FromSource, label: String): SrcSpan =
    node.source.spanOpt.getOrElse(fail(s"Missing source span for $label"))

  private def bindingValue(module: Module, name: String): Expr =
    module.members
      .collectFirst { case bnd: Bnd if bnd.name == name => bnd.value }
      .getOrElse(fail(s"Expected binding '$name'"))

  private def bindingLambda(module: Module, name: String): Lambda =
    bindingValue(module, name).terms
      .collectFirst { case lambda: Lambda => lambda }
      .getOrElse(fail(s"Expected lambda for binding '$name'"))

  private def bindingBody(module: Module, name: String): Expr =
    bindingLambda(module, name).body

  private def decodeTokens(result: SemanticTokensResult): List[DecodedToken] =
    val tokens   = ListBuffer.empty[DecodedToken]
    val data     = result.data
    var prevLine = 1
    var prevCol  = 1
    var i        = 0
    while i + 4 < data.length do
      val deltaLine = data(i)
      val deltaCol  = data(i + 1)
      val length    = data(i + 2)
      val tokenType = TokenType.values(data(i + 3))
      val line      = prevLine + deltaLine
      val col       = if deltaLine == 0 then prevCol + deltaCol else 1 + deltaCol
      tokens += DecodedToken(line, col, length, tokenType)
      prevLine = line
      prevCol  = col
      i += 5
    tokens.toList

  private def tokenAt(tokens: List[DecodedToken], span: SrcSpan): Option[DecodedToken] =
    tokens.find(token => token.line == span.start.line && token.col == span.start.col)

  private def tokenAt(tokens: List[DecodedToken], node: FromSource): Option[DecodedToken] =
    node.spanOpt.flatMap(span => tokenAt(tokens, span))

  private def assertTokenType(
    tokens:   List[DecodedToken],
    node:     FromSource,
    expected: TokenType,
    label:    String
  ): Unit =
    val token = tokenAt(tokens, node).getOrElse(fail(s"Missing token for $label"))
    assertEquals(token.tokenType, expected, s"Unexpected token type for $label")

  private def debugForRefs(
    refs:   List[Ref],
    tokens: List[DecodedToken],
    module: Module
  ): String =
    refs
      .map { ref =>
        val tokenOpt = tokenAt(tokens, ref)
        val refSpan  = spanOrFail(ref, s"ref '${ref.name}'")
        val tokenDesc = tokenOpt match
          case Some(tok) => s"${tok.tokenType.name}@${tok.line}:${tok.col}"
          case None => "none"
        val lineTokens =
          tokens
            .filter(_.line == refSpan.start.line)
            .map(t => s"${t.col}:${t.length}:${t.tokenType.name}")
            .mkString(", ")
        val resolved =
          ref.resolvedId
            .flatMap(module.resolvables.lookup)
            .map(_.getClass.getSimpleName)
            .getOrElse("none")
        s"${ref.name}@${refSpan.start.line}:${refSpan.start.col} " +
          s"token=$tokenDesc resolvedId=${ref.resolvedId} resolved=$resolved " +
          s"candidates=${ref.candidateIds} lineTokens=[$lineTokens]"
      }
      .mkString("\n")

  private def collectRefs(members: List[Member]): List[Ref] =
    members.flatMap(collectRefsFromMember)

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
      case _ =>
        Nil

  private def collectParamsFromAppFn(fn: Ref | App | Lambda): List[FnParam] =
    fn match
      case _: Ref =>
        Nil
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case lambda: Lambda =>
        lambda.params ++ collectParamsFromExpr(lambda.body)

  private def findParamByName(expr: Expr, name: String): FnParam =
    collectParamsFromExpr(expr)
      .find(_.name == name)
      .getOrElse(fail(s"Expected param '$name'"))

  private def refsForParam(expr: Expr, param: FnParam): List[Ref] =
    val paramId = param.id.getOrElse(fail(s"Expected id for param '${param.name}'"))
    collectRefsFromExpr(expr).filter(_.resolvedId.contains(paramId))

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

  private def collectRefsFromAppFn(fn: Ref | App | Lambda): List[Ref] =
    fn match
      case ref: Ref =>
        ref :: ref.qualifier.toList.flatMap(collectRefsFromTerm)
      case app: App =>
        collectRefsFromAppFn(app.fn) ++ collectRefsFromExpr(app.arg)
      case lambda: Lambda =>
        collectRefsFromExpr(lambda.body)
