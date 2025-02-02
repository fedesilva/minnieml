package mml.mmlclib.parser

import cats.Monad
import cats.syntax.all.*
import fastparse.*
import fastparse.MultiLineWhitespace.*
import mml.mmlclib.api.AstApi
import mml.mmlclib.ast.*

object Parser:

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

  def lit[$: P]: P[String] =
    P(LitString | LitInt | LitBoolean)

  def LitString[$: P]:  P[String] = P("\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"")
  def LitInt[$: P]:     P[String] = P(CharsWhileIn("0-9").!)
  def LitBoolean[$: P]: P[String] = P("true" | "false").!

  def defAs[$: P]:     P[Unit] = P("=")
  def end[$: P]:       P[Unit] = P(";")
  def moduleEnd[$: P]: P[Unit] = P(";".? | End)
  def moduleKw[$: P]:  P[Unit] = P("module")

  def term[F[_]: Monad: AstApi](using P[Any]): P[F[Term]] =
    val api = summon[AstApi[F]]
    P(
      LitString.map(s => api.createLiteralString(s).widen[Term]) |
        LitInt.map(n => api.createLiteralInt(n.toInt).widen[Term]) |
        LitBoolean.map(b => api.createLiteralBool(b.toBoolean).widen[Term]) |
        operatorId.map(op => api.createRef(op, none).widen[Term]) |
        bindingId.map(name => api.createRef(name, none).widen[Term])
    )

  def expr[F[_]: Monad: AstApi](using P[Any]): P[F[Expr]] =
    P(term[F].rep(1)).map { terms =>
      terms.toList.sequence.map { ts =>
        if ts.size == 1 then Expr(ts, ts.head.typeSpec)
        else Expr(ts)
      }
    }

  def letBinding[F[_]: Monad: AstApi](using P[Any]): P[F[Member]] =
    val api = summon[AstApi[F]]
    P("let" ~ bindingId ~ defAs ~ expr ~ end).map { case (id, exprF) =>
      exprF.flatMap { expr =>
        api.createLet(id, expr).widen[Member]
      }
    }

  def failedMember[F[_]: Monad: AstApi](src: String)(using P[Any]): P[F[Member]] =
    val api = summon[AstApi[F]]
    P(Index ~ CharsWhile(_ != ';').! ~ end ~ Index).map { case (idx, failed, endIdx) =>
      val start = indexToSourcePoint(idx, src)
      val end   = indexToSourcePoint(endIdx, src)
      api
        .createMemberError(
          start,
          end,
          s"Failed to parse member",
          Some(failed)
        )
        .widen[Member]
    }

  def indexToSourcePoint(index: Int, source: String): SourcePoint =
    val upToIndex = source.substring(0, index)
    val lines     = upToIndex.split("\n")
    val line      = lines.length
    val col       = if lines.isEmpty then index else lines.last.length + 1
    SourcePoint(line, col)

  def fnParser[F[_]: Monad: AstApi](using P[Any]): P[F[Member]] =
    val api = summon[AstApi[F]]
    P("fn" ~ bindingId ~ bindingId.rep(1) ~ defAs ~ expr ~ end).map { case (id, params, exprF) =>
      exprF.flatMap(expr => api.createFunction(id, params.toList, expr).widen[Member])
    }

  def memberParser[F[_]: Monad: AstApi](src: String)(using P[Any]): P[F[Member]] =
    P(
      letBinding |
        fnParser | failedMember(src)
    )

  def modVisibility[$: P]: P[ModVisibility] =
    P("pub").map(_ => ModVisibility.Public) |
      P("protected").map(_ => ModVisibility.Protected) |
      P("lexical").map(_ => ModVisibility.Lexical)

  def explicitModuleParser[F[_]: Monad: AstApi](src: String, punct: P[Any]): P[F[Module]] =
    given P[Any] = punct
    val api      = summon[AstApi[F]]
    P(Start ~ moduleKw ~ modVisibility.? ~ typeId.! ~ defAs ~ memberParser(src).rep ~ moduleEnd)
      .map { case (vis, name, membersF) =>
        membersF.toList.sequence.flatMap(members =>
          api.createModule(name, vis.getOrElse(ModVisibility.Public), members)
        )
      }

  def implicitModuleParser[F[_]: Monad: AstApi](
    src:   String,
    name:  String,
    punct: P[Any]
  ): P[F[Module]] =
    given P[Any] = punct

    val api = summon[AstApi[F]]
    P(Start ~ memberParser(src).rep ~ End)
      .map { case (membersF) =>
        membersF.toList.sequence.flatMap(members =>
          api.createModule(name, ModVisibility.Public, members)
        )
      }

  def moduleParser[F[_]: Monad: AstApi](
    src:   String,
    name:  Option[String],
    punct: P[Any]
  ): P[F[Module]] =
    given P[Any] = punct
    name.fold(
      explicitModuleParser(src, punct)
    )(n =>
      P(
        explicitModuleParser(src, punct) |
          implicitModuleParser(src, n, punct)
      )
    )

  def parseModule[F[_]: AstApi: Monad](
    source: String,
    name:   Option[String] = None
  ): F[Either[String, Module]] =
    val api = summon[AstApi[F]]
    parse(source, moduleParser[F](source, name, _)) match
      case Parsed.Success(result, _) => result.map(_.asRight)
      case f: Parsed.Failure => f.trace().longMsg.asLeft.pure[F]
