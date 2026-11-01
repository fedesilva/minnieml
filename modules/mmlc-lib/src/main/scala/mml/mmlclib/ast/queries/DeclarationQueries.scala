package mml.mmlclib.ast.queries

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Declaration facts read from AST nodes without registry state. */
object DeclarationQueries:

  /** Callable attributes when the node is a binding with metadata or a protocol signature. */
  def metadata(node: Resolvable): Option[BindingMeta] = node match
    case binding:   Bnd => binding.meta
    case signature: ProtocolSignature => signature.meta.some
    case _ => none

  /** The original callable name when metadata is present, otherwise the node's name. */
  def sourceName(node: Resolvable): String =
    metadata(node).map(_.originalName).getOrElse(node.name)

  /** Returns implementation-binding IDs recorded by this module's direct protocol instances.
    * Membership does not establish that an implementation is registered or valid.
    */
  def loweredMemberIds(module: Module): Set[String] =
    module.members.collect { case instance: ProtocolInstance => instance.memberIds }.flatten.toSet
