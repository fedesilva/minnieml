package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Checks for MemberError instances in a module.
  *
  * This checker should be the first in the semantic pipeline to catch parsing errors before
  * spending time on other semantic transformations.
  */
object MemberErrorChecker:

  private val phaseName = "mml.mmlclib.semantic.MemberErrorChecker"

  /** Check for MemberError instances in a module, accumulating errors in the state. */
  def checkModule(state: SemanticPhaseState): SemanticPhaseState =
    val errors = state.module.members.collect { case error: MemberError =>
      SemanticError.MemberErrorFound(error, phaseName)
    }
    state.addErrors(errors)
