package mml.mmlclib.api

import mml.mmlclib.ast._

trait AstApi[F[_]]:

  // **Modules & Bindings**
  def createModule(name: String, visibility: ModVisibility, members: List[Member]): F[Module]

  // **Comments**
  def createComment(text: String): F[Comment]

  // **Expressions**
  def createExpr(terms: List[Term]): F[Expr]

  // **Declarations**

  def createLet(name:      String, value:  Expr): F[Bnd]
  def createFunction(name: String, params: List[String], body: Expr): F[FnDef]

  // **Literals**
  def createLiteralInt(value:    Int):     F[LiteralInt]
  def createLiteralString(value: String):  F[LiteralString]
  def createLiteralBool(value:   Boolean): F[LiteralBool]
