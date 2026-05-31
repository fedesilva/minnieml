package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  DirectCallable,
  ScopeEntry,
  compileDirectLambda,
  compileLambdaLiteral,
  emitCall,
  emitExtractValue,
  emitGetElementPtr,
  emitIndirectCall,
  emitInsertValue,
  emitLoad,
  emitStore,
  emitTypeDefinition,
  evaluateDirectCaptures,
  getLlvmType,
  getNominalTypeName,
  renderFunctionLines,
  sizeOfLlvmTypeResolved
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
    arg.terms match
      case List(argLambda: Lambda) =>
        compileBoundLambdaArg(lambda, param, argLambda, state, functionScope, compileExpr)
      case _ =>
        for
          argRes <- compileExpr(arg, state, functionScope)
          entry = ScopeEntry(
            argRes.register,
            argRes.typeName,
            argRes.isLiteral,
            argRes.literalValue
          )
          extendedScope = functionScope + (param.name -> entry)
          bodyRes <- compileExpr(lambda.body, argRes.state, extendedScope)
        yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))
  else
    CodeGenError(
      "Immediate lambda application with multiple params/args not yet supported",
      lambda.some
    ).asLeft

/** Compile a scoped-binding whose bound value is itself a lambda literal.
  *
  * The bound lambda's [[Lambda.materialization]] decides the lowering shape:
  *   - `Direct` → emit `compileDirectLambda` (no env), bind `param` to a [[DirectCallable]] entry.
  *   - `NullEnv` → emit `compileNonCapturingLambda` via `compileLambdaLiteral`, bind `param` to a
  *     literal `{ ptr @fn, ptr null }` entry.
  *   - `Materialized` → emit `compileCapturingLambda` via `compileLambdaLiteral`, bind `param` to
  *     the fat-pointer register.
  */
private def compileBoundLambdaArg(
  outerLambda:   Lambda,
  param:         FnParam,
  argLambda:     Lambda,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  val uniqueName  = s"${param.name}_${state.nextAnonFnId}"
  val stateWithId = state.copy(nextAnonFnId = state.nextAnonFnId + 1)
  val fnName      = stateWithId.mangleName(uniqueName)

  argLambda.materialization match
    case Materialization.Direct =>
      val typeFnE = argLambda.typeSpec match
        case Some(tf: TypeFn) => tf.asRight
        case other =>
          CodeGenError(s"Direct lambda missing TypeFn typeSpec, got: $other", argLambda.some).asLeft
      for
        tf <- typeFnE
        returnType <- getLlvmType(tf.returnType, stateWithId)
        paramTypes <- tf.paramTypes.traverse(getLlvmType(_, stateWithId))
        argRes <- compileDirectLambda(
          argLambda,
          stateWithId,
          fnName,
          returnType,
          paramTypes.toList,
          functionScope,
          param.some
        )
        evaluated <- evaluateDirectCaptures(argLambda, argRes.state, functionScope)
        (stateAfterEval, outerCaps) = evaluated
        directEntry = ScopeEntry(
          0,
          "Function",
          directCallable =
            DirectCallable(fnName, outerCaps, paramTypes.toList, returnType.some).some
        )
        extendedScope = functionScope + (param.name -> directEntry)
        bodyRes <- compileExpr(outerLambda.body, stateAfterEval, extendedScope)
      yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))

    case Materialization.NullEnv =>
      val recursiveEntry = ScopeEntry(
        0,
        "Function",
        isLiteral    = true,
        literalValue = s"{ ptr @$fnName, ptr null }".some
      )
      val argScope = functionScope + (param.name -> recursiveEntry)
      for
        argRes <- compileLambdaLiteral(
          argLambda,
          stateWithId,
          argScope,
          (stateWithId, fnName).some,
          param.some
        )
        // Non-capturing: value is a constant literal, safe to discard sub-output.
        finalArgRes =
          if argRes.isLiteral then argRes.copy(state = argRes.state.copy(output = state.output))
          else argRes
        entry = ScopeEntry(
          finalArgRes.register,
          finalArgRes.typeName,
          finalArgRes.isLiteral,
          finalArgRes.literalValue
        )
        extendedScope = functionScope + (param.name -> entry)
        bodyRes <- compileExpr(outerLambda.body, finalArgRes.state, extendedScope)
      yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(finalArgRes.exitBlock))

    case Materialization.Materialized =>
      // Recursive self-call inside the body goes through the fat-pointer register built by
      // emitRecursiveSelfClosure; no literal entry pre-injected.
      for
        argRes <- compileLambdaLiteral(
          argLambda,
          stateWithId,
          functionScope,
          (stateWithId, fnName).some,
          param.some
        )
        entry = ScopeEntry(argRes.register, argRes.typeName, argRes.isLiteral, argRes.literalValue)
        extendedScope = functionScope + (param.name -> entry)
        bodyRes <- compileExpr(outerLambda.body, argRes.state, extendedScope)
      yield bodyRes.copy(exitBlock = bodyRes.exitBlock.orElse(argRes.exitBlock))

