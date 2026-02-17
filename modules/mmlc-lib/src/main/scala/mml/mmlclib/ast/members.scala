package mml.mmlclib.ast

case class Bnd(
  visibility:          Visibility          = Visibility.Protected,
  override val source: SourceOrigin,
  nameNode:            Name,
  value:               Expr,
  typeSpec:            Option[Type]        = None,
  typeAsc:             Option[Type]        = None,
  docComment:          Option[DocComment]  = None,
  meta:                Option[BindingMeta] = None,
  id:                  Option[String]      = None
) extends Decl,
      FromSource:
  private val syntheticSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))
  def span: SrcSpan = source match
    case SourceOrigin.Loc(s) => s
    case SourceOrigin.Synth => syntheticSpan

/** Represents a duplicate member declaration. The first occurrence remains valid and referenceable,
  * subsequent duplicates are wrapped in this node.
  */
case class DuplicateMember(
  span:            SrcSpan,
  originalMember:  Member,
  firstOccurrence: Member
) extends Member,
      InvalidNode,
      FromSource:
  override val source: SourceOrigin = SourceOrigin.Loc(span)

/** Represents a member that is invalid for reasons other than being a duplicate. For example,
  * functions with duplicate parameter names.
  */
case class InvalidMember(
  span:           SrcSpan,
  originalMember: Member,
  reason:         String
) extends Member,
      InvalidNode,
      FromSource:
  override val source: SourceOrigin = SourceOrigin.Loc(span)

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
