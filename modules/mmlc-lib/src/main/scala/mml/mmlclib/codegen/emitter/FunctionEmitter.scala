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
  emittedName: String
): Either[CodeGenError, CodeGenState] =
  // Try loopification for tail-recursive functions, fall back to regular codegen if pattern unsupported
  if lambda.meta.exists(_.isTailRecursive) then
    findTailRecPattern(lambda, bnd) match
      case Some(_) =>
        compileTailRecursiveLambda(bnd, lambda, state, returnType, paramTypes, emittedName)
      case None =>
        // Pattern not recognized - emit warning and fall back to regular codegen
        val warning = CompilerWarning.TailRecPatternUnsupported(
          bnd.name,
          "tail recursion pattern not recognized, falling back to standard recursion"
        )
        val stateWithWarning = state.addWarning(warning)
        compileRegularLambda(bnd, lambda, stateWithWarning, returnType, paramTypes, emittedName)
  else compileRegularLambda(bnd, lambda, state, returnType, paramTypes, emittedName)

/** Compiles a regular (non-tail-recursive) lambda to LLVM IR. */
private def compileRegularLambda(
  bnd:         Bnd,
  lambda:      Lambda,
  state:       CodeGenState,
  returnType:  String,
  paramTypes:  List[String],
  emittedName: String
): Either[CodeGenError, CodeGenState] =
  // Filter out Unit params (void) - they can't be passed in LLVM
  // Keep params and types in sync by filtering together
  val filteredParamsWithTypes = lambda.params
    .zip(paramTypes)
    .filter { case (_, typ) => typ != "void" }

  val filteredParams     = filteredParamsWithTypes.map(_._1)
  val filteredParamTypes = filteredParamsWithTypes.map(_._2)

  // Generate function declaration with parameters
  val paramDecls = filteredParamTypes.zipWithIndex
    .map { case (typ, idx) => s"$typ %$idx" }
    .mkString(", ")

  val functionDecl = s"define $returnType @$emittedName($paramDecls) #0 {"
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
      (param.name, (regNum, mmlType))
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
  functionScope: Map[String, (Int, String)]
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
                    case Some((paramReg, _)) =>
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

                        // For heap fields, clone the param before storing
                        val cloneFnOpt = TypeUtils
                          .getTypeName(field.typeSpec)
                          .flatMap(TypeUtils.cloneFnFor(_, stateWithPtr.resolvables))

                        val (valueToStore, stateAfterClone) = cloneFnOpt match
                          case Some(cloneFnName) =>
                            val cloneReg = stateWithPtr.nextRegister
                            val cloneLine = emitCall(
                              Some(cloneReg),
                              Some(fieldLlvmType),
                              cloneFnName,
                              List((fieldLlvmType, s"%$paramReg"))
                            )
                            (s"%$cloneReg", stateWithPtr.withRegister(cloneReg + 1).emit(cloneLine))
                          case None =>
                            (s"%$paramReg", stateWithPtr)

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

/** Exit branch with compound conditions (conjunction).
  *
  * Each condition is a (expr, exitWhenTrue) pair. The exit is taken when ALL conditions match their
  * expected truth value. For simple cases, this is a single-element list. For nested conditionals,
  * this accumulates outer conditions.
  *
  * @param conditions
  *   conjunction of (condition, exitWhenTrue) pairs
  * @param exitExpr
  *   expression to return when exit is taken
  * @param latchPrefix
  *   statements that must be executed before this exit can be checked (for nested cond exits)
  *
  * Example: `List((outer, false), (inner, true))` means exit when outer=false AND inner=true.
  */
private case class ExitBranch(
  conditions:  List[(Expr, Boolean)],
  exitExpr:    Expr,
  latchPrefix: List[BoundStatement] = Nil
)

/** A statement in a tail-recursive pattern, optionally binding a name.
  *
  * For sequence lambdas (__stmt), bindingName is None (side-effect only). For let-bindings,
  * bindingName is Some(name) and the result is bound to that name for use in subsequent code.
  */
