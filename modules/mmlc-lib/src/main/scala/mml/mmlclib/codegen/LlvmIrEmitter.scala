package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{CodeGenError, EmitResult, emitModule}

/** LLVM IR Emitter for MML.
  *
  * Entry point for LLVM IR code generation.
  */
object LlvmIrEmitter:

  /** Emits an entire module into LLVM IR.
    *
    * @param module
    *   the module containing definitions and bindings
    * @param entryPoint
    *   the mangled name of the entry point function (for binary mode)
    * @param targetTriple
    *   the target triple for code generation
    * @param targetAbi
    *   the ABI strategy for native lowering
    * @return
    *   Either a CodeGenError or the EmitResult containing IR and accumulated warnings.
    */
  def module(
    module:          Module,
    entryPoint:      Option[String],
    targetTriple:    String,
    targetAbi:       TargetAbi,
    targetCpu:       Option[String],
    emitAliasScopes: Boolean
  ): Either[CodeGenError, EmitResult] =
    emitModule(module, entryPoint, targetTriple, targetAbi, targetCpu, emitAliasScopes)
