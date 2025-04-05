package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.ast.{Decl, FromSource}
import mml.mmlclib.semantic.SemanticError

/** Pretty printer for semantic errors */
object SemanticErrorPrinter:

  /** Pretty print a list of semantic errors
    *
    * @param errors
    *   List of semantic errors
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[SemanticError]): String =
    if errors.isEmpty then "No semantic errors"
    else
      val errorMessages = errors.map(prettyPrintSingle)
      s"Semantic errors:\n${errorMessages.mkString("\n\n")}"

  /** Pretty print a single semantic error
    *
    * @param error
    *   The semantic error to pretty print
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: SemanticError): String = error match
    case SemanticError.UndefinedRef(ref, member) =>
      val location = LocationPrinter.printSpan(ref.span)
      val memberName = member match
        case d: Decl => d.name
        case _ => "unknown"
      s"Undefined reference '${ref.name}' at $location in $memberName"

    case SemanticError.DuplicateName(name, duplicates) =>
      val locations = duplicates
        .map {
          case d: FromSource => LocationPrinter.printSpan(d.span)
          case _ => "unknown location"
        }
        .mkString(", ")

      val snippets = SourceCodeExtractor.extractSnippetsFromError(error)

      s"""Duplicate name '$name' defined at: $locations
         |$snippets""".stripMargin

    case SemanticError.InvalidExpression(expr, message) =>
      val location = LocationPrinter.printSpan(expr.span)
      s"Invalid expression at $location: $message"