private case class BoundStatement(bindingName: Option[String], expr: Expr)

/** Pattern for tail-recursive function loopification.
  *
  * @param preStatements
  *   statements before the condition check (start of each iteration)
  * @param exitBranches
  *   exit conditions with their return expressions
  * @param latchStatements
  *   statements in the recursive branch before the recursive call
  * @param recursiveArgs
  *   arguments to the recursive call
  */
private case class TailRecPattern(
  preStatements:   List[BoundStatement],
  exitBranches:    List[ExitBranch],
  latchStatements: List[BoundStatement],
  recursiveArgs:   List[Expr]
)

private def compileTailRecursiveLambda(
  bnd:         Bnd,
  lambda:      Lambda,
  state:       CodeGenState,
  returnType:  String,
  paramTypes:  List[String],
  emittedName: String
): Either[CodeGenError, CodeGenState] =
  findTailRecPattern(lambda, bnd)
    .toRight(CodeGenError(s"Tail recursion shape not supported for '${bnd.name}'", Some(bnd)))
    .flatMap { pattern =>
      // Filter out Unit params (void) - they can't be passed in LLVM
      val paramsWithTypesAndArgs = lambda.params
        .zip(paramTypes)
        .zip(pattern.recursiveArgs)
        .filter { case ((_, typ), _) => typ != "void" }

      val filteredParams        = paramsWithTypesAndArgs.map { case ((p, _), _) => p }
      val filteredParamTypes    = paramsWithTypesAndArgs.map { case ((_, t), _) => t }
      val filteredRecursiveArgs = paramsWithTypesAndArgs.map { case (_, arg) => arg }

      if pattern.recursiveArgs.size != lambda.params.size then
        Left(
          CodeGenError(
            s"Tail recursion argument count mismatch for '${bnd.name}'",
            Some(bnd)
          )
        )
      else
        val paramDecls = filteredParamTypes.zipWithIndex
          .map { case (typ, idx) => s"$typ %$idx" }
          .mkString(", ")

        val functionDecl = s"define $returnType @$emittedName($paramDecls) {"
        val loopHeader   = "loop.header"
        val loopLatch    = "loop.latch"

        val baseState       = state.emit(functionDecl).emit("entry:")
        val stateAfterEntry = baseState.emit(s"  br label %$loopHeader")
        val headerState     = stateAfterEntry.emit(s"$loopHeader:")

        val paramCount            = filteredParams.size
        val phiStart              = paramCount
        val phiRegs               = filteredParams.indices.map(i => phiStart + i).toList
        val backBlockPlaceholder  = "__mml_tailrec_back_block"
        val backValuePlaceholders = filteredParams.indices.map(i => s"__mml_tailrec_back_$i")

        val phiLines =
          phiRegs.zip(filteredParamTypes).zipWithIndex.map { case ((phiReg, llvmType), idx) =>
            val entryValue = s"%$idx"
            val backValue  = backValuePlaceholders(idx)
            s"  %$phiReg = phi $llvmType [ $entryValue, %entry ], " +
              s"[ $backValue, %$backBlockPlaceholder ]"
          }

        val paramScope = filteredParams
          .zip(phiRegs)
          .map { case (param, reg) =>
            val mmlType = param.typeAsc.flatMap(getMmlTypeName).getOrElse("Unknown")
            param.name -> (reg, mmlType)
          }
          .toMap

        val stateAfterPhi = headerState.emitAll(phiLines).withRegister(phiStart + paramCount)

        for
          // Compile pre-statements with let-bindings added to scope
          preResult <- compileBoundStatements(pattern.preStatements, stateAfterPhi, paramScope)
          (stateAfterPreStatements, scopeAfterPre, _) = preResult
          // Compile exit branch conditions (may include latchPrefix for nested cond exits)
          checksResult <- compileExitBranchChecks(
            pattern.exitBranches,
            stateAfterPreStatements,
            scopeAfterPre,
            loopLatch
          )
          (stateAfterChecks, scopeAfterExitPrefixes) = checksResult
          // Emit latch block with remaining latch statements
          // Use scopeAfterExitPrefixes which includes bindings from exit latchPrefix
          latchState = stateAfterChecks.emit(s"$loopLatch:")
          latchResult <- compileBoundStatements(
            pattern.latchStatements,
            latchState,
            scopeAfterExitPrefixes
          )
          (stateAfterLatchStmts, scopeAfterLatch, latchExitBlock) = latchResult
          // Compile recursive args using full scope
          // Pass latchExitBlock so we track if latch statements ended in a different block
          argsResult <- compileTailRecArgs(
            filteredRecursiveArgs,
            stateAfterLatchStmts,
            scopeAfterLatch,
            latchExitBlock
          )
          (argValues, stateAfterArgs, lastExitBlock) = argsResult
          backEdgeBlock                              = lastExitBlock.getOrElse(loopLatch)
          stateAfterBack = stateAfterArgs.emit(s"  br label %$loopHeader")
          // Emit exit blocks (use scopeAfterExitPrefixes for exit expressions)
          stateAfterExits <- compileExitBlocks(
            pattern.exitBranches,
            stateAfterBack,
            scopeAfterExitPrefixes,
            returnType
          )
          finalState = stateAfterExits.emit("}").emit("")
        yield
          val replacements = backValuePlaceholders
            .zip(argValues)
            .toMap
            .updated(backBlockPlaceholder, backEdgeBlock)
          replacePlaceholders(finalState, replacements)
    }

