package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

import scala.collection.mutable.ListBuffer

class SemanticTokensTests extends BaseEffFunSuite:

  test("semantic tokens color println calls consistently") {
    val code =
      """
      fn hola(a: String) = concat "Hola, " a;

      let name = "fede";

      fn main() =
        let pepe = "pepe";
        println (hola name);
        println name;
        println pepe
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

      val missingTokens = refs.filter(ref => tokenAt(decodedTokens, ref.span).isEmpty)
      assert(
        missingTokens.isEmpty,
        s"Missing semantic tokens for println refs.\n${debugForRefs(refs, decodedTokens, module)}"
      )

      val wrongTokens = refs.filter { ref =>
        tokenAt(decodedTokens, ref.span).exists(_.tokenType != TokenType.Function)
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
        println "c"
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

  test("string literal in function argument gets string token") {
    val code =
      """
      struct Pair { name: String, value: String };

      fn show(p: Pair): String = "Name: " ++ p.name;

      fn main() =
        let p = Pair "x" "y";
        println ("Name: " ++ p.name);
        println (show p)
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
          val lineTokens = decodedTokens.filter(_.line == pl.line)
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

  test("synthesized constructor and destructor bindings excluded from workspace symbols") {
    val code =
      """
      struct User { name: String };

      fn main(): Int = 0;
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

  private def debugForRefs(
    refs:   List[Ref],
    tokens: List[DecodedToken],
    module: Module
  ): String =
    refs
      .map { ref =>
        val tokenOpt = tokenAt(tokens, ref.span)
        val tokenDesc = tokenOpt match
          case Some(token) => s"${token.tokenType.name}@${token.line}:${token.col}"
          case None => "none"
        val lineTokens =
          tokens
            .filter(_.line == ref.span.start.line)
            .map(t => s"${t.col}:${t.length}:${t.tokenType.name}")
            .mkString(", ")
        val resolved =
          ref.resolvedId
            .flatMap(module.resolvables.lookup)
            .map(_.getClass.getSimpleName)
            .getOrElse("none")
        s"${ref.name}@${ref.span.start.line}:${ref.span.start.col} " +
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
