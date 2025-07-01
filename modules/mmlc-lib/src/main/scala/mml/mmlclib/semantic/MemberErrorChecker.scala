package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Checks for MemberError instances in a module.
  */
object MemberErrorChecker:

  private val phaseName = "mml.mmlclib.semantic.MemberErrorChecker"

  /** Check for MemberError instances in a module, accumulating errors in the state. */
  def checkModule(state: SemanticPhaseState): SemanticPhaseState =
    val errors = state.module.members.collect { case error: MemberError =>
      SemanticError.MemberErrorFound(error, phaseName)
    }
    state.addErrors(errors)
