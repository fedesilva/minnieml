package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.expression.*
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter.getTbaaTag

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
      CompileResult(lit.value, state, true, "Int").asRight

    case lit: LiteralFloat =>
      // LLVM rejects decimal float literals that aren't exactly representable in IEEE 754.
      // Emit as double-precision hex (LLVM truncates to float).
      val hexStr = f"0x${java.lang.Double.doubleToRawLongBits(lit.value.toDouble)}%016X"
      CompileResult(0, state, true, "Float", literalValue = Some(hexStr)).asRight

    case _: LiteralUnit =>
      // Unit is a zero-sized type, just return a dummy result
      CompileResult(0, state, true, "Unit").asRight

    case lit: LiteralBool =>
      val literalValue = if lit.value then 1 else 0
      CompileResult(literalValue, state, true, "Bool").asRight

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
                  val (stateWithTbaa, tbaaTag) = getTbaaTag(typeSpec, state)
                  val (stateWithAlias, aliasTag, noaliasTag) =
                    AliasScopeEmitter.getAliasScopeTags(typeSpec, stateWithTbaa)
                  val line = emitLoad(
                    reg,
                    llvmType,
                    s"@$globalName",
                    tbaaTag,
                    aliasTag,
                    noaliasTag
                  )

                  getMmlTypeName(typeSpec) match {
                    case Some(typeName) =>
                      CompileResult(
                        reg,
                        stateWithAlias.withRegister(reg + 1).emit(line),
                        false,
                        typeName
                      ).asRight
                    case None =>
                      Left(
                        CodeGenError(
                          s"Could not determine MML type name for global reference '${ref.name}'",
                          Some(ref)
                        )
                      )
                  }
                case Left(err) =>
                  Left(err)
              }
            case None =>
              Left(
                CodeGenError(
                  s"Missing type information for global reference '${ref.name}' - TypeChecker should have provided this",
                  Some(ref)
                )
              )
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
      Left(
        CodeGenError(
          "NativeImpl encountered in expression context - this is a malformed AST or compiler bug",
          Some(impl)
        )
      )
    }

    case lambda: Lambda =>
      compileLambdaLiteral(lambda, state, functionScope)

    case other =>
      CodeGenError(s"Unsupported term: ${other.getClass.getSimpleName}", Some(other)).asLeft
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
    case Some(tf: TypeFn) => Right(tf)
    case other =>
      Left(CodeGenError(s"Lambda missing TypeFn typeSpec, got: $other", Some(lambda)))

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
            paramTypes,
            body
          )
        case None =>
          compileRegularLambdaLiteral(
            lambda,
            stateWithId,
            fnName,
            returnType,
            paramTypes,
            functionScope
          )
    yield result
  }

/** Compiles a tail-recursive let-bound lambda as a deferred LLVM function. */
private def compileTailRecLambdaLiteral(
  lambda:     Lambda,
  state:      CodeGenState,
  fnName:     String,
  returnType: String,
  paramTypes: List[String],
  body:       TailRecBody
): Either[CodeGenError, CompileResult] =
  val subState = state.copy(output = List.empty)
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
    val mergedState = mergeSubState(state, finalSubState).addDeferredDefinition(fnBody)
    CompileResult(
      register     = 0,
      state        = mergedState,
      isLiteral    = true,
      typeName     = "Function",
      literalValue = Some(s"{ ptr @$fnName, ptr null }")
    )
  }

