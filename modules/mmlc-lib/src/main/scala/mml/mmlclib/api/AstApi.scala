package mml.mmlclib.api

import mml.mmlclib.ast.*

trait AstApi[F[_]]:

  // **Modules & Bindings**
  def createModule(
    name:       String,
    visibility: ModVisibility,
    members:    List[Member],
    isImplicit: Boolean = false
  ): F[Module]

  // **Comments**
  def createComment(text: String): F[Comment]

  // **Expressions**
  def createExpr(terms: List[Term]): F[Expr]

  def createMemberError(
    start:      SourcePoint,
    end:        SourcePoint,
    message:    String,
    failedCode: Option[String]
  ): F[MemberError]

  // **Declarations**

  def createLet(name:      String, value:  Expr, typeSpec:     Option[TypeSpec] = None): F[Bnd]
  def createFunction(name: String, params: List[String], body: Expr): F[FnDef]

  // **Literals**
  def createLiteralInt(value:    Int):     F[LiteralInt]
  def createLiteralString(value: String):  F[LiteralString]
  def createLiteralBool(value:   Boolean): F[LiteralBool]

  def createLiteralUnit(): F[LiteralUnit.type]
  def createLiteralFloat(value: Float): F[LiteralFloat]

  def createRef(name: String, typeSpec: Option[TypeSpec]): F[Ref]
