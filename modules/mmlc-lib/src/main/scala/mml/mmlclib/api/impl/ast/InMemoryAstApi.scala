package mml.mmlclib.api.impl.ast

import mml.mmlclib.ast.*
import cats.effect.*
import mml.mmlclib.api.AstApi

object InMemoryAstApi extends AstApi[IO]:

  def createModule(name: String, members: List[Member]): IO[Module] =
    IO.pure(Module(name, members))

  def createBinding(name: String, expression: Expression): IO[Binding] =
    IO.pure(Binding(name, expression))

  def createComment(text: String): IO[Comment] =
    IO.pure(Comment(text))

  def createLiteralInt(value: Int): IO[Literal] =
    IO.pure(LiteralInt(value))

  def createLiteralString(value: String): IO[Literal] =
    IO.pure(LiteralString(value))

  def createLiteralBool(value: Boolean): IO[Literal] =
    IO.pure(LiteralBool(value))
