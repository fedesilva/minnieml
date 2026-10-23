package mml.mmlclib.ast

/** Resolve registered destruction functions once, at AST construction boundaries. */
object DestructionTargets:

  /** Environment helpers identify their layout in the typed destruction body. */
  def forEnvironment(layoutId: String, index: ResolvablesIndex): Option[String] =
    index.resolvables.collectFirst {
      case (id, binding: Bnd)
          if binding.meta.exists(_.origin == BindingOrigin.Destructor) && TermTraversal
            .collect(binding.value) { case destruction: DestroyClosureEnvironment => destruction }
            .exists(_.layoutId == layoutId) =>
        id
    }

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

  def forType(tpe: Type, index: ResolvablesIndex): Option[String] =
    TypeUtils
      .canonical(tpe, index)
      .flatMap(TypeUtils.getTypeName)
      .flatMap(TypeUtils.freeFnFor(_, index))
      .flatMap(named(_, index))
