package mml.mmlclib.codegen.emitter.abis.x86_64

import mml.mmlclib.codegen.emitter.CodeGenState
import mml.mmlclib.codegen.emitter.abis.StructLoweringRule
import mml.mmlclib.codegen.emitter.abis.StructLoweringUtils.shouldSplitStruct

/** On x86_64, structs > 16 bytes are passed via pointer with byval attribute. */
object LargeStructByval extends StructLoweringRule:

  def lowerParamTypes(
    structType: String,
    fieldTypes: List[String]
  ): Option[List[String]] =
    if !shouldSplitStruct(fieldTypes) then
      // Large struct - use byval pointer
      Some(List(s"ptr byval($structType) align 8"))
    else None

  def lowerArgs(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): Option[(List[(String, String)], CodeGenState)] =
    if !shouldSplitStruct(fieldTypes) then
      // Allocate struct on stack and pass pointer
      val allocReg  = state.nextRegister
      val allocLine = s"  %$allocReg = alloca $structType, align 8"
      val storeReg  = state.nextRegister + 1
      val storeLine = s"  store $structType $value, ptr %$allocReg, align 8"
      val finalState = state
        .withRegister(storeReg)
        .emit(allocLine)
        .emit(storeLine)
      Some((List((s"%$allocReg", s"ptr byval($structType) align 8")), finalState))
    else None
