package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.compileLocalBindingValue

/** A scoped result carries its value and destruction obligation in separate SSA operands. */
private[emitter] case class OwnershipResult(value: CompileResult, owns: CompileResult):

  def state:     CodeGenState   = owns.state
  def exitBlock: Option[String] = owns.exitBlock.orElse(value.exitBlock)

private case class OwnershipBinding(
  state:     CodeGenState,
  scope:     Map[String, ScopeEntry],
  exitBlock: Option[String]
)

/** Binds both results of an ownership-producing scope without changing the runtime value type. */
private[emitter] def compileOwnershipBinding(
  binding: BindOwnership,
  state:   CodeGenState,
  scope:   Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  for
    bound <- prepareOwnershipBinding(binding, state, scope)
    body <- compileExpr(binding.body, bound.state, bound.scope)
  yield body.copy(exitBlock = body.exitBlock.orElse(bound.exitBlock))

private def prepareOwnershipBinding(
  binding: BindOwnership,
  state:   CodeGenState,
  scope:   Map[String, ScopeEntry]
): Either[CodeGenError, OwnershipBinding] =
  compileOwnershipResult(binding.initializer, state, scope).map: result =>
    val extended = ownershipResultScope(binding.valueParam, binding.witnessParam, result, scope)
    OwnershipBinding(result.state, extended, result.exitBlock)

/** Identity keys keep saved witnesses available when an inner binding reuses their source name. */
private[emitter] def ownershipResultScope(
  valueParam:   FnParam,
  witnessParam: FnParam,
  result:       OwnershipResult,
  scope:        Map[String, ScopeEntry]
): Map[String, ScopeEntry] =
  List(valueParam -> result.value, witnessParam -> result.owns).foldLeft(scope):
    case (current, (param, value)) =>
      val entry = ScopeEntry.fromResult(value)
      current.updated(param.name, entry) ++ param.id.map(_ -> entry)

/** Only ownership-result leaves may finish this mode; ordinary values cannot invent a witness. */
private[emitter] def compileOwnershipResult(
  expr:  Expr,
  state: CodeGenState,
  scope: Map[String, ScopeEntry]
): Either[CodeGenError, OwnershipResult] =
  expr.terms match

    case List(result: YieldOwnership) =>
      for
        value <- compileExpr(result.value, state, scope)
        owns <- compileExpr(result.owns, value.state, scope)
      yield OwnershipResult(value, owns)

    case List(binding: BindOwnership) =>
      for
        bound <- prepareOwnershipBinding(binding, state, scope)
        body <- compileOwnershipResult(binding.body, bound.state, bound.scope)
      yield body.copy(owns = body.owns.copy(exitBlock = body.exitBlock.orElse(bound.exitBlock)))

    case List(app: App) =>
      app.fn match

        case lambda: Lambda if lambda.params.size == 1 =>
          val param = lambda.params.head

          for
            argument <- compileLocalBindingValue(param, app.arg, state, scope, compileExpr)
            body <- compileOwnershipResult(
              lambda.body,
              argument.state,
              scope.updated(param.name, argument.entry)
            )
          yield body.copy(owns =
            body.owns.copy(exitBlock = body.exitBlock.orElse(argument.exitBlock))
          )

        case _ =>
          CodeGenError("Ownership result requires an immediate unary scope", app.some).asLeft

    case List(cond: Cond) => compileOwnershipConditional(cond, state, scope)
    case List(group: TermGroup) => compileOwnershipResult(group.inner, state, scope)
    case List(inner: Expr) => compileOwnershipResult(inner, state, scope)
    case _ => CodeGenError("Missing ownership result at scope exit", expr.some).asLeft

private def compileOwnershipConditional(
  cond:  Cond,
  state: CodeGenState,
  scope: Map[String, ScopeEntry]
): Either[CodeGenError, OwnershipResult] =
  for
    condition <- compileExpr(cond.cond, state, scope)
    valueType <- cond.typeSpec
      .orElse(cond.ifTrue.typeSpec)
      .toRight(CodeGenError("Missing ownership result type", cond.some))
      .flatMap(getLlvmType(_, condition.state))
    thenLabel  = s"owns_then${condition.state.nextRegister}"
    elseLabel  = s"owns_else${condition.state.nextRegister + 1}"
    mergeLabel = s"owns_merge${condition.state.nextRegister + 2}"
    branch = condition.state
      .withRegister(condition.state.nextRegister + 3)
      .emit(s"  br i1 ${condition.operandStr}, label %$thenLabel, label %$elseLabel")
    onTrue <- compileOwnershipResult(cond.ifTrue, branch.emit(s"$thenLabel:"), scope)
    trueExit  = onTrue.exitBlock.getOrElse(thenLabel)
    afterTrue = onTrue.state.emit(s"  br label %$mergeLabel").emit(s"$elseLabel:")
    onFalse <- compileOwnershipResult(cond.ifFalse, afterTrue, scope)
    falseExit     = onFalse.exitBlock.getOrElse(elseLabel)
    valueRegister = onFalse.state.nextRegister
    ownsRegister  = valueRegister + (if valueType == "void" then 0 else 1)
    merge         = onFalse.state.emit(s"  br label %$mergeLabel").emit(s"$mergeLabel:")
    withValue =
      if valueType == "void" then merge
      else
        merge.emit(
          s"  %$valueRegister = phi $valueType " +
            s"[ ${onTrue.value.operandStr}, %$trueExit ], " +
            s"[ ${onFalse.value.operandStr}, %$falseExit ]"
        )
    finished = withValue
      .emit(
        s"  %$ownsRegister = phi i1 " +
          s"[ ${onTrue.owns.operandStr}, %$trueExit ], " +
          s"[ ${onFalse.owns.operandStr}, %$falseExit ]"
      )
      .withRegister(ownsRegister + 1)
    value = CompileResult(
      valueRegister,
      finished,
      valueType == "void",
      onTrue.value.typeName,
      mergeLabel.some
    )
    owns = CompileResult(ownsRegister, finished, false, "Bool", mergeLabel.some)
  yield OwnershipResult(value, owns)
