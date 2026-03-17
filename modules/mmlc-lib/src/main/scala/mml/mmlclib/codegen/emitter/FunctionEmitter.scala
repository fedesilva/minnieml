package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.compileExpr
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter
import mml.mmlclib.errors.CompilerWarning

/** Handles code generation for function definitions and applications. */

/** Compiles a binding (variable declaration).
  *
  * For literal initializations, emits a direct global assignment. For non-literal initializations,
  * emits a global initializer function.
  *
  * IMPORTANT: To avoid duplicating computation at the top level, if the binding is non-literal, we
  * discard the instructions produced by the first compile of the expression and recompile it within
  * the initializer function.
  *
  * @param bnd
  *   the binding to compile
  * @param state
  *   the current code generation state (before compiling the binding)
  * @return
  *   Either a CodeGenError or the updated CodeGenState.
  */
def compileBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] =
  val origState = state
  bnd.typeAsc.map(getLlvmType(_, state)) match
    case Some(Right(llvmType)) =>
      compileExpr(bnd.value, state).flatMap { compileRes =>
        if compileRes.isLiteral then
          Right(compileRes.state.emit(s"@${bnd.name} = global $llvmType ${compileRes.register}"))
        else
          // Discard the instructions from the initial compilation by using the original state.
          val initFnName = s"_init_global_${bnd.name}"
          val state2 = origState
            .emit(s"@${bnd.name} = global $llvmType 0")
            .emit(s"define internal void @$initFnName() {")
            .emit(s"entry:")
          compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
            val (stateWithAlias, aliasTag, noaliasTag) = bnd.typeSpec match
              case Some(spec) => AliasScopeEmitter.getAliasScopeTags(spec, compileRes2.state)
              case None => (compileRes2.state, None, None)
            val storeLine =
              emitStore(
                s"%${compileRes2.register}",
                llvmType,
                s"@${bnd.name}",
                aliasScope = aliasTag,
                noalias    = noaliasTag
              )
            stateWithAlias
              .emit(storeLine)
              .emit("  ret void")
              .emit("}")
              .emit("")
              .addInitializer(initFnName)
          }
      }
    case Some(Left(err)) => Left(err)
    case None => Left(CodeGenError(s"Type annotation missing for binding '${bnd.name}'", Some(bnd)))

/** Compiles a Bnd(Lambda) into LLVM IR function.
  *
  * Handles functions and operators that are represented as Bnd(Lambda) with BindingMeta.
  *
  * @param emittedName
  *   the mangled name to use for the function definition
  */
def compileBndLambda(
  bnd:         Bnd,
  lambda:      Lambda,
  state:       CodeGenState,
  returnType:  String,
  paramTypes:  List[String],
  emittedName: String,
  linkage:     String = ""
): Either[CodeGenError, CodeGenState] =
  // Try loopification for tail-recursive functions, fall back to regular codegen if pattern unsupported
  if lambda.meta.exists(_.isTailRecursive) then
    findTailRecBody(lambda, bnd) match
      case Some(body) =>
        compileTailRecursiveLambda(
          bnd,
          lambda,
          state,
          returnType,
          paramTypes,
          emittedName,
          body,
          linkage
        )
      case None =>
        // Pattern not recognized - emit warning and fall back to regular codegen
        val warning = CompilerWarning.TailRecPatternUnsupported(
          bnd.name,
          "tail recursion pattern not recognized, falling back to standard recursion"
        )
        val stateWithWarning = state.addWarning(warning)
        compileRegularLambda(
          bnd,
          lambda,
          stateWithWarning,
          returnType,
          paramTypes,
          emittedName,
          linkage
        )
  else compileRegularLambda(bnd, lambda, state, returnType, paramTypes, emittedName, linkage)

/** Check if a parameter is a NativePointer type at LLVM level. */
private def isPointerParam(param: FnParam, resolvables: ResolvablesIndex): Boolean =
  param.typeSpec
    .orElse(param.typeAsc)
    .flatMap(TypeUtils.getTypeName)
    .exists(TypeUtils.isPointerType(_, resolvables))

/** Filter out Unit params (void) — they can't be passed in LLVM. */
private def filterVoidParams(
  params:     List[FnParam],
  paramTypes: List[String]
): List[(FnParam, String)] =
  params.zip(paramTypes).filter((_, t) => t != "void")

