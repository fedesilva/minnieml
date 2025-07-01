package mml.mmlclib.util.error.print

import mml.mmlclib.api.{CodeGenApiError, CompilerError, NativeEmitterError}

/** Pretty printer for compiler errors */
object CompilerErrorPrinter:

  /** Pretty print any compiler error
    *
    * @param error
    *   The error to pretty print
    * @return
    *   A human-readable error message
    */
  def prettyPrint(error: Any): String = error match
    case CompilerError.SemanticErrors(errors) =>
      SemanticErrorPrinter.prettyPrint(errors)
    case CompilerError.ParserErrors(errors) =>
      ParserErrorPrinter.prettyPrint(errors)
    case CompilerError.Unknown(msg) =>
      s"Unknown compiler error: $msg"
    case CodeGenApiError.CodeGenErrors(errors) =>
      CodeGenErrorPrinter.prettyPrint(errors)
    case CodeGenApiError.CompilerErrors(errors) =>
      errors.map(prettyPrint).mkString("\n")
    case CodeGenApiError.Unknown(msg) =>
      s"Unknown code generation error: $msg"
    case NativeEmitterError.CompilationErrors(errors) =>
      errors.map(prettyPrint).mkString("\n")
    case NativeEmitterError.CodeGenErrors(errors) =>
      errors.map(prettyPrint).mkString("\n")
    case NativeEmitterError.LlvmErrors(errors) =>
      LlvmErrorPrinter.prettyPrint(errors)
    case NativeEmitterError.Unknown(msg) =>
      s"Unknown native emitter error: $msg"
    case other => other.toString
