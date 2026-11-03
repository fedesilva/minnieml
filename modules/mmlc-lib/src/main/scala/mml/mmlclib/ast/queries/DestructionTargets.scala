package mml.mmlclib.ast.queries

import mml.mmlclib.ast.*

/** Resolve registered destruction functions once, at AST construction boundaries. */
object DestructionTargets:

  /** Returns the first indexed destructor whose typed body identifies the requested layout. */
  def forEnvironment(layoutId: String, index: ResolvablesIndex): Option[String] =
    index.resolvables.collectFirst {
      case (id, binding: Bnd)
          if binding.meta.exists(_.origin == BindingOrigin.Destructor) && TermTraversal
            .collect(binding.value) { case destruction: DestroyClosureEnvironment => destruction }
            .exists(_.layoutId == layoutId) =>
        id
    }

  /** Prefers a destructor-origin binding with this name, then any binding with this name. Ties
    * follow index iteration order; callers use this for helpers without nominal type ownership.
    */
  def named(name: String, index: ResolvablesIndex): Option[String] =
    index.resolvables
      .collectFirst {
        case (id, b: Bnd)
            if b.name == name && b.meta.exists(_.origin == BindingOrigin.Destructor) =>
          id
      }
      .orElse(index.resolvables.collectFirst {
        case (id, b: Bnd) if b.name == name => id
      })

  /** Returns a registered destructor identity using the nominal declaration's owning module. */
  def forType(tpe: Type, index: ResolvablesIndex): Option[String] =
    MemoryFunctions.free(tpe, index).map(_.id)
