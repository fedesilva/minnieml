package mml.mmlclib.ast

case class FnDef(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  params:     List[FnParam],
  body:       Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends Decl,
      FromSource

sealed trait OpDef extends Decl, FromSource {
  def name:       String
  def precedence: Int
  def assoc:      Associativity
  def body:       Expr
}

case class BinOpDef(
  visibility: MemberVisibility = MemberVisibility.Public,
  span:       SrcSpan,
  name:       String,
  param1:     FnParam,
  param2:     FnParam,
  precedence: Int,
  assoc:      Associativity,
  /** Expression that is evaluated when the operator is used.
    */
  body: Expr,
  /** Type specification for the operator.
    *
    * If `None` type is unknown
    */
  typeSpec: Option[TypeSpec] = None,
  /** */
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends OpDef

case class UnaryOpDef(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  param:      FnParam,
  precedence: Int,
  assoc:      Associativity,
  body:       Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends OpDef

case class Bnd(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  value:      Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None,
  syntax:     Option[Decl]       = None
) extends Decl,
      FromSource

/** Represents a duplicate member declaration. The first occurrence remains valid and referenceable,
  * subsequent duplicates are wrapped in this node.
  */
case class DuplicateMember(
  span:            SrcSpan,
  originalMember:  Member,
  firstOccurrence: Member
) extends Member,
      InvalidNode,
      FromSource

/** Represents a member that is invalid for reasons other than being a duplicate. For example,
  * functions with duplicate parameter names.
  */
case class InvalidMember(
  span:           SrcSpan,
  originalMember: Member,
  reason:         String
) extends Member,
      InvalidNode,
      FromSource

case class ParsingMemberError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String]
) extends Member,
      Error

case class ParsingIdError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String],
  invalidId:  String
) extends Member,
      Error
