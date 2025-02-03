package mml.mmlclib.api.impl

import cats.effect.*
import mml.mmlclib.api.AstApi
import mml.mmlclib.ast.{Ref, *}

object InMemoryAstApi extends AstApi[IO]:

  override def createModule(
    name:       String,
    visibility: ModVisibility,
    members:    List[Member],
    isImplicit: Boolean = false
  ): IO[Module] =
    IO.pure(Module(name, visibility, members, isImplicit))

  def createMemberError(
    start:      SourcePoint,
    end:        SourcePoint,
    message:    String,
    failedCode: Option[String]
  ): IO[MemberError] =
    IO.pure(
      MemberError(start, end, message, failedCode)
    )

  override def createComment(text: String): IO[Comment] =
    IO.pure(Comment(text))

  override def createExpr(terms: List[Term]): IO[Expr] =
    IO.pure(Expr(terms))

  override def createLet(name: String, value: Expr, typeSpec: Option[TypeSpec] = None): IO[Bnd] =
    IO.pure(Bnd(name, value, typeSpec))

  override def createFunction(name: String, params: List[String], body: Expr): IO[FnDef] =
    IO.pure(FnDef(name, params, body))

  override def createLiteralInt(value: Int): IO[LiteralInt] =
    IO.pure(LiteralInt(value))

  override def createLiteralString(value: String): IO[LiteralString] =
    IO.pure(LiteralString(value))

  override def createLiteralBool(value: Boolean): IO[LiteralBool] =
    IO.pure(LiteralBool(value))

  override def createLiteralUnit(): IO[LiteralUnit.type] = IO.pure(LiteralUnit)

  override def createLiteralFloat(value: Float): IO[LiteralFloat] =
    IO.pure(LiteralFloat(value))

  override def createRef(name: String, typeSpec: Option[TypeSpec]): IO[Ref] =
    IO.pure(Ref(name, typeSpec))
