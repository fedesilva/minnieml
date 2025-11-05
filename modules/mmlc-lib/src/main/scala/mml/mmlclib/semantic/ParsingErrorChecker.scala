package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Checks for ParsingError instances in a module.
  */
object ParsingErrorChecker:

  private val phaseName = "mml.mmlclib.semantic.ParsingErrorChecker"

  /** Check for MemberError instances in a module, accumulating errors in the state. */
  def checkModule(state: SemanticPhaseState): SemanticPhaseState =
    val errors = state.module.members.collect {
      case error: ParsingMemberError =>
        SemanticError.MemberErrorFound(error, phaseName)
      case error: ParsingIdError =>
        SemanticError.ParsingIdErrorFound(error, phaseName)
    }
    state.addErrors(errors)
