package mml.mmlclib.codegen.emitter.abis

import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.abis.aarch64.AArch64AbiStrategy
import mml.mmlclib.codegen.emitter.abis.x86_64.X86_64AbiStrategy
import mml.mmlclib.codegen.emitter.{CodeGenState, sizeOfLlvmType}

trait StructLoweringRule:
  def lowerParamTypes(
    structType: String,
    fieldTypes: List[String]
  ): Option[List[String]]

  def lowerArgs(
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): Option[(List[(String, String)], CodeGenState)]

trait AbiStrategy:
  def name: String

  def lowerParamTypes(paramTypes: List[String], state: CodeGenState): List[String]

  def lowerArgs(
    args:  List[(String, String)],
    state: CodeGenState
  ): (List[(String, String)], CodeGenState)

  def needsSret(returnType: String, state: CodeGenState): Boolean

  def lowerReturnType(
    returnType: String,
    state:      CodeGenState
  ): (String, Option[String]) =
    if needsSret(returnType, state) then ("void", Some(s"ptr sret($returnType) align 8"))
    else (returnType, None)

  def emitSretCall(
    fnName:     String,
    returnType: String,
    args:       List[(String, String)],
    state:      CodeGenState,
    emitCallFn: (
      Option[Int],
      Option[String],
      String,
      List[(String, String)],
      Option[String],
      Option[String]
    ) => String,
    aliasScope: Option[String],
    noalias:    Option[String]
  ): (Int, CodeGenState) =
    val allocReg     = state.nextRegister
    val allocLine    = s"  %$allocReg = alloca $returnType, align 8"
    val sretArg      = (s"ptr sret($returnType) align 8", s"%$allocReg")
    val argsWithSret = sretArg :: args
    val callLine     = emitCallFn(None, Some("void"), fnName, argsWithSret, aliasScope, noalias)
    val loadReg      = allocReg + 1
    val loadLine     = s"  %$loadReg = load $returnType, ptr %$allocReg, align 8"
    val finalState = state
      .withRegister(loadReg + 1)
      .emit(allocLine)
      .emit(callLine)
      .emit(loadLine)
    (loadReg, finalState)

object AbiStrategy:
  def forTarget(targetAbi: TargetAbi): AbiStrategy =
    targetAbi match
      case TargetAbi.X86_64 => X86_64AbiStrategy
      case TargetAbi.AArch64 => AArch64AbiStrategy
      case TargetAbi.Default => DefaultAbiStrategy

object StructLoweringUtils:
  def isPointerType(llvmType: String): Boolean =
    llvmType == "ptr" || llvmType.endsWith("*")

  def shouldSplitStruct(fieldTypes: List[String]): Boolean =
    fieldTypes.map(sizeOfLlvmType).sum <= 16

  def isPackableAarch64(fieldTypes: List[String]): Boolean =
    fieldTypes.size == 2 && fieldTypes.forall(t => t == "i64" || isPointerType(t))

  def isHfaAarch64(fieldTypes: List[String]): Boolean =
    fieldTypes.nonEmpty &&
      fieldTypes.length <= 4 &&
      fieldTypes.forall(t => t == "float" || t == "double")

  def isLargeStructAarch64(fieldTypes: List[String]): Boolean =
    fieldTypes.map(sizeOfLlvmType).sum > 16 &&
      !isPackableAarch64(fieldTypes) &&
      !isHfaAarch64(fieldTypes)

  def firstParamLowering(
    rules:      List[StructLoweringRule],
    structType: String,
    fieldTypes: List[String]
  ): Option[List[String]] =
    rules.view.flatMap(_.lowerParamTypes(structType, fieldTypes)).headOption

  def firstArgLowering(
    rules:      List[StructLoweringRule],
    value:      String,
    structType: String,
    fieldTypes: List[String],
    state:      CodeGenState
  ): Option[(List[(String, String)], CodeGenState)] =
    rules.view.flatMap(_.lowerArgs(value, structType, fieldTypes, state)).headOption

object DefaultAbiStrategy extends AbiStrategy:
  val name: String = "default"

  def lowerParamTypes(paramTypes: List[String], state: CodeGenState): List[String] =
    paramTypes

  def lowerArgs(
    args:  List[(String, String)],
    state: CodeGenState
  ): (List[(String, String)], CodeGenState) =
    (args, state)

  def needsSret(returnType: String, state: CodeGenState): Boolean =
    false
