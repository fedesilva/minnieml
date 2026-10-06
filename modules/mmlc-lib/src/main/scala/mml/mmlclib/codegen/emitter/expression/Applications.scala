package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  LocalBindingResult,
  LocalCallableAnalysis,
  ScopeEntry,
  compileLambdaLiteral,
  compileLocalCallable,
  emitCall,
  emitExtractValue,
  emitIndirectCall,
  getLlvmType,
  getNominalTypeName
}

/** Collects all arguments from nested App nodes (handles curried applications).
  *
  * For example, `mult 2 2` is represented as App(App(Ref(mult), 2), 2).
  */
def collectArgsAndFunction(
  app:  App,
  args: List[Expr] = List.empty
): (Ref | Lambda, List[Expr]) =
  app.fn match
    case ref:       Ref => (ref, app.arg :: args)
    case nestedApp: App => collectArgsAndFunction(nestedApp, app.arg :: args)
    case lambda:    Lambda => (lambda, app.arg :: args)

/** Compiles an immediate lambda application (from let-expression desugaring).
  *
  * `let x = E; body` desugars to `App(Lambda([x], body), E)`. Emitting the binding and continuation
  * in the current LLVM function preserves lexical scope without constructing or calling a closure
  * for this desugaring lambda.
  */
def compileLambdaApp(
  lambda:        Lambda,
  allArgs:       List[Expr],
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  if lambda.params.size == 1 && allArgs.size == 1 then
    val param = lambda.params.head
    val arg   = allArgs.head
    for
      argRes <- compileLocalBindingValue(param, arg, state, functionScope, compileExpr)
      extendedScope = functionScope + (param.name -> argRes.entry)
      bodyRes <- compileExpr(lambda.body, argRes.state, extendedScope)
    // Preserve exit block from argument if body doesn't have one
    // (needed when arg contains a conditional like `let x = if cond then a else b end`)
    yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))
  else
    CodeGenError(
      "Immediate lambda application with multiple params/args not yet supported",
      lambda.some
    ).asLeft

/** Chooses the scope entry for a let binding before compiling its continuation. Planned lambdas
  * emit a plain entry; aliases reuse an existing target without constructing a closure. Other
  * expressions produce runtime values. Returning LocalBindingResult lets callers extend their scope
  * in all three cases without requiring an LLVM operand for a known callable.
  */
private[emitter] def compileLocalBindingValue(
  param:         FnParam,
  arg:           Expr,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, LocalBindingResult] =
  val candidate = param.id.flatMap(id => state.localCallableAnalysis.candidate(id).map(id -> _))
  val alias = for
    id <- param.id
    canonical <- state.localCallableAnalysis.aliases.get(id)
    ref <- LocalCallableAnalysis.reference(arg)
    target <- ScopeEntry.callable(ref, functionScope)
    if target.bindingId == canonical
  yield ScopeEntry.Callable(id, target)

  candidate match
    case Some((id, callable)) => compileLocalCallable(param, id, callable, state)
    case None =>
      alias match
        case Some(entry) => LocalBindingResult(state, entry, none).asRight
        case None =>
          compileRuntimeBinding(param, arg, state, functionScope, compileExpr).map { result =>
            LocalBindingResult(result.state, ScopeEntry.fromResult(result), result.exitBlock)
          }

/** Preserves the binding identity while emitting a runtime value. A lambda needs its entry symbol
  * before body emission so recursive references can construct the correct self closure.
  */
