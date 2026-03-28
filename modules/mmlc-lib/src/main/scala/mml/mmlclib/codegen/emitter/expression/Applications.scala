package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  ScopeEntry,
  compileLambdaLiteral,
  emitCall,
  emitExtractValue,
  emitIndirectCall,
  getLlvmType,
  getMmlTypeName
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
  * `let x = E; body` desugars to `App(Lambda([x], body), E)`.
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
    // Pre-allocate name for lambda args so the binding is in scope during
    // compilation — enables recursive let bindings (same as top-level fns
    // knowing their own name).
    val (preAlloc, argScope) = arg.terms match
      case List(_: Lambda) =>
        val uniqueName  = s"${param.name}_${state.nextAnonFnId}"
        val stateWithId = state.copy(nextAnonFnId = state.nextAnonFnId + 1)
        val fnName      = stateWithId.mangleName(uniqueName)
        val recursiveScope =
          arg match
            case Expr(_, List(argLambda: Lambda), _, _) if argLambda.captures.nonEmpty =>
              functionScope
            case _ =>
              val entry = ScopeEntry(
                0,
                "Function",
                isLiteral    = true,
                literalValue = Some(s"{ ptr @$fnName, ptr null }")
              )
              functionScope + (param.name -> entry)
        (Some((stateWithId, fnName)), recursiveScope)
      case _ =>
        (None, functionScope)
    val compileState = preAlloc.map(_._1).getOrElse(state)
    for
      argRes <- arg.terms match
        case List(lambdaLit: Lambda) =>
          compileLambdaLiteral(lambdaLit, compileState, argScope, preAlloc, Some(param))
            .map { res =>
              // Non-capturing: value is a constant literal, safe to discard sub-output.
              // Capturing: call-site IR (malloc/store/insertvalue) defines the fat pointer
              // register and must be preserved.
              if res.isLiteral then res.copy(state = res.state.copy(output = state.output))
              else res
            }
        case _ => compileExpr(arg, compileState, argScope)
      // Store literal info in the scope entry — no materialization needed
      entry = ScopeEntry(argRes.register, argRes.typeName, argRes.isLiteral, argRes.literalValue)
      extendedScope = functionScope + (param.name -> entry)
      bodyRes <- compileExpr(lambda.body, argRes.state, extendedScope)
    // Preserve exit block from argument if body doesn't have one
    // (needed when arg contains a conditional like `let x = if cond then a else b end`)
    yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))
  else
    Left(
      CodeGenError(
        "Immediate lambda application with multiple params/args not yet supported",
        Some(lambda)
      )
    )

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
      Left(
        CodeGenError(
          s"Native operator called with wrong number of arguments: ${allArgs.length}",
          Some(app)
        )
      )

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
        Left(CodeGenError(s"Missing type information for binary operator operand", Some(leftArg)))

    instruction = substituteTemplate(tpl, llvmType, List(leftOp, rightOp))
    line        = s"  %$resultReg = $instruction"
    finalState  = rightRes.state.withRegister(resultReg + 1).emit(line)

    typeName <- getMmlTypeForOp(fnRef) match
      case Some(t) => Right(t)
      case None =>
        Left(
          CodeGenError(
            s"Could not determine return type for binary operator '${fnRef.name}'",
            Some(fnRef)
          )
        )
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
        Left(
          CodeGenError(s"Missing type information for unary operator operand", Some(operandArg))
        )

    instruction = substituteTemplate(tpl, llvmType, List(operandOp))
    line        = s"  %$resultReg = $instruction"
    finalState  = operandRes.state.withRegister(resultReg + 1).emit(line)

    typeName <- getMmlTypeForOp(fnRef) match
      case Some(t) => Right(t)
      case None =>
        Left(
          CodeGenError(
            s"Could not determine return type for unary operator '${fnRef.name}'",
            Some(fnRef)
          )
        )
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
  val fnReturnTypeResult = app.typeSpec match
    case Some(typeSpec) => getLlvmType(typeSpec, state)
    case None =>
      Left(
        CodeGenError(
          s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this",
          Some(app)
        )
      )

  fnReturnTypeResult.flatMap { fnReturnType =>
    val fnName = getResolvedName(fnRef, state)
    val isNative = fnRef.resolvedId.flatMap(state.resolvables.lookup).exists {
      case bnd: Bnd => isNativeBinding(bnd)
      case _ => false
    }
    val useSret = isNative && state.abi.needsSret(fnReturnType, state)

    if fnReturnType == "void" then
      val callLine = emitCall(None, None, fnName, List.empty)
      Right(CompileResult(0, state.emit(callLine), false, "Unit"))
    else if useSret then
      // Sret call for nullary function returning large struct
      val (loadReg, finalState) =
        state.abi.emitSretCall(
          fnName,
          fnReturnType,
          List.empty,
          state,
          emitCall,
          None,
          None
        )
      app.typeSpec match
        case Some(ts) =>
          getMmlTypeName(ts) match
            case Some(typeName) =>
              Right(CompileResult(loadReg, finalState, false, typeName))
            case None =>
              Left(
                CodeGenError(
                  s"Could not determine MML type name for function application result from spec: $ts",
                  Some(app)
                )
              )
        case None =>
          Left(
            CodeGenError(
              s"Missing return type information for function application '${fnRef.name}'",
              Some(app)
            )
          )
    else
      val resultReg = state.nextRegister
      val callLine  = emitCall(Some(resultReg), Some(fnReturnType), fnName, List.empty)
      app.typeSpec match
        case Some(ts) =>
          getMmlTypeName(ts) match
            case Some(typeName) =>
              Right(
                CompileResult(
                  resultReg,
                  state.withRegister(resultReg + 1).emit(callLine),
                  false,
                  typeName
                )
              )
            case None =>
              Left(
                CodeGenError(
                  s"Could not determine MML type name for function application result from spec: $ts",
                  Some(app)
                )
              )
        case None =>
          Left(
            CodeGenError(
              s"Missing return type information for function application '${fnRef.name}'",
              Some(app)
            )
          )
  }

