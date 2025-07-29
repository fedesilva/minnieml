package mml.mmlclib.util.error.print

import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.semantic.{SemanticError, TypeError}

import scala.math.Ordering // Added Ordering import

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
      case SemanticError.DuplicateName(name, duplicates, _) =>
        // Sort duplicates by their starting index
        val sortedDuplicates =
          duplicates.collect { case d: FromSource => d }.sortBy(_.span.start.index) // Sort by index

        // Iterate over sorted duplicates
        val snippets = sortedDuplicates.map { d => // Use sorted list
          val location = LocationPrinter.printSpan(d.span)

          // Use extractSnippet with the specific span of the duplicate name
          // Use default contextLines=1, highlightExpr=false to highlight just the name span
          val snippet =
            extractSnippet(sourceCode, d.span, highlightExpr = false) // Use extractSnippet
              .getOrElse("Source line not available")

          s"\nAt ${Console.YELLOW}$location${Console.RESET}:\n$snippet"
        }
        snippets.mkString("\n")

      case SemanticError.UndefinedRef(ref, _, _) =>
        extractSnippet(sourceCode, ref.span, nameHighlightSpan = Some(ref.span))
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.UndefinedTypeRef(typeRef, _, _) =>
        extractSnippet(sourceCode, typeRef.span, nameHighlightSpan = Some(typeRef.span))
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.InvalidExpression(expr, _, _) =>
        extractSnippet(sourceCode, expr.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.MemberErrorFound(error, _) =>
        // For member errors, extract snippet with the error span highlighted
        extractSnippet(sourceCode, error.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.ParsingIdErrorFound(error, _) =>
        // For identifier errors, extract snippet with the invalid identifier highlighted
        extractSnippet(sourceCode, error.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.DanglingTerms(terms, _, _) =>
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

      case SemanticError.InvalidExpressionFound(invalidExpr, _) =>
        extractSnippet(sourceCode, invalidExpr.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case SemanticError.TypeCheckingError(error) =>
        extractTypeErrorSnippet(sourceCode, error)

  /** Extract source code snippet for a type checking error */
  private def extractTypeErrorSnippet(sourceCode: String, error: TypeError): String =
    error match
      case TypeError.MissingParameterType(param, fnDef, _) =>
        extractSnippet(sourceCode, param.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.MissingReturnType(fnDef, _) =>
        extractSnippet(sourceCode, fnDef.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.MissingOperatorParameterType(param, opDef, _) =>
        extractSnippet(sourceCode, param.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.MissingOperatorReturnType(opDef, _) =>
        extractSnippet(sourceCode, opDef.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.TypeMismatch(node, _, _, _) =>
        node match
          case fs: FromSource =>
            extractSnippet(sourceCode, fs.span, highlightExpr = true)
              .map(s => s"\n$s")
              .getOrElse("")

      case TypeError.UndersaturatedApplication(app, _, _, _) =>
        extractSnippet(sourceCode, app.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.OversaturatedApplication(app, _, _, _) =>
        extractSnippet(sourceCode, app.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.InvalidApplication(app, _, _, _) =>
        extractSnippet(sourceCode, app.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.ConditionalBranchTypeMismatch(cond, _, _, _) =>
        extractSnippet(sourceCode, cond.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.ConditionalBranchTypeUnknown(cond, _) =>
        extractSnippet(sourceCode, cond.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.UnresolvableType(typeRef, node, _) =>
        extractSnippet(sourceCode, typeRef.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

      case TypeError.IncompatibleTypes(node, _, _, _, _) =>
        node match
          case fs: FromSource =>
            extractSnippet(sourceCode, fs.span, highlightExpr = true)
              .map(s => s"\n$s")
              .getOrElse("")
          case _ => ""

      case TypeError.UntypedHoleInBinding(bnd, _) =>
        extractSnippet(sourceCode, bnd.span, highlightExpr = true)
          .map(s => s"\n$s")
          .getOrElse("")

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
      val currentLineNum = idx + startLine + 1 // 1-based line number

      // Check if the current line is within the highlight span's line range
      val isWithinHighlightLines =
        currentLineNum >= highlightSpan.start.line && currentLineNum <= highlightSpan.end.line

      if isWithinHighlightLines then
        // Determine the columns to highlight on this specific line
        val highlightStartCol =
          if currentLineNum == highlightSpan.start.line then highlightSpan.start.col
          else 1 // Start at col 1 for subsequent lines
        val highlightEndCol =
          if currentLineNum == highlightSpan.end.line then highlightSpan.end.col
          else line.length + 1 // Go to end of line for preceding/middle lines

        // Adjust for 0-based indexing and ensure bounds are valid
        val startIdx = math.max(0, highlightStartCol - 1)
        // Use endCol - 1 for substring's exclusive end index. Clamp to line length.
        val endIdx = math.min(line.length, highlightEndCol - 1)

        // Ensure startIdx is not beyond line length and startIdx is before endIdx
        if startIdx < line.length && startIdx < endIdx then
          val beforeText = line.substring(0, startIdx)
          val targetText = line.substring(startIdx, endIdx)
          val afterText  = line.substring(endIdx)

          // Apply highlighting (RED for the text)
          // Use highlightExpr flag - if true, highlight whole span, else just the specific part (though for duplicates, the span IS the specific part)
          // For simplicity now, let's always highlight the calculated targetText red.
          val highlightedText = s"${Console.RED}${Console.BOLD}$targetText${Console.RESET}"
          val formattedLine   = s"$num $beforeText$highlightedText$afterText"
          result += formattedLine

          // Add underline (squiggle - GREEN)
          val lineNumPrefixWidth = 7 // "XXXX | "
          val indentSize         = lineNumPrefixWidth + startIdx
          val underlineLength    = targetText.length

          // Only add underline if length is positive
          if underlineLength > 0 then
            val underline = " " * indentSize +
              s"${Console.GREEN}${Console.BOLD}" +
              "^" * underlineLength +
              Console.RESET
            result += underline
          // No else needed for underline
        else
          // Span doesn't actually cover anything visible on this line (e.g., empty line, or span ends at col 1)
          result += s"$num $line"
        // End of inner if
      else
        // Regular line outside the highlight span
        result += s"$num $line"
      // End of outer if
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