private def compileRuntimeBinding(
  param:         FnParam,
  arg:           Expr,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  val (preAlloc, argScope) = arg.terms match
    case List(lambda: Lambda) =>
      val uniqueName  = s"${param.name}_${state.nextAnonFnId}"
      val stateWithId = state.copy(nextAnonFnId = state.nextAnonFnId + 1)
      val fnName = state.callableTargetAnalysis.lambdas
        .get(lambda)
        .flatMap(state.knownCallableEntries.get)
        .fold(stateWithId.mangleName(uniqueName))(_.bodySymbol)
      val recursiveScope =
        if lambda.captures.nonEmpty then functionScope
        else
          val entry = ScopeEntry(
            0,
            "Function",
            isLiteral    = true,
            literalValue = s"{ ptr @$fnName, ptr null }".some
          )
          functionScope + (param.name -> entry)
      ((stateWithId, fnName).some, recursiveScope)
    case _ => (none, functionScope)

  val compileState = preAlloc.map(_._1).getOrElse(state)
  arg.terms match
    case List(lambdaLit: Lambda) =>
      compileLambdaLiteral(lambdaLit, compileState, argScope, preAlloc, param.some)
        .map { res =>
          // Non-capturing: value is a constant literal, safe to discard sub-output.
          // Capturing: call-site IR (malloc/store/insertvalue) defines the fat pointer
          // register and must be preserved.
          if res.isLiteral then res.copy(state = res.state.copy(output = state.output))
          else res
        }
    case _ => compileExpr(arg, compileState, argScope)

/** Compiles a native operator application using its template.
  *
  * Handles both binary and unary native operators.
  */
def compileNativeOp(
  fnRef:         Ref,
  tpl:           String,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  allArgs match
    case List(leftArg, rightArg) =>
      compileBinaryNativeOp(fnRef, tpl, leftArg, rightArg, state, functionScope, compileExpr)
    case List(operandArg) =>
      compileUnaryNativeOp(fnRef, tpl, operandArg, state, functionScope, compileExpr)
    case _ =>
      CodeGenError(
        s"Native operator called with wrong number of arguments: ${allArgs.length}",
        app.some
      ).asLeft

private def compileBinaryNativeOp(
  fnRef:         Ref,
  tpl:           String,
  leftArg:       Expr,
  rightArg:      Expr,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  for
    leftRes <- compileExpr(leftArg, state, functionScope)
    rightRes <- compileExpr(rightArg, leftRes.state, functionScope)

    resultReg = rightRes.state.nextRegister
    leftOp    = leftRes.operandStr
    rightOp   = rightRes.operandStr

    llvmType <- leftArg.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, rightRes.state)
      case None =>
        CodeGenError(
          s"Missing type information for binary operator operand",
          leftArg.some
        ).asLeft

    instruction = substituteTemplate(tpl, llvmType, List(leftOp, rightOp))
    line        = s"  %$resultReg = $instruction"
    finalState  = rightRes.state.withRegister(resultReg + 1).emit(line)

    typeName <- getMmlTypeForOp(fnRef) match
      case Some(t) => t.asRight
      case None =>
        CodeGenError(
          s"Could not determine return type for binary operator '${fnRef.name}'",
          fnRef.some
        ).asLeft
  yield CompileResult(resultReg, finalState, false, typeName)

private def compileUnaryNativeOp(
  fnRef:         Ref,
  tpl:           String,
  operandArg:    Expr,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  for
    operandRes <- compileExpr(operandArg, state, functionScope)

    resultReg = operandRes.state.nextRegister
    operandOp = operandRes.operandStr

    llvmType <- operandArg.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, operandRes.state)
      case None =>
        CodeGenError(
          s"Missing type information for unary operator operand",
          operandArg.some
        ).asLeft

    instruction = substituteTemplate(tpl, llvmType, List(operandOp))
    line        = s"  %$resultReg = $instruction"
    finalState  = operandRes.state.withRegister(resultReg + 1).emit(line)

    typeName <- getMmlTypeForOp(fnRef) match
      case Some(t) => t.asRight
      case None =>
        CodeGenError(
          s"Could not determine return type for unary operator '${fnRef.name}'",
          fnRef.some
        ).asLeft
  yield CompileResult(resultReg, finalState, false, typeName)

/** Checks if all arguments are unit literals. */
def allArgsAreUnitLiterals(allArgs: List[Expr]): Boolean =
  allArgs.forall { arg =>
    arg.terms match
      case List(_: LiteralUnit) => true
      case _ => false
  }

/** Checks if this is a nullary function call with unit arguments. */
def isNullaryWithUnitArgs(fnRef: Ref, allArgs: List[Expr], resolvables: ResolvablesIndex): Boolean =
  fnRef.resolvedId.flatMap(resolvables.lookup) match
    case Some(bnd: Bnd) if bnd.meta.exists(_.arity == CallableArity.Nullary) =>
      allArgsAreUnitLiterals(allArgs)
    case _ => false

/** Compiles a nullary function call (skips unit arguments). */
def compileNullaryCall(
  fnRef: Ref,
  app:   App,
  state: CodeGenState
): Either[CodeGenError, CompileResult] =
  compileCallableCall(fnRef, Nil, app.typeSpec, state)

/** Compiles a regular function call with arguments. */
def compileRegularCall(
  fnRef:         Ref,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  compileArgs(allArgs, state, functionScope, compileExpr).flatMap {
    case (compiledArgs, finalState) =>
      compileCallableCall(fnRef, compiledArgs, app.typeSpec, finalState)
  }

/** Retains an evaluated argument for call emission, which must not evaluate the expression again.
  * llvmType describes the emitted operand; typeSpec retains source type information needed for
  * native ABI lowering and alias metadata.
  */
private[emitter] case class CompiledArg(op: String, llvmType: String, typeSpec: Option[Type])

/** Emits a call to a resolved callable using its native template or target ABI when required. */
private[emitter] def compileCallableCall(
  fnRef:      Ref,
  args:       List[CompiledArg],
  resultType: Option[Type],
  state:      CodeGenState
): Either[CodeGenError, CompileResult] =
  resultType.toRight(CodeGenError("Missing callable result type", fnRef.some)).flatMap { tpe =>
    for
      llvmType <- getLlvmType(tpe, state)
      typeName <- getNominalTypeName(tpe)
      result <- getFunctionTemplate(fnRef.resolvedId.flatMap(state.resolvables.lookup)) match
        case Some(tpl) =>
          compileFunctionWithTemplate(tpl, args, llvmType, typeName, state)
        case None =>
          compileStandardCall(fnRef, args, llvmType, typeName, state)
    yield result
  }

/** Evaluates arguments once in source order, threading their effects through the returned state.
  * Unit expressions still execute, but contribute no operand to the LLVM argument list.
  */
private[emitter] def compileArgs(
  allArgs:       List[Expr],
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, (List[CompiledArg], CodeGenState)] =
  allArgs.foldLeft((List.empty[CompiledArg], state).asRight[CodeGenError]) {
    case (Right((compiledArgs, currentState)), arg) =>
      compileExpr(arg, currentState, functionScope).flatMap { argRes =>
        val argOp = argRes.operandStr

        arg.typeSpec match
          case Some(typeSpec) =>
            getLlvmType(typeSpec, argRes.state) match
              case Right(llvmType) =>
                // Skip void/Unit args - they can't be passed in LLVM
                if llvmType == "void" then (compiledArgs, argRes.state).asRight
                else
                  (
                    compiledArgs :+ CompiledArg(argOp, llvmType, arg.typeSpec),
                    argRes.state
                  ).asRight
              case Left(err) => err.asLeft
          case None =>
            CodeGenError(
              s"Missing type information for function argument - TypeChecker should have provided this",
              arg.some
            ).asLeft
      }
    case (Left(err), _) => err.asLeft
  }

/** Compiles a function with inline template (LLVM intrinsics like llvm.sqrt). */
private def compileFunctionWithTemplate(
  tpl:          String,
  compiledArgs: List[CompiledArg],
  returnType:   String,
  typeName:     String,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val instruction = compiledArgs match
    case List(CompiledArg(argOp, argType, _)) =>
      tpl.replace("%type", argType).replace("%operand", argOp)
    case args =>
      args.zipWithIndex
        .foldLeft(tpl) { case (t, (CompiledArg(argOp, argType, _), i)) =>
          t.replace(s"%operand${i + 1}", argOp).replace(s"%type${i + 1}", argType)
        }
        .replace("%type", args.headOption.map(_.llvmType).getOrElse(""))

  if returnType == "void" then
    CompileResult(0, state.emit(s"  $instruction"), false, typeName).asRight
  else
    val reg = state.nextRegister
    CompileResult(
      reg,
      state.withRegister(reg + 1).emit(s"  %$reg = $instruction"),
      false,
      typeName
    ).asRight

/** Compiles a standard function call (non-template). */
private def compileStandardCall(
  fnRef:        Ref,
  compiledArgs: List[CompiledArg],
  fnReturnType: String,
  typeName:     String,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val isNative = fnRef.resolvedId.flatMap(state.resolvables.lookup).exists {
    case bnd: Bnd => isNativeBinding(bnd)
    case _ => false
  }
  val rawArgs = compiledArgs.map(arg => (arg.op, arg.llvmType))
  val (stateWithAlias, aliasScopeTag, noaliasTag) =
    buildCallAliasMetadata(getResolvedName(fnRef, state), compiledArgs, state)
  val fnName = getResolvedName(fnRef, stateWithAlias)

  if isNative then
    mml.mmlclib.codegen.emitter.abis.NativeAbiPlan
      .classify(fnReturnType, rawArgs.map(_._2), stateWithAlias)
      .map { plan =>
        val (reg, emitted) = plan.emitCall(
          fnName,
          rawArgs.map(_._1),
          stateWithAlias,
          aliasScopeTag,
          noaliasTag
        )
        CompileResult(reg, emitted, false, typeName)
      }
  else
    val args = rawArgs.map { case (value, typ) => (typ, value) }
    if fnReturnType == "void" then
      val line = emitCall(none, none, fnName, args, aliasScopeTag, noaliasTag)
      CompileResult(0, stateWithAlias.emit(line), false, typeName).asRight
    else
      val reg  = stateWithAlias.nextRegister
      val line = emitCall(reg.some, fnReturnType.some, fnName, args, aliasScopeTag, noaliasTag)
      CompileResult(reg, stateWithAlias.withRegister(reg + 1).emit(line), false, typeName).asRight

private val StaticNullEnvClosure = """^\{ ptr @([^,\s]+), ptr null \}$""".r

private def staticNullEnvClosureTarget(closure: String): Option[String] =
  closure match
    case StaticNullEnvClosure(fnName) => fnName.some
    case _ => none

private def compileStaticNullEnvClosureCall(
  fnName:       String,
  compiledArgs: List[CompiledArg],
  fnReturnType: String,
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val allArgs = compiledArgs.map(arg => (arg.llvmType, arg.op)) :+ ("ptr", "null")
  if fnReturnType == "void" then
    val callLine = emitCall(none, none, fnName, allArgs)
    CompileResult(0, state.emit(callLine), false, "Unit").asRight
  else
    val resultReg = state.nextRegister
    val callLine  = emitCall(resultReg.some, fnReturnType.some, fnName, allArgs)
    app.typeSpec.flatMap(getNominalTypeName(_).toOption) match
      case Some(typeName) =>
        CompileResult(
          resultReg,
          state.withRegister(resultReg + 1).emit(callLine),
          false,
          typeName
        ).asRight
      case None =>
        CodeGenError(
          s"Could not determine MML type for static closure call result",
          app.some
        ).asLeft

private def sanitizeLabelPart(raw: String): String =
  raw.replace("%", "reg").replaceAll("[^A-Za-z0-9_\\.]", "_")

private def buildCallAliasMetadata(
  fnName:       String,
  compiledArgs: List[CompiledArg],
  state:        CodeGenState
): (CodeGenState, Option[String], Option[String]) =
  val labels = compiledArgs.zipWithIndex.map { case (arg, idx) =>
    val labelOp = sanitizeLabelPart(arg.op)
    s"$fnName.arg$idx.$labelOp"
  }
  buildAliasTags(labels, state)

private def buildAliasTags(
  labels: List[String],
  state:  CodeGenState
): (CodeGenState, Option[String], Option[String]) =
  if labels.isEmpty || !state.emitAliasScopes then (state, none, none)
  else
    val (stateWithScopes, scopeIds) = labels.foldLeft((state, List.empty[Int])) {
      case ((s, acc), label) =>
        val (s1, id) = s.ensureAliasScopeNode(label)
        (s1, id :: acc)
    }
    val scopeIdList   = scopeIds.reverse
    val aliasScopeTag = s"!{${scopeIdList.map(id => s"!$id").mkString(", ")}}"
    val otherScopeIds = stateWithScopes.aliasScopeIds.values.filterNot(scopeIdList.toSet).toList
    val sortedNoalias = otherScopeIds.sorted.map(id => s"!$id")
    val noaliasTagOpt =
      if sortedNoalias.isEmpty then none else s"!{${sortedNoalias.mkString(", ")}}".some
    (stateWithScopes, aliasScopeTag.some, noaliasTagOpt)

/** Calls a function value using its closure entry and environment. A constant null-environment pair
  * can call its entry symbol directly, but still passes the closure ABI's environment operand.
  */
def compileIndirectCall(
  fnRef:         Ref,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  compileArgs(allArgs, state, functionScope, compileExpr).flatMap { case (compiledArgs, argState) =>
    val fnReturnTypeResult = app.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, argState)
      case None =>
        CodeGenError(
          s"Missing return type for indirect call '${fnRef.name}'",
          app.some
        ).asLeft

    fnReturnTypeResult.flatMap { fnReturnType =>
      resolveIndirectCallee(fnRef, argState, functionScope, compileExpr).flatMap {
        case (closure, stateAfterCallee) =>
          staticNullEnvClosureTarget(closure) match
            case Some(fnName) =>
              compileStaticNullEnvClosureCall(
                fnName,
                compiledArgs,
                fnReturnType,
                app,
                stateAfterCallee
              )
            case None =>
              // Extract fn pointer and env from the fat pointer
              val fnReg  = stateAfterCallee.nextRegister
              val envReg = fnReg + 1
              val extractFn =
                emitExtractValue(fnReg, "{ ptr, ptr }", closure, 0)
              val extractEnv =
                emitExtractValue(envReg, "{ ptr, ptr }", closure, 1)
              val stateAfterExtract = stateAfterCallee
                .withRegister(envReg + 1)
                .emit(extractFn)
                .emit(extractEnv)

              // Build args with env as the last parameter
              val userArgs = compiledArgs.map(arg => (arg.llvmType, arg.op))
              val allArgs  = userArgs :+ ("ptr", s"%$envReg")
              val fnPtr    = s"%$fnReg"

              if fnReturnType == "void" then
                val callLine = emitIndirectCall(none, none, fnPtr, allArgs)
                CompileResult(0, stateAfterExtract.emit(callLine), false, "Unit").asRight
              else
                val resultReg = stateAfterExtract.nextRegister
                val callLine = emitIndirectCall(
                  resultReg.some,
                  fnReturnType.some,
                  fnPtr,
                  allArgs
                )
                app.typeSpec.flatMap(
                  getNominalTypeName(_).toOption
                ) match
                  case Some(typeName) =>
                    CompileResult(
                      resultReg,
                      stateAfterExtract
                        .withRegister(resultReg + 1)
                        .emit(callLine),
                      false,
                      typeName
                    ).asRight
                  case None =>
                    CodeGenError(
                      s"Could not determine MML type for indirect call result",
                      app.some
                    ).asLeft
      }
    }
  }

/** Uses ordinary reference emission so identity checks, field loads, and on-demand adapters also
  * apply when obtaining a callee value.
  */
private def resolveIndirectCallee(
  fnRef:         Ref,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, (String, CodeGenState)] =
  val calleeExpr = Expr(fnRef.source, List(fnRef), typeSpec = fnRef.typeSpec)
  compileExpr(calleeExpr, state, functionScope).map { compiled =>
    (compiled.operandStr, compiled.state)
  }
