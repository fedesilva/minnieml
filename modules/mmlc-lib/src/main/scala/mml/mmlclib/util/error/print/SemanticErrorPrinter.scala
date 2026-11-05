package mml.mmlclib.util.error.print

import mml.mmlclib.ast.{FromSource, Resolvable}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.{SemanticError, TypeError}
// Removed Ordering import

/** Pretty printer for semantic errors */
object SemanticErrorPrinter:
  private def spanOf(node: FromSource): Option[mml.mmlclib.ast.SrcSpan] =
    node.source.spanOpt

  private def locationOf(node: FromSource): String =
    spanOf(node).map(LocationPrinter.printSpan).getOrElse("<unknown>")

  /** Pretty print a list of semantic errors
    *
    * @param errors
    *   List of semantic errors
    * @param sourceInfo
    *   Optional source info with the original source text
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[SemanticError], sourceInfo: Option[SourceInfo] = None): String =
    if errors.isEmpty then "No semantic errors"
    else
      val errorMessages = errors.map(error => prettyPrintSingle(error, sourceInfo))
      s"${Console.RED}Semantic errors:${Console.RESET}\n${errorMessages.mkString("\n\n")}"

  /** Pretty print a single semantic error
    *
    * @param error
    *   The semantic error to pretty print
    * @param sourceInfo
    *   Optional source info with the original source text
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: SemanticError, sourceInfo: Option[SourceInfo]): String =
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
              s"'${ref.name}': $resolved, $candidates"
            case term => s"Term: ${diagnosticTermName(term)}"
          }
          .mkString("\n")
        s"${Console.YELLOW}Term info:${Console.RESET}\n$termInfos"

      case _ => "" // No special AST info for other error types

    // Generate the base error message without source code snippets
    val semantic = error
    val baseMessage = error match
      case SemanticError.UndefinedRef(ref, member, phase) =>
        val location = locationOf(ref)
        val memberName = member match
          case d: Resolvable => d.name
          case _ => "unknown"
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location in $memberName [phase: $phase]${Console.RESET}"

      case SemanticError.UndefinedTypeRef(typeRef, member, phase) =>
        val location = locationOf(typeRef)
        val memberName = member match
          case d: Resolvable => d.name
          case _ => "unknown"
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location in $memberName [phase: $phase]${Console.RESET}"

      case SemanticError.AmbiguousTypeRef(typeRef, candidates, _, phase) =>
        val locations = candidates.collect { case candidate: FromSource => locationOf(candidate) }
        s"${Console.RED}${semanticErrorMessage(semantic)} at ${locationOf(typeRef)}: " +
          s"candidates at ${locations.mkString(", ")} [phase: $phase]${Console.RESET}"

      case SemanticError.DuplicateName(_, duplicates, phase, _) =>
        // Sort duplicates by their starting index using sortBy
        val sortedDuplicates = duplicates
          .collect { case d: FromSource => d }
          .flatMap(d => spanOf(d).map(s => (d, s)))
          .sortBy(_._2.start.index)
        val locations = sortedDuplicates // Use sorted list
          .map { case (_, s) => LocationPrinter.printSpan(s) }
          .mkString(" ") // Use space separator
        s"${Console.RED}${semanticErrorMessage(semantic)} defined at: $locations [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidExpression(expr, message, phase) =>
        val location = locationOf(expr)
        s"${Console.RED}Invalid expression at $location: ${expressionMessage(message)} [phase: $phase]${Console.RESET}"

      case SemanticError.MemberErrorFound(error, phase) =>
        val location = locationOf(error)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Parser error at $location: ${diagnosticMessage(error)} [phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.ParsingIdErrorFound(error, phase) =>
        val location = locationOf(error)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Invalid identifier at $location: ${diagnosticMessage(error)} [phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.ParseTermErrorFound(error, phase) =>
        val location = locationOf(error)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Parser error at $location: ${diagnosticMessage(error)} " +
          s"[phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.ParseTypeErrorFound(error, phase) =>
        val location = locationOf(error)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Type parser error at $location: ${diagnosticMessage(error)} " +
          s"[phase: $phase]\n$snippet${Console.RESET}"

      case SemanticError.DanglingTerms(terms, message, phase) =>
        val locations = terms.map(t => locationOf(t)).mkString(", ")
        s"${Console.RED}${expressionMessage(message)} at $locations [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidExpressionFound(invalidExpr, phase) =>
        val location = locationOf(invalidExpr)
        s"${Console.RED}Invalid expression found at $location [phase: $phase]${Console.RESET}"

      case SemanticError.InvalidEntryPoint(error) =>
        val location = error.source.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        s"${Console.RED}${entryPointMessage(error)} at $location${Console.RESET}"

      case SemanticError.VisibilityViolation(ref, decl, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case error: SemanticError.UseAfterMove =>
        val location = error.source.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        val movedLocation =
          error.movedAt.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        val use = s"${semanticErrorMessage(semantic)}\nUse at: $location [phase: ${error.phase}]"
        s"${Console.RED}$use${Console.RESET}\nMoved at: $movedLocation"

      case SemanticError.ConsumingParamNotLastUse(param, ref, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.BorrowedValuePassedToConsumingParam(param, ref, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.PartialApplicationWithConsuming(fn, param, phase) =>
        val location = locationOf(fn)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.ConditionalOwnershipMismatch(cond, phase) =>
        val location = locationOf(cond)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.BorrowEscapeViaReturn(ref, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.CapturedBorrowedHeapBinding(ref, phase) =>
        val location = locationOf(ref)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.CapturedMovedHeapBinding(ref, movedAt, phase) =>
        val location      = locationOf(ref)
        val movedLocation = movedAt.spanOpt.map(LocationPrinter.printSpan).getOrElse("<unknown>")
        s"${Console.RED}${semanticErrorMessage(semantic)}\nUse at: $location [phase: $phase]${Console.RESET}\nMoved at: $movedLocation"

      case SemanticError.BorrowClosureEscapeViaReturn(lambda, phase) =>
        val location = locationOf(lambda)
        s"${Console.RED}${semanticErrorMessage(semantic)} at $location [phase: $phase]${Console.RESET}"

      case SemanticError.TypeCheckingError(error) =>
        prettyPrintTypeError(error)

      case SemanticError.ProtocolErrorFound(error, phase) =>
        val location = error.source.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
        val (headline, details) = diagnosticMessage(error).span(_ != '\n')
        s"${Console.RED}$headline at $location [phase: $phase]${Console.RESET}$details"

    // Add AST info and source code snippets if source code is available
    sourceInfo match
      case Some(info) =>
        // Pass both the source code and the error to extractSnippetsFromError
        val snippets = SourceCodeExtractor.extractSnippetsFromError(info, error)
        val fullMessage =
          if astInfo.nonEmpty then s"$baseMessage\n$astInfo\n$snippets"
          else s"$baseMessage\n$snippets"
        fullMessage
      case None =>
        if astInfo.nonEmpty then s"$baseMessage\n$astInfo"
        else baseMessage

  /** Pretty print a type error with proper formatting
    *
    * @param error
    *   The type error to pretty print
    * @return
    *   A human-readable error message
    */
  def prettyPrintTypeError(error: TypeError): String =
    val (source, phase) = typeErrorContext(error)
    val location        = source.spanOpt.map(LocationPrinter.printSpan).getOrElse("[synthetic]")
    s"${Console.RED}${typeErrorMessage(error)} at $location${Console.RESET}\n" +
      s"${Console.YELLOW}Phase: $phase${Console.RESET}"
