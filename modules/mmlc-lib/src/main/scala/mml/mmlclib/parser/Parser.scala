package mml.mmlclib.parser

import cats.syntax.option.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

object Parser:

  // -----------------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------------

  def parseModule(source: String, name: Option[String] = "Anon".some): Either[String, Module] =
    parse(source, moduleP(name, source, _)) match
      case Parsed.Success(result, _) =>
        Right(result)
      case f: Parsed.Failure =>
        Left(f.trace().longMsg)

  // -----------------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------------

  private def indexToSourcePoint(index: Int, source: String): SourcePoint =
    val upToIndex = source.substring(0, index)
    val lines     = upToIndex.split('\n')
    val line      = lines.length
    val col       = if lines.isEmpty then index else lines.last.length + 1
    SourcePoint(line, col)

  private def span(start: SourcePoint, end: SourcePoint): SourceSpan =
    SourceSpan(start, end)

  private def point(line: Int, col: Int): SourcePoint =
    SourcePoint(line, col)

  // -----------------------------------------------------------------------------
  // Parser for SourcePoint (spP)
  // -----------------------------------------------------------------------------

  private def spP[$: P](source: String): P[SourcePoint] =
    P(Index).map(index => indexToSourcePoint(index, source))

  // -----------------------------------------------------------------------------
  // Keywords
  // -----------------------------------------------------------------------------

  private def letKw[$: P]:   P[Unit] = P("let")
  private def fnKw[$: P]:    P[Unit] = P("fn")
  private def defAsKw[$: P]: P[Unit] = P("=")
  private def endKw[$: P]:   P[Unit] = P(";")
  private def mehKw[$: P]:   P[Unit] = P("_")
  private def holeKw[$: P]:  P[Unit] = P("???")

  private def ifKw[$: P]:   P[Unit] = P("if")
  private def elseKw[$: P]: P[Unit] = P("else")
  private def thenKw[$: P]: P[Unit] = P("then")

  private def moduleEndKw[$: P]: P[Unit] =
    P(";".? ~ CharsWhile(c => c.isWhitespace, 0) ~ End)

  private def moduleKw[$: P]: P[Unit] = P("module")

  /** Keywords for the language, put in a single parser for easy inclusion in other parsers. Of
    * particular interest is disallowing keywords as identifiers.
    */
  private def keywords[$: P]: P[Unit] =
    P(
      moduleKw |
        endKw |
        defAsKw |
        mehKw |
        letKw |
        holeKw |
        ifKw |
        elseKw |
        thenKw |
        fnKw
    )

  // -----------------------------------------------------------------------------
  // Identifiers
  // -----------------------------------------------------------------------------

  private def bindingIdP[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(!keywords ~ CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  private def operatorIdP[$: P]: P[String] =
    // import fastparse.NoWhitespace.*
    // Define the allowed operator characters in a string
    val opChars = "!@#$%^&*+<>?/\\|~-"
    // Use CharsWhile to accept one or more characters that are in opChars.
    P(CharsWhile(c => opChars.indexOf(c) >= 0, min = 1).!)

  private def typeIdP[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  // -----------------------------------------------------------------------------
  // Literals
  // -----------------------------------------------------------------------------

  private def litStringP(source: String)(using P[Any]): P[LiteralString] =
    P(spP(source) ~ "\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"" ~ spP(source))
      .map { case (start, s, end) => LiteralString(span(start, end), s) }

  private def litBoolP(source: String)(using P[Any]): P[LiteralBool] =
    P(spP(source) ~ ("true" | "false").! ~ spP(source))
      .map { (start, b, end) =>
        LiteralBool(span(start, end), b.toBoolean)
      }

  private def numericLitP(source: String)(using P[Any]): P[LiteralValue] =
    import fastparse.NoWhitespace.*
    P(
      // Try float patterns first
      // N.N
      (spP(source) ~ CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(source)).map {
        case (start, s, end) =>
          LiteralFloat(span(start, end), s.toFloat)
      } |
        // .N
        (spP(source) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(source)).map { case (start, s, end) =>
          LiteralFloat(span(start, end), s.toFloat)
        } |

        // If there's no dot, parse as integer
        P(spP(source) ~ CharIn("0-9").rep(1).! ~ spP(source)).map { case (start, s, end) =>
          LiteralInt(span(start, end), s.toInt)
        }
    )

  private def litUnitP(source: String)(using P[Any]): P[LiteralUnit] =
    P(spP(source) ~ "()" ~ spP(source)).map { case (start, end) =>
      LiteralUnit(span(start, end))
    }

  // -----------------------------------------------------------------------------
  // Term & Expression Parsers
  // -----------------------------------------------------------------------------

  private def ifExprP(source: String)(using P[Any]): P[Term] =
    P(
      spP(source) ~ ifKw ~/ exprP(source) ~ thenKw ~/ exprP(source) ~ elseKw ~/ exprP(
        source
      ) ~ spP(source)
    )
      .map { case (start, cond, ifTrue, ifFalse, end) =>
        Cond(span(start, end), cond, ifTrue, ifFalse)
      }

  private def groupTermP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ "(" ~ exprP(source) ~ ")" ~ spP(source))
      .map { case (start, expr, end) =>
        GroupTerm(span(start, end), expr)
      }

  private def refP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ bindingIdP ~ spP(source))
      .map { case (start, id, end) =>
        Ref(span(start, end), id, None)
      }

  // Keep source point handling only in opRefP
  private def opRefP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ operatorIdP ~ spP(source))
      .map { case (start, id, end) =>
        Ref(span(start, end), id, None)
      }

  private def mehP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ mehKw ~ spP(source))
      .map { case (start, end) =>
        MehRef(span(start, end), None)
      }

  private def holeP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ holeKw ~ spP(source))
      .map { case (start, end) =>
        Hole(span(start, end))
      }

  private def termP(source: String)(using P[Any]): P[Term] =
    P(
      ifExprP(source) |
        holeP(source) |
        opRefP(source) |
        litStringP(source) |
        numericLitP(source) |
        litBoolP(source) |
        litUnitP(source) |
        groupTermP(source) |
        refP(source) |
        mehP(source)
    )

  private def exprP(source: String)(using P[Any]): P[Expr] =
    P(spP(source) ~ termP(source).rep ~ spP(source))
      .map { case (start, ts, end) =>
        val termsList = ts.toList
        // If there's exactly one term, the Expr inherits that term's typeSpec
        val typeSpec =
          if termsList.size == 1 then termsList.head.typeSpec
          else None
        Expr(span(start, end), termsList, typeSpec)
      }

  // -----------------------------------------------------------------------------
  // Error Handling for Partial Parsers
  // -----------------------------------------------------------------------------

  private def failedMemberP(source: String)(using P[Any]): P[Member] =
    P(spP(source) ~ CharsWhile(_ != ';').! ~ endKw ~ spP(source))
      .map { case (start, snippet, end) =>
        MemberError(
          span       = SourceSpan(start, end),
          message    = "Failed to parse member",
          failedCode = snippet.some
        )
      }

  // -------------------------------------------------------------------------------
  // Types
  // -------------------------------------------------------------------------------

  private def typeNameP(source: String)(using P[Any]): P[TypeSpec] =
    P(spP(source) ~ typeIdP ~ spP(source))
      .map { case (start, id, end) =>
        TypeName(span(start, end), id)
      }

  private def typeSpecP(source: String)(using P[Any]): P[TypeSpec] =
    P(typeNameP(source))

  private def typeAscP(source: String)(using P[Any]): P[Option[TypeSpec]] =
    P(":" ~/ typeSpecP(source)).?

  // -----------------------------------------------------------------------------
  // Doc Comment Parser
  // -----------------------------------------------------------------------------

  private def docCommentP[$: P](src: String): P[Option[DocComment]] = {
    import fastparse.NoWhitespace.*
    def comment: P[String] = P("#-" ~ commentBody ~ "-#")
    def commentBody: P[String] =
      P((comment | (!("-#") ~ AnyChar).!).rep).map(_.mkString.stripMargin('#'))
    P(spP(src) ~ comment.! ~ spP(src)).?.map {
      case Some(start, s, end) =>
        DocComment(span(start, end), s).some
      case None => None
    }
  }

