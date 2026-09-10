package mml.mmlclib.ast

/** Resolve registered destruction functions once, at AST construction boundaries. */
object DestructionTargets:
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
    TypeUtils.getTypeName(tpe).flatMap(TypeUtils.freeFnFor(_, index)).flatMap(named(_, index))
