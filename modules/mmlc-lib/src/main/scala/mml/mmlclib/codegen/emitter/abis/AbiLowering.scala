package mml.mmlclib.codegen.emitter.abis

import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.abis.aarch64.PackTwoI64Structs
import mml.mmlclib.codegen.emitter.abis.x86_64.SplitSmallStructs
import mml.mmlclib.codegen.emitter.{CodeGenState, getStructFieldTypes}

trait StructLoweringRule:
  def targetAbi: TargetAbi

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

private val rules: List[StructLoweringRule] =
  List(SplitSmallStructs, PackTwoI64Structs)

private def rulesFor(state: CodeGenState): List[StructLoweringRule] =
  rules.filter(_.targetAbi == state.targetAbi)

private def sizeOfLlvmType(llvmType: String): Int =
  llvmType match
    case "i8" => 1
    case "i16" => 2
    case "i32" | "float" => 4
    case "i64" | "double" | "ptr" => 8
    case t if t.endsWith("*") => 8
    case _ => 8

private def firstParamLowering(
  rules:      List[StructLoweringRule],
  structType: String,
  fieldTypes: List[String]
): Option[List[String]] =
  rules.view.flatMap(_.lowerParamTypes(structType, fieldTypes)).headOption

private def firstArgLowering(
  rules:      List[StructLoweringRule],
  value:      String,
  structType: String,
  fieldTypes: List[String],
  state:      CodeGenState
): Option[(List[(String, String)], CodeGenState)] =
  rules.view.flatMap(_.lowerArgs(value, structType, fieldTypes, state)).headOption

private def shouldSplitStruct(fieldTypes: List[String]): Boolean =
  fieldTypes.map(sizeOfLlvmType).sum <= 16

def isPointerType(llvmType: String): Boolean =
  llvmType == "ptr" || llvmType.endsWith("*")

def isPackableAarch64(fieldTypes: List[String]): Boolean =
  fieldTypes.size == 2 && fieldTypes.forall(t => t == "i64" || isPointerType(t))

def shouldSplitX86_64(fieldTypes: List[String]): Boolean =
  shouldSplitStruct(fieldTypes)

def lowerNativeParamTypes(paramTypes: List[String], state: CodeGenState): List[String] =
  val abiRules = rulesFor(state)
  paramTypes.flatMap { typ =>
    getStructFieldTypes(typ, state) match
      case Some(fieldTypes) =>
        firstParamLowering(abiRules, typ, fieldTypes).getOrElse(List(typ))
      case None =>
        List(typ)
  }

def lowerNativeArgs(
  args:  List[(String, String)],
  state: CodeGenState
): (List[(String, String)], CodeGenState) =
  val abiRules = rulesFor(state)
  args.foldLeft((List.empty[(String, String)], state)) {
    case ((accArgs, currentState), (value, typ)) =>
      getStructFieldTypes(typ, currentState) match
        case Some(fieldTypes) =>
          firstArgLowering(abiRules, value, typ, fieldTypes, currentState) match
            case Some((loweredArgs, loweredState)) =>
              (accArgs ++ loweredArgs, loweredState)
            case None =>
              (accArgs :+ (value, typ), currentState)
        case None =>
          (accArgs :+ (value, typ), currentState)
  }
