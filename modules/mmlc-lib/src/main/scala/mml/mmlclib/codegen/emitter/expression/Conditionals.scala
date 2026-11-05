package mml.mmlclib.codegen.emitter.expression

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenReason,
  CodeGenState,
  CompileResult,
  ScopeEntry,
  getLlvmType
}

/** Type alias for the expression compiler function passed to avoid circular dependency. */
type ExprCompiler =
  (Expr, CodeGenState, Map[String, ScopeEntry]) => Either[CodeGenError, CompileResult]

/** Compiles a conditional expression (if-then-else) to LLVM IR.
  *
  * Generates LLVM basic blocks for then/else branches with a phi node to merge results.
  *
  * @param cond
  *   the conditional expression
  * @param state
  *   the current code generation state
  * @param functionScope
  *   map of local function parameters to their registers
  * @param compileExpr
  *   the expression compiler function (passed to avoid circular dependency)
  */
def compileCond(
  cond:          Cond,
  state:         CodeGenState,
  functionScope: Map[String, ScopeEntry],
  compileExpr:   ExprCompiler
): Either[CodeGenError, CompileResult] =

  for
    condRes <- compileExpr(cond.cond, state, functionScope)

    // Create basic blocks (use condRes.state to avoid label collisions in nested conditionals)
    thenBB  = condRes.state.nextRegister
    elseBB  = thenBB + 1
    mergeBB = elseBB + 1

    // Handle condition based on its type
    condOp = condRes.operandStr

    // Reserve block label slots by advancing register counter past mergeBB
    stateWithReservedLabels = condRes.state.withRegister(mergeBB + 1)

    // Check if condition result is boolean (from boolean operations) or integer
    condition <- compileBranchCondition(
      condRes,
      cond.cond,
      condOp,
      mergeBB,
      stateWithReservedLabels
    )

    (stateAfterCondition, branchCondition) = condition

    // Branch based on condition
    stateAfterBranch = stateAfterCondition.emit(
      s"  br i1 $branchCondition, label %then$thenBB, label %else$elseBB"
    )

    // Then block
    thenState = stateAfterBranch.emit(s"then$thenBB:")
    thenRes <- compileExpr(cond.ifTrue, thenState, functionScope)
    thenValue = thenRes.operandStr
    // Track the actual exit block (may differ from then$thenBB if nested conditional)
    thenExitBlock        = thenRes.exitBlock.getOrElse(s"then$thenBB")
    stateAfterThenBranch = thenRes.state.emit(s"  br label %merge$mergeBB")

    // Else block
    elseState = stateAfterThenBranch.emit(s"else$elseBB:")
    elseRes <- compileExpr(cond.ifFalse, elseState, functionScope)
    elseValue = elseRes.operandStr
    // Track the actual exit block (may differ from else$elseBB if nested conditional)
    elseExitBlock        = elseRes.exitBlock.getOrElse(s"else$elseBB")
    stateAfterElseBranch = elseRes.state.emit(s"  br label %merge$mergeBB")

    // Merge block with phi node (skip phi for void type)
    resultReg = stateAfterElseBranch.nextRegister

    // Get the type from the then branch result (both branches should have same type)
    phiType <- cond.ifTrue.typeSpec match
      case Some(typeSpec) => getLlvmType(typeSpec, stateAfterElseBranch)
      case None =>
        Left(
          CodeGenError(CodeGenReason.MissingConditionType, Some(cond.cond))
        )

    finalState = emitMergeBlock(
      phiType,
      mergeBB,
      resultReg,
      thenValue,
      thenExitBlock,
      elseValue,
      elseExitBlock,
      stateAfterElseBranch
    )

    // For void type, return 0 as dummy register
    actualResultReg = if phiType == "void" then 0 else resultReg
    // Set exitBlock so parent conditionals know where we exit from
    mergeBlockLabel = s"merge$mergeBB"
  yield CompileResult(actualResultReg, finalState, false, thenRes.typeName, Some(mergeBlockLabel))

/** Compiles the branch condition, handling boolean vs integer types. */
private def compileBranchCondition(
  condRes:                 CompileResult,
  condExpr:                Expr,
  condOp:                  String,
  mergeBB:                 Int,
  stateWithReservedLabels: CodeGenState
): Either[CodeGenError, (CodeGenState, String)] =
  if condRes.register > 0 && !condRes.isLiteral then Right((stateWithReservedLabels, condOp))
  else
    for
      typeSpec <- condExpr.typeSpec.toRight(
        CodeGenError(CodeGenReason.MissingConditionType, Some(condExpr))
      )
      llvmType <- getLlvmType(typeSpec, stateWithReservedLabels)
    yield
      val next = stateWithReservedLabels.emit(s"  %$mergeBB = icmp ne $llvmType $condOp, 0")
      (next, s"%$mergeBB")

/** Emits the merge block with optional phi node. */
private def emitMergeBlock(
  phiType:       String,
  mergeBB:       Int,
  resultReg:     Int,
  thenValue:     String,
  thenExitBlock: String,
  elseValue:     String,
  elseExitBlock: String,
  state:         CodeGenState
): CodeGenState =
  if phiType == "void" then
    // Unit/void type - no phi node needed, just merge block
    state.emit(s"merge$mergeBB:")
  else
    // Non-void type - emit phi to merge values from both branches
    // Use actual exit blocks (important for nested conditionals)
    state
      .withRegister(resultReg + 1)
      .emit(s"merge$mergeBB:")
      .emit(
        s"  %$resultReg = phi $phiType [ $thenValue, %$thenExitBlock ], [ $elseValue, %$elseExitBlock ]"
      )
