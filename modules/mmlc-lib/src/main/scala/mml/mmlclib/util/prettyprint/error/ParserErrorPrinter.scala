package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.parser.ParserError

/** Pretty printer for parser errors */
object ParserErrorPrinter:

  /** Pretty print a list of parser errors
    *
    * @param errors
    *   List of parser errors
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[ParserError]): String =
    if errors.isEmpty then "No parser errors"
    else
      val errorMessages = errors.map(prettyPrintSingle)
      s"Parser errors:\n${errorMessages.mkString("\n")}"

  /** Pretty print a single parser error
    *
    * @param error
    *   The parser error to pretty print
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: ParserError): String = error match
    case ParserError.Failure(message) =>
      val formattedMessage = formatParserMessage(message)
      s"Parser failure: $formattedMessage"
    case ParserError.Unknown(message) =>
      s"Unknown parser error: $message"

  /** Format a parser error message for better readability
    *
    * @param message
    *   The raw parser error message
    * @return
    *   A formatted message
    */
  private def formatParserMessage(message: String): String =
    // Clean up FastParse error messages
    // This can be expanded to better format the error traces
    message.split("\n").take(3).mkString("\n  ")
