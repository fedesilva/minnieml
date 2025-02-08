package mml.mmlclib.parser

import cats.syntax.option.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

object Parser:

  // -----------------------------------------------------------------------------
  // Error Handling for Partial Parsers
  // -----------------------------------------------------------------------------

  private def failedMemberP(src: String)(using P[Any]): P[Member] =
    P(Index ~ CharsWhile(_ != ';').! ~ endKw ~ Index).map { case (startIdx, snippet, endIdx) =>
      val start = indexToSourcePoint(startIdx, src)
      val stop  = indexToSourcePoint(endIdx, src)
      MemberError(
        span       = SourceSpan(start, stop),
        message    = "Failed to parse member",
        failedCode = Some(snippet)
      )
    }

  private def indexToSourcePoint(index: Int, source: String): SourcePoint =
    val upToIndex = source.substring(0, index)
    val lines     = upToIndex.split('\n')
    val line      = lines.length
    val col       = if lines.isEmpty then index else lines.last.length + 1
    SourcePoint(line, col)

  // -----------------------------------------------------------------------------
  // Keywords
  // -----------------------------------------------------------------------------
  private def defAsKw[$: P]: P[Unit] = P("=")
  private def endKw[$: P]:   P[Unit] = P(";")
  private def mehKw[$: P]:   P[Unit] = P("_")
  private def andKw[$: P]:   P[Unit] = P("and")
  private def orKw[$: P]:    P[Unit] = P("or")
  private def notKw[$: P]:   P[Unit] = P("not")
  private def moduleEndKw[$: P]: P[Unit] =
    P(";".? ~ CharsWhile(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', 0) ~ End)

  private def moduleKw[$: P]: P[Unit] = P("module")

  // all keywords in a sequence to be used later when parsing
  // to check when an identifier is a keyword
  private def keywords[$: P]: P[Unit] = P(
    moduleKw |
      endKw |
      defAsKw |
      mehKw |
      andKw |
      orKw |
      notKw
  )

  // -----------------------------------------------------------------------------
  // Identifiers
  // -----------------------------------------------------------------------------

  private def bindingIdP[$: P]: P[String] =
    // DO NOT allow spaces within an id
    import fastparse.NoWhitespace.*
    P(CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  private def operatorIdP[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(CharIn("!@#$%^&*-+<>?/\\|~").rep(1).!)

  private def typeIdP[$: P]: P[String] =
    // DO NOT allow spaces within an id
    import fastparse.NoWhitespace.*
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  // -----------------------------------------------------------------------------
  // Literals
  // -----------------------------------------------------------------------------

  private def litStringP[$: P]: P[String] = P("\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"")
  private def litBoolP[$: P]:   P[String] = P("true" | "false").!
  private def litUnitP[$: P]:   P[Unit]   = P("()")

  private def numericLitP[$: P]: P[LiteralValue] =
    P(
      // Try float patterns first
      (CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1)).!.map(s => LiteralFloat(s.toFloat)) |
        ("." ~ CharIn("0-9").rep(1)).!.map(s => LiteralFloat(s.toFloat)) |

        // If there's no dot at all, parse as integer
        CharIn("0-9").rep(1).!.map(s => LiteralInt(s.toInt))
    )

  // -----------------------------------------------------------------------------
  // Term & Expression Parsers
  // -----------------------------------------------------------------------------

  private def groupTermP(using P[Any]): P[Term] =
    P("(" ~ exprP ~ ")").map { innerExpr =>
      GroupTerm(innerExpr)
    }

  private def termP(using P[Any]): P[Term] =
    P(
      litStringP.map(s => LiteralString(s)) |
        litBoolP.map(b => LiteralBool(b.toBoolean)) |
        operatorIdP.map(op => Ref(op, None)) |
        litUnitP.map(_ => LiteralUnit) |
        groupTermP |
        numericLitP |
        bindingIdP.map(name => Ref(name, None))
    )

  private def exprP(using P[Any]): P[Expr] =
    P(termP.rep(1)).map { ts =>
      val termsList = ts.toList
      // If there's exactly one term, the Expr inherits that term's typeSpec
      val typeSpec =
        if termsList.size == 1 then termsList.head.typeSpec
        else None
      Expr(termsList, typeSpec)
    }

  // -----------------------------------------------------------------------------
  // Let Bindings, Fn Declarations, doc comments
  // -----------------------------------------------------------------------------

  private def docCommentP[$: P]: P[Option[DocComment]] = {
    import fastparse.NoWhitespace.*
    def comment:     P[String] = P("#-" ~ commentBody ~ "-#")
    def commentBody: P[String] = P((comment | (!("-#") ~ AnyChar).!).rep).map(_.mkString)
    comment.?.map(_.map(s => DocComment(s.stripMargin('#'))))
  }

  private def letBindingP(using P[Any]): P[Member] =
    P(docCommentP ~ "let" ~ bindingIdP ~ defAsKw ~ exprP ~ endKw)
      .map { case (doc, id, e) =>
        Bnd(id, e, e.typeSpec, doc)
      }

  def fnParserP(using P[Any]): P[Member] =
    P(docCommentP ~ "fn" ~ bindingIdP ~ bindingIdP.rep(1) ~ defAsKw ~ exprP ~ endKw).map {
      case (doc, fnName, params, bodyExpr) =>
        FnDef(fnName, params.toList, bodyExpr, None, doc)
    }

  // -----------------------------------------------------------------------------
  // Single `memberParser` Combining All Member Rules
  // -----------------------------------------------------------------------------

  private def membersP(src: String)(using P[Any]): P[Member] =
    P(
      letBindingP |
        fnParserP |
        failedMemberP(src)
    )

  // -----------------------------------------------------------------------------
  // Module Parsers (Implicit / Explicit)
  // -----------------------------------------------------------------------------

  private def modVisibilityP[$: P]: P[ModVisibility] =
    P("pub").map(_ => ModVisibility.Public) |
      P("protected").map(_ => ModVisibility.Protected) |
      P("lexical").map(_ => ModVisibility.Lexical)

  private def explicitModuleP(src: String)(using P[Any]): P[Module] =
    P(
      Start ~ docCommentP ~ moduleKw ~ modVisibilityP.? ~ typeIdP.! ~ defAsKw ~ membersP(
        src
      ).rep ~ moduleEndKw
    ).map { case (doc, maybeVis, moduleName, members) =>
      Module(moduleName, maybeVis.getOrElse(ModVisibility.Public), members.toList, false, doc)
    }

  private def implicitModuleP(src: String, name: String)(using P[Any]): P[Module] =
    P(Start ~ membersP(src).rep ~ moduleEndKw)
      .map { members =>
        Module(name, ModVisibility.Public, members.toList, isImplicit = true)
      }

  private def moduleP(src: String, name: Option[String], p: P[Any]): P[Module] =
    given P[Any] = p
    name.fold(
      explicitModuleP(src)
    ) { n =>
      P(explicitModuleP(src) | implicitModuleP(src, n))
    }

  // -----------------------------------------------------------------------------
  // Top-Level Function to Parse a Module
  // -----------------------------------------------------------------------------

  def parseModule(source: String, name: Option[String] = "Anon".some): Either[String, Module] =
    parse(source, moduleP(source, name, _)) match
      case Parsed.Success(result, _) =>
        Right(result)
      case f: Parsed.Failure =>
        Left(f.trace().longMsg)
