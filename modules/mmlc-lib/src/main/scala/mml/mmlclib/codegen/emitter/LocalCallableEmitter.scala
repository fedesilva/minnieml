package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.{CompiledArg, compileArgs}

/** Connects a planned binding to its allocated plain entry so calls can bypass closure values.
  * Scope entries for the original binding and its aliases hold this same target. The lambda body
  * remains in LocalCallablePlan; this record supplies the identity, types, and symbol needed at
  * call sites and when creating a value adapter.
  *
  * @param bindingId
  *   the original lambda binding ID, also used to share its closure adapter across value uses
  * @param signature
  *   the resolved parameter and return types, including Unit parameters
  * @param entrySymbol
  *   the LLVM symbol of the plain function, which takes no closure environment argument
  */
case class LocalCallableTarget(bindingId: String, signature: TypeFn, entrySymbol: String)

/** Emits a planned binding's body once and returns a scope entry without a runtime operand.
  * Registering the target before compiling its body lets recursive calls find the plain entry and
  * recursive value uses request its adapter. Tail-recursive bodies use the same plain-entry ABI.
  */
private[emitter] def compileLocalCallable(
  param:    FnParam,
  id:       String,
  callable: LocalCallableUse,
  state:    CodeGenState
): Either[CodeGenError, LocalBindingResult] =
  val name      = state.mangleName(s"${param.name}_${state.nextAnonFnId}")
  val allocated = state.copy(nextAnonFnId = state.nextAnonFnId + 1)
  val target    = LocalCallableTarget(id, callable.signature, name)
  val registered =
    allocated.copy(localCallableTargets = allocated.localCallableTargets + (id -> target))
  val lambda = callable.lambda

  for
    returnType <- getLlvmType(target.signature.returnType, registered)
    paramTypes <- target.signature.paramTypes.toList.traverse(getLlvmType(_, registered))
    tailBody = Option
      .when(lambda.meta.exists(_.isTailRecursive))(findTailRecBody(lambda, param))
      .flatten
    emitted <- tailBody match
      case Some(body) =>
        val isolated = registered.copy(output = Nil, entryPrologueOutput = Nil)
        compileTailRecursiveLambda(
          lambda,
          isolated,
          returnType,
          paramTypes,
          name,
          body,
          linkage      = "internal ",
          bindingParam = param.some
        ).map { compiled =>
          mergeFunctionBodyState(registered, compiled)
            .addDeferredDefinition(compiled.output.reverse.mkString("\n"))
        }
      case None =>
        val params = filterVoidParams(lambda.params, paramTypes)
        val scope = params.zipWithIndex.map { case ((p, _), index) =>
          val typeName = p.typeSpec
            .orElse(p.typeAsc)
            .flatMap(getNominalTypeName(_).toOption)
            .getOrElse("Unknown")
          p.name -> ScopeEntry(index, typeName)
        }.toMap
        val selfScope = Map(param.name -> ScopeEntry.Callable(id, target))
        val bodyState = registered.copy(
          output                  = Nil,
          entryPrologueOutput     = Nil,
          nextRegister            = params.size,
          insideLoopifiedFunction = false
        )
        compileExpr(lambda.body, bodyState, selfScope ++ scope).map { result =>
          val ret =
            if returnType == "void" then "  ret void"
            else s"  ret $returnType ${result.operandStr}"
          val completed    = result.state.emit(ret).emit("}")
          val declarations = formatParamDecls(params, registered.resolvables)
          val header       = s"define internal $returnType @$name($declarations) #0 {"
          mergeFunctionBodyState(registered, completed)
            .addDeferredDefinition(renderFunctionLines(header, completed).mkString("\n"))
        }
  yield LocalBindingResult(emitted, ScopeEntry.Callable(id, target), none)

/** Calls a known local entry without an environment argument. The caller checks full source arity,
  * including Unit parameters. Arguments execute once in source order; compileArgs discards Unit
  * operands only after emitting their effects.
  */
private[emitter] def compileLocalCallableCall(
  target: LocalCallableTarget,
  args:   List[Expr],
  state:  CodeGenState,
  scope:  Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  compileArgs(args, state, scope, compileExpr).flatMap { case (operands, evaluated) =>
    emitLocalCallableCall(target, operands, evaluated)
  }

/** Accepts evaluated operands so direct calls and adapter bodies share call emission without
  * evaluating source arguments a second time.
  */
private def emitLocalCallableCall(
  target: LocalCallableTarget,
  args:   List[CompiledArg],
  state:  CodeGenState
): Either[CodeGenError, CompileResult] =
  for
    returnType <- getLlvmType(target.signature.returnType, state)
    typeName <- getNominalTypeName(target.signature.returnType)
  yield
    val register = state.nextRegister
    val result   = Option.when(returnType != "void")(register)
    val line = emitCall(
      result,
      Option.when(returnType != "void")(returnType),
      target.entrySymbol,
      args.map(arg => (arg.llvmType, arg.op))
    )
    CompileResult(
      register,
      state.withRegister(register + (if result.isDefined then 1 else 0)).emit(line),
      false,
      typeName
    )

/** Produces a function value containing an adapter pointer and a null environment. The adapter
  * accepts and ignores the closure ABI's environment argument, then forwards to the existing plain
  * entry without duplicating its body. All aliases and value uses share one adapter per original
  * binding, including uses in deferred bodies.
  */
private[emitter] def compileLocalCallableValue(
  target: LocalCallableTarget,
  state:  CodeGenState
): Either[CodeGenError, CompileResult] =
  val entry = state.callableEntries.get(target.bindingId) match
    case Some(name) => (state, name).asRight[CodeGenError]
    case None =>
      for
        callable <- state.localCallablePlan.targets
          .get(target.bindingId)
          .toRight(CodeGenError("Missing planned local callable"))
        returnType <- getLlvmType(target.signature.returnType, state)
        paramTypes <- target.signature.paramTypes.toList.traverse(getLlvmType(_, state))
        (allocated, name) = state.allocAnonFnName
        registered = allocated.copy(callableEntries =
          allocated.callableEntries + (target.bindingId -> name)
        )
        emitted <- compileClosureEntry(callable.lambda, registered, name, returnType, paramTypes) {
          (bodyState, args) => emitLocalCallableCall(target, args, bodyState)
        }
      yield (emitted, name)

  entry.map { case (emitted, name) =>
    CompileResult(0, emitted, true, "Function", literalValue = s"{ ptr @$name, ptr null }".some)
  }
