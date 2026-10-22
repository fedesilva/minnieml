package mml.mmlclib.compiler

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.SemanticError

/** Lifts recovered parser errors from members and nested expressions. */
object ParsingErrorChecker:

  private val phaseName = "mml.mmlclib.compiler.ParsingErrorChecker"

  /** Accumulate parser diagnostics without changing the recovered tree. */
  def checkModule(state: CompilerState): CompilerState =
    state.addErrors(state.module.members.flatMap(errorsInMember))

  private def errorsInMember(member: Member): List[SemanticError] =
    member match

      case error: ParsingMemberError =>
        List(SemanticError.MemberErrorFound(error, phaseName))
      case error: ParsingIdError =>
        List(SemanticError.ParsingIdErrorFound(error, phaseName))

      case binding: Bnd =>
        TermTraversal.collect(binding.value) { case error: TermError =>
          SemanticError.TermErrorFound(error, phaseName)
        }

      case module: Module =>
        module.members.flatMap(errorsInMember)

      case _ => Nil
