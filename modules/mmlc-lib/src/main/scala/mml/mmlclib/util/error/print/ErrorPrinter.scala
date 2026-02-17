package mml.mmlclib.util.error.print

import mml.mmlclib.api.CompilerError
import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.codegen.LlvmCompilationError
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.parser.{ParserError, SourceInfo}
import mml.mmlclib.semantic.SemanticError

/** Simple error printer for compiler errors */
object ErrorPrinter:
  /** Format a location as line:col-line:col */
  def formatLocation(span: SrcSpan): String =
    s"[${span.start.line}:${span.start.col}]-[${span.end.line}:${span.end.col}]"

  private def spanOf(node: FromSource): Option[SrcSpan] =
    node.source.spanOpt

  private def startPosOf(node: FromSource): (Int, Int) =
    spanOf(node)
      .map(s => (s.start.line, s.start.col))
      .getOrElse((Int.MaxValue, Int.MaxValue))

  /** Pretty print any compiler error */
  def prettyPrint(error: Any, sourceInfo: Option[SourceInfo] = None): String = error match
    case CompilerError.SemanticErrors(errors) => prettyPrintSemanticErrors(errors, sourceInfo)
    case CompilerError.ParserErrors(errors) => prettyPrintParserErrors(errors)
    case CompilerError.Unknown(msg) => s"Unknown error: $msg"
    case error: SemanticError => prettyPrintSemanticErrors(List(error), sourceInfo)
    case error: ParserError => prettyPrintParserErrors(List(error))
    case error: CodeGenError => prettyPrintCodeGenErrors(List(error), sourceInfo)
    case error: LlvmCompilationError => prettyPrintLlvmErrors(List(error))
    case _ => error.toString

  /** Extract source position from error for sorting */
  private def getErrorSourcePosition(error: SemanticError): (Int, Int) = error match
    case SemanticError.UndefinedRef(ref, _, _) => (ref.span.start.line, ref.span.start.col)
    case SemanticError.UndefinedTypeRef(typeRef, _, _) =>
      (typeRef.span.start.line, typeRef.span.start.col)
    case SemanticError.DuplicateName(_, duplicates, _) =>
      duplicates
        .collect { case d: FromSource => d }
        .flatMap(spanOf)
        .minByOption(_.start.index)
        .map(s => (s.start.line, s.start.col))
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
    case SemanticError.InvalidEntryPoint(_, span) => (span.start.line, span.start.col)
    case SemanticError.UseAfterMove(ref, _, _) => (ref.span.start.line, ref.span.start.col)
    case SemanticError.ConsumingParamNotLastUse(_, ref, _) =>
      (ref.span.start.line, ref.span.start.col)
    case SemanticError.PartialApplicationWithConsuming(fn, _, _) =>
      (fn.span.start.line, fn.span.start.col)
    case SemanticError.ConditionalOwnershipMismatch(cond, _) =>
      (cond.span.start.line, cond.span.start.col)
    case SemanticError.BorrowEscapeViaReturn(ref, _) =>
      (ref.span.start.line, ref.span.start.col)
    case SemanticError.TypeCheckingError(error) =>
      // For type errors, we need to extract the position from the nested error
      // This is a bit hacky, but avoids code duplication
      error match
        case mml.mmlclib.semantic.TypeError.MissingParameterType(param, _, _) =>
          (param.span.start.line, param.span.start.col)
        case mml.mmlclib.semantic.TypeError.MissingReturnType(decl, _) =>
          startPosOf(decl.asInstanceOf[FromSource])
        case mml.mmlclib.semantic.TypeError.RecursiveFunctionMissingReturnType(decl, _) =>
          startPosOf(decl.asInstanceOf[FromSource])
        case mml.mmlclib.semantic.TypeError.MissingOperatorParameterType(param, _, _) =>
          (param.span.start.line, param.span.start.col)
        case mml.mmlclib.semantic.TypeError.MissingOperatorReturnType(decl, _) =>
          startPosOf(decl.asInstanceOf[FromSource])
        case mml.mmlclib.semantic.TypeError.TypeMismatch(node, _, _, _, _) =>
          startPosOf(node.asInstanceOf[FromSource])
        case mml.mmlclib.semantic.TypeError.UndersaturatedApplication(app, _, _, _) =>
          (app.span.start.line, app.span.start.col)
        case mml.mmlclib.semantic.TypeError.OversaturatedApplication(app, _, _, _) =>
          (app.span.start.line, app.span.start.col)
        case mml.mmlclib.semantic.TypeError.InvalidApplication(app, _, _, _) =>
          (app.span.start.line, app.span.start.col)
        case mml.mmlclib.semantic.TypeError.InvalidSelection(ref, _, _) =>
          (ref.span.start.line, ref.span.start.col)
        case mml.mmlclib.semantic.TypeError.UnknownField(ref, _, _) =>
          (ref.span.start.line, ref.span.start.col)
        case mml.mmlclib.semantic.TypeError.ConditionalBranchTypeMismatch(cond, _, _, _) =>
          (cond.span.start.line, cond.span.start.col)
        case mml.mmlclib.semantic.TypeError.ConditionalBranchTypeUnknown(cond, _) =>
          (cond.span.start.line, cond.span.start.col)
        case mml.mmlclib.semantic.TypeError.UnresolvableType(node, _, _) =>
          node match
            case fs: FromSource => startPosOf(fs)
            case _ => (Int.MaxValue, Int.MaxValue)
        case mml.mmlclib.semantic.TypeError.IncompatibleTypes(node, _, _, _, _) =>
          startPosOf(node.asInstanceOf[FromSource])
        case mml.mmlclib.semantic.TypeError.UntypedHoleInBinding(_, span, _) =>
          (span.start.line, span.start.col)

  /** Pretty print semantic errors */
  def prettyPrintSemanticErrors(
    errors:     List[SemanticError],
    sourceInfo: Option[SourceInfo]
  ): String =
    if errors.isEmpty then "No errors"
    else
      // Sort errors by source line and column position
      val sortedErrors = errors.sortBy(getErrorSourcePosition)
      val messages     = sortedErrors.map(err => prettyPrintSemanticError(err, sourceInfo))
      s"${messages.mkString("\n\n")}"

  /** Pretty print a single semantic error */
  private def prettyPrintSemanticError(
    error:      SemanticError,
    sourceInfo: Option[SourceInfo]
  ): String =
    // Extract AST information when applicable
    val astInfo = error match
      case SemanticError.UndefinedRef(ref, _, _) =>
        val resolved = ref.resolvedId
          .map(id => s"Resolved to: $id")
          .getOrElse("Not resolved")
        val candidates =
          if ref.candidateIds.isEmpty then "No candidates found"
          else s"Candidates: [${ref.candidateIds.mkString(", ")}]"
        s"Info: '$resolved', $candidates"

      case SemanticError.DanglingTerms(terms, _, _) =>
        val termInfos = terms
          .map {
            case ref: mml.mmlclib.ast.Ref =>
              val resolved = ref.resolvedId
                .map(id => s"Resolved to: $id")
                .getOrElse("Not resolved")
              val candidates =
                if ref.candidateIds.isEmpty then "No candidates found"
                else s"Candidates: [${ref.candidateIds.mkString(", ")}]"
              s"${Console.YELLOW}'${ref.name}': $resolved, $candidates${Console.RESET}"
            case term => s"Term: ${term.getClass.getSimpleName}"
          }
          .mkString("\n")
        s"${Console.YELLOW}Term info:${Console.RESET}\n$termInfos"

      case _ => "" // No special AST info for other error types

    val baseMessage = error match
      case SemanticError.DuplicateName(name, duplicates, phase) =>
        val locations = duplicates
          .collect { case d: FromSource => d }
          .flatMap(spanOf)
          .map(formatLocation)
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

      case SemanticError.InvalidEntryPoint(message, span) =>
        s"${Console.RED}$message at ${formatLocation(span)}${Console.RESET}"

      case SemanticError.UseAfterMove(ref, movedAt, phase) =>
        s"${Console.RED}Use of '${ref.name}' after move at ${formatLocation(ref.span)}${Console.RESET}\n${Console.YELLOW}Moved at: ${formatLocation(movedAt)}, Phase: $phase${Console.RESET}"

      case SemanticError.ConsumingParamNotLastUse(param, ref, phase) =>
        s"${Console.RED}Consuming parameter '${param.name}' must be the last use of '${ref.name}' at ${formatLocation(ref.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.PartialApplicationWithConsuming(fn, param, phase) =>
        s"${Console.RED}Cannot partially apply function with consuming parameter '${param.name}' at ${formatLocation(fn.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.ConditionalOwnershipMismatch(cond, phase) =>
        s"${Console.RED}Conditional branches have different ownership states at ${formatLocation(cond.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.BorrowEscapeViaReturn(ref, phase) =>
        s"${Console.RED}Cannot return borrowed value '${ref.name}' at ${formatLocation(ref.span)}${Console.RESET}\n${Console.YELLOW}Phase: $phase${Console.RESET}"

      case SemanticError.TypeCheckingError(error) =>
        // Delegate to SemanticErrorPrinter to avoid duplication
        SemanticErrorPrinter.prettyPrintTypeError(error)

    // Add AST info and source code snippets if source code is available
    sourceInfo match
      case Some(info) =>
        val codeSnippet = SourceCodeExtractor.extractSnippetsFromError(info, error)
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
  def prettyPrintCodeGenErrors(
    errors:     List[mml.mmlclib.codegen.emitter.CodeGenError],
    sourceInfo: Option[SourceInfo]
  ): String =
    if errors.isEmpty then "No code generation errors"
    else
      val messages = errors.map { err =>
        val baseMessage =
          s"${Console.RED}Code generation error: ${err.message}${Console.RESET}\n${Console.YELLOW}Phase: Code Generation${Console.RESET}"

        val snippetAndLocation =
          (err.node.collect { case fs: FromSource => fs }.flatMap(spanOf), sourceInfo) match
            case (Some(span), Some(info)) =>
              SourceCodeExtractor
                .extractSnippet(info, span)
                .map(snippet => s"${formatLocation(span)}\n$snippet")
                .getOrElse(formatLocation(span)) // Fallback if snippet extraction fails
            case (Some(span), None) => formatLocation(span)
            case (None, _) => ""

        if snippetAndLocation.nonEmpty then s"$baseMessage\n$snippetAndLocation"
        else baseMessage
      }
      messages.mkString("\n\n")

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
    case LlvmCompilationError.TripleResolutionError(msg) =>
      s"Target triple resolution failed: $msg"
