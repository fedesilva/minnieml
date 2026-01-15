package mml.mmlclib.util.error.print

import mml.mmlclib.api.CompilerError
import mml.mmlclib.codegen.LlvmCompilationError
import mml.mmlclib.codegen.emitter.CodeGenError

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
    case error: CodeGenError =>
      CodeGenErrorPrinter.prettyPrint(List(error))
    case error: LlvmCompilationError =>
      LlvmErrorPrinter.prettyPrint(List(error))
    case other => other.toString
