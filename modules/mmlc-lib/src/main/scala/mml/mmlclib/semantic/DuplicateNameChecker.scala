package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

// TODO: checks missing
// * multiple operators in each kind are possible but,
//  * they all need to have the same precedence and associativity
// * multiple functions with the same name are possible but,
//  * they all need to have the same arity

object DuplicateNameChecker:

  /** Check for duplicate names in a module. Returns either a list of errors or the module if no
    * duplicates are found.
    */
  def checkModule(module: Module): Either[List[SemanticError], Module] =
    val errors = checkMembers(module.members.collect { case m: Decl =>
      m
    })
    if errors.nonEmpty then errors.asLeft
    else module.asRight

  private def checkMembers(decls: List[Decl]): List[SemanticError] =

    // Create a key that distinguishes binop vs unary vs other
    def resolvableKey(r: Resolvable): (String, String) = r match {
      case _: BinOpDef => (r.name, "bin")
      case _: UnaryOpDef => (r.name, "unary")
      case _ => (r.name, "other")
    }

    // Global duplicate check for top-level declarations (excluding parameters)
    def memberDuplicates(
      resolvables: List[Resolvable],
      state:       Map[(String, String), List[Resolvable]]
    ): Map[(String, String), List[Resolvable]] = resolvables match {
      case res :: rest =>
        val key = resolvableKey(res)
        memberDuplicates(
          rest,
          state.updatedWith(key) {
            case Some(existing) => Some(res :: existing)
            case None => Some(List(res))
          }
        )
      case Nil => state
    }

    val topLevelMap = memberDuplicates(decls, Map.empty)
    val topLevelErrors = topLevelMap.collect {
      case ((name, _), items) if items.size > 1 =>
        SemanticError.DuplicateName(name, items)
    }.toList

    // Now, for each function or operator, check its parameters for duplicates locally.
    val paramErrors =
      decls
        .collect {
          case fn: FnDef =>
            fn.params.groupBy(_.name).collect {
              case (name, ps) if ps.size > 1 =>
                SemanticError.DuplicateName(name, ps)
            }
          case op: BinOpDef =>
            List(op.param1, op.param2).groupBy(_.name).collect {
              case (name, ps) if ps.size > 1 =>
                SemanticError.DuplicateName(name, ps)
            }
        }
        .flatten
        .toList

    topLevelErrors ++ paramErrors
