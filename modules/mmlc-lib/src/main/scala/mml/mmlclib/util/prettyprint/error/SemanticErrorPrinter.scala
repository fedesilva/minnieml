package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.ast.{Decl, FromSource}
import mml.mmlclib.semantic.SemanticError

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
      s"Semantic errors:\n${errorMessages.mkString("\n\n")}"

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
    // Generate the base error message without source code snippets
    val baseMessage = error match
      case SemanticError.UndefinedRef(ref, member) =>
        val location = LocationPrinter.printSpan(ref.span)
        val memberName = member match
          case d: Decl => d.name
          case _ => "unknown"
        s"Undefined reference '${ref.name}' at $location in $memberName"

      case SemanticError.DuplicateName(name, duplicates) =>
        val locations = duplicates
          .collect { case d: FromSource => LocationPrinter.printSpan(d.span) }
          .mkString(", ")
        s"Duplicate name '$name' defined at: $locations"

      case SemanticError.InvalidExpression(expr, message) =>
        val location = LocationPrinter.printSpan(expr.span)
        s"Invalid expression at $location: $message"

      case SemanticError.MemberErrorFound(error) =>
        val location = LocationPrinter.printSpan(error.span)
        val snippet  = error.failedCode.getOrElse("<no code available>")
        s"Parser error at $location: ${error.message}\n$snippet"

      case SemanticError.DanglingTerms(terms, message) =>
        val locations = terms.map(t => LocationPrinter.printSpan(t.span)).mkString(", ")
        s"$message at $locations"

    // Add source code snippets if source code is available
    sourceCode match
      case Some(src) =>
        // Pass both the source code and the error to extractSnippetsFromError
        val snippets = SourceCodeExtractor.extractSnippetsFromError(src, error)
        s"$baseMessage\n$snippets"
      case None => baseMessage
