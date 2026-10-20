package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Ownership sinks in an invocation determine whether a move lambda consumes its environment. */
object CaptureTransfers:

  def consumedByBody(lambda: Lambda, values: CallableValues): Set[String] =
    val captures = lambda.captures
      .map(_.ref)
      .filterNot { ref =>
        val origins = values.lambdas(ref)
        origins.nonEmpty && origins.forall { origin =>
          origin.captures.isEmpty && !origin.meta.exists(_.isPartialApplication)
        }
      }
      .flatMap(_.resolvedId)
      .toSet

    def resultCaptures(term: Term): Set[String] =
      values
        .resultReferences(term)
        .filter(_.qualifier.isEmpty)
        .flatMap(_.resolvedId)
        .toSet
        .intersect(captures)

    def transfers(term: Term): Set[String] = term match

      case binding: BindOwnership =>
        resultCaptures(binding.initializer) ++ transfers(binding.initializer) ++
          transfers(binding.body)

      case app: App =>
        values
          .stages(app)
          .flatMap { stage =>
            val arguments = stage.arguments.flatMap(transfers).toSet
            val callee = stage.callee match

              case scope: Lambda if stage.complete =>
                // Local bindings move inputs; source parameters retain their declared contract.
                val inputs =
                  if scope.borrowsArgument then Set.empty[String]
                  else stage.arguments.flatMap(resultCaptures).toSet
                inputs ++ transfers(scope.body)

              case value: (Ref | Lambda) => transfers(value)
              case _:     App => Set.empty[String]

            val consumedCallee =
              if stage.complete && values.consumesOnCall(stage.callee) then
                resultCaptures(stage.callee)
              else Set.empty[String]

            val consumedArguments =
              if values.recovery.isAvailable(stage.result) then
                values
                  .parameters(stage.callee)
                  .zip(stage.arguments)
                  .flatMap { (param, argument) =>
                    if param.consuming then resultCaptures(argument) else Set.empty[String]
                  }
                  .toSet
              else Set.empty[String]

            arguments ++ callee ++ consumedCallee ++ consumedArguments
          }
          .toSet

      case nested: Lambda =>
        if nested.isMove then nested.captures.flatMap(_.ref.resolvedId).toSet.intersect(captures)
        else Set.empty

      case other => TermTraversal.children(other).flatMap(transfers).toSet

    if lambda.isMove then transfers(lambda.body) ++ resultCaptures(lambda.body)
    else Set.empty

  /** A call-once entry takes responsibility for all owned fields when it disarms its destructor. */
  def ownedByInvocation(lambda: Lambda): Set[String] =
    lambda.meta.filter(_.transferredCaptures.nonEmpty).fold(Set.empty[String]) { meta =>
      lambda.captures.flatMap(_.ref.resolvedId).toSet -- meta.borrowedCaptures
    }