/** Compile condition checks for exit branches with compound conditions.
  *
  * For compound conditions, generates chained checks: exit.check.{exitIdx}.{condIdx} Each condition
  * in the chain must match for the exit to be taken.
  *
  * Exits with non-empty latchPrefix have their prefix compiled before checking their conditions.
  * This ensures nested conditional exits have the required scope.
  *
  * @return
  *   (updated state, scope after all latch prefixes)
  */
private def compileExitBranchChecks(
  exitBranches: List[ExitBranch],
  state:        CodeGenState,
  paramScope:   Map[String, (Int, String)],
  latchLabel:   String
): Either[CodeGenError, (CodeGenState, Map[String, (Int, String)])] =
  val numBranches = exitBranches.size
  exitBranches.zipWithIndex.foldLeft((state, paramScope).asRight[CodeGenError]) {
    case (Right((currentState, currentScope)), (branch, exitIdx)) =>
      val exitLabel = s"loop.exit.$exitIdx"
      val nextExitOrLatch =
        if exitIdx == numBranches - 1 then latchLabel else s"loop.check.${exitIdx + 1}"

      // If this exit has a latchPrefix, compile it first to get required scope
      val prefixResult =
        if branch.latchPrefix.isEmpty then Right((currentState, currentScope, Option.empty[String]))
        else compileBoundStatements(branch.latchPrefix, currentState, currentScope)

      prefixResult.flatMap { case (stateAfterPrefix, scopeAfterPrefix, _) =>
        compileCompoundConditions(
          branch.conditions,
          stateAfterPrefix,
          scopeAfterPrefix,
          exitIdx,
          condIdx     = 0,
          exitLabel   = exitLabel,
          fallthrough = nextExitOrLatch
        ).map { stateAfterConds =>
          // Emit the next check label if not the last exit
          val finalState =
            if exitIdx < numBranches - 1 then stateAfterConds.emit(s"loop.check.${exitIdx + 1}:")
            else stateAfterConds
          // Pass forward the extended scope (includes latchPrefix bindings)
          (finalState, scopeAfterPrefix)
        }
      }

    case (Left(err), _) => Left(err)
  }

/** Compile a chain of conditions for a single exit branch.
  *
  * Each condition must match its expected truth value for the exit to be taken. If any condition
  * doesn't match, we fall through to the next exit check or latch.
  */