/** Format LLVM parameter declarations, adding `noalias` for consuming pointer params. */
private def formatParamDecls(
  params:      List[(FnParam, String)],
  resolvables: ResolvablesIndex
): String =
  params.zipWithIndex
    .map { case ((param, typ), idx) =>
      if param.consuming && isPointerParam(param, resolvables)
      then s"$typ noalias %$idx"
      else s"$typ %$idx"
    }
    .mkString(", ")

/** Compiles a regular (non-tail-recursive) lambda to LLVM IR. */
private def compileRegularLambda(
  bnd:         Bnd,
  lambda:      Lambda,
  state:       CodeGenState,
  returnType:  String,
  paramTypes:  List[String],
  emittedName: String,
  linkage:     String = ""
): Either[CodeGenError, CodeGenState] =
  val filteredParamsWithTypes = filterVoidParams(lambda.params, paramTypes)
  val filteredParams          = filteredParamsWithTypes.map(_._1)
  val filteredParamTypes      = filteredParamsWithTypes.map(_._2)
  val paramDecls              = formatParamDecls(filteredParamsWithTypes, state.resolvables)

  val attrGroup    = if bnd.meta.exists(_.inlineHint) then "#1" else "#0"
  val functionDecl = s"define $linkage$returnType @$emittedName($paramDecls) $attrGroup {"
  val entryLine    = "entry:"

  // Setup function body state with initial lines
  val bodyState = state
    .emit(functionDecl)
    .emit(entryLine)
    .withRegister(0) // Reset register counter for local function scope

  // Create a scope map for lambda parameters
  val paramScope = filteredParams
    .zip(filteredParamTypes)
    .zipWithIndex
    .map { case ((param, typ), idx) =>
      val regNum    = idx
      val allocLine = s"  %${param.name}_ptr = alloca $typ"
      val storeLine = s"  store $typ %$idx, $typ* %${param.name}_ptr"
      val loadLine  = s"  %${regNum} = load $typ, $typ* %${param.name}_ptr"

      // We'll emit the alloca/store/load sequence for each parameter
      bodyState.emit(allocLine).emit(storeLine).emit(loadLine)

      val mmlType = param.typeAsc.flatMap(getMmlTypeName).getOrElse("Unknown")
      (param.name, ScopeEntry(regNum, mmlType))
    }
    .toMap

  // Register count starts after parameter setup
  val updatedState = bodyState.withRegister(filteredParams.size)

  val bodyResult =
    lambda.body.terms match
      case List(_: DataConstructor) =>
        compileStructConstructor(bnd, lambda, updatedState, paramScope)
      case _ =>
        compileExpr(lambda.body, updatedState, paramScope)

  // Compile the lambda body with the parameter scope
  bodyResult.flatMap { bodyRes =>
    val returnLine =
      if returnType == "void" then "  ret void"
      else
        val returnOp = bodyRes.operandStr
        s"  ret $returnType $returnOp"

    // Close function and add empty line
    Right(
      bodyRes.state
        .emit(returnLine)
        .emit("}")
        .emit("")
    )
  }

