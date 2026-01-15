package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def topLevelModuleP(
  name:       String,
  info:       SourceInfo,
  sourcePath: Option[String]
)(using P[Any]): P[Module] =
  P(
    spP(info) ~
      membersP(info).rep ~
      spNoWsP(info) ~
      spP(info) ~
      End
  ).map { case (start, members, end, _) =>
    val membersList = members.toList
    val filteredMembers = membersList.lastOption match
      case Some(ParsingMemberError(_, _, None)) => membersList.dropRight(1)
      case _ => membersList
    val expandedMembers = addStructConstructors(filteredMembers)
    Module(
      span       = span(start, end),
      name       = name,
      visibility = Visibility.Protected,
      members    = expandedMembers,
      docComment = None,
      sourcePath = sourcePath
    )
  }

private def addStructConstructors(members: List[Member]): List[Member] =
  members.flatMap {
    case struct: TypeStruct => List(struct, mkStructConstructor(struct))
    case other => List(other)
  }

private def mkStructConstructor(struct: TypeStruct): Bnd =
  val constructorName = s"mk${struct.name}"
  val returnType      = TypeRef(struct.span, struct.name)
  val params = struct.fields.toList.map { field =>
    FnParam(field.span, field.name, typeAsc = Some(field.typeSpec))
  }
  val arity = params.size match
    case 0 => CallableArity.Nullary
    case 1 => CallableArity.Unary
    case 2 => CallableArity.Binary
    case n => CallableArity.Nary(n)
  val meta = BindingMeta(
    origin        = BindingOrigin.Function,
    arity         = arity,
    precedence    = Precedence.Function,
    associativity = None,
    originalName  = struct.name,
    mangledName   = constructorName
  )
  val bodyExpr = Expr(
    struct.span,
    List(DataConstructor(struct.span, typeSpec = Some(returnType))),
    typeAsc  = None,
    typeSpec = Some(returnType)
  )
  val lambda = Lambda(
    span     = struct.span,
    params   = params,
    body     = bodyExpr,
    captures = Nil,
    typeSpec = bodyExpr.typeSpec,
    typeAsc  = Some(returnType)
  )
  Bnd(
    visibility = struct.visibility,
    span       = struct.span,
    name       = constructorName,
    value      = Expr(struct.span, List(lambda)),
    typeSpec   = bodyExpr.typeSpec,
    typeAsc    = Some(returnType),
    docComment = None,
    meta       = Some(meta)
  )
