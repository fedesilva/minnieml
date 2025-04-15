package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.codegen.emitter.CodeGenError

/** Pretty printer for code generation errors */
object CodeGenErrorPrinter:

  /** Pretty print a list of code generation errors
    *
    * @param errors
    *   List of code generation errors
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[CodeGenError]): String =
    if errors.isEmpty then "No code generation errors"
    else
      val errorMessages = errors.map(prettyPrintSingle)
      errorMessages.mkString("\n")

  /** Pretty print a single code generation error
    *
    * @param error
    *   The code generation error to pretty print
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: CodeGenError): String =
    s"Code generation error: ${error.message}"