private def compileStructConstructor(
  bnd:           Bnd,
  lambda:        Lambda,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  val returnTypeE = bnd.typeSpec match
    case Some(fnType: TypeFn) => Right(fnType.returnType)
    case Some(TypeScheme(_, _, bodyType: TypeFn)) => Right(bodyType.returnType)
    case Some(other) =>
      Left(
        CodeGenError(
          s"Expected function type for constructor, got ${other.getClass.getSimpleName}",
          Some(bnd)
        )
      )
    case None =>
      Left(CodeGenError(s"Missing type specification for constructor '${bnd.name}'", Some(bnd)))

  returnTypeE.flatMap { returnTypeSpec =>
    resolveTypeStruct(returnTypeSpec, state.resolvables) match
      case None =>
        Left(CodeGenError(s"Constructor return type is not a struct", Some(bnd)))
      case Some(structDef) =>
        if structDef.fields.size != lambda.params.size then
          Left(
            CodeGenError(
              s"Constructor parameter count does not match struct fields for '${structDef.name}'",
              Some(bnd)
            )
          )
        else
          getLlvmType(returnTypeSpec, state).flatMap { structLlvmType =>
            val allocReg       = state.nextRegister
            val allocLine      = s"  %$allocReg = alloca $structLlvmType"
            val stateWithAlloc = state.withRegister(allocReg + 1).emit(allocLine)

            val fieldsWithParams = structDef.fields.toList.zip(lambda.params).zipWithIndex
            val fieldsStateE = fieldsWithParams.foldLeft(stateWithAlloc.asRight[CodeGenError]) {
              case (stateE, ((field, param), fieldIndex)) =>
                stateE.flatMap { currentState =>
                  functionScope.get(param.name) match
                    case None =>
                      Left(
                        CodeGenError(
                          s"Missing constructor parameter '${param.name}' in scope",
                          Some(param)
                        )
                      )
                    case Some(entry) =>
                      getLlvmType(field.typeSpec, currentState).flatMap { fieldLlvmType =>
                        val fieldPtrReg = currentState.nextRegister
                        val ptrLine = emitGetElementPtr(
                          fieldPtrReg,
                          structLlvmType,
                          s"$structLlvmType*",
                          s"%$allocReg",
                          List(("i32", "0"), ("i32", fieldIndex.toString))
                        )
                        val stateWithPtr =
                          currentState.withRegister(fieldPtrReg + 1).emit(ptrLine)

                        val valueToStore    = entry.operandStr
                        val stateAfterClone = stateWithPtr

                        TbaaEmitter
                          .getTbaaStructFieldTag(returnTypeSpec, fieldIndex, stateAfterClone)
                          .map { case (stateWithTag, tag) =>
                            val (stateWithAlias, aliasTag, noaliasTag) =
                              AliasScopeEmitter.getAliasScopeTags(field.typeSpec, stateWithTag)
                            val storeLine =
                              emitStore(
                                valueToStore,
                                fieldLlvmType,
                                s"%$fieldPtrReg",
                                Some(tag),
                                aliasTag,
                                noaliasTag
                              )
                            stateWithAlias.emit(storeLine)
                          }
                      }
                }
            }

            fieldsStateE.map { stateAfterStores =>
              val (stateWithAlias, aliasTag, noaliasTag) =
                AliasScopeEmitter.getAliasScopeTags(returnTypeSpec, stateAfterStores)
              val resultReg = stateWithAlias.nextRegister
              val loadLine = emitLoad(
                resultReg,
                structLlvmType,
                s"%$allocReg",
                aliasScope = aliasTag,
                noalias    = noaliasTag
              )
              CompileResult(
                resultReg,
                stateWithAlias.withRegister(resultReg + 1).emit(loadLine),
                false,
                structDef.name
              )
            }
          }
  }

/** A statement in a tail-recursive body, optionally binding a name.
  *
  * For sequence lambdas (__stmt), bindingName is None (side-effect only). For let-bindings,
  * bindingName is Some(name) and the result is bound to that name for use in subsequent code.
  */
private case class BoundStatement(bindingName: Option[String], expr: Expr)

/** Recursive tree representing the body of a tail-recursive function for loopification.
  *
  * Each leaf either exits the loop (ret) or loops back (br to loop header). Branch nodes split on a
  * condition — both branches may contain recursive calls, enabling patterns like astar2's
  * visit_neighbors where different paths recurse with different arguments.
  */
sealed private trait TailRecBody

/** A leaf that exits the loop: compiles preStatements then returns exitExpr. */
private case class TailRecExit(preStatements: List[BoundStatement], exitExpr: Expr)
    extends TailRecBody

/** A leaf that loops back: compiles preStatements then jumps to loop header with new args. */
private case class TailRecCall(preStatements: List[BoundStatement], args: List[Expr])
    extends TailRecBody

/** A branch: compiles preStatements, evaluates condition, recurses into both subtrees. */
private case class TailRecBranch(
  preStatements: List[BoundStatement],
  condition:     Expr,
  ifTrue:        TailRecBody,
  ifFalse:       TailRecBody
) extends TailRecBody

/** A back edge from a recursive call site to the loop header. */
private case class BackEdge(blockLabel: String, argValues: List[String])

private def compileTailRecursiveLambda(
  bnd:         Bnd,
  lambda:      Lambda,
  state:       CodeGenState,
  returnType:  String,
  paramTypes:  List[String],
  emittedName: String,
  body:        TailRecBody,
  linkage:     String = ""
): Either[CodeGenError, CodeGenState] =
  val nonVoidIndices          = paramTypes.indices.filter(i => paramTypes(i) != "void").toList
  val filteredParamsWithTypes = nonVoidIndices.map(i => (lambda.params(i), paramTypes(i)))
  val filteredParams          = filteredParamsWithTypes.map(_._1)
  val filteredParamTypes      = filteredParamsWithTypes.map(_._2)
  val paramDecls              = formatParamDecls(filteredParamsWithTypes, state.resolvables)

  val attrGroup    = if bnd.meta.exists(_.inlineHint) then "#1" else "#0"
  val functionDecl = s"define $linkage$returnType @$emittedName($paramDecls) $attrGroup {"
  val loopHeader   = "loop.header"

  val baseState       = state.emit(functionDecl).emit("entry:")
  val stateAfterEntry = baseState.emit(s"  br label %$loopHeader")
  val headerState     = stateAfterEntry.emit(s"$loopHeader:")

  val paramCount = filteredParams.size
  val phiStart   = paramCount
  val phiRegs    = filteredParams.indices.map(i => phiStart + i).toList

  // Emit placeholder phi lines — replaced after collecting all back edges
  val phiPlaceholders = phiRegs.zip(filteredParamTypes).map { case (phiReg, llvmType) =>
    s"  %$phiReg = phi $llvmType __PHI_PLACEHOLDER_$phiReg"
  }

  val paramScope = filteredParams
    .zip(phiRegs)
    .map { case (param, reg) =>
      val mmlType = param.typeAsc.flatMap(getMmlTypeName).getOrElse("Unknown")
      param.name -> ScopeEntry(reg, mmlType)
    }
    .toMap

  val stateAfterPhi = headerState.emitAll(phiPlaceholders).withRegister(phiStart + paramCount)

  compileTailRecBody(
    body,
    stateAfterPhi,
    paramScope,
    returnType,
    loopHeader,
    loopHeader,
    nonVoidIndices
  ).map { case (stateAfterBody, backEdges) =>
    val finalState = stateAfterBody.emit("}").emit("")
    // Build phi replacements from collected back edges
    val replacements = phiRegs.zipWithIndex.map { case (phiReg, paramIdx) =>
      val entryValue  = s"%$paramIdx"
      val backEntries = backEdges.map(e => s"[ ${e.argValues(paramIdx)}, %${e.blockLabel} ]")
      val allEntries  = s"[ $entryValue, %entry ]" :: backEntries
      s"__PHI_PLACEHOLDER_$phiReg" -> allEntries.mkString(", ")
    }.toMap
    replacePlaceholders(finalState, replacements)
  }

/** Compile a tail-recursive body tree into LLVM IR.
  *
  * Each leaf either returns (TailRecExit) or jumps back to the loop header (TailRecCall). Branch
  * nodes split into conditional blocks. No merge blocks needed — every path terminates.
  *
  * @return
  *   updated state and list of back edges for phi node construction
  */
private def compileTailRecBody(
  body:           TailRecBody,
  state:          CodeGenState,
  scope:          Map[String, ScopeEntry],
  returnType:     String,
  loopHeader:     String,
  currentBlock:   String,
  nonVoidIndices: List[Int]
): Either[CodeGenError, (CodeGenState, List[BackEdge])] =
  body match
    case TailRecExit(preStmts, exitExpr) =>
      for
        preResult <- compileBoundStatements(preStmts, state, scope)
        (stateAfterPre, scopeAfterPre, _) = preResult
        exitRes <- compileExpr(exitExpr, stateAfterPre, scopeAfterPre)
        returnLine =
          if returnType == "void" then "  ret void"
          else s"  ret $returnType ${exitRes.operandStr}"
      yield (exitRes.state.emit(returnLine), Nil)

    case TailRecCall(preStmts, args) =>
      for
        preResult <- compileBoundStatements(preStmts, state, scope)
        (stateAfterPre, scopeAfterPre, preExitBlock) = preResult
        filteredArgs                                 = nonVoidIndices.map(args(_))
        argsResult <- compileTailRecArgs(
          filteredArgs,
          stateAfterPre,
          scopeAfterPre,
          preExitBlock
        )
        (argValues, stateAfterArgs, lastExitBlock) = argsResult
      yield
        val backBlock   = lastExitBlock.orElse(preExitBlock).getOrElse(currentBlock)
        val stateWithBr = stateAfterArgs.emit(s"  br label %$loopHeader")
        (stateWithBr, List(BackEdge(backBlock, argValues)))

    case TailRecBranch(preStmts, condition, ifTrue, ifFalse) =>
      for
        preResult <- compileBoundStatements(preStmts, state, scope)
        (stateAfterPre, scopeAfterPre, _) = preResult
        condRes <- compileExpr(condition, stateAfterPre, scopeAfterPre)
        branchResult <- compileBranchCondition(condition, condRes)
        (stateAfterCond, branchCond) = branchResult
        uid                          = stateAfterCond.nextRegister
        stateWithUid                 = stateAfterCond.withRegister(uid + 1)
        trueLabel                    = s"tailrec.then.$uid"
        falseLabel                   = s"tailrec.else.$uid"
        stateWithBr = stateWithUid.emit(
          s"  br i1 $branchCond, label %$trueLabel, label %$falseLabel"
        )
        trueResult <- compileTailRecBody(
          ifTrue,
          stateWithBr.emit(s"$trueLabel:"),
          scopeAfterPre,
          returnType,
          loopHeader,
          trueLabel,
          nonVoidIndices
        )
        (stateAfterTrue, trueEdges) = trueResult
        falseResult <- compileTailRecBody(
          ifFalse,
          stateAfterTrue.emit(s"$falseLabel:"),
          scopeAfterPre,
          returnType,
          loopHeader,
          falseLabel,
          nonVoidIndices
        )
        (stateAfterFalse, falseEdges) = falseResult
      yield (stateAfterFalse, trueEdges ++ falseEdges)

private def compileBranchCondition(
  cond:    Expr,
  condRes: CompileResult
): Either[CodeGenError, (CodeGenState, String)] =
  val condOp = condRes.operandStr
  cond.typeSpec match
    case Some(typeSpec) =>
      getLlvmType(typeSpec, condRes.state).map { llvmType =>
        if llvmType == "i1" then (condRes.state, condOp)
        else
          val compareReg   = condRes.state.nextRegister
          val compareState = condRes.state.withRegister(compareReg + 1)
          val stateAfterCompare =
            compareState.emit(s"  %$compareReg = icmp ne $llvmType $condOp, 0")
          (stateAfterCompare, s"%$compareReg")
      }
    case None =>
      Left(
        CodeGenError(
          "Missing type information for tail-recursive condition",
          Some(cond)
        )
      )

private def compileTailRecArgs(
  args:             List[Expr],
  state:            CodeGenState,
  functionScope:    Map[String, ScopeEntry],
  initialExitBlock: Option[String]
): Either[CodeGenError, (List[String], CodeGenState, Option[String])] =
  args.foldLeft((List.empty[String], state, initialExitBlock).asRight[CodeGenError]) {
    case (Right((values, currentState, currentExitBlock)), arg) =>
      compileExpr(arg, currentState, functionScope).map { res =>
        val value         = res.operandStr
        val nextExitBlock = res.exitBlock.orElse(currentExitBlock)
        (values :+ value, res.state, nextExitBlock)
      }
    case (Left(err), _) => Left(err)
  }

/** Compile pre-statements, returning updated state, scope, and exit block.
  *
  * The exit block tracks which block we're in after compiling all statements. This is important for
  * tail recursion when statements contain conditionals that end in merge blocks.
  */
private def compileBoundStatements(
  statements:    List[BoundStatement],
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry]
): Either[CodeGenError, (CodeGenState, Map[String, ScopeEntry], Option[String])] =
  statements.foldLeft((state, functionScope, Option.empty[String]).asRight[CodeGenError]) {
    case (Right((currentState, currentScope, prevExitBlock)), BoundStatement(bindingName, expr)) =>
      compileExpr(expr, currentState, currentScope).flatMap { res =>
        // Preserve exit block across statements (like compileTailRecArgs does)
        val newExitBlock = res.exitBlock.orElse(prevExitBlock)
        bindingName match
          case Some(name) =>
            val entry = ScopeEntry(res.register, res.typeName, res.isLiteral, res.literalValue)
            Right((res.state, currentScope + (name -> entry), newExitBlock))
          case None =>
            // Side-effect only: discard result
            Right((res.state, currentScope, newExitBlock))
      }
    case (Left(err), _) => Left(err)
  }

