package mml.mmlclib.api

import mml.mmlclib.ast.*

trait AstApi[F[_]]:

  def createModule(name:  String, members:    List[Member]): F[Module]
  def createBinding(name: String, expression: Expression):   F[Binding]

  def createComment(text: String): F[Comment]

  def createLiteralInt(value:    Int):    F[Literal]
  def createLiteralString(value: String): F[Literal]
  def createLiteralBool(value:   Boolean): F[Literal]
