package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.expression.*
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter

/** Handles code generation for expressions, terms, and operators. */

/** Compiles a term (the smallest unit in an expression).
  *
  * Terms include literals, references, grouped expressions, or nested expressions.
  *
  * @param term
  *   the term to compile
  * @param state
  *   the current code generation state
  * @param functionScope
  *   optional map of local function parameters to their registers
  * @return
  *   Either a CodeGenError or a CompileResult for the term.
  */
def compileTerm(
  term:          Term,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry] = Map.empty
): Either[CodeGenError, CompileResult] = {
  term match {
    case lit: LiteralInt =>
      val typeName = lit.typeSpec.flatMap(getNominalTypeName(_).toOption).getOrElse("Int")
      CompileResult(lit.value, state, true, typeName).asRight

    case lit: LiteralFloat =>
      // LLVM rejects decimal float literals that aren't exactly representable in IEEE 754.
      // Emit as double-precision hex (LLVM truncates to float).
      val typeName = lit.typeSpec.flatMap(getNominalTypeName(_).toOption).getOrElse("Float")
      val hexStr   = f"0x${java.lang.Double.doubleToRawLongBits(lit.value.toDouble)}%016X"
      CompileResult(0, state, true, typeName, literalValue = hexStr.some).asRight

    case lit: LiteralUnit =>
      // Unit is a zero-sized type, just return a dummy result
      val typeName = lit.typeSpec.flatMap(getNominalTypeName(_).toOption).getOrElse("Unit")
      CompileResult(0, state, true, typeName).asRight

    case lit: LiteralBool =>
      val typeName     = lit.typeSpec.flatMap(getNominalTypeName(_).toOption).getOrElse("Bool")
      val literalValue = if lit.value then 1 else 0
      CompileResult(literalValue, state, true, typeName).asRight

    case hole: Hole =>
      compileHole(hole, state)

    case lit: LiteralString =>
      compileLiteralString(lit, state)

    case ref: Ref if ref.qualifier.isDefined =>
      compileSelectionRef(ref, state, functionScope)

    case ref: Ref => {
      // Check if reference exists in the function's local scope
      functionScope.get(ref.name) match {
        case Some(entry) =>
          // Reference to a function parameter or local binding
          CompileResult(
            entry.register,
            state,
            entry.isLiteral,
            entry.typeName,
            literalValue = entry.literalValue
          ).asRight
        case None =>
          // Global reference - get actual type from typeSpec
          ref.typeSpec match {
            case Some(typeSpec) =>
              getLlvmType(typeSpec, state) match {
                case Right(llvmType) =>
                  val reg                      = state.nextRegister
                  val globalName               = getResolvedName(ref, state)
                  val (stateWithTbaa, tbaaTag) = TbaaEmitter.getTbaaTag(typeSpec, state)
                  val (stateWithAlias, aliasTag, noaliasTag) =
                    AliasScopeEmitter.getAliasScopeTags(typeSpec, stateWithTbaa)
                  val loadLine = emitLoad(
                    reg,
                    llvmType,
                    s"@$globalName",
                    tbaaTag,
                    aliasTag,
                    noaliasTag
                  )

                  getNominalTypeName(typeSpec) match {
                    case Right(typeName) =>
                      CompileResult(
                        reg,
                        stateWithAlias.withRegister(reg + 1).emit(loadLine),
                        false,
                        typeName
                      ).asRight

                    case Left(_) =>
                      CodeGenError(
                        s"Could not determine MML type name for global reference '${ref.name}'",
                        ref.some
                      ).asLeft
                  }
                case Left(err) =>
                  err.asLeft
              }
            case None =>
              CodeGenError(
                s"Missing type information for global reference '${ref.name}' - TypeChecker should have provided this",
                ref.some
              ).asLeft
          }
      }
    }

    case TermGroup(_, expr, _) =>
      compileExpr(expr, state, functionScope)

    case e: Expr =>
      compileExpr(e, state, functionScope)

    case app: App =>
      compileApp(app, state, functionScope)

    case Cond(_, condExpr, ifTrue, ifFalse, _, _) =>
      compileCond(condExpr, ifTrue, ifFalse, state, functionScope, compileExpr)

    case impl @ NativeImpl(_, _, _, _, _, _) => {
      // Native implementation should be handled at function declaration level (compileBndLambda).
      // If reached here, it implies it's being used as an expression, which is invalid.
      CodeGenError(
        "NativeImpl encountered in expression context - this is a malformed AST or compiler bug",
        impl.some
      ).asLeft
    }

    case lambda: Lambda =>
      compileLambdaLiteral(lambda, state, functionScope)

    case other =>
      CodeGenError(s"Unsupported term: ${other.getClass.getSimpleName}", other.some).asLeft
  }
}