/** Map MML type name to LLVM type for literal materialization. */
private def mmlTypeNameToLlvm(typeName: String): Either[CodeGenError, String] =
  typeName match
    case "Bool" => Right("i1")
    case "Int64" | "Int" | "SizeT" => Right("i64")
    case "Int32" => Right("i32")
    case "Int16" => Right("i16")
    case "Int8" | "Byte" | "Word" | "Char" => Right("i8")
    case "Float" => Right("float")
    case "Double" => Right("double")
    case _ => Left(CodeGenError(s"Unknown MML type name '$typeName'"))

private def replacePlaceholders(
  state:        CodeGenState,
  replacements: Map[String, String]
): CodeGenState =
  val updatedOutput = state.output.map { line =>
    replacements.foldLeft(line) { case (acc, (placeholder, value)) =>
      acc.replace(placeholder, value)
    }
  }
  state.copy(output = updatedOutput)

/** Extract a tail-recursive body tree from a lambda. */
private def findTailRecBody(lambda: Lambda, bnd: Bnd): Option[TailRecBody] =
  extractBody(lambda.body, lambda, bnd, Nil)

/** Walk through let-binding/sequence chains, building TailRecBody tree.
  *
  * At a Cond, recurse into both branches. Both-recursive is valid — the key improvement over the
  * old flat model. At a self-call App, produce TailRecCall. Branches without recursive calls are
  * wrapped as TailRecExit by the caller.
  */
