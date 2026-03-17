package mml.mmlclib.codegen.emitter.abis.aarch64

import mml.mmlclib.codegen.emitter.abis.StructLoweringRule
import mml.mmlclib.codegen.emitter.abis.StructLoweringUtils.{isPackableAarch64, isPointerType}
import mml.mmlclib.codegen.emitter.{CodeGenState, emitExtractValue, emitInsertValue, emitPtrToInt}

object PackTwoI64Structs extends StructLoweringRule:

  def lowerParamTypes(
    structType: String,
    fieldTypes: List[String]
  ): Option[List[String]] =
    if isPackableAarch64(fieldTypes) then Some(List("[2 x i64]")) else None

  def lowerArgs(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): Option[(List[(String, String)], CodeGenState)] =
    if isPackableAarch64(fieldTypes) then
      val (packedValue, packedType, finalState) =
        packStructArg(value, structType, fieldTypes, state)
      Some((List((packedValue, packedType)), finalState))
    else None

  private def packStructArg(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): (String, String, CodeGenState) =
    val (fieldValues, stateAfterExtract) =
      fieldTypes.zipWithIndex.foldLeft((List.empty[String], state)) {
        case ((acc, st), (fieldType, idx)) =>
          val extractReg  = st.nextRegister
          val extractLine = emitExtractValue(extractReg, structType, value, idx)
          val extractedState =
            st.withRegister(extractReg + 1).emit(extractLine)

          if isPointerType(fieldType) then
            val castReg  = extractedState.nextRegister
            val castLine = emitPtrToInt(castReg, fieldType, s"%$extractReg", "i64")
            val castState =
              extractedState.withRegister(castReg + 1).emit(castLine)
            (acc :+ s"%$castReg", castState)
          else (acc :+ s"%$extractReg", extractedState)
      }

    fieldValues match
      case first :: second :: Nil =>
        val packType = "[2 x i64]"
        val insert0  = stateAfterExtract.nextRegister
        val insert0Ln =
          emitInsertValue(insert0, packType, "undef", "i64", first, 0)
        val state0  = stateAfterExtract.withRegister(insert0 + 1).emit(insert0Ln)
        val insert1 = state0.nextRegister
        val insert1Ln =
          emitInsertValue(insert1, packType, s"%$insert0", "i64", second, 1)
        val finalState = state0.withRegister(insert1 + 1).emit(insert1Ln)
        (s"%$insert1", packType, finalState)
      case _ =>
        (value, structType, stateAfterExtract)
