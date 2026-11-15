package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.DeclarationQueries

/** Finds the source function used by entry-point validation and executable-wrapper emission. */
object EntryPoint:

  /** Finds an ordinary source `main` function. Generic templates, their specializations, and
    * protocol instance implementations cannot supply the program entry point.
    */
  def find(module: Module): Option[(Bnd, Lambda)] =
    val instanceMemberIds = DeclarationQueries.loweredMemberIds(module)
    module.members.collectFirst {
      case binding: Bnd
          if !DeclarationQueries.isGenericFunction(binding) &&
            !binding.id.exists(instanceMemberIds.contains) &&
            binding.meta.exists(meta =>
              meta.origin == BindingOrigin.Function && meta.originalName == "main"
            ) =>
        binding.value.terms.headOption.collect {
          case lambda: Lambda if !lambda.meta.exists(_.templateId.nonEmpty) => (binding, lambda)
        }
    }.flatten
