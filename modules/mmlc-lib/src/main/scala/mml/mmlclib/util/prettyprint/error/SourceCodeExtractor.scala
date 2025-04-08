package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.semantic.SemanticError

/** Utility for extracting source code snippets for error reporting */
object SourceCodeExtractor:

  /** Extract source code snippets for a semantic error
    *
    * @param sourceCode
    *   The source code string
    * @param error
    *   The semantic error
    * @return
    *   A formatted string with source code snippets
    */
  def extractSnippetsFromError(sourceCode: String, error: SemanticError): String =
    val sourceLines = sourceCode.split("\n")

    error match
      case SemanticError.DuplicateName(name, duplicates) =>
        // For duplicates, we have multiple spans. Find their source lines and show each one
        val snippets = duplicates.collect { case d: FromSource =>
          val location = LocationPrinter.printSpan(d.span)

          // Find the line where this duplicate is defined (1-based)
          val lineNumber = d.span.start.line

          // Generate a snippet focused on this specific line
          val snippet = if lineNumber > 0 && lineNumber <= sourceLines.length then
            val line        = sourceLines(lineNumber - 1) // Convert to 0-based
            val highlighted = formatLineWithHighlight(line, name, lineNumber)
            highlighted
          else "Source line not available"

          s"\nAt ${Console.YELLOW}$location${Console.RESET}:\n$snippet"
        }
        snippets.mkString("\n")

      case SemanticError.UndefinedRef(ref, _) =>
        extractSnippet(sourceCode, ref.span, nameHighlightSpan = Some(ref.span))
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.InvalidExpression(expr, _) =>
        extractSnippet(sourceCode, expr.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.MemberErrorFound(error) =>
        // For member errors, just return the failed code directly
        error.failedCode.getOrElse("Source code not available")

      case SemanticError.DanglingTerms(terms, _) =>
        // Extract snippets for each dangling term
        val snippets = terms.collect { case term: FromSource =>
          val location = LocationPrinter.printSpan(term.span)

          // Find the line where this term is defined (1-based)
          val lineNumber = term.span.start.line

          // Generate a snippet with the term highlighted
          val snippet =
            if lineNumber > 0 && lineNumber <= sourceLines.length then
              extractSnippet(sourceCode, term.span, highlightExpr = true)
                .getOrElse("Source line not available")
            else "Source line not available"

          s"\nAt ${Console.YELLOW}$location${Console.RESET}:\n$snippet"
        }
        snippets.mkString("\n")

  /** Format a line with a highlighted identifier
    *
    * @param line
    *   The source line to format
    * @param name
    *   The name to highlight
    * @param lineNum
    *   The line number (1-based)
    * @return
    *   Formatted lines with highlighting and underline
    */
  private def formatLineWithHighlight(line: String, name: String, lineNum: Int): String =
    // Find where the name appears in the line
    val index = line.indexOf(name)
    if index >= 0 then
      // Format the line number
      val numStr     = lineNum.toString.reverse.padTo(4, ' ').reverse
      val lineNumStr = s"${Console.CYAN}$numStr |${Console.RESET}"

      // Split the line into three parts: before, target, after
      val beforeText = line.substring(0, index)
      val targetText = line.substring(index, index + name.length)
      val afterText  = line.substring(index + name.length)

      // Format with highlight
      val highlightedText = s"${Console.RED}${Console.BOLD}$targetText${Console.RESET}"
      val formattedLine   = s"$lineNumStr $beforeText$highlightedText$afterText"

      // Add an underline
      val indentSize = lineNumStr.length + 1 + index
      val underline = " " * indentSize +
        s"${Console.GREEN}${Console.BOLD}" +
        "^" * name.length +
        Console.RESET

      s"$formattedLine\n$underline"
    else
      // If we can't find the name, just show the line normally
      val numStr     = lineNum.toString.reverse.padTo(4, ' ').reverse
      val lineNumStr = s"${Console.CYAN}$numStr |${Console.RESET}"
      s"$lineNumStr $line"

  /** Extract a source code snippet around a source span
    *
    * @param sourceCode
    *   The full source code string
    * @param span
    *   The source span to extract code around
    * @param contextLines
    *   Number of lines of context to include before and after
    * @param nameHighlightSpan
    *   Optional specific span to highlight (for identifiers)
    * @param highlightExpr
    *   Whether to highlight the entire expression
    * @return
    *   Option containing the formatted code snippet
    */
  def extractSnippet(
    sourceCode:        String,
    span:              SrcSpan,
    contextLines:      Int             = 1,
    nameHighlightSpan: Option[SrcSpan] = None,
    highlightExpr:     Boolean         = false
  ): Option[String] =
    // Split the source code into lines
    val lines = sourceCode.split("\n")

    if lines.isEmpty then None
    else
      // Calculate the line range with context
      val startLine = math.max(0, span.start.line - contextLines - 1) // 0-based index
      val endLine   = math.min(lines.length - 1, span.end.line + contextLines - 1) // 0-based index

      // Extract the lines
      val codeLines = lines.slice(startLine, endLine + 1)

      // Format with line numbers
      val lineNumbers =
        (startLine + 1 to endLine + 1)
          .map(i => {
            val numStr = i.toString.reverse.padTo(4, ' ').reverse
            s"${Console.CYAN}$numStr |${Console.RESET}"
          })
          .toArray

      // Format the code lines with highlighting
      val processedLines = formatCodeLines(
        codeLines,
        lineNumbers,
        startLine,
        nameHighlightSpan.getOrElse(span),
        highlightExpr
      )

      Some(processedLines.mkString("\n"))

  /** Format code lines with appropriate highlighting
    *
    * @param codeLines
    *   The source code lines to format
    * @param lineNumbers
    *   Formatted line numbers
    * @param startLine
    *   The starting line index (0-based)
    * @param highlightSpan
    *   The source span to highlight
    * @param highlightExpr
    *   Whether to highlight the entire expression
    * @return
    *   Formatted lines with highlighting
    */
  def formatCodeLines(
    codeLines:     Array[String],
    lineNumbers:   Array[String],
    startLine:     Int,
    highlightSpan: SrcSpan,
    highlightExpr: Boolean
  ): List[String] =
    val result = List.newBuilder[String]

    for (((line, num), idx) <- codeLines.zip(lineNumbers).zipWithIndex) {
      val lineNum = idx + startLine + 1 // 1-based line number

      // Check if this is a line with a highlight
      val isHighlightLine = lineNum == highlightSpan.start.line

      if isHighlightLine then
        if highlightExpr then
          // Highlight entire line
          val highlightedLine = s"$num ${Console.RED}$line${Console.RESET}"
          result += highlightedLine
        else
          // Highlight just the specific identifier
          val (beforeText, targetText, afterText) = splitLineAtSpan(line, highlightSpan)
          val highlightedText = s"${Console.RED}${Console.BOLD}$targetText${Console.RESET}"
          val formattedLine   = s"$num $beforeText$highlightedText$afterText"
          result += formattedLine

          // Add an underline below the highlighted text
          val indentSize = num.length + 1 + (highlightSpan.start.col - 1)
          val underline = " " * indentSize +
            s"${Console.GREEN}${Console.BOLD}" +
            "^" * targetText.length +
            Console.RESET
          result += underline
      else
        // Regular line without highlighting
        result += s"$num $line"
    }

    result.result()

  /** Split a line of text at a source span
    *
    * @param line
    *   The source line
    * @param span
    *   The source span
    * @return
    *   Triple of (text before span, text at span, text after span)
    */
  def splitLineAtSpan(line: String, span: SrcSpan): (String, String, String) =
    val startCol = math.max(0, span.start.col - 1) // 0-based column
    val endCol   = math.min(line.length, startCol + (span.end.col - span.start.col))

    val beforeText =
      if startCol > 0 && startCol <= line.length then line.substring(0, startCol) else ""
    val targetText =
      if startCol < line.length then line.substring(startCol, math.min(endCol, line.length)) else ""
    val afterText = if endCol < line.length then line.substring(endCol) else ""

    (beforeText, targetText, afterText)
