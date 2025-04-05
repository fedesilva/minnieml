package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.api.{CodeGenApiError, CompilerError, NativeEmitterError}
import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.codegen.{CodeGenError, LlvmCompilationError}
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.SemanticError

import scala.io.Source
import scala.util.Try

/** Simple error printer for compiler errors */
object ErrorPrinter:
  // Source code access for error reporting
  private val sourceFileCache = scala.collection.mutable.Map.empty[String, Array[String]]
  private var currentSourceFile: Option[String] = None

  /** Set current source file being processed */
  def setCurrentSourceFile(path: String): Unit =
    currentSourceFile = Some(path)
    if !sourceFileCache.contains(path) then
      Try {
        val lines = Source.fromFile(path).getLines().toArray
        sourceFileCache.put(path, lines)
      }.recover { case _ => /* Silent failure */
      }

  /** Set source code for error reporting from a string (useful for tests) */
  def setSourceFromString(source: String, virtualPath: String = "<string>"): Unit =
    currentSourceFile = Some(virtualPath)
    val lines = source.trim.split("\n").map(_.stripTrailing())
    sourceFileCache.put(virtualPath, lines)

  /** Format a location as line:col-line:col */
  def formatLocation(span: SrcSpan): String =
    s"[${span.start.line}:${span.start.col}]-[${span.end.line}:${span.end.col}]"

  /** Pretty print any compiler error */
  def prettyPrint(error: Any): String = error match
    case CompilerError.SemanticErrors(errors) => prettyPrintSemanticErrors(errors)
    case CompilerError.ParserErrors(errors) => prettyPrintParserErrors(errors)
    case CompilerError.Unknown(msg) => s"Unknown error: $msg"
    case CodeGenApiError.CodeGenErrors(errors) => prettyPrintCodeGenErrors(errors)
    case CodeGenApiError.CompilerErrors(errors) => errors.map(prettyPrint).mkString("\n")
    case CodeGenApiError.Unknown(msg) => s"Unknown code generation error: $msg"
    case NativeEmitterError.CompilationErrors(errors) => errors.map(prettyPrint).mkString("\n")
    case NativeEmitterError.CodeGenErrors(errors) => errors.map(prettyPrint).mkString("\n")
    case NativeEmitterError.LlvmErrors(errors) => prettyPrintLlvmErrors(errors)
    case NativeEmitterError.Unknown(msg) => s"Unknown native emitter error: $msg"
    case _ => error.toString

  /** Pretty print semantic errors */
  private def prettyPrintSemanticErrors(errors: List[SemanticError]): String =
    if errors.isEmpty then "No errors"
    else
      val messages = errors.map(prettyPrintSemanticError)
      s"Semantic errors:\n${messages.mkString("\n\n")}"

  /** Pretty print a single semantic error */
  private def prettyPrintSemanticError(error: SemanticError): String = error match
    case SemanticError.DuplicateName(name, duplicates) =>
      val locations = duplicates
        .collect { case d: FromSource => formatLocation(d.span) }
        .mkString(", ")

      val snippets = currentSourceFile
        .flatMap { file =>
          sourceFileCache.get(file).map { lines =>
            // Show code snippets for each duplicate
            val codeSnippets = duplicates.collect { case d: FromSource =>
              // Get line range for this span - ensure we don't go below 0
              val startLineIdx = math.max(0, d.span.start.line - 1) // 0-based index, minimum 0
              val endLineIdx = math.min(
                lines.length - 1,
                d.span.end.line - 1
              ) // 0-based index, maximum is last line

              // Build snippet with all relevant lines
              if startLineIdx < lines.length then
                val relevantLines = (startLineIdx to endLineIdx).map(i => lines(i)).mkString("\n")
                s"At ${formatLocation(d.span)}:\n$relevantLines"
              else s"At ${formatLocation(d.span)}: <source line not available>"
            }
            codeSnippets.mkString("\n\n")
          }
        }
        .getOrElse("")

      s"Duplicate name '$name' defined at: $locations\n$snippets"

    case SemanticError.UndefinedRef(ref, member) =>
      s"Undefined reference '${ref.name}' at ${formatLocation(ref.span)}"

    case SemanticError.InvalidExpression(expr, message) =>
      s"Invalid expression at ${formatLocation(expr.span)}: $message"

    case SemanticError.DanglingTerms(terms, message) =>
      val locations = terms.map(t => formatLocation(t.span)).mkString(", ")

      // Get source code snippets if available
      val snippets = currentSourceFile
        .flatMap { file =>
          sourceFileCache.get(file).map { lines =>
            // Show code snippets for each dangling term
            val codeSnippets = terms.map { term =>
              // Get line range for this span
              val startLineIdx = math.max(0, term.span.start.line - 1)
              val endLineIdx   = math.min(lines.length - 1, term.span.end.line - 1)

              // Build snippet with all relevant lines
              if startLineIdx < lines.length then
                val relevantLines = (startLineIdx to endLineIdx).map(i => lines(i)).mkString("\n")
                s"At ${formatLocation(term.span)}:\n$relevantLines"
              else s"At ${formatLocation(term.span)}: <source line not available>"
            }
            codeSnippets.mkString("\n\n")
          }
        }
        .getOrElse("")

      s"$message at $locations\n$snippets"

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
