package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.api.{CodeGenApiError, CompilerError, NativeEmitterError}
import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.codegen.{CodeGenError, LlvmCompilationError}
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.SemanticError

/** Simple error printer for compiler errors */
object ErrorPrinter:
  /** Format a location as line:col-line:col */
  def formatLocation(span: SrcSpan): String =
    s"[${span.start.line}:${span.start.col}]-[${span.end.line}:${span.end.col}]"

  /** Pretty print any compiler error */
  def prettyPrint(error: Any, sourceCode: Option[String] = None): String = error match
    case CompilerError.SemanticErrors(errors) => prettyPrintSemanticErrors(errors, sourceCode)
    case CompilerError.ParserErrors(errors) => prettyPrintParserErrors(errors)
    case CompilerError.Unknown(msg) => s"Unknown error: $msg"
    case CodeGenApiError.CodeGenErrors(errors) => prettyPrintCodeGenErrors(errors)
    case CodeGenApiError.CompilerErrors(errors) =>
      errors.map(err => prettyPrint(err, sourceCode)).mkString("\n")
    case CodeGenApiError.Unknown(msg) => s"Unknown code generation error: $msg"
    case NativeEmitterError.CompilationErrors(errors) =>
      errors.map(err => prettyPrint(err, sourceCode)).mkString("\n")
    case NativeEmitterError.CodeGenErrors(errors) =>
      errors.map(err => prettyPrint(err, sourceCode)).mkString("\n")
    case NativeEmitterError.LlvmErrors(errors) => prettyPrintLlvmErrors(errors)
    case NativeEmitterError.Unknown(msg) => s"Unknown native emitter error: $msg"
    case _ => error.toString

  /** Pretty print semantic errors */
  private def prettyPrintSemanticErrors(
    errors:     List[SemanticError],
    sourceCode: Option[String]
  ): String =
    if errors.isEmpty then "No errors"
    else
      val messages = errors.map(err => prettyPrintSemanticError(err, sourceCode))
      s"Semantic errors:\n${messages.mkString("\n\n")}"

  /** Pretty print a single semantic error */
  private def prettyPrintSemanticError(error: SemanticError, sourceCode: Option[String]): String =
    val baseMessage = error match
      case SemanticError.DuplicateName(name, duplicates) =>
        val locations = duplicates
          .collect { case d: FromSource => formatLocation(d.span) }
          .mkString(", ")

        s"Duplicate name '$name' defined at: $locations"

      case SemanticError.UndefinedRef(ref, member) =>
        s"Undefined reference '${ref.name}' at ${formatLocation(ref.span)}"

      case SemanticError.InvalidExpression(expr, message) =>
        s"Invalid expression at ${formatLocation(expr.span)}: $message"

      case SemanticError.MemberErrorFound(error) =>
        val location = formatLocation(error.span)
        s"Parser error at $location: ${error.message}"

      case SemanticError.DanglingTerms(terms, message) =>
        val locations = terms.map(t => formatLocation(t.span)).mkString(", ")
        s"$message at $locations"

    // Add source code snippet if available
    sourceCode match
      case Some(src) =>
        val codeSnippet = SourceCodeExtractor.extractSnippetsFromError(src, error)
        s"$baseMessage\n$codeSnippet"
      case None => baseMessage

  /** Pretty print parser errors */
  private def prettyPrintParserErrors(errors: List[ParserError]): String =
    errors.map(prettyPrintParserError).mkString("\n")

  /** Pretty print a parser error */
  private def prettyPrintParserError(error: ParserError): String = error match
    case ParserError.Failure(message) => s"Parser error: $message"
    case ParserError.Unknown(message) => s"Unknown parser error: $message"

  /** Pretty print code generation errors */
  private def prettyPrintCodeGenErrors(errors: List[CodeGenError]): String =
    errors.map(e => s"Code generation error: ${e.message}").mkString("\n")

  /** Pretty print LLVM compilation errors */
  private def prettyPrintLlvmErrors(errors: List[LlvmCompilationError]): String =
    errors.map(prettyPrintLlvmError).mkString("\n")

  /** Pretty print a LLVM compilation error */
  private def prettyPrintLlvmError(error: LlvmCompilationError): String = error match
    case LlvmCompilationError.TemporaryFileCreationError(msg) =>
      s"Failed to create temporary file: $msg"
    case LlvmCompilationError.UnsupportedOperatingSystem(os) => s"Unsupported operating system: $os"
    case LlvmCompilationError.CommandExecutionError(cmd, msg, code) =>
      s"Command execution failed (exit code $code): $cmd\n$msg"
    case LlvmCompilationError.ExecutableRunError(path, code) =>
      s"Executable run failed (exit code $code): $path"
    case LlvmCompilationError.LlvmNotInstalled(tools) =>
      s"LLVM tools not installed: ${tools.mkString(", ")}"