/** Compile a direct call to a Direct lambda.
  *
  * Emits `call $ret @$entry(userArgs..., captureOps...)` with captures appended at the trailing
  * positions. No env pointer; no fat-pointer extraction.
  */
def compileDirectCall(
  fnRef:         Ref,
  direct:        DirectCallable,
  allArgs:       List[Expr],
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  compileArgs(allArgs, state, functionScope, compileExpr).flatMap { case (compiledArgs, argState) =>
    val fnReturnTypeE = app.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, argState)
      case None =>
        CodeGenError(
          s"Missing return type for direct call '${fnRef.name}'",
          app.some
        ).asLeft

    fnReturnTypeE.flatMap { fnReturnType =>
      val userArgs    = compiledArgs.map(a => (a.llvmType, a.op))
      val captureArgs = direct.captureOperands.map { case (op, ty) => (ty, op) }
      val callArgs    = userArgs ++ captureArgs

      if fnReturnType == "void" then
        val callLine = emitCall(none, none, direct.entryName, callArgs)
        CompileResult(0, argState.emit(callLine), false, "Unit").asRight
      else
        val resultReg = argState.nextRegister
        val callLine  = emitCall(resultReg.some, fnReturnType.some, direct.entryName, callArgs)
        app.typeSpec.flatMap(getNominalTypeName(_).toOption) match
          case Some(typeName) =>
            CompileResult(
              resultReg,
              argState.withRegister(resultReg + 1).emit(callLine),
              false,
              typeName
            ).asRight
          case None =>
            CodeGenError(
              s"Could not determine MML type for direct call result on '${fnRef.name}'",
              app.some
            ).asLeft
    }
  }

/** Builds a first-class closure for an undersaturated Direct callable.
  *
  * The original callable keeps its plain Direct ABI. The generated partial-entry closure stores
  * already-applied operands and trailing Direct captures in a small env, then forwards remaining
  * user arguments to the Direct entry.
  */
