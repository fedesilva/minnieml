package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.ast.{Decl, FromSource}
import mml.mmlclib.semantic.SemanticError
// Removed Ordering import

/** Pretty printer for semantic errors */
object SemanticErrorPrinter:

  /** Pretty print a list of semantic errors
    *
    * @param errors
    *   List of semantic errors
    * @param sourceCode
    *   Optional source code string
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[SemanticError], sourceCode: Option[String] = None): String =
    if errors.isEmpty then "No semantic errors"
    else
      val errorMessages = errors.map(error => prettyPrintSingle(error, sourceCode))
      s"${Console.RED}Semantic errors:${Console.RESET}\n${errorMessages.mkString("\n\n")}"

  /** Pretty print a single semantic error
    *
    * @param error
    *   The semantic error to pretty print
    * @param sourceCode
    *   Optional source code string
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: SemanticError, sourceCode: Option[String]): String =
    // Extract AST information when applicable
    val astInfo = error match
      case SemanticError.UndefinedRef(ref, _) =>
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

      case SemanticError.DanglingTerms(terms, _) =>
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
              s"'${ref.name}': $resolved, $candidates"
            case term => s"Term: ${term.getClass.getSimpleName}"
          }
          .mkString("\n")
        s"${Console.YELLOW}Term info:${Console.RESET}\n$termInfos"

      case _ => "" // No special AST info for other error types

    // Generate the base error message without source code snippets
    val baseMessage = error match
      case SemanticError.UndefinedRef(ref, member) =>
        val location = LocationPrinter.printSpan(ref.span)
        val memberName = member match
          case d: Decl => d.name
          case _ => "unknown"
        s"${Console.RED}Undefined reference '${ref.name}' at $location in $memberName${Console.RESET}"

      case SemanticError.DuplicateName(name, duplicates) =>
        // Sort duplicates by their starting index using sortBy
        val sortedDuplicates = duplicates
          .collect { case d: FromSource => d }
          .sortBy(_.span.start.index) // Use sortBy
        val locations = sortedDuplicates // Use sorted list
          .map(d => LocationPrinter.printSpan(d.span))
          .mkString(" ") // Use space separator
        s"${Console.RED}Duplicate name '$name' defined at: $locations${Console.RESET}"

      case SemanticError.InvalidExpression(expr, message) =>
        val location = LocationPrinter.printSpan(expr.span)
        s"${Console.RED}Invalid expression at $location: $message${Console.RESET}"

      case SemanticError.MemberErrorFound(error) =>
        val location = LocationPrinter.printSpan(error.span)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"${Console.RED}Parser error at $location: ${error.message}\n$snippet${Console.RESET}"

      case SemanticError.DanglingTerms(terms, message) =>
        val locations = terms.map(t => LocationPrinter.printSpan(t.span)).mkString(", ")
        s"${Console.RED}$message at $locations${Console.RESET}"

    // Add AST info and source code snippets if source code is available
    sourceCode match
      case Some(src) =>
        // Pass both the source code and the error to extractSnippetsFromError
        val snippets = SourceCodeExtractor.extractSnippetsFromError(src, error)
        val fullMessage =
          if astInfo.nonEmpty then s"$baseMessage\n$astInfo\n$snippets"
          else s"$baseMessage\n$snippets"
        fullMessage
      case None =>
        if astInfo.nonEmpty then s"$baseMessage\n$astInfo"
        else baseMessage