/** Compiles an expression-position lambda literal to a deferred LLVM function, returning its
  * address as a function pointer.
  */
private[emitter] def compileLambdaLiteral(
  lambda:           Lambda,
  state:            CodeGenState,
  functionScope:    Map[String, ScopeEntry],
  preAllocatedName: Option[(CodeGenState, String)] = None,
  bindingParam:     Option[FnParam]                = None
): Either[CodeGenError, CompileResult] =
  val typeFn = lambda.typeSpec match
    case Some(tf: TypeFn) => tf.asRight
    case other =>
      CodeGenError(s"Lambda missing TypeFn typeSpec, got: $other", lambda.some).asLeft

  typeFn.flatMap { tf =>
    val (stateWithId, fnName) = preAllocatedName.getOrElse(state.allocAnonFnName)
    for
      returnType <- getLlvmType(tf.returnType, stateWithId)
      paramTypes <- tf.paramTypes.traverse(getLlvmType(_, stateWithId))

      // Check for tail recursion in let-bound lambdas
      tailRecBody = for
        param <- bindingParam
        if lambda.meta.exists(_.isTailRecursive)
        body <- findTailRecBody(lambda, param.name, param.id)
      yield body

      result <- tailRecBody match
        case Some(body) =>
          compileTailRecLambdaLiteral(
            lambda,
            stateWithId,
            fnName,
            returnType,
            paramTypes.toList,
            body,
            functionScope
          )
        case None =>
          compileRegularLambdaLiteral(
            lambda,
            stateWithId,
            fnName,
            returnType,
            paramTypes.toList,
            functionScope,
            bindingParam
          )
    yield result
  }

