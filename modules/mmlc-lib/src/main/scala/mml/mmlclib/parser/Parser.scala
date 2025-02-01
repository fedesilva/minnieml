package mml.mmlclib.parser

import fastparse._, MultiLineWhitespace._ // Changed from AnyWhitespace
import mml.mmlclib.api.AstApi
import mml.mmlclib.ast.Module
import mml.mmlclib.ast.ModVisibility

object Parser:
  // Identifiers
  def bindingId[$: P]: P[String] =
    P(CharIn("a-z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  def typeId[$: P]: P[String] =
    P(CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  def typeVarId[$: P]: P[String] =
    P("'" ~ CharIn("A-Z") ~ CharsWhileIn("a-zA-Z0-9", 0)).!

  def number[$: P]: P[Int] =
    P(CharsWhileIn("0-9")).!.map(_.toInt)

  // Core tokens
  def defAs[$: P]:    P[Unit] = P("=")
  def semi[$: P]:     P[Unit] = P(";")
  def moduleKw[$: P]: P[Unit] = P("module")

  // Module parser
  def moduleParser[F[_]]: (AstApi[F], P[?]) => P[F[Module]] = (api, punct) =>
    implicit val ctx = punct
    P(Start ~ moduleKw ~ typeId ~ defAs ~ semi).map { case name =>
      api.createModule(name, ModVisibility.Public, List.empty)
    }

  def parseModule[F[_]: AstApi](source: String): Either[String, F[Module]] =
    val api = summon[AstApi[F]]
    parse(source.trim, moduleParser[F](api, _)) match
      case Parsed.Success(result, _) => Right(result)
      case f: Parsed.Failure => Left(f.trace().longMsg)
