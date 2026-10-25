package mml.mmlclib.semantic

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** The lexical provenance of an allocated binding, retained when its definition moves. */
final case class BindingOwner(path: String):

  def scope(ordinal: Int): BindingOwner = BindingOwner(s"$path::scope::$ordinal")

object BindingOwner:

  def binding(moduleName: String, bindingName: String): BindingOwner =
    BindingOwner(BindingIds.declaration(moduleName, bindingName))

/** Allocation history survives phase iterations and removal of generated definitions. */
final case class BindingIdSupply(
  allocated: Set[String]      = Set.empty,
  next:      Long             = 0L,
  scopes:    Map[String, Int] = Map.empty
):

  def reserve(ids: Iterable[String]): BindingIdSupply = copy(allocated = allocated ++ ids)

  def include(module: Module): BindingIdSupply =
    reserve(ResolvablesIndexer.definitions(module).flatMap(_.id))

  def fresh(owner: BindingOwner, purpose: String, name: String): (BindingIdSupply, String) =
    @scala.annotation.tailrec
    def available(ordinal: Long): (BindingIdSupply, String) =
      val id = s"${owner.path}::generated::$purpose::$ordinal::$name"
      if allocated.contains(id) then available(ordinal + 1)
      else (copy(allocated = allocated + id, next = ordinal + 1), id)

    available(next)

object BindingIds:

  type Allocation[A] = State[BindingIdSupply, A]

  /** Declaration identity shared by definitions, generated references, and allocation owners. */
  def declaration(moduleName: String, name: String, kind: String = "bnd"): String =
    s"$moduleName::$kind::$name"

  /** Retain new declaration and field identities at construction, alongside allocated locals. */
  def reserveDeclaration(declaration: Decl): Allocation[Unit] =
    val ids = declaration match
      case struct: TypeStruct => struct.id.toList ++ struct.fields.flatMap(_.id)
      case other => other.id.toList
    State.modify(_.reserve(ids))

  def scope(owner: BindingOwner): Allocation[BindingOwner] = State { supply =>
    val ordinal = supply.scopes.getOrElse(owner.path, 0)
    (supply.copy(scopes = supply.scopes.updated(owner.path, ordinal + 1)), owner.scope(ordinal))
  }

  /** The first assigned parameter anchors nested allocation. Generated parameters retain their
    * purpose and ordinal as part of that scope anchor. Re-entering a relocated definition keeps the
    * same anchor; parameterless scopes allocate an explicit path.
    */
  def within(params: List[FnParam], owner: BindingOwner): Allocation[BindingOwner] =
    params
      .flatMap(_.id)
      .headOption
      .flatMap { id =>
        val separator = id.lastIndexOf("::")
        Option.when(separator >= 0)(BindingOwner(id.substring(0, separator)))
      }
      .fold(scope(owner))(_.pure[Allocation])

  /** Preserve assigned identities; source parameters use their lexical scope and name. */
  def assign(param: FnParam, owner: BindingOwner): Allocation[FnParam] = State { supply =>
    param.id match
      case Some(id) => (supply.reserve(List(id)), param)
      case None =>
        val proposed = s"${owner.path}::${param.name}"
        if supply.allocated.contains(proposed) then
          val (next, id) = supply.fresh(owner, "parameter", param.name)
          (next, param.copy(id = id.some))
        else (supply.reserve(List(proposed)), param.copy(id = proposed.some))
  }

  /** Allocate a distinct definition even when the template already has an identity. */
  def fresh(param: FnParam, owner: BindingOwner, purpose: String): Allocation[FnParam] =
    State { supply =>
      val (next, id) = supply.fresh(owner, purpose, param.name)
      (next, param.copy(id = id.some))
    }