private def compileCompoundConditions(
  conditions:  List[(Expr, Boolean)],
  state:       CodeGenState,
  paramScope:  Map[String, (Int, String)],
  exitIdx:     Int,
  condIdx:     Int,
  exitLabel:   String,
  fallthrough: String
): Either[CodeGenError, CodeGenState] =
  conditions match
    case Nil =>
      // No conditions left - shouldn't happen, but if it does, just branch to exit
      Right(state.emit(s"  br label %$exitLabel"))

    case (cond, exitWhenTrue) :: Nil =>
      // Last condition - branch to exit or fallthrough
      for
        condRes <- compileExpr(cond, state, paramScope)
        result <- compileBranchCondition(cond, condRes)
        (stateAfterCond, branchCond) = result
        (trueLabel, falseLabel) =
          if exitWhenTrue then (exitLabel, fallthrough) else (fallthrough, exitLabel)
      yield stateAfterCond.emit(s"  br i1 $branchCond, label %$trueLabel, label %$falseLabel")

    case (cond, exitWhenTrue) :: rest =>
      // More conditions follow - branch to next check or fallthrough
      val nextCheckLabel = s"exit.check.$exitIdx.${condIdx + 1}"
      for
        condRes <- compileExpr(cond, state, paramScope)
        result <- compileBranchCondition(cond, condRes)
        (stateAfterCond, branchCond) = result
        // If condition matches expected, continue to next check; otherwise fallthrough
        (trueLabel, falseLabel) =
          if exitWhenTrue then (nextCheckLabel, fallthrough) else (fallthrough, nextCheckLabel)
        stateWithBranch = stateAfterCond.emit(
          s"  br i1 $branchCond, label %$trueLabel, label %$falseLabel"
        )
        stateWithLabel = stateWithBranch.emit(s"$nextCheckLabel:")
        finalState <- compileCompoundConditions(
          rest,
          stateWithLabel,
          paramScope,
          exitIdx,
          condIdx + 1,
          exitLabel,
          fallthrough
        )
      yield finalState