/** Compiles a regular (non-tail-recursive) lambda literal as a deferred LLVM function. */
private def compileRegularLambdaLiteral(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  returnType:    String,
  paramTypes:    List[String],
  functionScope: Map[String, ScopeEntry]
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
      functionScope
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
  val subState = state.copy(output = List.empty, nextRegister = 0)
  val paramScope = filteredParamsWithTypes.zipWithIndex.map { case ((param, _), idx) =>
    val mmlType = param.typeAsc.flatMap(getMmlTypeName).getOrElse("Unknown")
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
    fnBody        = (header :: "entry:" :: finalSubState.output.reverse).mkString("\n")
    mergedState   = mergeSubState(state, finalSubState).addDeferredDefinition(fnBody)
  yield CompileResult(
    register     = 0,
    state        = mergedState,
    isLiteral    = true,
    typeName     = "Function",
    literalValue = Some(s"{ ptr @$fnName, ptr null }")
  )

/** Capturing lambda: allocate env struct, store captures, build fat pointer. Inside the deferred
  * function, load captures from env into scope.
  */
private def compileCapturingLambda(
  lambda:                  Lambda,
  state:                   CodeGenState,
  fnName:                  String,
  returnType:              String,
  filteredParamsWithTypes: List[(FnParam, String)],
  allParamDecls:           String,
  envParamIdx:             Int,
  functionScope:           Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  // Resolve LLVM types for each capture
  val captureTypesResult = lambda.captures.traverse { ref =>
    ref.typeSpec match
      case Some(ts) => getLlvmType(ts, state).map(t => (ref, t))
      case None =>
        Left(CodeGenError(s"Capture '${ref.name}' has no type", Some(ref)))
  }

  captureTypesResult.flatMap { captureTypes =>
    val capLlvmTypes = captureTypes.map(_._2)
    val envTypeName  = s"closure_env_$fnName"
    val envTypeDef   = emitTypeDefinition(envTypeName, capLlvmTypes)
    val envTypeRef   = s"%$envTypeName"

    // Register env type and malloc/free declarations
    val stateWithEnv = state
      .withNativeType(envTypeName, envTypeDef)
      .withFunctionDeclaration("malloc", "ptr", List("i64"))
      .withFunctionDeclaration("free", "void", List("ptr"))

    // --- Call site: allocate env, store captures, build fat pointer ---
    val envSize   = capLlvmTypes.map(sizeOfLlvmType).sum
    val mallocReg = stateWithEnv.nextRegister
    val mallocLine =
      emitCall(Some(mallocReg), Some("ptr"), "malloc", List(("i64", envSize.toString)))
    var siteState = stateWithEnv.withRegister(mallocReg + 1).emit(mallocLine)

    // Store each capture into the env struct
    captureTypes.zipWithIndex.foreach { case ((ref, llvmType), idx) =>
      val capOp = functionScope.get(ref.name) match
        case Some(entry) => entry.operandStr
        case None => s"@${ref.name}" // global fallback
      val gepReg = siteState.nextRegister
      val gepLine =
        s"  %$gepReg = getelementptr $envTypeRef, ptr %$mallocReg, i32 0, i32 $idx"
      val storeLine = s"  store $llvmType $capOp, ptr %$gepReg"
      siteState = siteState.withRegister(gepReg + 1).emit(gepLine).emit(storeLine)
    }

    // Build the fat pointer { ptr @fn, ptr %env }
    val fp0Reg = siteState.nextRegister
    val fp1Reg = fp0Reg + 1
    val insertFn =
      emitInsertValue(fp0Reg, "{ ptr, ptr }", "undef", "ptr", s"@$fnName", 0)
    val insertEnv =
      emitInsertValue(fp1Reg, "{ ptr, ptr }", s"%$fp0Reg", "ptr", s"%$mallocReg", 1)
    siteState = siteState.withRegister(fp1Reg + 1).emit(insertFn).emit(insertEnv)

    // --- Deferred function: load captures from env into scope ---
    val subState = siteState.copy(output = List.empty, nextRegister = 0)
    val paramScope = filteredParamsWithTypes.zipWithIndex.map { case ((param, _), idx) =>
      val mmlType = param.typeAsc.flatMap(getMmlTypeName).getOrElse("Unknown")
      (param.name, ScopeEntry(idx, mmlType))
    }.toMap
    var bodyState = subState.withRegister(envParamIdx + 1)

    // Load each capture from the env struct
    val captureScope = captureTypes.zipWithIndex.map { case ((ref, llvmType), idx) =>
      val gepReg  = bodyState.nextRegister
      val loadReg = gepReg + 1
      val gepLine =
        s"  %$gepReg = getelementptr $envTypeRef, ptr %$envParamIdx, i32 0, i32 $idx"
      val loadLine = s"  %$loadReg = load $llvmType, ptr %$gepReg"
      bodyState = bodyState.withRegister(loadReg + 1).emit(gepLine).emit(loadLine)
      val mmlType = ref.typeSpec.flatMap(getMmlTypeName).getOrElse("Unknown")
      (ref.name, ScopeEntry(loadReg, mmlType))
    }.toMap

    val allScope = functionScope ++ paramScope ++ captureScope

    for
      bodyRes <- compileExpr(lambda.body, bodyState, allScope)
      retLine =
        if returnType == "void" then "  ret void"
        else s"  ret $returnType ${bodyRes.operandStr}"
      finalSubState = bodyRes.state.emit(retLine).emit("}")
      header        = s"define internal $returnType @$fnName($allParamDecls) #0 {"
      fnBody        = (header :: "entry:" :: finalSubState.output.reverse).mkString("\n")
      mergedState   = mergeSubState(siteState, finalSubState).addDeferredDefinition(fnBody)
    yield CompileResult(
      register = fp1Reg,
      state    = mergedState,
      false,
      "Function"
    )
  }

/** Merge metadata from a sub-state back into the parent state. NOTE: must be updated when new
  * metadata fields are added to CodeGenState.
  */
private def mergeSubState(parent: CodeGenState, sub: CodeGenState): CodeGenState =
  parent.copy(
    stringConstants      = sub.stringConstants,
    nextStringId         = sub.nextStringId,
    tbaaNodes            = sub.tbaaNodes,
    tbaaOutput           = sub.tbaaOutput,
    nextTbaaId           = sub.nextTbaaId,
    tbaaRootId           = sub.tbaaRootId,
    tbaaScalarIds        = sub.tbaaScalarIds,
    tbaaStructIds        = sub.tbaaStructIds,
    aliasScopeOutput     = sub.aliasScopeOutput,
    aliasScopeDomainId   = sub.aliasScopeDomainId,
    aliasScopeIds        = sub.aliasScopeIds,
    warnings             = sub.warnings,
    deferredDefinitions  = sub.deferredDefinitions,
    nextAnonFnId         = sub.nextAnonFnId,
    nativeTypes          = sub.nativeTypes,
    functionDeclarations = sub.functionDeclarations
  )

private def compileSelectionRef(
  ref:           Ref,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  ref.qualifier match
    case None =>
      Left(CodeGenError(s"Selection ref missing qualifier for '${ref.name}'", Some(ref)))
    case Some(qualifier) =>
      compileTerm(qualifier, state, functionScope).flatMap { qualifierRes =>
        val baseTypeSpec = qualifier.typeSpec
        baseTypeSpec match
          case Some(baseType) =>
            resolveTypeStruct(baseType, state.resolvables) match
              case Some(structDef) =>
                val fieldIndex = structDef.fields.indexWhere(_.name == ref.name)
                if fieldIndex < 0 then
                  Left(
                    CodeGenError(
                      s"Unknown struct field '${ref.name}' for selection",
                      Some(ref)
                    )
                  )
                else
                  val structTypeE = getLlvmType(baseType, qualifierRes.state)
                  val fieldType   = structDef.fields(fieldIndex).typeSpec
                  structTypeE.flatMap { structLlvmType =>
                    val baseValue = qualifierRes.operandStr
                    val fieldReg  = qualifierRes.state.nextRegister
                    val line =
                      emitExtractValue(fieldReg, structLlvmType, baseValue, fieldIndex)
                    getMmlTypeName(fieldType) match
                      case Some(typeName) =>
                        Right(
                          CompileResult(
                            fieldReg,
                            qualifierRes.state.withRegister(fieldReg + 1).emit(line),
                            false,
                            typeName,
                            qualifierRes.exitBlock
                          )
                        )
                      case None =>
                        Left(
                          CodeGenError(
                            s"Could not determine type name for selected field '${ref.name}'",
                            Some(ref)
                          )
                        )
                  }
              case None =>
                Left(
                  CodeGenError(
                    s"Selection base is not a struct for field '${ref.name}'",
                    Some(ref)
                  )
                )
          case None =>
            Left(
              CodeGenError(
                s"Missing type information for selection base '${ref.name}'",
                Some(ref)
              )
            )
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
      CodeGenError(s"Invalid expression structure", Some(expr)).asLeft
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
      // Indirect call: local binding with TypeFn typeSpec (function pointer)
      val isIndirect = functionScope.contains(ref.name) &&
        ref.typeSpec.exists(t => resolveToTypeFn(t, state.resolvables).isDefined)
      if isIndirect then compileIndirectCall(ref, allArgs, app, state, functionScope, compileExpr)
      else
        getNativeOpTemplate(ref.resolvedId.flatMap(state.resolvables.lookup)) match
          case Some(tpl) =>
            compileNativeOp(ref, tpl, allArgs, app, state, functionScope, compileExpr)
          case None if isNullaryWithUnitArgs(ref, allArgs, state.resolvables) =>
            compileNullaryCall(ref, app, state)
          case None =>
            compileRegularCall(ref, allArgs, app, state, functionScope, compileExpr)
