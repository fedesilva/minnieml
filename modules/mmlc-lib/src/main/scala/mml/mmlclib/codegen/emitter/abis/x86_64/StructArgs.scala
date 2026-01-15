package mml.mmlclib.codegen.emitter.abis.x86_64

import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.abis.{StructLoweringRule, shouldSplitX86_64}
import mml.mmlclib.codegen.emitter.{CodeGenState, emitExtractValue}

object SplitSmallStructs extends StructLoweringRule:
  val targetAbi: TargetAbi = TargetAbi.X86_64

  def lowerParamTypes(
    structType: String,
    fieldTypes: List[String]
  ): Option[List[String]] =
    if shouldSplitX86_64(fieldTypes) then Some(fieldTypes) else None

  def lowerArgs(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): Option[(List[(String, String)], CodeGenState)] =
    if shouldSplitX86_64(fieldTypes) then
      val (fieldArgs, finalState) =
        splitStructArgs(value, structType, fieldTypes, state)
      Some((fieldArgs, finalState))
    else None

  private def splitStructArgs(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): (List[(String, String)], CodeGenState) =
    fieldTypes.zipWithIndex.foldLeft((List.empty[(String, String)], state)) {
      case ((fieldAcc, st), (fieldType, idx)) =>
        val reg  = st.nextRegister
        val line = emitExtractValue(reg, structType, value, idx)
        (fieldAcc :+ (s"%$reg", fieldType), st.withRegister(reg + 1).emit(line))
    }
