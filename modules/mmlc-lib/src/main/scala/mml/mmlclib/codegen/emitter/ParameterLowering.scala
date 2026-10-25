package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.CompiledArg

/** A source binding and its LLVM representation before runtime projection. */
private[emitter] case class LoweredParameter(
  parameter:   FnParam,
  sourceIndex: Int,
  llvmType:    String
):

  def isRuntime: Boolean = llvmType != "void"

/** Keeps lexical parameters separate from runtime operands. Emitters choose argument or PHI
  * registers; Unit bindings remain available without consuming either kind of register.
  */
private[emitter] case class ParameterLowering(parameters: List[LoweredParameter]):

  val runtime:       List[LoweredParameter] = parameters.filter(_.isRuntime)
  val runtimeCount:  Int                    = runtime.size
  val sourceIndices: List[Int]              = runtime.map(_.sourceIndex)
  val runtimeTypes:  List[String]           = runtime.map(_.llvmType)

  def declarations(resolvables: ResolvablesIndex): String =
    runtime.zipWithIndex
      .map { case (lowered, index) =>
        val param = lowered.parameter
        val attribute =
          if param.consuming && isPointerParam(param, resolvables) then " noalias" else ""
        s"${lowered.llvmType}$attribute %$index"
      }
      .mkString(", ")

  def arguments: List[CompiledArg] =
    runtime.zipWithIndex.map { case (lowered, index) =>
      val param = lowered.parameter
      CompiledArg(s"%$index", lowered.llvmType, param.typeSpec.orElse(param.typeAsc))
    }

  def argumentScope: Either[CodeGenError, Map[String, ScopeEntry]] =
    scope((0 until runtimeCount).toList)

  def scope(registers: List[Int]): Either[CodeGenError, Map[String, ScopeEntry]] =
    if registers.size != runtimeCount then
      CodeGenError("Parameter register count does not match runtime parameter count").asLeft
    else
      val runtimeRegisters = sourceIndices.zip(registers).toMap
      parameters
        .traverse { lowered =>
          val param = lowered.parameter
          for
            tpe <- param.typeSpec
              .orElse(param.typeAsc)
              .toRight(
                CodeGenError(s"Missing type information for parameter '${param.name}'", param.some)
              )
            entry <- runtimeRegisters.get(lowered.sourceIndex) match
              case Some(register) =>
                getNominalTypeName(tpe).map(name => ScopeEntry(register, name))
              case None =>
                ScopeEntry(0, "Unit", isLiteral = true, literalValue = "undef".some).asRight
          yield (param.name :: param.id.toList).map(_ -> entry)
        }
        .map(_.flatten.toMap)

private[emitter] object ParameterLowering:

  /** Nullary callable types accept a Unit expression without declaring a lexical parameter. */
  def from(
    lambda:     Lambda,
    paramTypes: List[String]
  ): Either[CodeGenError, ParameterLowering] =
    val types =
      if lambda.params.isEmpty && paramTypes == List("void") then Nil else paramTypes
    if lambda.params.size != types.size then
      CodeGenError(
        "Source parameter count does not match lowered parameter types",
        lambda.some
      ).asLeft
    else
      val parameters =
        lambda.params.zip(types).zipWithIndex.map { case ((parameter, llvmType), sourceIndex) =>
          LoweredParameter(parameter, sourceIndex, llvmType)
        }
      ParameterLowering(parameters).asRight
