package mml.mmlclib.parser

import fastparse.*
import MultiLineWhitespace.*
import mml.mmlclib.api.AstApi
import mml.mmlclib.ast.*
import cats.{Monad, Semigroup}
import cats.syntax.all.*

object Parser:

  def bindingId[$: P]: P[String] =
    P(CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  def typeId[$: P]: P[String] =
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  def lit[$: P]: P[String] =
    P(LitString | LitInt | LitBoolean)

  def LitString[$: P]:  P[String] = P("\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"")
  def LitInt[$: P]:     P[String] = P(CharsWhileIn("0-9").!)
  def LitBoolean[$: P]: P[String] = P("true" | "false").!

  def defAs[$: P]:    P[Unit] = P("=")
  def end[$: P]:      P[Unit] = P(";")
  def moduleKw[$: P]: P[Unit] = P("module")

  def term[F[_]](api: AstApi[F])(using P[Any], Monad[F]): P[F[Term]] =
    P(LitString).map(s => api.createLiteralString(s).widen[Term]) |
      P(LitInt).map(n => api.createLiteralInt(n.toInt).widen[Term]) |
      P(LitBoolean).map(b => api.createLiteralBool(b.toBoolean).widen[Term]) |
      P(bindingId).map(name => Monad[F].pure(Ref(name, none)).widen[Term])

  def expr[F[_]](api: AstApi[F])(using P[Any], Monad[F]): P[F[Expr]] =
    P(term(api).rep(1)).map { terms =>
      terms.toList.sequence.map(ts => Expr(ts))
    }

  def letBinding[F[_]](api: AstApi[F])(using P[Any], Monad[F]): P[F[Member]] =
    P("let" ~ bindingId ~ defAs ~ expr(api) ~ end).map { case (id, exprF) =>
      exprF.flatMap(expr => api.createLet(id, expr).widen[Member])
    }

  def failedMember[F[_]](src: String, api: AstApi[F])(using P[Any], Monad[F]): P[F[Member]] =
    P(Index ~ CharsWhile(_ != ';').! ~ end ~ Index).map { case (idx, failed, endIdx) =>
      // make this part of the AstApi.
      val start = indexToSourcePoint(idx, src)
      val end   = indexToSourcePoint(endIdx, src)
      api
        .createMemberError(
          start,
          end,
          s"Failed to parse member: $failed",
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

  def memberParser[F[_]](src: String, api: AstApi[F])(using P[Any], Monad[F]): P[F[Member]] =
    P(letBinding(api) | failedMember(src, api))

  def modVisibility[$: P]: P[ModVisibility] =
    P("pub").map(_ => ModVisibility.Public) |
      P("protected").map(_ => ModVisibility.Protected) |
      P("lexical").map(_ => ModVisibility.Lexical)

  def moduleParser[F[_]](src: String, api: AstApi[F], punct: P[Any])(using Monad[F]): P[F[Module]] =
    given P[Any] = punct
    P(Start ~ moduleKw ~ modVisibility.? ~ typeId.! ~ defAs ~ memberParser(src, api).rep ~ end)
      .map { case (vis, name, membersF) =>
        membersF.toList.sequence.flatMap(members =>
          api.createModule(name, vis.getOrElse(ModVisibility.Public), members)
        )
      }

  def parseModule[F[_]: AstApi: Monad](source: String): F[Either[String, Module]] =
    val api = summon[AstApi[F]]
    parse(source.trim, moduleParser[F](source, api, _)) match
      case Parsed.Success(result, _) => result.map(_.asRight)
      case f: Parsed.Failure => f.trace().longMsg.asLeft.pure[F]
