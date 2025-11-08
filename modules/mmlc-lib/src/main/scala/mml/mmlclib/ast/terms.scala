package mml.mmlclib.ast

import cats.data.NonEmptyList

sealed trait Term extends AstNode, Typeable, FromSource

case class TermError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String]
) extends Term,
      Error:
  final val typeSpec: Option[TypeSpec] = None
  final val typeAsc:  Option[TypeSpec] = None

case class Expr(
  span:     SrcSpan,
  terms:    List[Term],
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term

case class Cond(
  span:     SrcSpan,
  cond:     Expr,
  ifTrue:   Expr,
  ifFalse:  Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

case class App(
  span:     SrcSpan,
  fn:       Ref | App,
  arg:      Expr,
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term

case class Lambda(
  span:     SrcSpan,
  params:   List[FnParam],
  body:     Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

case class TermGroup(
  span:    SrcSpan,
  inner:   Expr,
  typeAsc: Option[TypeSpec] = None
) extends Term:
  def typeSpec: Option[TypeSpec] = inner.typeSpec

case class Tuple(
  span:     SrcSpan,
  elements: NonEmptyList[Expr],
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term

/** Points to something declared elsewhere */
case class Ref(
  span:       SrcSpan,
  name:       String,
  typeAsc:    Option[TypeSpec]   = None,
  typeSpec:   Option[TypeSpec]   = None,
  resolvedAs: Option[Resolvable] = None,
  candidates: List[Resolvable]   = Nil
) extends Term,
      FromSource

case class Placeholder(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends Term,
      FromSource

case class Hole(
  span:     SrcSpan,
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term,
      FromSource

// **Literals**

sealed trait LiteralValue extends Term, FromSource

case class LiteralInt(
  span:     SrcSpan,
  value:    Int,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralInt {
  def apply(span: SrcSpan, value: Int): LiteralInt =
    new LiteralInt(span, value, Some(TypeRef(span, "Int")), None)

  def unapply(lit: LiteralInt): Option[(SrcSpan, Int)] =
    Some((lit.span, lit.value))
}

case class LiteralString(
  span:     SrcSpan,
  value:    String,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralString {
  def apply(span: SrcSpan, value: String): LiteralString =
    new LiteralString(span, value, Some(TypeRef(span, "String")), None)

  def unapply(lit: LiteralString): Option[(SrcSpan, String)] =
    Some((lit.span, lit.value))
}

case class LiteralBool(
  span:     SrcSpan,
  value:    Boolean,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralBool {
  def apply(span: SrcSpan, value: Boolean): LiteralBool =
    new LiteralBool(span, value, Some(TypeRef(span, "Bool")), None)

  def unapply(lit: LiteralBool): Option[(SrcSpan, Boolean)] =
    Some((lit.span, lit.value))
}

case class LiteralUnit(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralUnit {
  def apply(span: SrcSpan): LiteralUnit =
    new LiteralUnit(span, Some(TypeRef(span, "Unit")), None)

  def unapply(lit: LiteralUnit): Option[SrcSpan] =
    Some(lit.span)
}

case class LiteralFloat(
  span:     SrcSpan,
  value:    Float,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralFloat {
  def apply(span: SrcSpan, value: Float): LiteralFloat =
    new LiteralFloat(span, value, Some(TypeRef(span, "Float")), None)

  def unapply(lit: LiteralFloat): Option[(SrcSpan, Float)] =
    Some((lit.span, lit.value))
}

case class NativeImpl(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None,
  nativeOp: Option[String]   = None
) extends Native,
      Term

/** Represents an expression that could not be resolved or is otherwise invalid. Preserves the
  * original expression for debugging and error reporting.
  */
case class InvalidExpression(
  span:         SrcSpan,
  originalExpr: Expr,
  typeSpec:     Option[TypeSpec] = None,
  typeAsc:      Option[TypeSpec] = None
) extends Term,
      InvalidNode,
      FromSource
