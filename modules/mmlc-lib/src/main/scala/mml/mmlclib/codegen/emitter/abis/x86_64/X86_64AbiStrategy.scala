package mml.mmlclib.codegen.emitter.abis.x86_64

import mml.mmlclib.codegen.emitter.abis.StructLoweringUtils.*
import mml.mmlclib.codegen.emitter.abis.{AbiStrategy, StructLoweringRule}
import mml.mmlclib.codegen.emitter.{CodeGenState, getStructFieldTypes}

object X86_64AbiStrategy extends AbiStrategy:
  val name: String = "x86_64"

  private val rules: List[StructLoweringRule] = List(SplitSmallStructs, LargeStructByval)

  def lowerParamTypes(paramTypes: List[String], state: CodeGenState): List[String] =
    paramTypes.flatMap { typ =>
      getStructFieldTypes(typ, state) match
        case Some(fieldTypes) =>
          firstParamLowering(rules, typ, fieldTypes).getOrElse(List(typ))
        case None =>
          List(typ)
    }

  def lowerArgs(
    args:  List[(String, String)],
    state: CodeGenState
  ): (List[(String, String)], CodeGenState) =
    args.foldLeft((List.empty[(String, String)], state)) {
      case ((accArgs, currentState), (value, typ)) =>
        getStructFieldTypes(typ, currentState) match
          case Some(fieldTypes) =>
            firstArgLowering(rules, value, typ, fieldTypes, currentState) match
              case Some((loweredArgs, loweredState)) =>
                (accArgs ++ loweredArgs, loweredState)
              case None =>
                (accArgs :+ (value, typ), currentState)
          case None =>
            (accArgs :+ (value, typ), currentState)
    }

  def needsSret(returnType: String, state: CodeGenState): Boolean =
    getStructFieldTypes(returnType, state) match
      case Some(fieldTypes) => !shouldSplitStruct(fieldTypes)
      case None => false