private def extractBody(
  expr:          Expr,
  lambda:        Lambda,
  bnd:           Bnd,
  accStatements: List[BoundStatement]
): Option[TailRecBody] =
  expr.terms match
    case List(app: App) =>
      app.fn match
        case innerLambda: Lambda =>
          val binding =
            if isSequenceLambda(innerLambda) then None
            else innerLambda.params.headOption.map(_.name)
          val stmt = BoundStatement(binding, app.arg)
          extractBody(innerLambda.body, lambda, bnd, accStatements :+ stmt)
        case _ =>
          collectAppArgs(app).flatMap { case (ref, args) =>
            if isSelfRef(ref, bnd) && args.size == lambda.params.size then
              TailRecCall(accStatements, args).some
            else None
          }

    case List(cond: Cond) =>
      val trueBody  = extractBody(cond.ifTrue, lambda, bnd, Nil)
      val falseBody = extractBody(cond.ifFalse, lambda, bnd, Nil)
      (trueBody, falseBody) match
        case (Some(tb), Some(fb)) =>
          TailRecBranch(accStatements, cond.cond, tb, fb).some
        case (Some(tb), None) =>
          TailRecBranch(
            accStatements,
            cond.cond,
            tb,
            TailRecExit(Nil, cond.ifFalse)
          ).some
        case (None, Some(fb)) =>
          TailRecBranch(
            accStatements,
            cond.cond,
            TailRecExit(Nil, cond.ifTrue),
            fb
          ).some
        case (None, None) => None

    case _ => None

/** Detect sequence lambda: single param named __stmt */
private def isSequenceLambda(lambda: Lambda): Boolean =
  lambda.params match
    case List(param) => param.name == "__stmt"
    case _ => false

private def collectAppArgs(app: App): Option[(Ref, List[Expr])] =
  def loop(current: App, acc: List[Expr]): Option[(Ref, List[Expr])] =
    current.fn match
      case ref:  Ref => Some((ref, current.arg :: acc))
      case next: App => loop(next, current.arg :: acc)
      case _ => None
  loop(app, Nil)

private def isSelfRef(ref: Ref, bnd: Bnd): Boolean =
  if ref.qualifier.isDefined then return false
  // Compare by ID if available, otherwise fall back to name
  ref.resolvedId match
    case Some(id) => bnd.id.contains(id)
    case None => ref.name == bnd.name
