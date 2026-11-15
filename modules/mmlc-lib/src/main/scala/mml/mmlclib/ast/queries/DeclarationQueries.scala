package mml.mmlclib.ast.queries

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Queries for declaration names, metadata, and instance implementation IDs. */
object DeclarationQueries:

  /** A checked generic function supplies a template, rather than an executable body. */
  def isGenericFunction(member: Member): Boolean = member match
    case binding: Bnd =>
      binding.value.terms match
        case List(lambda: Lambda) => lambda.typeParams.nonEmpty
        case _ => false
    case _ => false

  /** Returns checked template bindings stored in the index, including retained local declarations.
    */
  def genericFunctions(index: ResolvablesIndex): List[Bnd] =
    index.resolvables.valuesIterator
      .collect {
        case binding: Bnd if isGenericFunction(binding) => binding
      }
      .toList
      .sortBy(_.id)

  /** Callable attributes when the node is a binding with metadata or a protocol signature. */
  def metadata(node: Resolvable): Option[BindingMeta] = node match
    case binding:   Bnd => binding.meta
    case signature: ProtocolSignature => signature.meta.some
    case _ => none

  /** The original callable name when metadata is present, otherwise the node's name. */
  def sourceName(node: Resolvable): String =
    metadata(node).map(_.originalName).getOrElse(node.name)

  /** Bindings accept their internal name and original source name; other nodes use sourceName. */
  def matchesName(node: Resolvable, name: String): Boolean = node match
    case binding: Bnd => binding.name == name || sourceName(binding) == name
    case _ => sourceName(node) == name

  /** Returns all module bindings matching an internal or source name, in member order. */
  def lookupNames(name: String, module: Module): Seq[Member] =
    module.members.collect {
      case binding: Bnd if matchesName(binding, name) => binding
    }

  /** Returns implementation-binding IDs recorded by this module's direct protocol instances. */
  def loweredMemberIds(module: Module): Set[String] =
    module.members.collect { case instance: ProtocolInstance => instance.memberIds }.flatten.toSet
