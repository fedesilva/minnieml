package mml.mmlclib.parser

import fastparse._
import MmlWhitespace.*
import mml.mmlclib.ast.*
import cats.syntax.option.*

object Parser:

  // -----------------------------------------------------------------------------
  // Basic Low-Level Parsers
  // -----------------------------------------------------------------------------

  private def bindingIdP[$: P]: P[String] =
    // DO NOT allow spaces within an id
    import fastparse.NoWhitespace.*
    P(CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  private def operatorIdP[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(CharIn("!@#$%^&*-+<>?/\\|").rep(1).!)

  private def typeIdP[$: P]: P[String] =
    // DO NOT allow spaces within an id
    import fastparse.NoWhitespace.*
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

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

  private def defAsKw[$: P]: P[Unit] = P("=")
  private def endKw[$: P]:   P[Unit] = P(";")

  // Accept optional semicolon or end-of-input as module terminator
  private def moduleEndKw[$: P]: P[Unit] = P(";".? | End)
  private def moduleKw[$: P]:    P[Unit] = P("module")

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
  // Let Bindings, Fn Declarations, etc.
  // -----------------------------------------------------------------------------

  private def letBindingP(using P[Any]): P[Member] =
    P("let" ~ bindingIdP ~ defAsKw ~ exprP ~ endKw).map { case (id, e) =>
      Bnd(id, e, e.typeSpec)
    }

  def fnParserP(using P[Any]): P[Member] =
    P("fn" ~ bindingIdP ~ bindingIdP.rep(1) ~ defAsKw ~ exprP ~ endKw).map {
      case (fnName, params, bodyExpr) =>
        FnDef(fnName, params.toList, bodyExpr, None)
    }

  // -----------------------------------------------------------------------------
  // Error Capture for Partial Parsers
  // -----------------------------------------------------------------------------

  private def failedMemberP(src: String)(using P[Any]): P[Member] =
    P(Index ~ CharsWhile(_ != ';').! ~ endKw ~ Index).map { case (startIdx, snippet, endIdx) =>
      val start = indexToSourcePoint(startIdx, src)
      val stop  = indexToSourcePoint(endIdx, src)
      MemberError(
        start      = start,
        end        = stop,
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
    P(Start ~ moduleKw ~ modVisibilityP.? ~ typeIdP.! ~ defAsKw ~ membersP(src).rep ~ moduleEndKw)
      .map { case (maybeVis, moduleName, members) =>
        Module(moduleName, maybeVis.getOrElse(ModVisibility.Public), members.toList)
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
