package mml.mmlclib.util.error.print

import mml.mmlclib.api.CompilerError
import mml.mmlclib.ast.{ClosureEnvironmentError, Error}
import mml.mmlclib.codegen.LlvmCompilationError
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.{ProtocolError, SemanticError, TypeError}

/** Plain diagnostic wording, shared by APIs and output-specific renderers. Extension errors remain
  * representable without imposing a message method on their data contract.
  */
def diagnosticMessage(
  error: CompilationError | Error | ProtocolError | ClosureEnvironmentError
): String =
  error match
    case error: SemanticError => semanticErrorMessage(error)
    case error: TypeError => typeErrorMessage(error)
    case error: ProtocolError => protocolMessage(error)
    case error: Error => syntaxMessage(error.reason)
    case error: ClosureEnvironmentError => closureEnvironmentMessage(error)
    case error: CodeGenError => codeGenMessage(error.reason)
    case error: LlvmCompilationError => toolchainMessage(error)
    case ParserError.Failure(trace) => trace
    case ParserError.Unknown(detail) => detail
    case CompilerError.SemanticErrors(errors) =>
      if errors.size == 1 then diagnosticMessage(errors.head)
      else s"${errors.size} errors: ${errors.map(diagnosticMessage).mkString("; ")}"
    case CompilerError.ParserErrors(errors) =>
      if errors.size == 1 then diagnosticMessage(errors.head)
      else s"${errors.size} parse errors: ${errors.map(diagnosticMessage).mkString("; ")}"
    case CompilerError.Unknown(detail) => detail
    case extension => extension.toString
