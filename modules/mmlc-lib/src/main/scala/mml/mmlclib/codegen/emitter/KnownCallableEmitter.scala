package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.{compileArgs, compileCallableCall, getResolvedName}

/** The entry convention is independent of the environment operand belonging to a closure value. */
case class KnownCallableEntry(
  definition: CallableDefinition,
  bodySymbol: String,
  callSymbol: String,
  closureAbi: Boolean
):
  def needsEnvironment: Boolean = definition.lambda.captures.nonEmpty

object KnownCallableEntry:

  /** Registers symbols before body emission so returned callables and forward uses share entries.
    */
  def allocate(state: CodeGenState): Map[String, KnownCallableEntry] =
    state.callableTargetAnalysis.definitions.map { (id, definition) =>
      val plain =
        definition.binding.isDefined || state.localCallableAnalysis.candidate(id).isDefined
      val symbol =
        definition.binding.fold(state.mangleName(s"${definition.name}_${definition.ordinal}")) {
          binding =>
            getResolvedName(Ref(binding.source, binding.name, resolvedId = binding.id), state)
        }
      val entry =
        if !plain && definition.lambda.captures.isEmpty && definition.lambda.meta.exists(
            _.isTailRecursive
          )
        then s"${symbol}__closure_entry"
        else symbol
      id -> KnownCallableEntry(definition, symbol, entry, !plain)
    }

private[emitter] def compileKnownCallableValue(
  targetId:    String,
  environment: String,
  state:       CodeGenState
): Either[CodeGenError, CompileResult] =
  state.knownCallableEntries
    .get(targetId)
    .toRight(CodeGenError("Missing known callable entry"))
    .flatMap { entry =>
      entry.definition.binding match
        case Some(binding) =>
          compileCallableValue(
            Ref(binding.source, binding.name, resolvedId = binding.id, typeSpec = binding.typeSpec),
            state
          )
        case None if !entry.closureAbi =>
          val target = LocalCallableTarget(targetId, entry.definition.signature, entry.callSymbol)
          compileLocalCallableValue(target, state)
        case None if environment == "null" =>
          CompileResult(
            0,
            state,
            true,
            "Function",
            literalValue = s"{ ptr @${entry.callSymbol}, ptr null }".some
          ).asRight
        case None =>
          val register = state.nextRegister
          val pair     = s"{ ptr @${entry.callSymbol}, ptr undef }"
          val line     = emitInsertValue(register, "{ ptr, ptr }", pair, "ptr", environment, 1)
          CompileResult(
            register,
            state.withRegister(register + 1).emit(line),
            false,
            "Function"
          ).asRight
    }

/** Obtains only the environment of a known callable, without evaluating a captured value twice. */
private[emitter] def compileKnownEnvironment(
  ref:      Ref,
  targetId: String,
  state:    CodeGenState,
  scope:    Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  scope.get(ref.name) match
    case Some(ScopeEntry.KnownCallable(id, known, environment))
        if ref.qualifier.isEmpty && ref.resolvedId.contains(id) && known == targetId =>
      CompileResult(0, state, true, "RawPtr", literalValue = environment.some).asRight
    case _ =>
      compileTerm(ref, state, scope).map { result =>
        val register = result.state.nextRegister
        val line     = emitExtractValue(register, "{ ptr, ptr }", result.operandStr, 1)
        CompileResult(
          register,
          result.state.withRegister(register + 1).emit(line),
          false,
          "RawPtr",
          exitBlock = result.exitBlock
        )
      }

/** A proof changes call selection, not evaluation or ownership. Unknown targets use ordinary calls.
  */
private[emitter] def compileKnownCallableCall(
  targetId: String,
  callee:   Ref,
  args:     List[Expr],
  state:    CodeGenState,
  scope:    Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  for
    entry <- state.knownCallableEntries
      .get(targetId)
      .toRight(CodeGenError("Missing known callable entry", callee.some))
    evaluated <- compileArgs(args, state, scope, compileExpr)
    environment <-
      if entry.needsEnvironment then
        compileKnownEnvironment(callee, targetId, evaluated.state, scope)
      else if callee.qualifier.isDefined then
        compileTerm(callee, evaluated.state, scope).map { value =>
          CompileResult(
            0,
            value.state,
            true,
            "RawPtr",
            exitBlock    = value.exitBlock,
            literalValue = "null".some
          )
        }
      else
        CompileResult(0, evaluated.state, true, "RawPtr", literalValue = "null".some)
          .asRight[CodeGenError]
    env   = environment.operandStr
    ready = environment.state
    result <- entry.definition.binding match
      case Some(binding) =>
        val ref =
          Ref(binding.source, binding.name, resolvedId = binding.id, typeSpec = binding.typeSpec)
        compileCallableCall(
          ref,
          evaluated.operands,
          entry.definition.signature.returnType.some,
          ready
        )
      case None =>
        for
          returnType <- getLlvmType(entry.definition.signature.returnType, ready)
          typeName <- getNominalTypeName(entry.definition.signature.returnType)
        yield
          val register = ready.nextRegister
          val result   = Option.when(returnType != "void")(register)
          val arguments = evaluated.operands.map(arg => (arg.llvmType, arg.op)) ++
            Option.when(entry.closureAbi)(("ptr", env)).toList
          val line = emitCall(
            result,
            Option.when(returnType != "void")(returnType),
            entry.callSymbol,
            arguments
          )
          CompileResult(
            register,
            ready.withRegister(register + (if result.isDefined then 1 else 0)).emit(line),
            false,
            typeName
          )
  yield result.copy(
    exitBlock = result.exitBlock.orElse(environment.exitBlock).orElse(evaluated.exitBlock)
  )