/** Compiles a regular function call with arguments. */
def compileRegularCall(
  fnRef:         Ref,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  // Compile all arguments
  compileArgs(allArgs, state, functionScope, compileExpr).flatMap {
    case (compiledArgs, finalState) =>
      // Get function return type from the application's typeSpec
      val fnReturnTypeResult = app.typeSpec match
        case Some(typeSpec) => getLlvmType(typeSpec, finalState)
        case None =>
          Left(
            CodeGenError(
              s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this",
              Some(app)
            )
          )

      fnReturnTypeResult.flatMap { fnReturnType =>
        // __free_closure: emit inline dtor dispatch (extract env, load dtor, call it)
        if fnRef.name == "__free_closure" then
          emitClosureFreeViaEnvDtor(compiledArgs, app, finalState)
        else
          // For per-env free functions, extract env ptr from fat pointer arg
          val isClosureEnvFree = fnRef.name.startsWith("__free___closure_env_")
          val (adjustedArgs, stateAfterExtract) =
            if isClosureEnvFree then extractEnvPtrFromArgs(compiledArgs, finalState)
            else (compiledArgs, finalState)

          // Check for function template (for LLVM intrinsics like llvm.sqrt)
          getFunctionTemplate(
            fnRef.resolvedId.flatMap(stateAfterExtract.resolvables.lookup)
          ) match
            case Some(tpl) =>
              compileFunctionWithTemplate(fnRef, tpl, adjustedArgs, app, stateAfterExtract)
            case None =>
              compileStandardCall(fnRef, adjustedArgs, fnReturnType, app, stateAfterExtract)
      }
  }

private case class CompiledArg(op: String, llvmType: String, typeSpec: Option[Type])

/** Free a closure via the destructor pointer embedded in its env struct.
  *
  * The arg is a fat pointer `{ ptr fn, ptr env }`. The env struct's field 0 holds a pointer to the
  * destructor function. We extract the env ptr, null-check it (non-capturing functions have null
  * env), and if non-null load the dtor from field 0 and call it with the env ptr.
  */
private def emitClosureFreeViaEnvDtor(
  compiledArgs: List[CompiledArg],
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  compiledArgs match
    case List(arg) if arg.llvmType == "{ ptr, ptr }" =>
      val envReg  = state.nextRegister
      val cmpReg  = envReg + 1
      val dtorReg = cmpReg + 1

      val freeLabel = s"closure_free_${envReg}_dtor"
      val endLabel  = s"closure_free_${envReg}_end"

      val extractEnv =
        emitExtractValue(envReg, "{ ptr, ptr }", arg.op, 1)
      val nullCheck =
        s"  %$cmpReg = icmp eq ptr %$envReg, null"
      val branch =
        s"  br i1 %$cmpReg, label %$endLabel, label %$freeLabel"
      val freeLbl  = s"$freeLabel:"
      val loadDtor = s"  %$dtorReg = load ptr, ptr %$envReg"
      val callDtor = s"  call void %$dtorReg(ptr %$envReg)"
      val brEnd    = s"  br label %$endLabel"
      val endLbl   = s"$endLabel:"

      val finalState = state
        .withRegister(dtorReg + 1)
        .emit(extractEnv)
        .emit(nullCheck)
        .emit(branch)
        .emit(freeLbl)
        .emit(loadDtor)
        .emit(callDtor)
        .emit(brEnd)
        .emit(endLbl)

      Right(
        CompileResult(0, finalState, false, "Unit", exitBlock = Some(endLabel))
      )
    case _ =>
      Left(
        CodeGenError(
          "__free_closure expects a single { ptr, ptr } argument",
          Some(app)
        )
      )

/** For closure env free functions, extract the env pointer (index 1) from the fat pointer arg. */
private def extractEnvPtrFromArgs(
  compiledArgs: List[CompiledArg],
  state:        CodeGenState
): (List[CompiledArg], CodeGenState) =
  compiledArgs match
    case List(arg) if arg.llvmType == "{ ptr, ptr }" =>
      val envReg      = state.nextRegister
      val extractLine = emitExtractValue(envReg, "{ ptr, ptr }", arg.op, 1)
      val newArg      = CompiledArg(s"%$envReg", "ptr", None)
      (List(newArg), state.withRegister(envReg + 1).emit(extractLine))
    case other =>
      (other, state)

/** Compiles all arguments to a function call. */
private def compileArgs(
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
                if llvmType == "void" then Right((compiledArgs, argRes.state))
                else
                  Right(
                    (
                      compiledArgs :+ CompiledArg(argOp, llvmType, arg.typeSpec),
                      argRes.state
                    )
                  )
              case Left(err) => Left(err)
          case None =>
            Left(
              CodeGenError(
                s"Missing type information for function argument - TypeChecker should have provided this",
                Some(arg)
              )
            )
      }
    case (Left(err), _) => Left(err)
  }

/** Compiles a function with inline template (LLVM intrinsics like llvm.sqrt). */
private def compileFunctionWithTemplate(
  fnRef:        Ref,
  tpl:          String,
  compiledArgs: List[CompiledArg],
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val resultReg = state.nextRegister
  val instruction = compiledArgs match
    case List(CompiledArg(argOp, argType, _)) =>
      // Single arg: use %operand (like unary operators)
      tpl.replace("%type", argType).replace("%operand", argOp)
    case args =>
      // Multiple args: use %operand1, %operand2, ... (like binary operators)
      args.zipWithIndex
        .foldLeft(tpl) { case (t, (CompiledArg(argOp, argType, _), i)) =>
          t.replace(s"%operand${i + 1}", argOp).replace(s"%type${i + 1}", argType)
        }
        .replace("%type", args.headOption.map(_.llvmType).getOrElse(""))

  val line = s"  %$resultReg = $instruction"
  app.typeSpec.flatMap(getMmlTypeName) match
    case Some(typeName) =>
      Right(CompileResult(resultReg, state.withRegister(resultReg + 1).emit(line), false, typeName))
    case None =>
      Left(
        CodeGenError(s"Could not determine return type for function '${fnRef.name}'", Some(app))
      )

/** Compiles a standard function call (non-template). */
private def compileStandardCall(
  fnRef:        Ref,
  compiledArgs: List[CompiledArg],
  fnReturnType: String,
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, CompileResult] =
  val isNative = fnRef.resolvedId.flatMap(state.resolvables.lookup).exists {
    case bnd: Bnd => isNativeBinding(bnd)
    case _ => false
  }
  val rawArgs = compiledArgs.map(arg => (arg.op, arg.llvmType))
  val (finalArgs, stateAfterSplit) =
    if isNative then state.abi.lowerArgs(rawArgs, state)
    else (rawArgs, state)
  val (stateWithAlias, aliasScopeTag, noaliasTag) =
    buildCallAliasMetadata(getResolvedName(fnRef, stateAfterSplit), compiledArgs, stateAfterSplit)
  val fnName = getResolvedName(fnRef, stateWithAlias)
  val args   = finalArgs.map { case (value, typ) => (typ, value) }

  // Check if this native function needs sret (large struct return on x86_64)
  val useSret = isNative && stateWithAlias.abi.needsSret(fnReturnType, stateWithAlias)

  if fnReturnType == "void" then
    val callLine = emitCall(None, None, fnName, args, aliasScopeTag, noaliasTag)
    Right(CompileResult(0, stateWithAlias.emit(callLine), false, "Unit"))
  else if useSret then
    val (loadReg, finalState) =
      stateWithAlias.abi.emitSretCall(
        fnName,
        fnReturnType,
        args,
        stateWithAlias,
        emitCall,
        aliasScopeTag,
        noaliasTag
      )
    app.typeSpec match
      case Some(ts) =>
        getMmlTypeName(ts) match
          case Some(typeName) =>
            Right(CompileResult(loadReg, finalState, false, typeName))
          case None =>
            Left(CodeGenError(s"Could not determine MML type name for result: $ts", Some(app)))
      case None =>
        Left(CodeGenError(s"Missing return type for function '${fnRef.name}'", Some(app)))
  else
    val resultReg = stateWithAlias.nextRegister
    val callLine =
      emitCall(Some(resultReg), Some(fnReturnType), fnName, args, aliasScopeTag, noaliasTag)
    app.typeSpec match
      case Some(ts) =>
        getMmlTypeName(ts) match
          case Some(typeName) =>
            Right(
              CompileResult(
                resultReg,
                stateWithAlias.withRegister(resultReg + 1).emit(callLine),
                false,
                typeName
              )
            )
          case None =>
            Left(CodeGenError(s"Could not determine MML type name for result: $ts", Some(app)))
      case None =>
        Left(CodeGenError(s"Missing return type for function '${fnRef.name}'", Some(app)))

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
  if labels.isEmpty || !state.emitAliasScopes then (state, None, None)
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
      if sortedNoalias.isEmpty then None else Some(s"!{${sortedNoalias.mkString(", ")}}")
    (stateWithScopes, Some(aliasScopeTag), noaliasTagOpt)

/** Compiles an indirect call through a function pointer (e.g. calling a lambda parameter). */
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
        Left(
          CodeGenError(
            s"Missing return type for indirect call '${fnRef.name}'",
            Some(app)
          )
        )

    fnReturnTypeResult.flatMap { fnReturnType =>
      // Look up the fat pointer { fn_ptr, env_ptr } from scope
      val closureOp = functionScope.get(fnRef.name) match
        case Some(entry) => Right(entry.operandStr)
        case None =>
          Left(
            CodeGenError(
              s"Function pointer '${fnRef.name}' not found in scope",
              Some(fnRef)
            )
          )

      closureOp.flatMap { closure =>
        // Extract fn pointer and env from the fat pointer
        val fnReg  = argState.nextRegister
        val envReg = fnReg + 1
        val extractFn =
          emitExtractValue(fnReg, "{ ptr, ptr }", closure, 0)
        val extractEnv =
          emitExtractValue(envReg, "{ ptr, ptr }", closure, 1)
        val stateAfterExtract = argState
          .withRegister(envReg + 1)
          .emit(extractFn)
          .emit(extractEnv)

        // Build args with env as the last parameter
        val userArgs = compiledArgs.map(arg => (arg.llvmType, arg.op))
        val allArgs  = userArgs :+ ("ptr", s"%$envReg")
        val fnPtr    = s"%$fnReg"

        if fnReturnType == "void" then
          val callLine = emitIndirectCall(None, None, fnPtr, allArgs)
          Right(
            CompileResult(0, stateAfterExtract.emit(callLine), false, "Unit")
          )
        else
          val resultReg = stateAfterExtract.nextRegister
          val callLine = emitIndirectCall(
            Some(resultReg),
            Some(fnReturnType),
            fnPtr,
            allArgs
          )
          app.typeSpec.flatMap(getMmlTypeName) match
            case Some(typeName) =>
              Right(
                CompileResult(
                  resultReg,
                  stateAfterExtract
                    .withRegister(resultReg + 1)
                    .emit(callLine),
                  false,
                  typeName
                )
              )
            case None =>
              Left(
                CodeGenError(
                  s"Could not determine MML type for indirect call result",
                  Some(app)
                )
              )
      }
    }
  }
