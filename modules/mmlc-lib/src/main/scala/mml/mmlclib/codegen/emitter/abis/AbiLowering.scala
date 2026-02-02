package mml.mmlclib.codegen.emitter.abis

import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.abis.aarch64.{LargeStructIndirect, PackTwoI64Structs}
import mml.mmlclib.codegen.emitter.abis.x86_64.{LargeStructByval, SplitSmallStructs}
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
  List(SplitSmallStructs, LargeStructByval, LargeStructIndirect, PackTwoI64Structs)

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

def isLargeStructAarch64(fieldTypes: List[String]): Boolean =
  fieldTypes.map(sizeOfLlvmType).sum > 16 && !isPackableAarch64(fieldTypes)

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

/** Checks if a return type needs sret lowering (large struct return).
  *
  * On both x86_64 and AArch64, structs >16 bytes must be returned via a hidden first pointer
  * parameter (sret).
  */
def needsSretReturn(returnType: String, state: CodeGenState): Boolean =
  getStructFieldTypes(returnType, state) match
    case Some(fieldTypes) =>
      state.targetAbi match
        case TargetAbi.X86_64 => !shouldSplitX86_64(fieldTypes)
        case TargetAbi.AArch64 => isLargeStructAarch64(fieldTypes)
        case _ => false
    case None => false

/** Lowers return type for sret convention if needed.
  *
  * @return
  *   (newReturnType, Option[sretParamType]) - if sret is needed, returns ("void", Some(sretParam))
  */
def lowerNativeReturnType(
  returnType: String,
  state:      CodeGenState
): (String, Option[String]) =
  if needsSretReturn(returnType, state) then ("void", Some(s"ptr sret($returnType) align 8"))
  else (returnType, None)

/** Emits sret call site code: allocates space, calls with sret, loads result.
  *
  * @return
  *   (resultReg, updatedState) where resultReg holds the loaded struct value
  */
def emitSretCall(
  fnName:     String,
  returnType: String,
  args:       List[(String, String)],
  state:      CodeGenState,
  emitCallFn: (Option[Int], Option[String], String, List[(String, String)]) => String
): (Int, CodeGenState) =
  val allocReg     = state.nextRegister
  val allocLine    = s"  %$allocReg = alloca $returnType, align 8"
  val sretArg      = (s"%$allocReg", s"ptr sret($returnType) align 8")
  val argsWithSret = sretArg :: args
  val callLine     = emitCallFn(None, Some("void"), fnName, argsWithSret)
  val loadReg      = allocReg + 1
  val loadLine     = s"  %$loadReg = load $returnType, ptr %$allocReg, align 8"
  val finalState = state
    .withRegister(loadReg + 1)
    .emit(allocLine)
    .emit(callLine)
    .emit(loadLine)
  (loadReg, finalState)
