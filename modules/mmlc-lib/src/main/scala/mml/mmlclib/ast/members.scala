package mml.mmlclib.ast

case class Bnd(
  visibility: Visibility          = Visibility.Protected,
  source:     SourceOrigin,
  nameNode:   Name,
  value:      Expr,
  typeSpec:   Option[Type]        = None,
  typeAsc:    Option[Type]        = None,
  docComment: Option[DocComment]  = None,
  meta:       Option[BindingMeta] = None,
  id:         Option[String]      = None
) extends Decl,
      FromSource

/** Represents a duplicate member declaration. The first occurrence remains valid and referenceable,
  * subsequent duplicates are wrapped in this node.
  */
case class DuplicateMember(
  source:          SourceOrigin,
  originalMember:  Member,
  firstOccurrence: Member
) extends Member,
      InvalidNode,
      FromSource

/** Represents a member that is invalid for reasons other than being a duplicate. For example,
  * functions with duplicate parameter names.
  */
case class InvalidMember(
  source:         SourceOrigin,
  originalMember: Member,
  reason:         String
) extends Member,
      InvalidNode,
      FromSource

object InvalidMember:
  def apply(span: SrcSpan, originalMember: Member, reason: String): InvalidMember =
    new InvalidMember(SourceOrigin.Loc(span), originalMember, reason)

case class ParsingMemberError(
  source:     SourceOrigin,
  message:    String,
  failedCode: Option[String]
) extends Member,
      Error

object ParsingMemberError:
  def apply(span: SrcSpan, message: String, failedCode: Option[String]): ParsingMemberError =
    new ParsingMemberError(SourceOrigin.Loc(span), message, failedCode)

case class ParsingIdError(
  source:     SourceOrigin,
  message:    String,
  failedCode: Option[String],
  invalidId:  String
) extends Member,
      Error

object ParsingIdError:
  def apply(
    span:       SrcSpan,
    message:    String,
    failedCode: Option[String],
    invalidId:  String
  ): ParsingIdError =
    new ParsingIdError(SourceOrigin.Loc(span), message, failedCode, invalidId)
