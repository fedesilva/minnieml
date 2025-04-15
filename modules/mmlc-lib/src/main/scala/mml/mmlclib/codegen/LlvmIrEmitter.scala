package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{CodeGenError, emitModule}

/** LLVM IR Emitter for MML.
  *
  * Entry point for LLVM IR code generation.
  */
object LlvmIrEmitter:

  /** Emits an entire module into LLVM IR.
    *
    * @param module
    *   the module containing definitions and bindings
    * @return
    *   Either a CodeGenError or the complete LLVM IR as a String.
    */
  def module(module: Module): Either[CodeGenError, String] =
    emitModule(module)
