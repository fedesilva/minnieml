package mml.mmlclib.compiler

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.SemanticError

/** Checks for ParsingError instances in a module. */
object ParsingErrorChecker:

  private val phaseName = "mml.mmlclib.compiler.ParsingErrorChecker"

  /** Check for MemberError instances in a module, accumulating errors in the state. */
  def checkModule(state: CompilerState): CompilerState =
    val errors = state.module.members.collect {
      case error: ParsingMemberError =>
        SemanticError.MemberErrorFound(error, phaseName)
      case error: ParsingIdError =>
        SemanticError.ParsingIdErrorFound(error, phaseName)
    }
    state.addErrors(errors)
