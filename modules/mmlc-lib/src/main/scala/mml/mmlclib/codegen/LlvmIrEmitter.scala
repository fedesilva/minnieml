package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.DeclarationQueries
import mml.mmlclib.codegen.emitter.{CodeGenError, EmitResult, emitModule}

/** LLVM IR Emitter for MML.
  *
  * Entry point for LLVM IR code generation.
  */
object LlvmIrEmitter:

  /** Emits executable module members into LLVM IR. Checked generic templates remain indexed but do
    * not produce emitted functions.
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
    module:           Module,
    entryPoint:       Option[String],
    targetTriple:     String,
    targetAbi:        TargetAbi,
    targetAttributes: TargetAttributes,
    emitAliasScopes:  Boolean,
    sanitizeAddress:  Boolean = false
  ): Either[CodeGenError, EmitResult] =
    emitModule(
      module.copy(members = module.members.filterNot(DeclarationQueries.isGenericFunction)),
      entryPoint,
      targetTriple,
      targetAbi,
      targetAttributes,
      emitAliasScopes,
      sanitizeAddress
    )
