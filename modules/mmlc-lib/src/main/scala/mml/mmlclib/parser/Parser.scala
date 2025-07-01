package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

import MmlWhitespace.*

// TODO: to be expanded
enum ParserError extends CompilationError:
  case Failure(message: String)
  case Unknown(message: String)

type ParserResult = Either[ParserError, Module]

object Parser:

  // -----------------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------------

  def parseModule(source: String, name: Option[String] = "Anon".some): ParserResult =
    parse(source, moduleP(name, source, _)) match
      case Parsed.Success(result, _) =>
        result.asRight
      case f: Parsed.Failure =>
        // TODO:
        ParserError.Failure(f.trace().longMsg).asLeft

  // -----------------------------------------------------------------------------
  // Module Parsers (Explicit / Implicit)
  // -----------------------------------------------------------------------------

  private def moduleP(name: Option[String], source: String, p: P[Any]): P[Module] =
    given P[Any] = p
    name.fold(
      // If the module name is not provided, we assume it's an explicit module,
      // that is, the name is declared in the first line of the file.
      explicitModuleP(source)
    ) { n =>
      // If the module name is provided, we first try the explicit module parser,
      // and if that fails, we try the implicit module parser using the
      // provided name.
      P(explicitModuleP(source) | implicitModuleP(n, source))
    }

  private def explicitModuleP(source: String)(using P[Any]): P[Module] =
    P(
      Start
        ~ spP(source)
        ~ docCommentP(source)
        ~ modVisibilityP.?
        ~ moduleKw
        ~ typeIdP.!
        ~ defAsKw
        ~ membersP(source).rep
        ~ moduleEndKw
        ~ spP(source)
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

  private def modVisibilityP[$: P]: P[ModVisibility] =
    P("pub").map(_ => ModVisibility.Public) |
      P("prot").map(_ => ModVisibility.Protected) |
      P("lex").map(_ => ModVisibility.Lexical)

  private def memberVisibilityP[$: P]: P[MemberVisibility] =
    P("pub").map(_ => MemberVisibility.Public) |
      P("prot").map(_ => MemberVisibility.Protected) |
      P("priv").map(_ => MemberVisibility.Private)

  // -----------------------------------------------------------------------------
  // Single Member Parser
  // -----------------------------------------------------------------------------

  private def membersP(source: String)(using P[Any]): P[Member] =
    P(
      Pass ~
        binOpDefP(source) |
        unaryOpP(source) |
        letBindingP(source) |
        fnDefP(source) |
        typeAliasP(source) |
        nativeTypeDefP(source) |
        failedMemberP(source)
    )

  // -----------------------------------------------------------------------------
  // Let Bindings & Fn Declarations
  // -----------------------------------------------------------------------------

  private def letBindingP(source: String)(using P[Any]): P[Member] =
    P(
      Pass ~
        // Implicit whitespace consumption happens here
        docCommentP(source)
        ~ memberVisibilityP.?
        ~ letKw
        ~ spP(source) // Offset 4 chars back "let ".
        ~ bindingIdP
        ~ typeAscP(source)
        ~ defAsKw
        ~ exprP(source)
        ~ endKw
        ~ spP(source) // Capture end point *after* ';'
    )
      .map { case (doc, vis, startPoint, name, typeAsc, expr, endPoint) =>
        Bnd(
          visibility = vis.getOrElse(MemberVisibility.Protected),
          span(startPoint, endPoint),
          name,
          expr,
          expr.typeSpec,
          typeAsc,
          doc
        )
      }

  private def fnParamP(source: String)(using P[Any]): P[FnParam] =
    P(
      Pass ~
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

  private def fnDefP(source: String)(using P[Any]): P[Member] =
    P(
      Pass ~
        docCommentP(source)
        ~ memberVisibilityP.?
        ~ fnKw
        ~ spP(source) // Offset 3 chars back "fn ".
        ~ bindingIdP
        ~ "("
        ~ fnParamP(source).rep
        ~ ")"
        ~ typeAscP(source)
        ~ defAsKw
        ~ exprP(source)
        ~ endKw
        ~ spP(source) // Capture end point *after* ';'
    ).map { case (doc, vis, startPoint, fnName, params, typeAsc, bodyExpr, endPoint) =>
      FnDef(
        visibility = vis.getOrElse(MemberVisibility.Protected),
        span       = span(startPoint, endPoint),
        name       = fnName,
        params     = params.toList,
        body       = bodyExpr,
        typeSpec   = bodyExpr.typeSpec,
        typeAsc    = typeAsc,
        docComment = doc
      )
    }

  private def assocP[$: P]: P[Associativity] =
    P("left").map(_ => Associativity.Left) | P("right").map(_ => Associativity.Right)

  // TODO: we need to handle precedence values that are out of range
  //      we should return an  error for this cases
  //      and turn the member into a MemberError
  private def precedenceP[$: P]: P[Int] =
    P(CharIn("0-9").rep(1).!).map(_.toInt)

  private def binOpDefP(source: String)(using P[Any]): P[Member] =
    P(
      // Implicit whitespace consumption happens here
      Pass ~
        docCommentP(source)
        ~ memberVisibilityP.?
        ~ opKw
        ~ spP(source) // Offset 3 chars back "op ".
        ~ operatorIdP
        ~ "("
        ~ fnParamP(source)
        ~ fnParamP(source)
        ~ ")"
        ~ typeAscP(source)
        ~ precedenceP.?
        ~ assocP.?
        ~ defAsKw
        ~ exprP(source)
        ~ endKw
        ~ spP(source) // Capture end point *after* ';'
    ).map {
      case (
            doc,
            vis,
            startPoint,
            opName,
            param1,
            param2,
            typeAsc,
            precedence,
            assoc,
            bodyExpr,
            endPoint
          ) =>
        BinOpDef(
          visibility = vis.getOrElse(MemberVisibility.Protected),
          span       = span(startPoint, endPoint),
          name       = opName,
          param1     = param1,
          param2     = param2,
          precedence = precedence.getOrElse(50),
          assoc      = assoc.getOrElse(Associativity.Left),
          body       = bodyExpr,
          typeSpec   = bodyExpr.typeSpec,
          typeAsc    = typeAsc,
          docComment = doc
        )
    }

  private def unaryOpP(source: String)(using P[Any]): P[Member] =
    P(
      // Implicit whitespace consumption happens here
      Pass ~
        docCommentP(source)
        ~ memberVisibilityP.?
        ~ opKw
        ~ spP(source) // Offset 3 chars back "op ".
        ~ operatorIdP
        ~ "("
        ~ fnParamP(source)
        ~ ")"
        ~ precedenceP.?
        ~ assocP.?
        ~ defAsKw
        ~ exprP(source)
        ~ endKw
        ~ spP(source) // Capture end point *after* ';'
    ).map { case (doc, vis, startPoint, opName, param, precedence, assoc, bodyExpr, endPoint) =>
      UnaryOpDef(
        visibility = vis.getOrElse(MemberVisibility.Protected),
        span       = span(startPoint, endPoint),
        name       = opName,
        param      = param,
        precedence = precedence.getOrElse(50),
        assoc      = assoc.getOrElse(Associativity.Right),
        body       = bodyExpr,
        typeSpec   = bodyExpr.typeSpec,
        docComment = doc
      )
    }

  // -----------------------------------------------------------------------------
  // Doc Comment Parser
  // -----------------------------------------------------------------------------

  private def docCommentP[$: P](src: String): P[Option[DocComment]] =
    import fastparse.NoWhitespace.*

    def comment: P[String] = P("#-" ~ commentBody ~ "-#")
    def commentBody: P[String] =
      P((comment | (!("-#") ~ AnyChar).!).rep).map(_.mkString.stripMargin('#'))

    P(spP(src) ~ comment.! ~ spP(src)).?.map {
      case Some(start, s, end) =>
        // remove markers
        val clean = s.replaceAll("#-", "").replaceAll("-#", "").trim
        DocComment(span(start, end), clean).some
      case None => none
    }

  // -------------------------------------------------------------------------------
  // Types
  // -------------------------------------------------------------------------------

  private def typeAscP(source: String)(using P[Any]): P[Option[TypeSpec]] =
    P(":" ~/ typeSpecP(source)).?

  private def typeSpecP(source: String)(using P[Any]): P[TypeSpec] =
    P(
      typeNameP(source)
    )

  private def typeNameP(source: String)(using P[Any]): P[TypeSpec] =
    P(spP(source) ~ typeIdP ~ spP(source))
      .map { case (start, id, end) =>
        TypeRef(span(start, end), id)
      }

  private def nativeTypeDefP(source: String)(using P[Any]): P[TypeDef] =
    P(
      spP(source)
        ~ memberVisibilityP.? ~ typeKw ~ typeIdP ~ defAsKw ~ nativeTypeP(source) ~ endKw
        ~ spP(source)
    ).map { case (start, vis, id, typeSpec, end) =>
      TypeDef(
        visibility = vis.getOrElse(MemberVisibility.Protected),
        span(start, end),
        id,
        typeSpec.some
      )
    }

  private def typeAliasP(source: String)(using P[Any]): P[TypeAlias] =
    P(
      spP(source)
        ~ memberVisibilityP.? ~ typeKw ~ typeIdP ~ defAsKw ~ typeSpecP(source) ~ endKw
        ~ spP(source)
    ).map { case (start, vis, id, typeSpec, end) =>
      TypeAlias(
        visibility = vis.getOrElse(MemberVisibility.Protected),
        span(start, end),
        id,
        typeSpec
      )
    }

  // -----------------------------------------------------------------------------
  // Term & Expression Parsers
  // -----------------------------------------------------------------------------

  private def exprP(source: String)(using P[Any]): P[Expr] =
    P(spP(source) ~ termP(source).rep ~ spP(source))
      .map { case (start, ts, end) =>
        val termsList = ts.toList
        // If there's exactly one term, the Expr inherits that term's typeSpec
        val (typeSpec, typeAsc) =
          if termsList.size == 1 then (termsList.head.typeSpec, termsList.head.typeAsc)
          else (None, None)
        Expr(span(start, end), termsList, typeAsc, typeSpec)
      }

  private def nativeImplP(source: String)(using P[Any]): P[NativeImpl] =
    P(spP(source) ~ "@native" ~ spP(source))
      .map { case (start, end) =>
        NativeImpl(span(start, end))
      }

  private def nativeTypeP(source: String)(using P[Any]): P[NativeTypeImpl] =
    P(spP(source) ~ "@native" ~ spP(source))
      .map { case (start, end) =>
        NativeTypeImpl(span(start, end))
      }

  private def termP(source: String)(using P[Any]): P[Term] =
    P(
      litBoolP(source) |
        nativeImplP(source) |
        ifExprP(source) |
        litUnitP(source) |
        holeP(source) |
        litStringP(source) |
        opRefP(source) |
        numericLitP(source) |
        groupTermP(source) |
        tupleP(source) |
        refP(source) |
        phP(source)
    )

  private def tupleP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ "(" ~ exprP(source).rep(sep = ",") ~ ")" ~ spP(source))
      .map { case (start, exprs, end) =>
        NonEmptyList
          .fromList(exprs.toList)
          .fold(
            TermError(
              span       = span(start, end),
              message    = "Tuple must have at least one element",
              failedCode = source.substring(start.index, end.index).some
            )
          ) { elements =>
            Tuple(span(start, end), elements)
          }

      }

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
        TermGroup(span(start, end), expr)
      }

  private def refP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ bindingIdP ~ typeAscP(source) ~ spP(source))
      .map { case (start, id, typeAsc, end) =>
        Ref(span(start, end), id, typeAsc = typeAsc)
      }

  private def opRefP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ operatorIdP ~ spP(source))
      .map { case (start, id, end) =>
        Ref(span(start, end), id)
      }

  private def phP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ mehKw ~ spP(source))
      .map { case (start, end) =>
        Placeholder(span(start, end), None)
      }

  private def holeP(source: String)(using P[Any]): P[Term] =
    P(spP(source) ~ holeKw ~ spP(source))
      .map { case (start, end) =>
        Hole(span(start, end))
      }

  // -----------------------------------------------------------------------------
  // Literals
  // -----------------------------------------------------------------------------

  private def numericLitP(source: String)(using P[Any]): P[LiteralValue] =
    import fastparse.NoWhitespace.*
    P(
      // Try float patterns first
      // N.N
      (spP(source) ~ CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(
        source
      )).map { case (start, s, end) =>
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

  private def litStringP(source: String)(using P[Any]): P[LiteralString] =
    P(spP(source) ~ "\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"" ~ spP(source))
      .map { case (start, s, end) => LiteralString(span(start, end), s) }

  private def litBoolP(source: String)(using P[Any]): P[LiteralBool] =
    P(spP(source) ~ ("true" | "false").! ~ spP(source))
      .map { (start, b, end) =>
        LiteralBool(span(start, end), b.toBoolean)
      }

  private def litUnitP(source: String)(using P[Any]): P[LiteralUnit] =
    P(spP(source) ~ "()" ~ spP(source)).map { case (start, end) =>
      LiteralUnit(span(start, end))
    }

  // -----------------------------------------------------------------------------
  // Error Handling for Partial Parsers
  // -----------------------------------------------------------------------------

  private def failedMemberP(source: String)(using P[Any]): P[Member] =
    P(spP(source) ~ CharsWhile(_ != ';').! ~ endKw ~ spP(source))
      .map { case (start, snippet, end) =>
        MemberError(
          span       = SrcSpan(start, end),
          message    = "Failed to parse member",
          failedCode = snippet.some
        )
      }

  // -----------------------------------------------------------------------------
  // Identifiers
  // -----------------------------------------------------------------------------

  /** All bindings (let or fn, alpha ops) share this rule.
    */
  private def bindingIdP[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(!keywords ~ CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  private def operatorIdP[$: P]: P[String] =

    // Symbolic operators
    val opChars    = "=!#$%^&*+<>?/\\|~-"
    val symbolicOp = P(CharsWhile(c => opChars.indexOf(c) >= 0, min = 1).!)

    // Parse either a symbolic or alphabetic operator
    P(symbolicOp | bindingIdP)

  private def typeIdP[$: P]: P[String] =
    import fastparse.NoWhitespace.*
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  // -----------------------------------------------------------------------------
  // Keywords
  // -----------------------------------------------------------------------------

  private def letKw[$: P]:   P[Unit] = P("let")
  private def fnKw[$: P]:    P[Unit] = P("fn")
  private def opKw[$: P]:    P[Unit] = P("op")
  private def defAsKw[$: P]: P[Unit] = P("=")
  private def endKw[$: P]:   P[Unit] = P(";")
  private def mehKw[$: P]:   P[Unit] = P("_")
  private def holeKw[$: P]:  P[Unit] = P("???")
  private def typeKw[$: P]:  P[Unit] = P("type")

  private def ifKw[$: P]:   P[Unit] = P("if")
  private def elseKw[$: P]: P[Unit] = P("else")
  private def thenKw[$: P]: P[Unit] = P("then")

  private def moduleEndKw[$: P]: P[Unit] =
    P(";".? ~ CharsWhile(c => c.isWhitespace, 0) ~ End)

  private def moduleKw[$: P]: P[Unit] = P("module")

  private def nativeKw[$: P]: P[Unit] = P("@native")

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
        typeKw |
        nativeKw |
        fnKw
    )

  // -----------------------------------------------------------------------------
  // Parser for SourcePoint (spP)
  // -----------------------------------------------------------------------------

  private def spP[$: P](source: String): P[SrcPoint] =
    P(Index).map(index => indexToSourcePoint(index, source))

  // -----------------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------------

  /** Note that passing around the source and walking through it for every grammar rule is not the
    * most efficient way to do this. This is just a quick and dirty way to get this done.
    *
    * A more efficient way would be to pass a `SourceInfo` around that has all the line breaks
    * indexed and then use that to calculate the source point.
    */
  private def indexToSourcePoint(index: Int, source: String): SrcPoint =
    val upToIndex = source.substring(0, index)
    val lines     = upToIndex.split('\n')
    val line      = lines.length
    val col       = if lines.isEmpty then index + 1 else lines.last.length + 1
    SrcPoint(line, col, index)

  private def span(start: SrcPoint, end: SrcPoint): SrcSpan =
    SrcSpan(start, end)
