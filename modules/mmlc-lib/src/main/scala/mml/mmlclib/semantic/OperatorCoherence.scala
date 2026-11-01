package mml.mmlclib.semantic

import mml.mmlclib.ast.queries.{DeclarationQueries, OperatorQueries}
import mml.mmlclib.compiler.CompilerState

/** Enforces operator syntax coherence while retaining every possible implementation. */
object OperatorCoherence:

  /** Visible declarations of the same operator and arity must agree even when unused. For example,
    * two protocols may declare binary + at precedence 70, but one cannot declare it at 80: the
    * expression tree must be known before operand types select a protocol instance.
    */
  def checkModule(state: CompilerState): CompilerState =
    val index = state.module.resolvables
    val scope = ProtocolLookupScope.module(state.module)
    val names =
      scope.levels.flatten.flatMap(index.lookup).map(DeclarationQueries.sourceName).distinct
    val errors = names.flatMap { name =>
      state.protocols.visibleCandidates(name, scope, index) match
        case Left(ProtocolLookupError.RejectedDeclaration(_)) => Nil
        case Left(error) => List(ProtocolError.RegistryFailure(state.module, error))
        case Right(candidates) =>
          val operators = OperatorQueries.candidates(candidates.map(_.id), index)
          OperatorQueries.conflicts(operators).map { conflict =>
            ProtocolError.OperatorGroupingConflict(conflict.first, conflict.other)
          }
    }
    state.addErrors(errors.map(SemanticError.ProtocolErrorFound(_, phaseName)))

  private val phaseName = "mml.mmlclib.semantic.OperatorCoherence"
