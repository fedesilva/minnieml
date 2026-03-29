package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  ScopeEntry,
  TypeNameResolver,
  compileLambdaLiteral,
  emitCall,
  emitExtractValue,
  emitIndirectCall,
  getLlvmType
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
                literalValue = s"{ ptr @$fnName, ptr null }".some
              )
              functionScope + (param.name -> entry)
        ((stateWithId, fnName).some, recursiveScope)
      case _ =>
        (none, functionScope)
    val compileState = preAlloc.map(_._1).getOrElse(state)
    for
      argRes <- arg.terms match
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
      // Store literal info in the scope entry — no materialization needed
      entry = ScopeEntry(argRes.register, argRes.typeName, argRes.isLiteral, argRes.literalValue)
      extendedScope = functionScope + (param.name -> entry)
      bodyRes <- compileExpr(lambda.body, argRes.state, extendedScope)
    // Preserve exit block from argument if body doesn't have one
    // (needed when arg contains a conditional like `let x = if cond then a else b end`)
    yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))
  else
    CodeGenError(
      "Immediate lambda application with multiple params/args not yet supported",
      lambda.some
    ).asLeft

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

    typeName <- getMmlTypeForOp(fnRef, finalState.resolvables) match
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

    typeName <- getMmlTypeForOp(fnRef, finalState.resolvables) match
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
  val fnReturnTypeResult = app.typeSpec match
    case Some(typeSpec) => getLlvmType(typeSpec, state)
    case None =>
      CodeGenError(
        s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this",
        app.some
      ).asLeft

  fnReturnTypeResult.flatMap { fnReturnType =>
    val fnName = getResolvedName(fnRef, state)
    val isNative = fnRef.resolvedId.flatMap(state.resolvables.lookup).exists {
      case bnd: Bnd => isNativeBinding(bnd)
      case _ => false
    }
    val useSret = isNative && state.abi.needsSret(fnReturnType, state)

    if fnReturnType == "void" then
      val callLine = emitCall(none, none, fnName, List.empty)
      CompileResult(0, state.emit(callLine), false, "Unit").asRight
    else if useSret then
      // Sret call for nullary function returning large struct
      val (loadReg, finalState) =
        state.abi.emitSretCall(
          fnName,
          fnReturnType,
          List.empty,
          state,
          emitCall,
          none,
          none
        )
      app.typeSpec match
        case Some(ts) =>
          TypeNameResolver.getMmlTypeName(ts, finalState.resolvables) match
            case Right(typeName) =>
              CompileResult(loadReg, finalState, false, typeName).asRight
            case Left(_) =>
              CodeGenError(
                s"Could not determine MML type name for function application result from spec: $ts",
                app.some
              ).asLeft
        case None =>
          CodeGenError(
            s"Missing return type information for function application '${fnRef.name}'",
            app.some
          ).asLeft
    else
      val resultReg = state.nextRegister
      val callLine  = emitCall(resultReg.some, fnReturnType.some, fnName, List.empty)
      app.typeSpec match
        case Some(ts) =>
          TypeNameResolver.getMmlTypeName(ts, state.resolvables) match
            case Right(typeName) =>
              CompileResult(
                resultReg,
                state.withRegister(resultReg + 1).emit(callLine),
                false,
                typeName
              ).asRight
            case Left(_) =>
              CodeGenError(
                s"Could not determine MML type name for function application result from spec: $ts",
                app.some
              ).asLeft
        case None =>
          CodeGenError(
            s"Missing return type information for function application '${fnRef.name}'",
            app.some
          ).asLeft
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
          CodeGenError(
            s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this",
            app.some
          ).asLeft

      fnReturnTypeResult.flatMap { fnReturnType =>
        val adjustedArgsAndState =
          getClosureDestructorKind(fnRef, finalState) match
            case Some(_) =>
              extractClosureEnvArg(compiledArgs, app, finalState)
            case None =>
              (compiledArgs, finalState).asRight

        adjustedArgsAndState.flatMap { case (adjustedArgs, stateAfterExtract) =>
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
  }

private case class CompiledArg(op: String, llvmType: String, typeSpec: Option[Type])

private def getClosureDestructorKind(
  fnRef: Ref,
  state: CodeGenState
): Option[DestructorKind] =
  fnRef.resolvedId
    .flatMap(state.resolvables.lookup)
    .collect { case bnd: Bnd => bnd.meta.flatMap(_.destructorKind) }
    .flatten

/** Closure destructors consume the raw env pointer, so adapt the fat pointer arg first. */
private def extractClosureEnvArg(
  compiledArgs: List[CompiledArg],
  app:          App,
  state:        CodeGenState
): Either[CodeGenError, (List[CompiledArg], CodeGenState)] =
  compiledArgs match
    case List(arg) if arg.llvmType == "{ ptr, ptr }" =>
      val envReg      = state.nextRegister
      val extractLine = emitExtractValue(envReg, "{ ptr, ptr }", arg.op, 1)
      val newArg      = CompiledArg(s"%$envReg", "ptr", none)
      (List(newArg), state.withRegister(envReg + 1).emit(extractLine)).asRight
    case _ =>
      CodeGenError(
        "Closure destructor expects a single { ptr, ptr } argument",
        app.some
      ).asLeft

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
  app.typeSpec.flatMap(TypeNameResolver.getMmlTypeName(_, state.resolvables).toOption) match
    case Some(typeName) =>
      CompileResult(
        resultReg,
        state.withRegister(resultReg + 1).emit(line),
        false,
        typeName
      ).asRight
    case None =>
      CodeGenError(s"Could not determine return type for function '${fnRef.name}'", app.some).asLeft

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
    val callLine = emitCall(none, none, fnName, args, aliasScopeTag, noaliasTag)
    CompileResult(0, stateWithAlias.emit(callLine), false, "Unit").asRight
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
        TypeNameResolver.getMmlTypeName(ts, finalState.resolvables) match
          case Right(typeName) =>
            CompileResult(loadReg, finalState, false, typeName).asRight
          case Left(_) =>
            CodeGenError(s"Could not determine MML type name for result: $ts", app.some).asLeft
      case None =>
        CodeGenError(s"Missing return type for function '${fnRef.name}'", app.some).asLeft
  else
    val resultReg = stateWithAlias.nextRegister
    val callLine =
      emitCall(resultReg.some, fnReturnType.some, fnName, args, aliasScopeTag, noaliasTag)
    app.typeSpec match
      case Some(ts) =>
        TypeNameResolver.getMmlTypeName(ts, stateWithAlias.resolvables) match
          case Right(typeName) =>
            CompileResult(
              resultReg,
              stateWithAlias.withRegister(resultReg + 1).emit(callLine),
              false,
              typeName
            ).asRight
          case Left(_) =>
            CodeGenError(s"Could not determine MML type name for result: $ts", app.some).asLeft
      case None =>
        CodeGenError(s"Missing return type for function '${fnRef.name}'", app.some).asLeft

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
        CodeGenError(
          s"Missing return type for indirect call '${fnRef.name}'",
          app.some
        ).asLeft

    fnReturnTypeResult.flatMap { fnReturnType =>
      resolveIndirectCallee(fnRef, argState, functionScope, compileExpr).flatMap {
        case (closure, stateAfterCallee) =>
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
              TypeNameResolver.getMmlTypeName(_, stateAfterExtract.resolvables).toOption
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

private def resolveIndirectCallee(
  fnRef:         Ref,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, (String, CodeGenState)] =
  functionScope.get(fnRef.name) match
    case Some(entry) =>
      (entry.operandStr, state).asRight
    case None =>
      val calleeExpr = Expr(fnRef.source, List(fnRef), typeSpec = fnRef.typeSpec)
      compileExpr(calleeExpr, state, functionScope).map { compiled =>
        (compiled.operandStr, compiled.state)
      }
