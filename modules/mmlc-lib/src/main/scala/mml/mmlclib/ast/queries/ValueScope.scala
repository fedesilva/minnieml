package mml.mmlclib.ast.queries

import mml.mmlclib.ast.*

/** Lexical levels run from innermost to outermost; each level contains visible definition IDs. For
  * List(List(localCombineId), List(semigroupCombineId, otherCombineId)), a local parameter named
  * combine hides both outer signatures. Without that local name, both signatures survive. Callers
  * supply visible signature or binding IDs, not the IDs of their containing protocols.
  */
case class ValueScope(levels: List[List[String]]):

  /** Add the assigned parameter IDs as the innermost lexical level. */
  def within(params: List[FnParam]): ValueScope =
    copy(levels = params.flatMap(_.id) :: levels)

object ValueScope:

  /** A module's value level contains ordinary bindings and protocol signatures in member order.
    * Recorded instance implementations are excluded; declarations without IDs are omitted.
    */
  def module(module: Module): ValueScope =

    val instanceBindings = DeclarationQueries.loweredMemberIds(module)
    val visible = module.members.flatMap {
      case binding:  Bnd if !binding.id.exists(instanceBindings.contains) =>            binding.id.toList
      case protocol: Protocol => protocol.signatures.flatMap(_.id)
      case _ => Nil
    }

    ValueScope(List(visible))