/** Compile exit blocks - each evaluates its exit expression and returns. */
private def compileExitBlocks(
  exitBranches: List[ExitBranch],
  state:        CodeGenState,
  paramScope:   Map[String, (Int, String)],
  returnType:   String
): Either[CodeGenError, CodeGenState] =
  exitBranches.zipWithIndex.foldLeft(state.asRight[CodeGenError]) {
    case (Right(currentState), (branch, idx)) =>
      val blockState = currentState.emit(s"loop.exit.$idx:")
      compileExpr(branch.exitExpr, blockState, paramScope).map { exitRes =>
        val returnLine =
          if returnType == "void" then "  ret void"
          else
            val returnOp = exitRes.operandStr
            s"  ret $returnType $returnOp"
        exitRes.state.emit(returnLine)
      }
    case (Left(err), _) => Left(err)
  }

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
  functionScope:    Map[String, (Int, String)],
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
  functionScope: Map[String, (Int, String)]
): Either[CodeGenError, (CodeGenState, Map[String, (Int, String)], Option[String])] =
  statements.foldLeft((state, functionScope, Option.empty[String]).asRight[CodeGenError]) {
    case (Right((currentState, currentScope, prevExitBlock)), BoundStatement(bindingName, expr)) =>
      compileExpr(expr, currentState, currentScope).flatMap { res =>
        // Preserve exit block across statements (like compileTailRecArgs does)
        val newExitBlock = res.exitBlock.orElse(prevExitBlock)
        bindingName match
          case Some(name) =>
            // Let binding: add the result to scope
            // If result is a literal, materialize it into a register
            if res.isLiteral then
              val r = res.state.nextRegister
              mmlTypeNameToLlvm(res.typeName).map { llvmType =>
                val s = res.state
                  .emit(s"  %$r = add $llvmType 0, ${res.register}")
                  .withRegister(r + 1)
                (s, currentScope + (name -> (r, res.typeName)), newExitBlock)
              }
            else
              Right(
                (res.state, currentScope + (name -> (res.register, res.typeName)), newExitBlock)
              )
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

private def findTailRecPattern(lambda: Lambda, bnd: Bnd): Option[TailRecPattern] =
  findTerminalCondWithPreStatements(lambda.body, Nil).flatMap { case (preStatements, cond) =>
    extractCondChainPattern(cond, lambda, bnd, Nil).map { case (exits, latchStmts, args) =>
      TailRecPattern(preStatements, exits, latchStmts, args)
    }
  }

/** Extract exit branches, latch statements, and recursive args from a Cond chain.
  *
  * @param outerConditions
  *   conditions from enclosing conditionals (for nested latch conditionals)
  */
private def extractCondChainPattern(
  cond:            Cond,
  lambda:          Lambda,
  bnd:             Bnd,
  accExits:        List[ExitBranch],
  outerConditions: List[(Expr, Boolean)] = Nil
): Option[(List[ExitBranch], List[BoundStatement], List[Expr])] =
  val trueResult =
    extractRecursiveBranch(cond.ifTrue, lambda, bnd, outerConditions :+ (cond.cond, true))
  val falseResult =
    extractRecursiveBranch(cond.ifFalse, lambda, bnd, outerConditions :+ (cond.cond, false))

  (trueResult, falseResult) match
    // "if cond then (stmts; recurse) else base" - exit when cond is FALSE
    case (Some((nestedExits, latchStmts, args)), None) =>
      val exitConds = outerConditions :+ (cond.cond, false)
      val exit      = ExitBranch(exitConds, cond.ifFalse)
      Some((accExits ++ nestedExits :+ exit, latchStmts, args))

    // "if cond then base else (stmts; recurse)" - exit when cond is TRUE
    case (None, Some((nestedExits, latchStmts, args))) =>
      val exitConds = outerConditions :+ (cond.cond, true)
      val exit      = ExitBranch(exitConds, cond.ifTrue)
      Some((accExits ++ nestedExits :+ exit, latchStmts, args))

    // Neither branch recursive - check for elif (nested Cond in ifFalse)
    case (None, None) =>
      cond.ifFalse.terms match
        case List(nestedCond: Cond) =>
          val exitConds = outerConditions :+ (cond.cond, true)
          val exit      = ExitBranch(exitConds, cond.ifTrue)
          extractCondChainPattern(nestedCond, lambda, bnd, accExits :+ exit, outerConditions)
        case _ => None

    // Both branches recursive - invalid
    case (Some(_), Some(_)) => None

/** Extract latch statements and recursive args from a branch that contains a recursive call.
  *
  * Returns (nestedExits, latchStatements, recursiveArgs) where nestedExits are any additional exit
  * branches from nested conditionals within the latch.
  */
private def extractRecursiveBranch(
  expr:            Expr,
  lambda:          Lambda,
  bnd:             Bnd,
  outerConditions: List[(Expr, Boolean)]
): Option[(List[ExitBranch], List[BoundStatement], List[Expr])] =
  extractSelfCallWithPreStatements(expr, lambda, bnd, Nil, outerConditions)

/** Traverse through lambdas to find self-call, collecting pre-statements with bindings.
  *
  * Also handles nested conditionals in the latch - these produce additional exit branches.
  *
  * @return
  *   (nestedExits, latchStatements, recursiveArgs)
  */
private def extractSelfCallWithPreStatements(
  expr:            Expr,
  lambda:          Lambda,
  bnd:             Bnd,
  accStatements:   List[BoundStatement],
  outerConditions: List[(Expr, Boolean)]
): Option[(List[ExitBranch], List[BoundStatement], List[Expr])] =
  expr.terms match
    case List(app: App) =>
      app.fn match
        case innerLambda: Lambda =>
          // Sequence lambda: no binding. Let-binding lambda: bind the parameter name.
          val binding =
            if isSequenceLambda(innerLambda) then None
            else innerLambda.params.headOption.map(_.name)
          val stmt = BoundStatement(binding, app.arg)
          extractSelfCallWithPreStatements(
            innerLambda.body,
            lambda,
            bnd,
            accStatements :+ stmt,
            outerConditions
          )
        case _ =>
          collectAppArgs(app).flatMap { case (ref, args) =>
            if isSelfRef(ref, bnd) && args.size == lambda.params.size then
              Some((Nil, accStatements, args)) // No nested exits, direct self-call
            else None
          }

    // Handle nested conditional in latch: recurse into it for nested pattern extraction
    case List(nestedCond: Cond) =>
      extractNestedLatchCond(nestedCond, lambda, bnd, accStatements, outerConditions)

    case _ => None

/** Extract pattern from a nested conditional within the latch.
  *
  * This handles cases like: `if inner_cond then early_exit else (more_stmts; recurse)` where the
  * nested conditional creates an additional exit branch.
  *
  * The key insight is that exits from nested conditionals need the latch statements that precede
  * the nested conditional to be in scope. So we populate `latchPrefix` with `accStatements`.
  */
private def extractNestedLatchCond(
  cond:            Cond,
  lambda:          Lambda,
  bnd:             Bnd,
  accStatements:   List[BoundStatement],
  outerConditions: List[(Expr, Boolean)]
): Option[(List[ExitBranch], List[BoundStatement], List[Expr])] =
  // Check which branch has the recursive call
  // Pass accStatements through so innermost exits get the full prefix
  val trueResult =
    extractSelfCallWithPreStatements(
      cond.ifTrue,
      lambda,
      bnd,
      accStatements,
      outerConditions :+ (cond.cond, true)
    )
  val falseResult =
    extractSelfCallWithPreStatements(
      cond.ifFalse,
      lambda,
      bnd,
      accStatements,
      outerConditions :+ (cond.cond, false)
    )

  (trueResult, falseResult) match
    // Recursive in true branch, exit in false branch
    case (Some((nestedExits, innerLatchStmts, args)), None) =>
      val exitConds = outerConditions :+ (cond.cond, false)
      // Only add latchPrefix if no nested exits (innermost exit gets the prefix)
      // Nested exits already have accStatements from the recursive call
      val latchPrefix = if nestedExits.isEmpty then accStatements else Nil
      val exit        = ExitBranch(exitConds, cond.ifFalse, latchPrefix = latchPrefix)
      Some((nestedExits :+ exit, innerLatchStmts, args))

    // Recursive in false branch, exit in true branch
    case (None, Some((nestedExits, innerLatchStmts, args))) =>
      val exitConds = outerConditions :+ (cond.cond, true)
      // Only add latchPrefix if no nested exits (innermost exit gets the prefix)
      // Nested exits already have accStatements from the recursive call
      val latchPrefix = if nestedExits.isEmpty then accStatements else Nil
      val exit        = ExitBranch(exitConds, cond.ifTrue, latchPrefix = latchPrefix)
      Some((nestedExits :+ exit, innerLatchStmts, args))

    // Both branches recursive - invalid
    case (Some(_), Some(_)) => None

    // Neither branch recursive - can't extract pattern
    case (None, None) => None

/** Traverse through lambdas to find terminal Cond, collecting pre-statements with bindings. */
private def findTerminalCondWithPreStatements(
  expr:          Expr,
  accStatements: List[BoundStatement]
): Option[(List[BoundStatement], Cond)] =
  expr.terms match
    case List(cond: Cond) =>
      Some((accStatements.reverse, cond))

    case List(app: App) =>
      app.fn match
        case innerLambda: Lambda =>
          // Sequence lambda: no binding. Let-binding lambda: bind the parameter name.
          val binding =
            if isSequenceLambda(innerLambda) then None
            else innerLambda.params.headOption.map(_.name)
          val stmt = BoundStatement(binding, app.arg)
          findTerminalCondWithPreStatements(innerLambda.body, stmt :: accStatements)
        case _ => None

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