/** Compiles a tail-recursive let-bound lambda as a deferred LLVM function. */
private def compileTailRecLambdaLiteral(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  returnType:    String,
  paramTypes:    List[String],
  body:          TailRecBody,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  if lambda.captures.nonEmpty then
    compileTailRecCapturingLambda(
      lambda,
      state,
      fnName,
      returnType,
      paramTypes,
      body,
      functionScope
    )
  else compileTailRecNonCapturing(lambda, state, fnName, returnType, paramTypes, body)

private def compileTailRecNonCapturing(
  lambda:     Lambda,
  state:      CodeGenState,
  fnName:     String,
  returnType: String,
  paramTypes: List[String],
  body:       TailRecBody
): Either[CodeGenError, CompileResult] =
  val subState = state.copy(
    output              = List.empty,
    entryPrologueOutput = List.empty
  )
  compileTailRecursiveLambda(
    lambda,
    subState,
    returnType,
    paramTypes,
    fnName,
    body,
    linkage = "internal "
  ).map { finalSubState =>
    val fnBody      = finalSubState.output.reverse.mkString("\n")
    val mergedState = mergeDeferredBodyState(state, finalSubState).addDeferredDefinition(fnBody)
    CompileResult(
      register     = 0,
      state        = mergedState,
      isLiteral    = true,
      typeName     = "Function",
      literalValue = s"{ ptr @$fnName, ptr null }".some
    )
  }

private def compileTailRecCapturingLambda(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  returnType:    String,
  paramTypes:    List[String],
  body:          TailRecBody,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  emitCallSiteEnv(lambda, state, fnName, functionScope).flatMap { envResult =>
    val siteState = envResult.siteState
    val subState = siteState.copy(
      output              = List.empty,
      entryPrologueOutput = List.empty
    )
    val capInfo = (envResult.envTypeRef, envResult.captureTypes)
    compileTailRecursiveLambda(
      lambda,
      subState,
      returnType,
      paramTypes,
      fnName,
      body,
      linkage     = "internal ",
      captureInfo = capInfo.some
    ).map { finalSubState =>
      val fnBody = finalSubState.output.reverse.mkString("\n")
      val mergedState =
        mergeDeferredBodyState(siteState, finalSubState).addDeferredDefinition(fnBody)
      CompileResult(
        register = envResult.fpRegister,
        state    = mergedState,
        false,
        "Function"
      )
    }
  }

/** Compiles a regular (non-tail-recursive) lambda literal as a deferred LLVM function. */
private def compileRegularLambdaLiteral(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  returnType:    String,
  paramTypes:    List[String],
  functionScope: Map[String, ScopeEntry],
  bindingParam:  Option[FnParam]
): Either[CodeGenError, CompileResult] =
  val filteredParamsWithTypes = filterVoidParams(lambda.params, paramTypes)
  val userParamDecls          = formatParamDecls(filteredParamsWithTypes, state.resolvables)
  val envParamIdx             = filteredParamsWithTypes.size
  val allParamDecls =
    if userParamDecls.isEmpty then s"ptr %$envParamIdx"
    else s"$userParamDecls, ptr %$envParamIdx"

  if lambda.captures.isEmpty then
    compileNonCapturingLambda(
      lambda,
      state,
      fnName,
      returnType,
      filteredParamsWithTypes,
      allParamDecls,
      envParamIdx,
      functionScope
    )
  else
    compileCapturingLambda(
      lambda,
      state,
      fnName,
      returnType,
      filteredParamsWithTypes,
      allParamDecls,
      envParamIdx,
      functionScope,
      bindingParam
    )

/** Non-capturing lambda: deferred function ignores env, returns { ptr @fn, ptr null }. */
private def compileNonCapturingLambda(
  lambda:                  Lambda,
  state:                   CodeGenState,
  fnName:                  String,
  returnType:              String,
  filteredParamsWithTypes: List[(FnParam, String)],
  allParamDecls:           String,
  envParamIdx:             Int,
  functionScope:           Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  val subState = state.copy(
    output                  = List.empty,
    entryPrologueOutput     = List.empty,
    nextRegister            = 0,
    insideLoopifiedFunction = false
  )
  val paramScope = filteredParamsWithTypes.zipWithIndex.map { case ((param, _), idx) =>
    val mmlType = param.typeAsc
      .flatMap(getNominalTypeName(_).toOption)
      .getOrElse("Unknown")
    (param.name, ScopeEntry(idx, mmlType))

  }.toMap
  val bodyState = subState.withRegister(envParamIdx + 1)

  for
    bodyRes <- compileExpr(lambda.body, bodyState, functionScope ++ paramScope)
    retLine =
      if returnType == "void" then "  ret void"
      else s"  ret $returnType ${bodyRes.operandStr}"
    finalSubState = bodyRes.state.emit(retLine).emit("}")
    header        = s"define internal $returnType @$fnName($allParamDecls) #0 {"
    fnBody        = renderFunctionLines(header, finalSubState).mkString("\n")
    mergedState   = mergeDeferredBodyState(state, finalSubState).addDeferredDefinition(fnBody)
  yield CompileResult(
    register     = 0,
    state        = mergedState,
    isLiteral    = true,
    typeName     = "Function",
    literalValue = s"{ ptr @$fnName, ptr null }".some
  )

/** Result of call-site env setup for a capturing lambda. */
private case class EnvSetupResult(
  siteState:    CodeGenState,
  fpRegister:   Int,
  envTypeRef:   String,
  captureTypes: List[(Capture, String)]
)

private def resolveClosureEnvStruct(
  lambda: Lambda,
  state:  CodeGenState
): Either[CodeGenError, TypeStruct] =
  lambda.meta.flatMap(_.envStructName) match
    case Some(envStructName) =>
      state.resolvables.resolvableTypes.values.collectFirst {
        case ts: TypeStruct if ts.name == envStructName => ts
      } match
        case Some(envStruct) => envStruct.asRight
        case None =>
          CodeGenError(s"Missing closure env struct '$envStructName'", lambda.some).asLeft
    case None =>
      CodeGenError("Capturing lambda missing envStructName", lambda.some).asLeft

private def emitRecursiveSelfClosure(
  fnName:       String,
  envParamIdx:  Int,
  state:        CodeGenState,
  bindingParam: Option[FnParam]
): (CodeGenState, Map[String, ScopeEntry]) =
  bindingParam match
    case None => (state, Map.empty)
    case Some(param) =>
      val selfFnReg = state.nextRegister
      val selfFpReg = selfFnReg + 1
      val insertFn =
        emitInsertValue(selfFnReg, "{ ptr, ptr }", "undef", "ptr", s"@$fnName", 0)
      val insertEnv =
        emitInsertValue(selfFpReg, "{ ptr, ptr }", s"%$selfFnReg", "ptr", s"%$envParamIdx", 1)
      val nextState =
        state.withRegister(selfFpReg + 1).emit(insertFn).emit(insertEnv)
      val selfScope = Map(param.name -> ScopeEntry(selfFpReg, "Function"))
      (nextState, selfScope)

/** Resolve capture types, create env struct, emit call-site IR.
  *
  * Move lambdas: malloc env, store dtor + captures, build fat pointer. Borrow lambdas: alloca env,
  * store captures (no dtor), build fat pointer.
  */
private def emitCallSiteEnv(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, EnvSetupResult] =
  for
    envStruct <- resolveClosureEnvStruct(lambda, state)
    captureTypes <- lambda.captures.traverse { cap =>
      val ref = cap.ref
      ref.typeSpec match
        case Some(ts) => getLlvmType(ts, state).map(t => (cap, t))
        case None =>
          CodeGenError(s"Capture '${ref.name}' has no type", ref.some).asLeft
    }
  yield
    val envTypeRef = s"%struct.${envStruct.name}"
    // Field offset: move envs have __dtor at field 0, borrow envs start captures at field 0
    val fieldOffset = if lambda.isMove then 1 else 0

    // Allocate env: malloc for move, alloca for borrow
    val (siteStateAfterDtor, envPtrOp) =
      if lambda.isMove then
        val stateWithEnv = state
          .withFunctionDeclaration("malloc", "ptr", List("i64"))
          .withFunctionDeclaration("free", "void", List("ptr"))
        val envSize   = sizeOfLlvmTypeResolved(envTypeRef, stateWithEnv)
        val mallocReg = stateWithEnv.nextRegister
        val mallocLine =
          emitCall(mallocReg.some, "ptr".some, "malloc", List(("i64", envSize.toString)))
        val afterMalloc = stateWithEnv.withRegister(mallocReg + 1).emit(mallocLine)

        val dtorName   = s"__free_${envStruct.name}"
        val dtorGepReg = afterMalloc.nextRegister
        val dtorGepLine = emitGetElementPtr(
          dtorGepReg,
          envTypeRef,
          "ptr",
          s"%$mallocReg",
          List(("i32", "0"), ("i32", "0"))
        )
        val (stateWithDtorTag, dtorTag) =
          TbaaEmitter
            .getTbaaStructFieldTag(envStruct, 0, afterMalloc)
            .getOrElse((afterMalloc, ""))
        val dtorStoreLine = emitStore(
          s"@${state.mangleName(dtorName)}",
          "ptr",
          s"%$dtorGepReg",
          Option.when(dtorTag.nonEmpty)(dtorTag)
        )
        val afterDtor =
          stateWithDtorTag.withRegister(dtorGepReg + 1).emit(dtorGepLine).emit(dtorStoreLine)
        (afterDtor, s"%$mallocReg")
      else
        // Borrow: stack-allocate env, no dtor
        val allocaReg  = state.nextRegister
        val allocaLine = s"  %$allocaReg = alloca $envTypeRef"
        val afterAlloca =
          if state.insideLoopifiedFunction then
            state.withRegister(allocaReg + 1).emitEntryPrologue(allocaLine)
          else state.withRegister(allocaReg + 1).emit(allocaLine)
        (afterAlloca, s"%$allocaReg")

    val siteStateAfterCaptures =
      captureTypes.zipWithIndex.foldLeft(siteStateAfterDtor) { case (st, ((cap, llvmType), idx)) =>
        val ref = cap.ref
        val rawCapOp = functionScope.get(ref.name) match
          case Some(entry) => entry.operandStr
          case None => s"@${ref.name}"

        // CapturedLiteral: emit ABI-lowered clone call before storing into env (move only)
        val (stateBeforeStore, capOp) = cap match
          case Capture.CapturedLiteral(_, cloneFnId) =>
            val cloneFnMmlName = st.resolvables.resolvables
              .collectFirst {
                case (id, bnd: Bnd) if id == cloneFnId => bnd.name
              }
              .getOrElse(cloneFnId.split("::").last)
            val cloneFnLlvmName  = resolveMemFnLlvmName(cloneFnMmlName, st)
            val rawArgs          = List((rawCapOp, llvmType))
            val (lowered, stLow) = st.abi.lowerArgs(rawArgs, st)
            val callArgs         = lowered.map((op, typ) => (typ, op))
            val declParamTypes   = lowered.map(_._2)
            val needsSret        = stLow.abi.needsSret(llvmType, stLow)
            if needsSret then
              val (retReg, stAfterCall) = stLow.abi.emitSretCall(
                cloneFnLlvmName,
                llvmType,
                callArgs,
                stLow,
                (reg, retTy, fn, args, _, _) => emitCall(reg, retTy, fn, args),
                None,
                None
              )
              val stWithDecl = stAfterCall
                .withFunctionDeclaration(cloneFnLlvmName, "void", "ptr" :: declParamTypes)
              (stWithDecl, s"%$retReg")
            else
              val cloneReg = stLow.nextRegister
              val stWithDecl = stLow.withFunctionDeclaration(
                cloneFnLlvmName,
                llvmType,
                declParamTypes
              )
              val cloneLine =
                emitCall(cloneReg.some, llvmType.some, cloneFnLlvmName, callArgs)
              (stWithDecl.withRegister(cloneReg + 1).emit(cloneLine), s"%$cloneReg")
          case _ =>
            (st, rawCapOp)

        val gepReg = stateBeforeStore.nextRegister
        val gepLine = emitGetElementPtr(
          gepReg,
          envTypeRef,
          "ptr",
          envPtrOp,
          List(("i32", "0"), ("i32", (idx + fieldOffset).toString))
        )
        val (stateWithFieldTag, fieldTag) =
          TbaaEmitter
            .getTbaaStructFieldTag(envStruct, idx + fieldOffset, stateBeforeStore)
            .getOrElse((stateBeforeStore, ""))
        val storeLine = emitStore(
          capOp,
          llvmType,
          s"%$gepReg",
          Option.when(fieldTag.nonEmpty)(fieldTag)
        )
        stateWithFieldTag.withRegister(gepReg + 1).emit(gepLine).emit(storeLine)
      }

    val fp0Reg = siteStateAfterCaptures.nextRegister
    val fp1Reg = fp0Reg + 1
    val insertFn =
      emitInsertValue(fp0Reg, "{ ptr, ptr }", "undef", "ptr", s"@$fnName", 0)
    val insertEnv =
      emitInsertValue(fp1Reg, "{ ptr, ptr }", s"%$fp0Reg", "ptr", envPtrOp, 1)
    val siteState =
      siteStateAfterCaptures.withRegister(fp1Reg + 1).emit(insertFn).emit(insertEnv)

    EnvSetupResult(siteState, fp1Reg, envTypeRef, captureTypes)

/** Capturing lambda: allocate env at call site, load captures in deferred function body. */
private def compileCapturingLambda(
  lambda:                  Lambda,
  state:                   CodeGenState,
  fnName:                  String,
  returnType:              String,
  filteredParamsWithTypes: List[(FnParam, String)],
  allParamDecls:           String,
  envParamIdx:             Int,
  functionScope:           Map[String, ScopeEntry],
  bindingParam:            Option[FnParam]
): Either[CodeGenError, CompileResult] =
  emitCallSiteEnv(lambda, state, fnName, functionScope).flatMap { envResult =>
    val siteState  = envResult.siteState
    val envTypeRef = envResult.envTypeRef

    val subState = siteState.copy(
      output                  = List.empty,
      entryPrologueOutput     = List.empty,
      nextRegister            = 0,
      insideLoopifiedFunction = false
    )
    val paramScope = filteredParamsWithTypes.zipWithIndex.map { case ((param, _), idx) =>
      val mmlType = param.typeAsc
        .flatMap(getNominalTypeName(_).toOption)
        .getOrElse("Unknown")
      (param.name, ScopeEntry(idx, mmlType))
    }.toMap
    val initialBodyState = subState.withRegister(envParamIdx + 1)

    val captureFieldOffset = if lambda.isMove then 1 else 0
    val (bodyState, captureScope) =
      emitCaptureLoads(
        envTypeRef,
        envParamIdx,
        envResult.captureTypes,
        initialBodyState,
        captureFieldOffset
      )
    val (bodyStateWithSelf, selfScope) =
      emitRecursiveSelfClosure(fnName, envParamIdx, bodyState, bindingParam)

    val allScope = functionScope ++ paramScope ++ captureScope ++ selfScope

    for
      bodyRes <- compileExpr(lambda.body, bodyStateWithSelf, allScope)
      retLine =
        if returnType == "void" then "  ret void"
        else s"  ret $returnType ${bodyRes.operandStr}"
      finalSubState = bodyRes.state.emit(retLine).emit("}")
      header        = s"define internal $returnType @$fnName($allParamDecls) #0 {"
      fnBody        = renderFunctionLines(header, finalSubState).mkString("\n")
      mergedState = mergeDeferredBodyState(siteState, finalSubState)
        .addDeferredDefinition(fnBody)
    yield CompileResult(
      register = envResult.fpRegister,
      state    = mergedState,
      false,
      "Function"
    )
  }

/** Deferred lambda bodies compile in an isolated output/register context, but all other metadata
  * produced by that sub-run must flow back to the enclosing state.
  */
private def mergeDeferredBodyState(parent: CodeGenState, sub: CodeGenState): CodeGenState =
  sub.copy(
    output                  = parent.output,
    entryPrologueOutput     = parent.entryPrologueOutput,
    nextRegister            = parent.nextRegister,
    insideLoopifiedFunction = parent.insideLoopifiedFunction
  )

private def compileSelectionRef(
  ref:           Ref,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  ref.qualifier match
    case None =>
      CodeGenError(s"Selection ref missing qualifier for '${ref.name}'", ref.some).asLeft
    case Some(qualifier) =>
      compileTerm(qualifier, state, functionScope).flatMap { qualifierRes =>
        val baseTypeSpec = qualifier.typeSpec
        baseTypeSpec match
          case Some(baseType) =>
            resolveTypeStruct(baseType, state.resolvables) match
              case Some(structDef) =>
                val fieldIndex = structDef.fields.indexWhere(_.name == ref.name)
                if fieldIndex < 0 then
                  CodeGenError(
                    s"Unknown struct field '${ref.name}' for selection",
                    ref.some
                  ).asLeft
                else
                  val structTypeE = getLlvmType(baseType, qualifierRes.state)
                  val fieldType   = structDef.fields(fieldIndex).typeSpec
                  structTypeE.flatMap { structLlvmType =>
                    val baseValue = qualifierRes.operandStr
                    val fieldReg  = qualifierRes.state.nextRegister
                    val line =
                      emitExtractValue(fieldReg, structLlvmType, baseValue, fieldIndex)
                    getNominalTypeName(fieldType) match
                      case Right(typeName) =>
                        CompileResult(
                          fieldReg,
                          qualifierRes.state.withRegister(fieldReg + 1).emit(line),
                          false,
                          typeName,
                          qualifierRes.exitBlock
                        ).asRight
                      case Left(_) =>
                        CodeGenError(
                          s"Could not determine type name for selected field '${ref.name}'",
                          ref.some
                        ).asLeft
                  }
              case None =>
                CodeGenError(
                  s"Selection base is not a struct for field '${ref.name}'",
                  ref.some
                ).asLeft
          case None =>
            CodeGenError(
              s"Missing type information for selection base '${ref.name}'",
              ref.some
            ).asLeft
      }

/** Compiles an expression.
  *
  * Dispatches based on the structure of the expression:
  *   - A single-term expression is compiled directly.
  *   - A binary operation (with exactly three terms: left, operator, right) is handled via
  *     compileBinaryOp.
  *   - A unary operation (with two terms: operator and argument) is handled via compileUnaryOp.
  *
  * @param expr
  *   the expression to compile
  * @param state
  *   the current code generation state
  * @param functionScope
  *   optional map of local function parameters to their registers
  * @return
  *   Either a CodeGenError or a CompileResult for the expression.
  */
def compileExpr(
  expr:          Expr,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry] = Map.empty
): Either[CodeGenError, CompileResult] = {
  expr.terms match {
    case List(term) =>
      compileTerm(term, state, functionScope)
    case List(left, op: Ref, right) if op.resolvedId.flatMap(state.resolvables.lookup).exists {
          case bnd: Bnd => bnd.meta.exists(_.arity == CallableArity.Binary)
          case _ => false
        } =>
      compileBinaryOp(op, left, right, state, functionScope)
    case List(op: Ref, arg) if op.resolvedId.flatMap(state.resolvables.lookup).exists {
          case bnd: Bnd => bnd.meta.exists(_.arity == CallableArity.Unary)
          case _ => false
        } =>
      compileUnaryOp(op, arg, state, functionScope)
    case _ =>
      CodeGenError(s"Invalid expression structure", expr.some).asLeft
  }
}

/** Compiles a binary operation by evaluating both sides and then applying the operation.
  *
  * @param opRef
  *   the operator reference containing AST resolution information
  * @param left
  *   the left operand term
  * @param right
  *   the right operand term
  * @param state
  *   the current code generation state
  * @param functionScope
  *   optional map of local function parameters to their registers
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
def compileBinaryOp(
  opRef:         Ref,
  left:          Term,
  right:         Term,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry] = Map.empty
): Either[CodeGenError, CompileResult] =
  for
    leftCompileResult <- compileTerm(left, state, functionScope)
    rightCompileResult <- compileTerm(right, leftCompileResult.state, functionScope)
    result <- applyBinaryOp(opRef, left, leftCompileResult, rightCompileResult)
  yield result

/** Compiles a unary operation by evaluating the argument and then applying the operation.
  *
  * @param opRef
  *   the operator reference containing AST resolution information
  * @param arg
  *   the operand term
  * @param state
  *   the current code generation state
  * @param functionScope
  *   optional map of local function parameters to their registers
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
def compileUnaryOp(
  opRef:         Ref,
  arg:           Term,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry] = Map.empty
): Either[CodeGenError, CompileResult] =
  for
    argCompileResult <- compileTerm(arg, state, functionScope)
    result <- applyUnaryOp(opRef, arg, argCompileResult)
  yield result

/** Compiles a function application.
  *
  * Handles function calls in MML, including nested applications for curried functions. For example,
  * `mult 2 2` is represented as App(App(Ref(mult), Expr(2)), Expr(2)).
  *
  * @param app
  *   the function application to compile
  * @param state
  *   the current code generation state
  * @param functionScope
  *   optional map of local function parameters to their registers
  * @return
  *   Either a CodeGenError or a CompileResult for the function application.
  */
def compileApp(
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry] = Map.empty
): Either[CodeGenError, CompileResult] =
  val (fnOrLambda, allArgs) = collectArgsAndFunction(app)

  fnOrLambda match
    case lambda: Lambda =>
      compileLambdaApp(lambda, allArgs, state, functionScope, compileExpr)

    case ref: Ref =>
      val hasFunctionType =
        ref.typeSpec.exists(t => resolveToTypeFn(t, state.resolvables).isDefined)
      // Direct call only applies to refs that resolve to emitted callable symbols.
      // First-class function values, including globals stored as { fn_ptr, env_ptr }, must use
      // the shared indirect-call path.
      val isIndirect = hasFunctionType &&
        (functionScope.contains(ref.name) || !isDirectCallableRef(ref, state))
      if isIndirect then compileIndirectCall(ref, allArgs, app, state, functionScope, compileExpr)
      else
        getNativeOpTemplate(ref.resolvedId.flatMap(state.resolvables.lookup)) match
          case Some(tpl) =>
            compileNativeOp(ref, tpl, allArgs, app, state, functionScope, compileExpr)
          case None if isNullaryWithUnitArgs(ref, allArgs, state.resolvables) =>
            compileNullaryCall(ref, app, state)
          case None =>
            compileRegularCall(ref, allArgs, app, state, functionScope, compileExpr)
