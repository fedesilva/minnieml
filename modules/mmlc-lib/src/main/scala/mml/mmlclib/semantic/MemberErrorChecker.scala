package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Checks for MemberError instances in a module.
  *
  * This checker should be the first in the semantic pipeline to catch parsing errors before
  * spending time on other semantic transformations.
  */
object MemberErrorChecker:

  /** Check for MemberError instances in a module. Returns either a list of errors or the module if
    * no MemberError instances are found.
    *
    * @param module
    *   the module to check
    * @return
    *   either a list of errors or the module if no MemberError instances are found
    */
  def checkModule(module: Module): Either[List[SemanticError], Module] =
    val errors = module.members.collect { case error: MemberError =>
      SemanticError.MemberErrorFound(error)
    }

    if errors.nonEmpty then errors.asLeft
    else module.asRight
