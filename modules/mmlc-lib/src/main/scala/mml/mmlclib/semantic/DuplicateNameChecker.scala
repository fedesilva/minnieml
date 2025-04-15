package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object DuplicateNameChecker:

  /** Check for duplicate names in a module. Returns either a list of errors or the module if no
    * duplicates are found.
    */
  def checkModule(module: Module): Either[List[SemanticError], Module] =
    val errors =
      checkMembers(module.members.collect { case m: Decl =>
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
            case Some(existing) => (res :: existing).some
            case None => List(res).some
          }
        )
      case Nil => state
    }

    // First, collect all operator names
    val operatorNames = decls.collect { case op: OpDef =>
      op.name
    }.toSet

    val topLevelMap = memberDuplicates(decls, Map.empty)

    // Check for duplicates within the same category (bin, unary, other)
    val sameTypeErrors = topLevelMap.collect {
      case ((name, _), items) if items.size > 1 =>
        SemanticError.DuplicateName(name, items)
    }.toList

    // Check for functions with names that match operator names
    // (operators have precedence, so we flag the functions as duplicates)
    val functionOpErrors = decls.collect {
      case fn: FnDef if operatorNames.contains(fn.name) =>
        // Find all declarations with this name (including the operators)
        val allWithSameName = decls.filter(_.name == fn.name)
        SemanticError.DuplicateName(fn.name, allWithSameName)
    }

    val topLevelErrors = sameTypeErrors ++ functionOpErrors

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
