package mml.mmlclib.lsp

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.DeclarationQueries

/** Source declarations available to editor queries in the module's existing symbol index. */
private[lsp] object SourceDeclarations:

  /** Returns source-query members, including local templates retained in the index. Templates
    * precede enclosing executable bodies whose spans cover the same source; their concrete
    * instances are omitted to avoid repeating source declarations.
    */
  def members(module: Module): List[Member] =
    val templates   = DeclarationQueries.genericFunctions(module.resolvables)
    val templateIds = templates.flatMap(_.id).toSet
    val ordinary = module.members.filterNot {
      case binding: Bnd =>
        binding.id.exists(templateIds.contains) || binding.value.terms.exists {
          case lambda: Lambda => lambda.meta.exists(_.templateId.nonEmpty)
          case _ => false
        }
      case _ => false
    }
    templates.sortBy(binding =>
      binding.value.spanOpt.map(s => s.end.index - s.start.index)
    ) ++ ordinary
