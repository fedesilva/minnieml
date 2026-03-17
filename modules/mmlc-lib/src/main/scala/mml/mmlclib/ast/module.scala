package mml.mmlclib.ast

case class Module(
  source:      SourceOrigin,
  name:        String,
  visibility:  Visibility,
  members:     List[Member],
  docComment:  Option[DocComment] = None,
  sourcePath:  Option[String]     = None,
  resolvables: ResolvablesIndex   = ResolvablesIndex()
) extends AstNode,
      FromSource,
      Member

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
