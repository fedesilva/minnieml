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

    def resultCaptures(term: Term): Set[String] = term match
      case ref:   Ref if ref.qualifier.isEmpty => ref.resolvedId.toSet.intersect(captures)
      case expr:  Expr => expr.terms.lastOption.fold(Set.empty[String])(resultCaptures)
      case group: TermGroup => resultCaptures(group.inner)
      case cond:  Cond => resultCaptures(cond.ifTrue) ++ resultCaptures(cond.ifFalse)
      case app:   App =>
        app.fn match
          case scope: Lambda => resultCaptures(scope.body)
          case _ => Set.empty
      case _ => Set.empty

    def transfers(term: Term): Set[String] = term match
      case app: App =>
        app.fn match
          case scope: Lambda =>
            // A local binding moves an owned source into its continuation parameter.
            resultCaptures(app.arg) ++ transfers(app.arg) ++ transfers(scope.body)
          case _ =>
            val (callee, arguments) = CallableValues.application(app)
            val parameters          = values.parameters(callee)
            val consumedCallee =
              if arguments.size >= parameters.size && values.consumesOnCall(callee) then
                resultCaptures(callee)
              else Set.empty[String]
            val consumedArguments = parameters
              .zip(arguments)
              .flatMap { (param, argument) =>
                if param.consuming then resultCaptures(argument) else Set.empty[String]
              }
              .toSet
            consumedCallee ++ consumedArguments ++ arguments.flatMap(transfers)
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
