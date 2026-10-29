package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Direct lambda applications share the same local-scope lowering before and after type checking.
  */
object DirectLambdaApplication:

  /** Bind supplied arguments in source order, retaining a residual lambda for missing parameters.
    * For `{ a, b -> body; } first`, the result binds `a` once and returns `{ b -> body; }`.
    * Parameter identities and ownership markers survive; available types survive as well.
    */
  def lower(lambda: Lambda, args: List[Expr], source: SourceOrigin): Callee =
    val paramArgs       = lambda.params.zip(args)
    val remainingParams = lambda.params.drop(args.length)

    paramArgs match
      case Nil => lambda
      case _ if remainingParams.nonEmpty =>
        val remainingType = lambda.typeSpec.collect { case function: TypeFn =>
          NonEmptyList
            .fromList(function.paramTypes.toList.drop(args.length))
            .map(params => function.copy(paramTypes = params))
        }.flatten
        val residualLambda = lambda.copy(
          params   = remainingParams,
          captures = Nil,
          typeSpec = remainingType,
          meta = lambda.meta
            .getOrElse(LambdaMeta())
            .copy(
              isPartialApplication = true,
              transferredCaptures =
                paramArgs.collect { case (p, _) if p.consuming => p.id }.flatten.toSet,
              borrowedCaptures =
                paramArgs.collect { case (p, _) if !p.consuming => p.id }.flatten.toSet
            )
            .some
        )
        bindArguments(paramArgs, residualLambda, source)
      case _ =>
        val (lastParam, lastArg) = paramArgs.last
        val signature = for
          input <- lastParam.typeSpec.orElse(lastParam.typeAsc)
          output <- lambda.body.typeSpec
        yield TypeFn(lambda.source, NonEmptyList.one(input), output)
        val bodyLambda = lambda.copy(
          params   = List(lastParam),
          captures = Nil,
          typeSpec = signature,
          meta     = lambda.meta.getOrElse(LambdaMeta()).copy(isInvocationParameter = true).some
        )
        val applied = App(source, bodyLambda, lastArg, typeSpec = lambda.body.typeSpec)
        bindArguments(paramArgs.init, applied, source, invocation = true)

  private def bindArguments(
    paramArgs:  List[(FnParam, Expr)],
    inner:      Callee,
    source:     SourceOrigin,
    invocation: Boolean = false
  ): Callee =
    paramArgs.foldRight[Callee](inner) { case ((param, arg), bodyTerm) =>
      val bodyExpr = Expr(source, List(bodyTerm), typeSpec = bodyTerm.typeSpec)
      val applied  = LocalBindings.bind(param, arg, bodyExpr, source)
      applied.fn match
        case scope: Lambda if invocation =>
          applied.copy(fn = scope.copy(meta = LambdaMeta(isInvocationParameter = true).some))
        case _ => applied
    }
