package mml.mmlclib.ast

case class Bnd(
  visibility: Visibility          = Visibility.Protected,
  span:       SrcSpan,
  name:       String,
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

/** Index for soft reference lookups. Stores resolvables and resolvable types by their stable IDs.
  * Updated alongside AST rewrites to always point to the current version of each node.
  */
case class ResolvablesIndex(
  resolvables:     Map[String, Resolvable]     = Map.empty,
  resolvableTypes: Map[String, ResolvableType] = Map.empty
):
  def lookup(id:     String): Option[Resolvable]     = resolvables.get(id)
  def lookupType(id: String): Option[ResolvableType] = resolvableTypes.get(id)

  def updated(r: Resolvable): ResolvablesIndex =
    r.id.fold(this)(id => copy(resolvables = resolvables.updated(id, r)))

  def updatedType(t: ResolvableType): ResolvablesIndex =
    t.id.fold(this)(id => copy(resolvableTypes = resolvableTypes.updated(id, t)))

  def updatedAll(rs: Iterable[Resolvable]): ResolvablesIndex =
    rs.foldLeft(this)((idx, r) => idx.updated(r))

  def updatedAllTypes(ts: Iterable[ResolvableType]): ResolvablesIndex =
    ts.foldLeft(this)((idx, t) => idx.updatedType(t))
