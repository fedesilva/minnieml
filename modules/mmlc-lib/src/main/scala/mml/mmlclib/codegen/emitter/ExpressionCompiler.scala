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
  // Direct lambdas have a dedicated lowering and must never reach the value-position path.
  if lambda.materialization == Materialization.Direct then
    CodeGenError(
      "Direct lambda reached compileLambdaLiteral (value-position path); MaterializationAnalyzer should have set isDirect=false. This is a compiler bug.",
      lambda.some
    ).asLeft
  else
    val typeFn = lambda.typeSpec match
      case Some(tf: TypeFn) => tf.asRight
      case other =>
        CodeGenError(s"Lambda missing TypeFn typeSpec, got: $other", lambda.some).asLeft

    typeFn.flatMap { tf =>
      for
        returnType <- getLlvmType(tf.returnType, state)
        paramTypes <- tf.paramTypes.traverse(getLlvmType(_, state))

        namedClosureEntry = preAllocatedName match
          case Some(_) => none
          case None =>
            reusableNamedClosureEntry(lambda, state, returnType, paramTypes.toList)
        (stateWithId, fnName) = preAllocatedName
          .orElse(namedClosureEntry.map(plan => (state, plan.entryName)))
          .getOrElse(state.allocAnonFnName)

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
            namedClosureEntry match
              case Some(plan) =>
                compileReusableNamedClosureEntry(
                  plan,
                  lambda,
                  stateWithId,
                  fnName,
                  returnType,
                  paramTypes.toList
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

private case class NamedClosureEntryPlan(
  key:       NamedClosureEntryKey,
  entryName: String
)

private def reusableNamedClosureEntry(
  lambda:     Lambda,
  state:      CodeGenState,
  returnType: String,
  paramTypes: List[String]
): Option[NamedClosureEntryPlan] =
  if lambda.captures.nonEmpty || lambda.isMove || lambda.meta.exists(_.isTailRecursive) then none
  else
    etaForwardTarget(lambda, state).map { ref =>
      val targetSymbol = getResolvedName(ref, state)
      val key          = NamedClosureEntryKey(targetSymbol, returnType, paramTypes)
      val entryName    = state.namedClosureEntries.getOrElse(key, s"${targetSymbol}__closure_entry")
      NamedClosureEntryPlan(key, entryName)
    }

private def etaForwardTarget(lambda: Lambda, state: CodeGenState): Option[Ref] =
  lambda.body.terms match
    case List(app: App) =>
      val (fnOrLambda, args) = collectArgsAndFunction(app)
      fnOrLambda match
        case ref: Ref
            if argsMatchParams(args, lambda.params) && resolvesToNamedUserFunction(ref, state) =>
          ref.some
        case _ => none
    case List(ref: Ref) if lambda.params.isEmpty && resolvesToNamedUserFunction(ref, state) =>
      ref.some
    case _ => none

private def argsMatchParams(args: List[Expr], params: List[FnParam]): Boolean =
  args.length == params.length &&
    args.zip(params).forall { case (arg, param) =>
      arg.terms match
        case List(ref: Ref) =>
          (ref.resolvedId, param.id) match
            case (Some(refId), Some(paramId)) => refId == paramId
            case _ => ref.name == param.name
        case _ => false
    }

private def resolvesToNamedUserFunction(ref: Ref, state: CodeGenState): Boolean =
  ref.resolvedId.flatMap(state.resolvables.lookup) match
    case Some(bnd: Bnd) =>
      bnd.value.terms match
        case List(_: Lambda) => !isNativeBinding(bnd)
        case _ => false
    case _ => false

private def compileReusableNamedClosureEntry(
  plan:       NamedClosureEntryPlan,
  lambda:     Lambda,
  state:      CodeGenState,
  fnName:     String,
  returnType: String,
  paramTypes: List[String]
): Either[CodeGenError, CompileResult] =
  state.namedClosureEntries.get(plan.key) match
    case Some(entryName) =>
      CompileResult(
        register     = 0,
        state        = state,
        isLiteral    = true,
        typeName     = "Function",
        literalValue = s"{ ptr @$entryName, ptr null }".some
      ).asRight
    case None =>
      val cachedState = state.withNamedClosureEntry(plan.key, plan.entryName)
      compileRegularLambdaLiteral(
        lambda,
        cachedState,
        fnName,
        returnType,
        paramTypes,
        Map.empty,
        none
      )

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
    linkage  = "internal ",
    entryAbi = TailRecEntryAbi.PlainDirect
  ).map { finalSubState =>
    val wrapperName = s"${fnName}__closure_entry"
    val fnBody      = finalSubState.output.reverse.mkString("\n")
    val wrapperBody = renderNonCapturingTailRecClosureWrapper(
      lambda,
      state,
      fnName,
      wrapperName,
      returnType,
      paramTypes
    )
    val mergedState = mergeDeferredBodyState(state, finalSubState)
      .addDeferredDefinition(fnBody)
      .addDeferredDefinition(wrapperBody)
    CompileResult(
      register     = 0,
      state        = mergedState,
      isLiteral    = true,
      typeName     = "Function",
      literalValue = s"{ ptr @$wrapperName, ptr null }".some
    )
  }

private def renderNonCapturingTailRecClosureWrapper(
  lambda:      Lambda,
  state:       CodeGenState,
  directName:  String,
  wrapperName: String,
  returnType:  String,
  paramTypes:  List[String]
): String =
  val filteredParamsWithTypes = filterVoidParams(lambda.params, paramTypes)
  val userParamDecls          = formatParamDecls(filteredParamsWithTypes, state.resolvables)
  val envParamIdx             = filteredParamsWithTypes.size
  val allParamDecls =
    if userParamDecls.isEmpty then s"ptr %$envParamIdx"
    else s"$userParamDecls, ptr %$envParamIdx"
  val callArgs = filteredParamsWithTypes.zipWithIndex.map { case ((_, llvmType), idx) =>
    (llvmType, s"%$idx")
  }
  val resultReg = envParamIdx + 1
  val callLine =
    if returnType == "void" then emitCall(none, none, directName, callArgs)
    else emitCall(resultReg.some, returnType.some, directName, callArgs)
  val retLine =
    if returnType == "void" then "  ret void"
    else s"  ret $returnType %$resultReg"

  List(
    s"define internal $returnType @$wrapperName($allParamDecls) #0 {",
    "entry:",
    callLine,
    retLine,
    "}",
    ""
  ).mkString("\n")

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
    compileTailRecursiveLambda(
      lambda,
      subState,
      returnType,
      paramTypes,
      fnName,
      body,
      linkage  = "internal ",
      entryAbi = TailRecEntryAbi.ClosureEntry,
      captureInfo =
        TailRecCaptureInfo.ClosureEnv(envResult.envTypeRef, envResult.captureLayout).some
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

/** A single lowered capture slot for Direct trailing params or materialized closure env fields.
  *
  * Two kinds:
  *   - [[Value]] — a value-shaped capture; binds `capName` to the slot in the inner body scope.
  *   - [[DirectOp]] — one operand of an enclosing direct-callable's `captureOperands`, threaded so
  *     the nested body can call `callableName` with operands valid in the inner SSA frame.
  *
  * `outerOperand` is the operand string in the **outer** scope. Direct lambdas pass it as a
  * trailing parameter; materialized closures store it into the env.
  */
private[emitter] sealed trait CodegenCaptureSlot:
  def llvmType:     String
  def outerOperand: String
  def tbaaTypeName: String

private[emitter] object CodegenCaptureSlot:
  /** Value-shaped capture.
    *
    * When `cloneFnId` is `Some(id)`, the binder site must call the clone function on the raw outer
    * operand and pass the cloned result as the trailing argument (heap-literal captures). When
    * `None`, the outer operand is passed verbatim.
    */
  case class Value(
    capName:      String,
    mmlType:      String,
    llvmType:     String,
    outerOperand: String,
    cloneFnId:    Option[String]
  ) extends CodegenCaptureSlot:
    def tbaaTypeName: String = mmlType

  case class DirectOp(
    callableName: String,
    entryName:    String,
    llvmType:     String,
    outerOperand: String,
    tbaaTypeName: String
  ) extends CodegenCaptureSlot

/** Capture layout after expanding Direct-callable captures into value-shaped operands.
  *
  * @param slots
  *   one entry per LLVM trailing parameter or env field, in declaration order.
  * @param innerScope
  *   value-shaped captures bound to their inner trailing-param register.
  * @param innerDirectable
  *   re-bound direct-callable captures whose operands now point at inner trailing-param registers
  *   (replacing the outer operands that the body would otherwise inherit).
  * @param directEntries
  *   Direct-callable captures by source name, including zero-capture Direct callables that
  *   contribute no slots.
  */
private[emitter] case class CodegenCaptureLayout(
  slots:           List[CodegenCaptureSlot],
  innerScope:      Map[String, ScopeEntry],
  innerDirectable: Map[String, DirectCallable],
  directEntries:   Map[String, DirectCallable]
):
  def paramDecls(userParamCount: Int): List[String] =
    slots.zipWithIndex.map { case (s, i) => s"${s.llvmType} %${userParamCount + i}" }

/** Compute the shared codegen capture layout for Direct lambdas and materialized closure envs.
  *
  * Walks `lambda.captures` in order. A `CapturedRef` that resolves to a direct-callable in the
  * enclosing scope is **expanded** into one slot per operand of that callable; generated bodies get
  * a rebound [[DirectCallable]] whose operands point at the loaded values or trailing-param
  * registers. Plain value captures (including [[Capture.CapturedLiteral]] heap-literal captures
  * that need a binder-site clone) contribute one slot each.
  */
private[emitter] def computeCodegenCaptureLayout(
  lambda:         Lambda,
  state:          CodeGenState,
  functionScope:  Map[String, ScopeEntry],
  userParamCount: Int
): Either[CodeGenError, CodegenCaptureLayout] =
  def valueSlotFor(
    ref:       Ref,
    cloneFnId: Option[String]
  ): Either[CodeGenError, CodegenCaptureSlot] =
    ref.typeSpec match
      case None =>
        CodeGenError(s"Capture '${ref.name}' has no type", ref.some).asLeft
      case Some(ts) =>
        getLlvmType(ts, state).map { ty =>
          val mmlType = getNominalTypeName(ts).toOption.getOrElse("Unknown")
          val outerOp = functionScope.get(ref.name).map(_.operandStr).getOrElse(s"@${ref.name}")
          CodegenCaptureSlot.Value(ref.name, mmlType, ty, outerOp, cloneFnId)
        }

  type CaptureAcc = (List[CodegenCaptureSlot], Map[String, DirectCallable])
  val layoutE = lambda.captures.foldLeft[Either[CodeGenError, CaptureAcc]](
    (List.empty[CodegenCaptureSlot], Map.empty[String, DirectCallable]).asRight
  ) { (accE, cap) =>
    accE.flatMap { case (slots, directEntries) =>
      cap match
        case Capture.CapturedLiteral(ref, cloneFnId) =>
          valueSlotFor(ref, cloneFnId.some).map(slot => (slots :+ slot, directEntries))
        case Capture.CapturedRef(ref) =>
          functionScope.get(ref.name) match
            case None =>
              CodeGenError(
                s"Capture '${ref.name}' missing from enclosing scope",
                ref.some
              ).asLeft
            case Some(entry) =>
              entry.directCallable match
                case Some(dc) =>
                  val expanded = dc.captureOperands.map { op =>
                    CodegenCaptureSlot.DirectOp(
                      ref.name,
                      dc.entryName,
                      op.llvmType,
                      op.operand,
                      op.tbaaTypeName
                    )
                  }
                  (slots ++ expanded, directEntries.updated(ref.name, dc)).asRight
                case None =>
                  valueSlotFor(ref, None).map(slot => (slots :+ slot, directEntries))
    }
  }

  layoutE.map { case (slots, directEntries) =>
    val (innerScope, perCallable) =
      slots.zipWithIndex.foldLeft(
        (
          Map.empty[String, ScopeEntry],
          directEntries.map { case (name, direct) =>
            name -> (direct, List.empty[DirectOperand])
          }
        )
      ) { case ((scope, dcs), (slot, i)) =>
        slot match
          case CodegenCaptureSlot.Value(name, mmlType, _, _, _) =>
            (scope + (name -> ScopeEntry(userParamCount + i, mmlType)), dcs)
          case CodegenCaptureSlot.DirectOp(name, entryName, ty, _, _) =>
            val innerOp = s"%${userParamCount + i}"
            val direct  = dcs.get(name).map(_._1).getOrElse(DirectCallable(entryName, Nil))
            val prevOps = dcs.get(name).map(_._2).getOrElse(Nil)
            val updated =
              dcs.updated(name, (direct, prevOps :+ DirectOperand(innerOp, ty, slot.tbaaTypeName)))
            (scope, updated)
      }

    val innerDirectable = perCallable.map { case (name, (direct, innerOps)) =>
      name -> direct.copy(captureOperands = innerOps)
    }

    CodegenCaptureLayout(slots, innerScope, innerDirectable, directEntries)
  }

/** ABI-lowered clone call for a single capture.
  *
  * Used both by env-materialization and by Direct-path heap-literal captures. Emits the call,
  * registers the function declaration, and returns the cloned-operand string aligned with the
  * caller's expected `llvmType`.
  */
private[emitter] def emitCaptureCloneCall(
  rawOp:     String,
  llvmType:  String,
  cloneFnId: String,
  state:     CodeGenState
): (CodeGenState, String) =
  val cloneFnMmlName = state.resolvables.resolvables
    .collectFirst {
      case (id, bnd: Bnd) if id == cloneFnId => bnd.name
    }
    .getOrElse(cloneFnId.split("::").last)
  val cloneFnLlvmName  = resolveMemFnLlvmName(cloneFnMmlName, state)
  val rawArgs          = List((rawOp, llvmType))
  val (lowered, stLow) = state.abi.lowerArgs(rawArgs, state)
  val callArgs         = lowered.map((op, typ) => (typ, op))
  val declParamTypes   = lowered.map(_._2)
  if stLow.abi.needsSret(llvmType, stLow) then
    val (retReg, stAfterCall) = stLow.abi.emitSretCall(
      cloneFnLlvmName,
      llvmType,
      callArgs,
      stLow,
      (reg, retTy, fn, args, _, _) => emitCall(reg, retTy, fn, args),
      None,
      None
    )
    val stWithDecl =
      if isNativeMemFn(cloneFnMmlName, stAfterCall) then
        stAfterCall.withFunctionDeclaration(cloneFnLlvmName, "void", "ptr" :: declParamTypes)
      else stAfterCall
    (stWithDecl, s"%$retReg")
  else
    val cloneReg = stLow.nextRegister
    val stWithDecl =
      if isNativeMemFn(cloneFnMmlName, stLow) then
        stLow.withFunctionDeclaration(cloneFnLlvmName, llvmType, declParamTypes)
      else stLow
    val cloneLine = emitCall(cloneReg.some, llvmType.some, cloneFnLlvmName, callArgs)
    (stWithDecl.withRegister(cloneReg + 1).emit(cloneLine), s"%$cloneReg")

/** Pre-evaluate a Direct lambda's effective trailing operands at the binder site.
  *
  * For each slot, returns the outer-scope operand aligned with the inner LLVM trailing-param order.
  * Heap-literal slots emit a clone call before yielding their operand; pass the resulting operands
  * to [[compileDirectCall]] at every call site of the binder.
  */
private[emitter] def evaluateDirectCaptures(
  lambda:        Lambda,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, (CodeGenState, List[DirectOperand], List[DirectCaptureCleanup])] =
  // userParamCount only affects inner SSA indices; the outer operand list is independent of it.
  computeCodegenCaptureLayout(lambda, state, functionScope, userParamCount = 0).map { trailing =>
    trailing.slots.foldLeft(
      (state, List.empty[DirectOperand], List.empty[DirectCaptureCleanup])
    ) {
      case (
            (st, ops, cleanups),
            slot @ CodegenCaptureSlot.Value(_, _, ty, outerOp, Some(cloneId))
          ) =>
        val (stAfter, clonedOp) = emitCaptureCloneCall(outerOp, ty, cloneId, st)
        // A move lambda owns the cloned heap literal; the binder scope frees it at scope exit.
        val newCleanups =
          if lambda.isMove then cleanups :+ DirectCaptureCleanup(clonedOp, ty, slot.tbaaTypeName)
          else cleanups
        (stAfter, ops :+ DirectOperand(clonedOp, ty, slot.tbaaTypeName), newCleanups)
      case (
            (st, ops, cleanups),
            slot @ CodegenCaptureSlot.Value(_, _, ty, outerOp, None)
          ) =>
        // A move lambda takes ownership of owned heap captures moved in by value.
        val newCleanups =
          if lambda.isMove && TypeUtils.isHeapType(slot.tbaaTypeName, st.resolvables) then
            cleanups :+ DirectCaptureCleanup(outerOp, ty, slot.tbaaTypeName)
          else cleanups
        (st, ops :+ DirectOperand(outerOp, ty, slot.tbaaTypeName), newCleanups)
      case ((st, ops, cleanups), slot) =>
        (st, ops :+ DirectOperand(slot.outerOperand, slot.llvmType, slot.tbaaTypeName), cleanups)
    }
  }

/** Free owned heap captures of a Direct move lambda once at the binder scope exit.
  *
  * Direct lambdas have no env destructor, so the binder scope owns the moved-in captures. The free
  * convention mirrors [[emitEnvHeapFieldFrees]]: ABI-lower the operand and declare the free
  * function with the lowered parameter types.
  */
private[emitter] def emitDirectCaptureFrees(
  cleanups: List[DirectCaptureCleanup],
  state:    CodeGenState
): Either[CodeGenError, CodeGenState] =
  cleanups.foldLeft(state.asRight[CodeGenError]) { (stE, cleanup) =>
    stE.flatMap { st =>
      TypeUtils.freeFnFor(cleanup.mmlTypeName, st.resolvables) match
        case None => st.asRight
        case Some(freeFnName) =>
          val llvmFreeName         = resolveMemFnLlvmName(freeFnName, st)
          val rawArgs              = List((cleanup.operand, cleanup.llvmType))
          val (lowered, stLowered) = st.abi.lowerArgs(rawArgs, st)
          val callArgs             = lowered.map((op, typ) => (typ, op))
          val declParamTypes       = lowered.map(_._2)
          // Runtime free functions need a declaration; generated struct destructors are
          // defined in this module, so declaring them would conflict with their definition.
          val stWithDecl =
            if isNativeMemFn(freeFnName, stLowered) then
              stLowered.withFunctionDeclaration(llvmFreeName, "void", declParamTypes)
            else stLowered
          stWithDecl.emit(emitCall(None, None, llvmFreeName, callArgs)).asRight
    }
  }

/** Compiles a Direct lambda as a deferred LLVM function with no env parameter.
  *
  * Signature: `(userParams..., captureTypes...)`. Captures are passed as trailing arguments at
  * every call site. The binder receives a [[DirectCallable]] scope entry; consumers must use
  * [[compileApp]] to invoke it.
  */
private[emitter] def compileDirectLambda(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  returnType:    String,
  paramTypes:    List[String],
  functionScope: Map[String, ScopeEntry],
  selfBinder:    Option[FnParam]
): Either[CodeGenError, CompileResult] =
  val filteredParamsWithTypes = filterVoidParams(lambda.params, paramTypes)
  val userParamDecls          = formatParamDecls(filteredParamsWithTypes, state.resolvables)
  val userParamCount          = filteredParamsWithTypes.size

  computeCodegenCaptureLayout(lambda, state, functionScope, userParamCount).flatMap { trailing =>
    val tailRecBody = for
      param <- selfBinder
      if lambda.meta.exists(_.isTailRecursive)
      body <- findTailRecBody(lambda, param.name, param.id)
    yield body

    tailRecBody match
      case Some(body) =>
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
          linkage     = "internal ",
          entryAbi    = TailRecEntryAbi.PlainDirect,
          captureInfo = TailRecCaptureInfo.DirectTrailing(trailing).some
        ).map { finalSubState =>
          val fnBody = finalSubState.output.reverse.mkString("\n")
          val mergedState =
            mergeDeferredBodyState(state, finalSubState).addDeferredDefinition(fnBody)
          CompileResult(
            register  = 0,
            state     = mergedState,
            isLiteral = true,
            typeName  = "Function"
          )
        }

      case None =>
        val captureDecls = trailing.paramDecls(userParamCount)
        val allParamDecls =
          val parts =
            (if userParamDecls.isEmpty then Nil else List(userParamDecls)) ++ captureDecls
          parts.mkString(", ")

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

        // Inner-scope operands for the self-recursive call site mirror the inner trailing-param
        // layout (one operand per slot, in declaration order).
        val innerCaptureOps = trailing.slots.zipWithIndex.map { case (slot, i) =>
          DirectOperand(s"%${userParamCount + i}", slot.llvmType, slot.tbaaTypeName)
        }
        val selfScope = selfBinder.map { p =>
          val entry = ScopeEntry(
            0,
            "Function",
            directCallable =
              DirectCallable(fnName, innerCaptureOps, paramTypes, returnType.some).some
          )
          p.name -> entry
        }.toMap

        val bodyState = subState.withRegister(userParamCount + trailing.slots.size)

        for
          bodyRes <-
            compileExpr(
              lambda.body,
              bodyState,
              functionScope ++ paramScope ++ directTrailingCaptureScope(trailing) ++ selfScope
            )
          retLine =
            if returnType == "void" then "  ret void"
            else s"  ret $returnType ${bodyRes.operandStr}"
          finalSubState = bodyRes.state.emit(retLine).emit("}")
          header        = s"define internal $returnType @$fnName($allParamDecls) #0 {"
          fnBody        = renderFunctionLines(header, finalSubState).mkString("\n")
          mergedState   = mergeDeferredBodyState(state, finalSubState).addDeferredDefinition(fnBody)
        yield CompileResult(
          register  = 0,
          state     = mergedState,
          isLiteral = true,
          typeName  = "Function"
        )
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
  siteState:     CodeGenState,
  fpRegister:    Int,
  envTypeRef:    String,
  captureLayout: CodegenCaptureLayout
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

private def lambdaReferencesBinding(
  lambda:       Lambda,
  bindingParam: Option[FnParam]
): Either[CodeGenError, Boolean] =
  bindingParam match
    case None => false.asRight
    case Some(param) =>
      param.id match
        case None =>
          CodeGenError(
            s"Missing semantic ID for closure binding '${param.name}'",
            lambda.some
          ).asLeft
        case Some(targetId) => exprReferencesBinding(lambda.body, targetId).asRight

private def exprReferencesBinding(expr: Expr, targetId: String): Boolean =
  expr.terms.exists(termReferencesBinding(_, targetId))

private def termReferencesBinding(term: Term, targetId: String): Boolean =
  term match
    case ref: Ref =>
      ref.resolvedId.contains(targetId) || ref.qualifier.exists(termReferencesBinding(_, targetId))
    case expr: Expr =>
      exprReferencesBinding(expr, targetId)
    case app: App =>
      appFnReferencesBinding(app.fn, targetId) || exprReferencesBinding(app.arg, targetId)
    case lambda: Lambda if lambda.params.exists(_.id.contains(targetId)) =>
      false
    case lambda: Lambda =>
      exprReferencesBinding(lambda.body, targetId)
    case TermGroup(_, inner, _) =>
      exprReferencesBinding(inner, targetId)
    case Cond(_, cond, ifTrue, ifFalse, _, _) =>
      List(cond, ifTrue, ifFalse).exists(exprReferencesBinding(_, targetId))
    case Tuple(_, elements, _, _) =>
      elements.toList.exists(exprReferencesBinding(_, targetId))
    case invalid: InvalidExpression =>
      exprReferencesBinding(invalid.originalExpr, targetId)
    case _ =>
      false

private def appFnReferencesBinding(fn: Ref | App | Lambda, targetId: String): Boolean =
  fn match
    case ref:    Ref => termReferencesBinding(ref, targetId)
    case app:    App => termReferencesBinding(app, targetId)
    case lambda: Lambda => termReferencesBinding(lambda, targetId)

/** Resolve capture types, create env struct, emit call-site IR.
  *
  * Heap move envs use malloc and carry a destructor field. Stack borrow envs use alloca and carry
  * capture fields only.
  */
private def emitCallSiteEnv(
  lambda:        Lambda,
  state:         CodeGenState,
  fnName:        String,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, EnvSetupResult] =
  for
    envStruct <- resolveClosureEnvStruct(lambda, state)
    captureLayout <- computeCodegenCaptureLayout(lambda, state, functionScope, userParamCount = 0)
    envAllocation = lambda.closureEnvAllocation
    envTypeRef    = s"%struct.${envStruct.name}"
    fieldOffset   = envAllocation.captureFieldOffset
    allocation <-
      envAllocation match
        case ClosureEnvAllocation.NoEnv =>
          CodeGenError(
            "Lambda without a closure env reached env materialization path",
            lambda.some
          ).asLeft
        case ClosureEnvAllocation.HeapMoveEnv =>
          val stateWithEnv = state
            .withFunctionDeclaration("malloc", "ptr", List("i64"))
            .withFunctionDeclaration("free", "void", List("ptr"))
          val envSize   = sizeOfLlvmTypeResolved(envTypeRef, stateWithEnv)
          val mallocReg = stateWithEnv.nextRegister
          val mallocLine =
            emitCall(mallocReg.some, "ptr".some, "malloc", List(("i64", envSize.toString)))
          val afterMalloc = stateWithEnv.withRegister(mallocReg + 1).emit(mallocLine)
          val dtorName    = s"__free_${envStruct.name}"
          val dtorGepReg  = afterMalloc.nextRegister
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
          (afterDtor, s"%$mallocReg").asRight
        case ClosureEnvAllocation.StackBorrowEnv =>
          val allocaReg  = state.nextRegister
          val allocaLine = s"  %$allocaReg = alloca $envTypeRef"
          val afterAlloca =
            if state.insideLoopifiedFunction then
              state.withRegister(allocaReg + 1).emitEntryPrologue(allocaLine)
            else state.withRegister(allocaReg + 1).emit(allocaLine)
          (afterAlloca, s"%$allocaReg").asRight
    (siteStateAfterDtor, envPtrOp) = allocation
    siteStateAfterCaptures <-
      captureLayout.slots.zipWithIndex.foldLeft(siteStateAfterDtor.asRight[CodeGenError]) {
        case (stE, (slot, idx)) =>
          stE.flatMap { st =>
            val (stateBeforeStore, capOp) =
              slot match
                case CodegenCaptureSlot.Value(_, _, llvmType, outerOp, Some(cloneFnId)) =>
                  emitCaptureCloneCall(outerOp, llvmType, cloneFnId, st)
                case _ =>
                  (st, slot.outerOperand)

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
              slot.llvmType,
              s"%$gepReg",
              Option.when(fieldTag.nonEmpty)(fieldTag)
            )
            stateWithFieldTag.withRegister(gepReg + 1).emit(gepLine).emit(storeLine).asRight
          }
      }
  yield
    val fp0Reg = siteStateAfterCaptures.nextRegister
    val fp1Reg = fp0Reg + 1
    val insertFn =
      emitInsertValue(fp0Reg, "{ ptr, ptr }", "undef", "ptr", s"@$fnName", 0)
    val insertEnv =
      emitInsertValue(fp1Reg, "{ ptr, ptr }", s"%$fp0Reg", "ptr", envPtrOp, 1)
    val siteState =
      siteStateAfterCaptures.withRegister(fp1Reg + 1).emit(insertFn).emit(insertEnv)

    EnvSetupResult(siteState, fp1Reg, envTypeRef, captureLayout)

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

    val captureFieldOffset = lambda.closureEnvAllocation.captureFieldOffset
    val (bodyState, captureScope) =
      emitCaptureLoads(
        envTypeRef,
        envParamIdx,
        envResult.captureLayout,
        initialBodyState,
        captureFieldOffset
      )
    for
      referencesSelf <- lambdaReferencesBinding(lambda, bindingParam)
      (bodyStateWithSelf, selfScope) =
        if referencesSelf then
          emitRecursiveSelfClosure(fnName, envParamIdx, bodyState, bindingParam)
        else (bodyState, Map.empty)
      allScope = functionScope ++ paramScope ++ captureScope ++ selfScope
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
      functionScope.get(ref.name).flatMap(_.directCallable) match
        case Some(direct) =>
          val suppliedUserArgCount = allArgs.size
          val directUserParamCount = direct.paramTypes.count(_ != "void")
          app.typeSpec
            .flatMap(t => resolveToTypeFn(t, state.resolvables))
            .filter(_ => suppliedUserArgCount < directUserParamCount) match
            case Some(resultFnType) =>
              compileDirectPartialApplication(
                ref,
                direct,
                allArgs,
                resultFnType,
                app,
                state,
                functionScope,
                compileExpr
              )
            case None =>
              compileDirectCall(ref, direct, allArgs, app, state, functionScope, compileExpr)
        case None =>
          val hasFunctionType =
            ref.typeSpec.exists(t => resolveToTypeFn(t, state.resolvables).isDefined)
          // Direct call only applies to refs that resolve to emitted callable symbols.
          // First-class function values, including globals stored as { fn_ptr, env_ptr }, must use
          // the shared indirect-call path.
          val isIndirect = hasFunctionType &&
            (functionScope.contains(ref.name) || !resolvesToNamedFunctionSymbol(ref, state))
          if isIndirect then
            compileIndirectCall(ref, allArgs, app, state, functionScope, compileExpr)
          else
            getNativeOpTemplate(ref.resolvedId.flatMap(state.resolvables.lookup)) match
              case Some(tpl) =>
                compileNativeOp(ref, tpl, allArgs, app, state, functionScope, compileExpr)
              case None if isNullaryWithUnitArgs(ref, allArgs, state.resolvables) =>
                compileNullaryCall(ref, app, state)
              case None =>
                compileRegularCall(ref, allArgs, app, state, functionScope, compileExpr)
