package mml.mmlclib.lsp

import mml.mmlclib.api.CompilerError
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.LlvmCompilationError
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{CompilationError, diagnosticSources}
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.{SemanticError, TypeError}
import mml.mmlclib.util.error.print.{
  diagnosticMessage,
  entryPointMessage,
  expressionMessage,
  typeErrorMessage
}

/** Converts source-bearing semantic and type errors to LSP diagnostics. */
object Diagnostics:

  /** Extract diagnostics from compiler state. */
  def fromCompilerState(state: CompilerState): List[Diagnostic] =
    state.visibleErrors.toList.flatMap(fromErrorDiagnostics)

  /** Convert a single compilation error to a diagnostic. */
  def fromError(error: CompilationError): Option[Diagnostic] =
    fromErrorDiagnostics(error).headOption

  /** Convert a single compilation error to all related diagnostics. */
  def fromErrorAll(error: CompilationError): List[Diagnostic] =
    fromErrorDiagnostics(error)

  private def fromErrorDiagnostics(error: CompilationError): List[Diagnostic] =
    error match
      case semantic: SemanticError =>
        fromSpans(diagnosticSources(semantic).flatMap(_.spanOpt), formatSemanticError(semantic))
      case typeError: TypeError =>
        fromSpans(diagnosticSources(typeError).flatMap(_.spanOpt), formatTypeError(typeError))

      case ParserError.Failure(_) => Nil
      case ParserError.Unknown(_) => Nil
      case _: CompilerError => Nil
      case _: CodeGenError => Nil
      case _: LlvmCompilationError => Nil
      // CompilationError is open; other families have no editor conversion contract.
      case _ => Nil

  /** Render messages only for source spans that can be shown in the editor. */
  private def fromSpans(spans: List[SrcSpan], message: => String): List[Diagnostic] =
    spans.map { span =>
      Diagnostic(
        range    = Range.fromSrcSpan(span),
        severity = DiagnosticSeverity.Error,
        message  = message
      )
    }

  private def formatSemanticError(error: SemanticError): String = error match
    case SemanticError.InvalidExpression(_, reason, _) =>
      s"Invalid expression: ${expressionMessage(reason)}"
    case SemanticError.DanglingTerms(_, reason, _) =>
      s"Dangling terms: ${expressionMessage(reason)}"
    case SemanticError.MemberErrorFound(error, _) => s"Member error: ${diagnosticMessage(error)}"
    case SemanticError.ParsingIdErrorFound(error, _) =>
      s"Parsing error: ${diagnosticMessage(error)}"
    case SemanticError.ParseTermErrorFound(error, _) =>
      s"Parsing error: ${diagnosticMessage(error)}"
    case SemanticError.InvalidEntryPoint(error) =>
      s"Invalid entry point: ${entryPointMessage(error)}"
    case other => diagnosticMessage(other)

  private def formatTypeError(error: TypeError): String = typeErrorMessage(error)
