package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.ProtocolRegistry

/** Finds the source function used by entry-point validation and executable-wrapper emission. */
object EntryPoint:

  /** A protocol's `main` signature names an instance implementation, not the program entry point.
    */
  def find(module: Module): Option[(Bnd, Lambda)] =
    val instanceMemberIds = ProtocolRegistry.loweredMemberIds(module)
    module.members.collectFirst {
      case binding: Bnd
          if !binding.id.exists(instanceMemberIds.contains) &&
            binding.meta.exists(meta =>
              meta.origin == BindingOrigin.Function && meta.originalName == "main"
            ) =>
        binding.value.terms.headOption.collect { case lambda: Lambda => (binding, lambda) }
    }.flatten
