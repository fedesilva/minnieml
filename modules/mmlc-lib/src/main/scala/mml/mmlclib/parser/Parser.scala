package mml.mmlclib.parser

import fastparse._
import fastparse.MultiLineWhitespace._
import mml.mmlclib.ast._

object Parser:

  // -----------------------------------------------------------------------------
  // Basic Low-Level Parsers
  // -----------------------------------------------------------------------------

  def bindingId[$: P]: P[String] =
    // DO NOT allow spaces within an id
    import fastparse.NoWhitespace.*
    P(CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  def operatorId[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(CharIn("!@#$%^&*-+<>?/\\|").rep(1).!)

  def typeId[$: P]: P[String] =
    // DO NOT allow spaces within an id
    import fastparse.NoWhitespace.*
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  def litString[$: P]: P[String] = P("\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"")
  def listInt[$: P]:   P[String] = P(CharsWhileIn("0-9").!)
  def litBool[$: P]:   P[String] = P("true" | "false").!
  def litFloat[$: P]:  P[String] = P(CharsWhileIn("0-9.").!)
  def litUnit[$: P]:   P[Unit]   = P("()")

  def defAs[$: P]: P[Unit] = P("=")
  def end[$: P]:   P[Unit] = P(";")

  // Accept optional semicolon or end-of-input as module terminator
  def moduleEnd[$: P]: P[Unit] = P(";".? | End)
  def moduleKw[$: P]:  P[Unit] = P("module")

  // -----------------------------------------------------------------------------
  // Term & Expression Parsers
  // -----------------------------------------------------------------------------

  def term(using P[Any]): P[Term] =
    P(
      litString.map(s => LiteralString(s)) |
        listInt.map(n => LiteralInt(n.toInt)) |
        litBool.map(b => LiteralBool(b.toBoolean)) |
        operatorId.map(op => Ref(op, None)) |
        litUnit.map(_ => LiteralUnit) |
        litFloat.map(f => LiteralFloat(f.toFloat)) |
        bindingId.map(name => Ref(name, None))
    )

  def expr(using P[Any]): P[Expr] =
    P(term.rep(1)).map { ts =>
      val termsList = ts.toList
      // If there's exactly one term, the Expr inherits that term's typeSpec:
      val typeSpec =
        if termsList.size == 1 then termsList.head.typeSpec
        else None
      Expr(termsList, typeSpec)
    }

  // -----------------------------------------------------------------------------
  // Let Bindings, Fn Declarations, etc.
  // -----------------------------------------------------------------------------

  def letBinding(using P[Any]): P[Member] =
    P("let" ~ bindingId ~ defAs ~ expr ~ end).map { case (id, e) =>
      Bnd(id, e, e.typeSpec)
    }

  def fnParser(using P[Any]): P[Member] =
    P("fn" ~ bindingId ~ bindingId.rep(1) ~ defAs ~ expr ~ end).map {
      case (fnName, params, bodyExpr) =>
        FnDef(fnName, params.toList, bodyExpr, None)
    }

  // -----------------------------------------------------------------------------
  // Error Capture for Partial Parsers
  // -----------------------------------------------------------------------------

  def failedMember(src: String)(using P[Any]): P[Member] =
    P(Index ~ CharsWhile(_ != ';').! ~ end ~ Index).map { case (startIdx, snippet, endIdx) =>
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

  def memberParser(src: String)(using P[Any]): P[Member] =
    P(
      letBinding |
        fnParser |
        failedMember(src)
    )

  // -----------------------------------------------------------------------------
  // Module Parsers (Implicit / Explicit)
  // -----------------------------------------------------------------------------

  def modVisibility[$: P]: P[ModVisibility] =
    P("pub").map(_ => ModVisibility.Public) |
      P("protected").map(_ => ModVisibility.Protected) |
      P("lexical").map(_ => ModVisibility.Lexical)

  def explicitModuleParser(src: String)(using P[Any]): P[Module] =
    P(Start ~ moduleKw ~ modVisibility.? ~ typeId.! ~ defAs ~ memberParser(src).rep ~ moduleEnd)
      .map { case (maybeVis, moduleName, members) =>
        Module(moduleName, maybeVis.getOrElse(ModVisibility.Public), members.toList, false)
      }

  def implicitModuleParser(src: String, name: String)(using P[Any]): P[Module] =
    P(Start ~ memberParser(src).rep ~ moduleEnd)
      .map { members =>
        Module(name, ModVisibility.Public, members.toList, true)
      }

  def moduleParser(src: String, name: Option[String], punct: P[Any]): P[Module] =
    given P[Any] = punct
    name.fold(
      // If no name => parse explicit module
      explicitModuleParser(src)
    ) { n =>
      // If a name is provided => either explicit or implicit
      P(explicitModuleParser(src) | implicitModuleParser(src, n))
    }

  // -----------------------------------------------------------------------------
  // Top-Level Function to Parse a Module
  // -----------------------------------------------------------------------------

  def parseModule(source: String, name: Option[String] = None): Either[String, Module] =
    parse(source, moduleParser(source, name, _)) match
      case Parsed.Success(result, _) =>
        Right(result)
      case f: Parsed.Failure =>
        Left(f.trace().longMsg)
