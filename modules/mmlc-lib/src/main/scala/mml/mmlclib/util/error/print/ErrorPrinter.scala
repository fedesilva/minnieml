package mml.mmlclib.util.error.print

import mml.mmlclib.api.{CodeGenApiError, CompilerError, NativeEmitterError}
import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.codegen.LlvmCompilationError
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

  /** Extract source position from error for sorting */
  private def getErrorSourcePosition(error: SemanticError): (Int, Int) = error match
    case SemanticError.UndefinedRef(ref, _, _) => (ref.span.start.line, ref.span.start.col)
    case SemanticError.UndefinedTypeRef(typeRef, _, _) =>
      (typeRef.span.start.line, typeRef.span.start.col)
    case SemanticError.DuplicateName(_, duplicates, _) =>
      duplicates
        .collect { case d: FromSource => d }
        .minByOption(_.span.start.index)
        .map(d => (d.span.start.line, d.span.start.col))
        .getOrElse((Int.MaxValue, Int.MaxValue))
    case SemanticError.InvalidExpression(expr, _, _) => (expr.span.start.line, expr.span.start.col)
    case SemanticError.MemberErrorFound(error, _) => (error.span.start.line, error.span.start.col)
    case SemanticError.ParsingIdErrorFound(error, _) =>
      (error.span.start.line, error.span.start.col)
    case SemanticError.DanglingTerms(terms, _, _) =>
      terms
        .minByOption(_.span.start.index)
        .map(t => (t.span.start.line, t.span.start.col))
        .getOrElse((Int.MaxValue, Int.MaxValue))
    case SemanticError.InvalidExpressionFound(invalidExpr, _) =>
      (invalidExpr.span.start.line, invalidExpr.span.start.col)
    case SemanticError.TypeCheckingError(error) =>
      // For type errors, we need to extract the position from the nested error
      // This is a bit hacky, but avoids code duplication
      error match
        case mml.mmlclib.semantic.TypeError.MissingParameterType(param, _, _) =>
          (param.span.start.line, param.span.start.col)
        case mml.mmlclib.semantic.TypeError.MissingReturnType(fnDef, _) =>
          (fnDef.span.start.line, fnDef.span.start.col)
        case mml.mmlclib.semantic.TypeError.MissingOperatorParameterType(param, _, _) =>
          (param.span.start.line, param.span.start.col)
        case mml.mmlclib.semantic.TypeError.MissingOperatorReturnType(opDef, _) =>
          (opDef.span.start.line, opDef.span.start.col)
        case mml.mmlclib.semantic.TypeError.TypeMismatch(node, _, _, _) =>
          val fs = node.asInstanceOf[FromSource]
          (fs.span.start.line, fs.span.start.col)
        case mml.mmlclib.semantic.TypeError.UndersaturatedApplication(app, _, _, _) =>
          (app.span.start.line, app.span.start.col)
        case mml.mmlclib.semantic.TypeError.OversaturatedApplication(app, _, _, _) =>
          (app.span.start.line, app.span.start.col)
        case mml.mmlclib.semantic.TypeError.InvalidApplication(app, _, _, _) =>
          (app.span.start.line, app.span.start.col)
        case mml.mmlclib.semantic.TypeError.ConditionalBranchTypeMismatch(cond, _, _, _) =>
          (cond.span.start.line, cond.span.start.col)
        case mml.mmlclib.semantic.TypeError.ConditionalBranchTypeUnknown(cond, _) =>
          (cond.span.start.line, cond.span.start.col)
        case mml.mmlclib.semantic.TypeError.UnresolvableType(typeRef, _, _) =>
          (typeRef.span.start.line, typeRef.span.start.col)
        case mml.mmlclib.semantic.TypeError.IncompatibleTypes(node, _, _, _, _) =>
          val fs = node.asInstanceOf[FromSource]
          (fs.span.start.line, fs.span.start.col)
        case mml.mmlclib.semantic.TypeError.UntypedHoleInBinding(bnd, _) =>
          (bnd.span.start.line, bnd.span.start.col)

  /** Pretty print semantic errors */
  private def prettyPrintSemanticErrors(
    errors:     List[SemanticError],
    sourceCode: Option[String]
  ): String =
    if errors.isEmpty then "No errors"
    else
      // Sort errors by source line and column position
      val sortedErrors = errors.sortBy(getErrorSourcePosition)
      val messages     = sortedErrors.map(err => prettyPrintSemanticError(err, sourceCode))
      s"${messages.mkString("\n\n")}"

  /** Pretty print a single semantic error */
  private def prettyPrintSemanticError(error: SemanticError, sourceCode: Option[String]): String =
    // Extract AST information when applicable
    val astInfo = error match
      case SemanticError.UndefinedRef(ref, _, _) =>
        val resolved = ref.resolvedAs
          .map(r => s"Resolved as: ${r.getClass.getSimpleName}(${r.name})")
          .getOrElse("Not resolved")
        val candidates =
          if ref.candidates.isEmpty then "No candidates found"
          else {
            val candidateList =
              ref.candidates.map(c => s"${c.getClass.getSimpleName}(${c.name})").mkString(", ")
            s"Candidates: [$candidateList]"
          }
        s"Info: '$resolved', $candidates"

      case SemanticError.DanglingTerms(terms, _, _) =>
        val termInfos = terms
          .map {
            case ref: mml.mmlclib.ast.Ref =>
              val resolved = ref.resolvedAs
                .map(r => s"Resolved as: ${r.getClass.getSimpleName}(${r.name})")
                .getOrElse("Not resolved")
              val candidates =
                if ref.candidates.isEmpty then "No candidates found"
                else {
                  val candidateList = ref.candidates
                    .map(c => s"${c.getClass.getSimpleName}(${c.name})")
                    .mkString(", ")
                  s"Candidates: [$candidateList]"
                }
              s"${Console.YELLOW}'${ref.name}': $resolved, $candidates${Console.RESET}"
            case term => s"Term: ${term.getClass.getSimpleName}"
          }
          .mkString("\n")
        s"${Console.YELLOW}Term info:${Console.RESET}\n$termInfos"

      case _ => "" // No special AST info for other error types

    val baseMessage = error match
      case SemanticError.DuplicateName(name, duplicates, phase) =>
        val locations = duplicates
          .collect { case d: FromSource => formatLocation(d.span) }
          .mkString(", ")

        s"${Console.RED}Duplicate name '$name' defined at: $locations${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.UndefinedRef(ref, member, phase) =>
        s"${Console.RED}Undefined reference '${ref.name}' at ${formatLocation(ref.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.UndefinedTypeRef(typeRef, member, phase) =>
        s"${Console.RED}Undefined type reference '${typeRef.name}' at ${formatLocation(typeRef.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.InvalidExpression(expr, message, phase) =>
        s"${Console.RED}Invalid expression at ${formatLocation(expr.span)}: $message${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.MemberErrorFound(error, phase) =>
        val location = formatLocation(error.span)
        s"${Console.RED}Parser error at $location: ${error.message}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.ParsingIdErrorFound(error, phase) =>
        val location = formatLocation(error.span)
        s"${Console.RED}Invalid identifier at $location: ${error.message}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.DanglingTerms(terms, message, phase) =>
        val locations = terms.map(t => formatLocation(t.span)).mkString(", ")
        s"${Console.RED}$message at $locations${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.InvalidExpressionFound(invalidExpr, phase) =>
        s"${Console.RED}Invalid expression found at ${formatLocation(invalidExpr.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.TypeCheckingError(error) =>
        // Delegate to SemanticErrorPrinter to avoid duplication
        SemanticErrorPrinter.prettyPrintTypeError(error)

    // Add AST info and source code snippets if source code is available
    sourceCode match
      case Some(src) =>
        val codeSnippet = SourceCodeExtractor.extractSnippetsFromError(src, error)
        val fullMessage =
          if astInfo.nonEmpty then s"$baseMessage\n$astInfo\n$codeSnippet"
          else s"$baseMessage\n$codeSnippet"
        fullMessage
      case None =>
        if astInfo.nonEmpty then s"$baseMessage\n$astInfo"
        else baseMessage

  /** Pretty print parser errors */
  private def prettyPrintParserErrors(errors: List[ParserError]): String =
    errors.map(prettyPrintParserError).mkString("\n")

  /** Pretty print a parser error */
  private def prettyPrintParserError(error: ParserError): String = error match
    case ParserError.Failure(message) => s"${Console.RED}Parser error: $message${Console.RESET}"
    case ParserError.Unknown(message) => s"Unknown parser error: $message"

  /** Pretty print code generation errors */
  private def prettyPrintCodeGenErrors(
    errors: List[mml.mmlclib.codegen.emitter.CodeGenError]
  ): String =
    errors.map(e => s"Code generation error: ${e.message}").mkString("\n")

  /** Pretty print LLVM compilation errors */
  private def prettyPrintLlvmErrors(errors: List[LlvmCompilationError]): String =
    errors.map(prettyPrintLlvmError).mkString("\n")

  /** Pretty print a LLVM compilation error */
  private def prettyPrintLlvmError(error: LlvmCompilationError): String = error match
    case LlvmCompilationError.TemporaryFileCreationError(msg) =>
      s"Failed to create temporary file: $msg"
    case LlvmCompilationError.UnsupportedOperatingSystem(os) => s"Unsupported operating system: $os"
    case LlvmCompilationError.UnsupportedArchitecture(arch) => s"Unsupported architecture: $arch"
    case LlvmCompilationError.CommandExecutionError(cmd, msg, code) =>
      s"Command execution failed (exit code $code): $cmd\n$msg"
    case LlvmCompilationError.ExecutableRunError(path, code) =>
      s"Executable run failed (exit code $code): $path"
    case LlvmCompilationError.LlvmNotInstalled(tools) =>
      s"LLVM tools not installed: ${tools.mkString(", ")}"
    case LlvmCompilationError.RuntimeResourceError(msg) =>
      s"MML runtime resource error: $msg"
