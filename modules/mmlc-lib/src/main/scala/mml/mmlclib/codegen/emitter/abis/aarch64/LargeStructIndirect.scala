package mml.mmlclib.codegen.emitter.abis.aarch64

import mml.mmlclib.codegen.emitter.CodeGenState
import mml.mmlclib.codegen.emitter.abis.StructLoweringRule
import mml.mmlclib.codegen.emitter.abis.StructLoweringUtils.isLargeStructAarch64

/** On AArch64, structs >16 bytes are passed indirectly via pointer (AAPCS64). */
object LargeStructIndirect extends StructLoweringRule:

  def lowerParamTypes(
    structType: String,
    fieldTypes: List[String]
  ): Option[List[String]] =
    if isLargeStructAarch64(fieldTypes) then Some(List("ptr"))
    else None

  def lowerArgs(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): Option[(List[(String, String)], CodeGenState)] =
    if isLargeStructAarch64(fieldTypes) then
      val allocReg  = state.nextRegister
      val allocLine = s"  %$allocReg = alloca $structType, align 8"
      val storeReg  = state.nextRegister + 1
      val storeLine = s"  store $structType $value, ptr %$allocReg, align 8"
      val finalState = state
        .withRegister(storeReg)
        .emit(allocLine)
        .emit(storeLine)
      Some((List((s"%$allocReg", "ptr")), finalState))
    else None