// -----------------------------------------------------------------------------
// Let Bindings & Fn Declarations
// -----------------------------------------------------------------------------

  private def letBindingP(source: String)(using P[Any]): P[Member] =
    P(
      spP(source)
        ~ docCommentP(source)
        ~ letKw
        ~ bindingIdP
        ~ typeAscP(source)
        ~ defAsKw
        ~ exprP(source)
        ~ endKw
        ~ spP(source)
    )
      .map { case (start, doc, name, typeAsc, expr, end) =>
        Bnd(span(start, end), name, expr, expr.typeSpec, typeAsc, doc)
      }

  private def fnParamP(source: String)(using P[Any]): P[FnParam] =
    P(
      spP(source) ~ docCommentP(source) ~ bindingIdP ~ typeAscP(source) ~ spP(
        source
      )
    ).map { case (start, doc, name, t, end) =>
      FnParam(
        span       = span(start, end),
        name       = name,
        typeAsc    = t,
        docComment = doc
      )
    }

  private def fnParserP(source: String)(using P[Any]): P[Member] =
    P(
      spP(source)
        ~ docCommentP(source)
        ~ fnKw
        ~ bindingIdP
        ~ "("
        ~ fnParamP(source).rep
        ~ ")"
        ~ defAsKw
        ~ exprP(source)
        ~ endKw
        ~ spP(source)
    ).map { case (start, doc, fnName, params, bodyExpr, end) =>
      FnDef(
        span       = span(start, end),
        name       = fnName,
        params     = params.toList,
        body       = bodyExpr,
        typeSpec   = bodyExpr.typeSpec,
        docComment = doc
      )
    }

  // -----------------------------------------------------------------------------
  // Single Member Parser
  // -----------------------------------------------------------------------------

  private def membersP(source: String)(using P[Any]): P[Member] =
    P(
      letBindingP(source) |
        fnParserP(source) |
        failedMemberP(source)
    )

  // -----------------------------------------------------------------------------
  // Module Parsers (Explicit / Implicit)
  // -----------------------------------------------------------------------------

  private def modVisibilityP[$: P]: P[ModVisibility] =
    P("pub").map(_ => ModVisibility.Public) |
      P("protected").map(_ => ModVisibility.Protected) |
      P("lexical").map(_ => ModVisibility.Lexical)

  private def explicitModuleP(source: String)(using P[Any]): P[Module] =
    P(
      Start
        ~ spP(source)
        ~ docCommentP(source)
        ~ moduleKw
        ~ modVisibilityP.?
        ~ typeIdP.!
        ~ defAsKw
        ~ membersP(source).rep
        ~ moduleEndKw ~
        spP(source)
    ).map { case (start, doc, maybeVis, moduleName, members, end) =>
      Module(
        span       = span(start, end),
        name       = moduleName,
        visibility = maybeVis.getOrElse(ModVisibility.Public),
        members    = members.toList,
        isImplicit = false,
        docComment = doc
      )
    }

  private def implicitModuleP(name: String, source: String)(using P[Any]): P[Module] =
    P(Start ~ spP(source) ~ membersP(source).rep ~ moduleEndKw ~ spP(source))
      .map { case (start, members, end) =>
        Module(
          span       = span(start, end),
          name       = name,
          visibility = ModVisibility.Public,
          members    = members.toList,
          isImplicit = true
        )
      }

  private def moduleP(name: Option[String], source: String, p: P[Any]): P[Module] =
    given P[Any] = p
    name.fold(
      explicitModuleP(source)
    ) { n =>
      P(explicitModuleP(source) | implicitModuleP(n, source))
    }