def compileDirectPartialApplication(
  fnRef:         Ref,
  direct:        DirectCallable,
  allArgs:       List[Expr],
  resultFnType:  TypeFn,
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =
  compileArgs(allArgs, state, functionScope, compileExpr).flatMap { case (compiledArgs, argState) =>
    direct.returnType match
      case None =>
        CodeGenError(
          s"Direct callable '${fnRef.name}' is missing call signature for partial application",
          app.some
        ).asLeft
      case Some(finalReturnType) =>
        for
          remainingTypes <- resultFnType.paramTypes
            .traverse(getLlvmType(_, argState))
            .map(_.filterNot(_ == "void"))
          partialReturnType <- getLlvmType(resultFnType.returnType, argState)
          result <- emitDirectPartialClosure(
            fnRef,
            direct,
            compiledArgs,
            remainingTypes,
            partialReturnType,
            finalReturnType,
            app,
            argState
          )
        yield result
  }

private def emitDirectPartialClosure(
  fnRef:             Ref,
  direct:            DirectCallable,
  compiledArgs:      List[CompiledArg],
  remainingTypes:    List[String],
  partialReturnType: String,
  finalReturnType:   String,
  app:               App,
  state:             CodeGenState
): Either[CodeGenError, CompileResult] =
  val appliedFields = compiledArgs.map(arg => (arg.llvmType, arg.op))
  val captureFields = direct.captureOperands.map { case (op, llvmType) => (llvmType, op) }
  val envFields     = appliedFields ++ captureFields

  if finalReturnType != partialReturnType then
    CodeGenError(
      s"Direct partial application return mismatch: $finalReturnType vs $partialReturnType",
      app.some
    ).asLeft
  else if envFields.isEmpty then
    CodeGenError(
      s"Direct partial application for '${fnRef.name}' has no env fields",
      app.some
    ).asLeft
  else
    val papId       = state.nextAnonFnId
    val stateWithId = state.copy(nextAnonFnId = papId + 1)
    val entryName   = stateWithId.mangleName(s"${fnRef.name}_pap_$papId")
    val envTypeName = s"struct.${stateWithId.mangleName(s"${fnRef.name}_pap_env_$papId")}"
    val envTypeRef  = s"%$envTypeName"
    val dtorName    = stateWithId.mangleName(s"__free_${fnRef.name}_pap_env_$papId")
    val envTypeDef  = emitTypeDefinition(envTypeName, "ptr" :: envFields.map(_._1))
    val stateWithEnvDef = stateWithId
      .withNativeType(envTypeName, envTypeDef)
      .withFunctionDeclaration("malloc", "ptr", List("i64"))
      .withFunctionDeclaration("mml_free_raw", "void", List("ptr"))

    val envSize    = sizeOfLlvmTypeResolved(envTypeRef, stateWithEnvDef)
    val mallocReg  = stateWithEnvDef.nextRegister
    val mallocLine = emitCall(mallocReg.some, "ptr".some, "malloc", List(("i64", envSize.toString)))
    val stateAfterAlloc =
      stateWithEnvDef.withRegister(mallocReg + 1).emit(mallocLine)

    val dtorGepReg = stateAfterAlloc.nextRegister
    val dtorGepLine = emitGetElementPtr(
      dtorGepReg,
      envTypeRef,
      "ptr",
      s"%$mallocReg",
      List(("i32", "0"), ("i32", "0"))
    )
    val dtorStoreLine = emitStore(s"@$dtorName", "ptr", s"%$dtorGepReg")
    val stateAfterDtorStore =
      stateAfterAlloc.withRegister(dtorGepReg + 1).emit(dtorGepLine).emit(dtorStoreLine)

    val stateAfterStores = envFields.zipWithIndex.foldLeft(stateAfterDtorStore) {
      case (st, ((llvmType, op), idx)) =>
        val gepReg = st.nextRegister
        val gepLine = emitGetElementPtr(
          gepReg,
          envTypeRef,
          "ptr",
          s"%$mallocReg",
          List(("i32", "0"), ("i32", (idx + 1).toString))
        )
        val storeLine = emitStore(op, llvmType, s"%$gepReg")
        st.withRegister(gepReg + 1).emit(gepLine).emit(storeLine)
    }

    val fp0Reg = stateAfterStores.nextRegister
    val fp1Reg = fp0Reg + 1
    val insertFn =
      emitInsertValue(fp0Reg, "{ ptr, ptr }", "undef", "ptr", s"@$entryName", 0)
    val insertEnv =
      emitInsertValue(fp1Reg, "{ ptr, ptr }", s"%$fp0Reg", "ptr", s"%$mallocReg", 1)
    val siteState = stateAfterStores.withRegister(fp1Reg + 1).emit(insertFn).emit(insertEnv)
    val entryBody = renderDirectPartialEntry(
      direct,
      entryName,
      envTypeRef,
      appliedFields.map(_._1),
      captureFields.map(_._1),
      remainingTypes,
      finalReturnType
    )
    val dtorBody   = renderDirectPartialEnvFree(dtorName)
    val finalState = siteState.addDeferredDefinition(entryBody).addDeferredDefinition(dtorBody)

    CompileResult(fp1Reg, finalState, false, "Function").asRight

private def renderDirectPartialEntry(
  direct:          DirectCallable,
  entryName:       String,
  envTypeRef:      String,
  appliedTypes:    List[String],
  captureTypes:    List[String],
  remainingTypes:  List[String],
  finalReturnType: String
): String =
  val remainingDecls = remainingTypes.zipWithIndex.map { case (llvmType, idx) =>
    s"$llvmType %$idx"
  }
  val envParamIdx = remainingTypes.size
  val allParamDecls =
    (remainingDecls :+ s"ptr %$envParamIdx").mkString(", ")

  val envFieldTypes  = appliedTypes ++ captureTypes
  val fieldLoadStart = envParamIdx + 1
  val (loadLines, loadedOps) =
    envFieldTypes.zipWithIndex.foldLeft((List.empty[String], List.empty[String])) {
      case ((lines, ops), (llvmType, idx)) =>
        val gepReg  = fieldLoadStart + idx * 2
        val loadReg = gepReg + 1
        val gepLine = emitGetElementPtr(
          gepReg,
          envTypeRef,
          "ptr",
          s"%$envParamIdx",
          List(("i32", "0"), ("i32", (idx + 1).toString))
        )
        val loadLine = emitLoad(loadReg, llvmType, s"%$gepReg")
        (lines :+ gepLine :+ loadLine, ops :+ s"%$loadReg")
    }

  val (loadedApplied, loadedCaptures) = loadedOps.splitAt(appliedTypes.size)
  val remainingArgs = remainingTypes.zipWithIndex.map { case (llvmType, idx) =>
    (llvmType, s"%$idx")
  }
  val appliedArgs = appliedTypes.zip(loadedApplied).map { case (llvmType, op) =>
    (llvmType, op)
  }
  val captureArgs = captureTypes.zip(loadedCaptures).map { case (llvmType, op) =>
    (llvmType, op)
  }
  val callArgs = appliedArgs ++ remainingArgs ++ captureArgs

  val callStartReg = fieldLoadStart + envFieldTypes.size * 2
  val bodyLines =
    if finalReturnType == "void" then
      loadLines :+ emitCall(none, none, direct.entryName, callArgs) :+ "  ret void"
    else
      loadLines :+
        emitCall(callStartReg.some, finalReturnType.some, direct.entryName, callArgs) :+
        s"  ret $finalReturnType %$callStartReg"

  val functionLines = bodyLines :+ "}" :+ ""
  renderFunctionLines(
    s"define internal $finalReturnType @$entryName($allParamDecls) #0 {",
    CodeGenState(output = functionLines.reverse)
  ).mkString("\n")

private def renderDirectPartialEnvFree(dtorName: String): String =
  List(
    s"define internal void @$dtorName(ptr %0) #0 {",
    "entry:",
    "  call void @mml_free_raw(ptr %0)",
    "  ret void",
    "}",
    ""
  ).mkString("\n")

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
          getNominalTypeName(ts) match
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
          getNominalTypeName(ts) match
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
  app.typeSpec.flatMap(getNominalTypeName(_).toOption) match
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
        getNominalTypeName(ts) match
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
        getNominalTypeName(ts) match
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
