package mml.mmlclib.parser

import fastparse._, MultiLineWhitespace._
import mml.mmlclib.api.AstApi
import mml.mmlclib.ast.{Module, ModVisibility, Member}
import cats.Monad
import cats.syntax.all._

object Parser:

  def bindingId[$: P]: P[String] =
    P(CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  def typeId[$: P]: P[String] =
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  def number[$: P]: P[Int] =
    P(CharsWhileIn("0-9")).!.map(_.toInt)

  def defAs[$: P]:    P[Unit] = P("=")
  def end[$: P]:      P[Unit] = P(";")
  def moduleKw[$: P]: P[Unit] = P("module")

  def letBinding[F[_]](api: AstApi[F])(using P[Any], Monad[F]): P[F[Member]] =
    P("let" ~ bindingId ~ defAs ~ number ~ end).map { (id: String, n: Int) =>
      api.createLiteralInt(n).flatMap { lit =>
        api.createExpr(List(lit)).flatMap { expr =>
          // Using widen to upcast F[Bnd] to F[Member]
          api.createLet(id, expr).widen[Member]
        }
      }
    }

  def memberParser[F[_]](api: AstApi[F])(using P[Any], Monad[F]): P[F[Member]] =
    letBinding(api)

  def moduleParser[F[_]](api: AstApi[F], punct: P[Any])(using Monad[F]): P[F[Module]] =
    given P[Any] = punct

    P(Start ~ moduleKw ~ typeId.! ~ defAs ~ memberParser(api).rep ~ end).map {
      case (name, membersF) =>
        membersF.toList.sequence.flatMap { members =>
          api.createModule(name, ModVisibility.Public, members)
        }
    }

  def parseModule[F[_]: AstApi: Monad](source: String): F[Either[String, Module]] =
    val api = summon[AstApi[F]]
    parse(source.trim, moduleParser[F](api, _)) match
      case Parsed.Success(result, _) => result.map(module => Right(module))
      case f: Parsed.Failure => Monad[F].pure(Left(f.trace().longMsg))
