package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.ast.{FromSource, SrcSpan}
import mml.mmlclib.semantic.SemanticError

import scala.io.Source
import scala.util.Try

/** Utility for extracting source code snippets for error reporting */
object SourceCodeExtractor:

  /** Thread-local cache of files we've already read */
  private val sourceFileCache =
    ThreadLocal.withInitial(() => scala.collection.mutable.Map.empty[String, Array[String]])

  /** Current source file being processed - a simplification for demo purposes. In a real
    * implementation, SrcSpan would include file information
    */
  private var currentSourceFile: Option[String] = None

  /** ANSI color codes for terminal output */
  object Colors:
    val Red:        String = Console.RED
    val Green:      String = Console.GREEN
    val Yellow:     String = Console.YELLOW
    val Blue:       String = Console.BLUE
    val Purple:     String = Console.MAGENTA
    val Cyan:       String = Console.CYAN
    val White:      String = Console.WHITE
    val Reset:      String = Console.RESET
    val Bold:       String = Console.BOLD
    val Underlined: String = Console.UNDERLINED

  /** Set the current source file being processed
    *
    * @param filePath
    *   Path to the source file
    */
  def setCurrentSourceFile(filePath: String): Unit =
    currentSourceFile = Some(filePath)

    // Pre-cache the file contents
    if !sourceFileCache.get().contains(filePath) then
      Try {
        val lines = Source.fromFile(filePath).getLines().toArray
        sourceFileCache.get().put(filePath, lines)
      }.recover { case _ =>
      // Silent failure - we'll handle missing files gracefully later
      }

  /** Extract source code snippets for a semantic error
    *
    * @param error
    *   The semantic error
    * @return
    *   A formatted string with source code snippets
    */
  def extractSnippetsFromError(error: SemanticError): String = error match
    case SemanticError.DuplicateName(name, duplicates) =>
      // For duplicates, we have multiple spans. Find their source lines and show each one
      val sourceLines = findSourceLines()

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

        s"\nAt ${Colors.Yellow}$location${Colors.Reset}:\n$snippet"
      }
      snippets.mkString("\n")

    case SemanticError.UndefinedRef(ref, _) =>
      extractSnippet(ref.span, nameHighlightSpan = Some(ref.span))
        .map(s => s"\n$s")
        .getOrElse("")

    case SemanticError.InvalidExpression(expr, _) =>
      extractSnippet(expr.span, highlightExpr = true)
        .map(s => s"\n$s")
        .getOrElse("")

  /** Find all source lines in the current file */
  private def findSourceLines(): Array[String] =
    currentSourceFile
      .flatMap { file =>
        Try {
          sourceFileCache
            .get()
            .getOrElseUpdate(
              file,
              Source.fromFile(file).getLines().toArray
            )
        }.toOption
      }
      .getOrElse(Array.empty)

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
      val lineNumStr = s"${Colors.Cyan}$numStr |${Colors.Reset}"

      // Split the line into three parts: before, target, after
      val beforeText = line.substring(0, index)
      val targetText = line.substring(index, index + name.length)
      val afterText  = line.substring(index + name.length)

      // Format with highlight
      val highlightedText = s"${Colors.Red}${Colors.Bold}$targetText${Colors.Reset}"
      val formattedLine   = s"$lineNumStr $beforeText$highlightedText$afterText"

      // Add an underline
      val indentSize = lineNumStr.length + 1 + index
      val underline = " " * indentSize +
        s"${Colors.Green}${Colors.Bold}" +
        "^" * name.length +
        Colors.Reset

      s"$formattedLine\n$underline"
    else
      // If we can't find the name, just show the line normally
      val numStr     = lineNum.toString.reverse.padTo(4, ' ').reverse
      val lineNumStr = s"${Colors.Cyan}$numStr |${Colors.Reset}"
      s"$lineNumStr $line"

  /** Extract a source code snippet around a source span
    *
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
    span:              SrcSpan,
    contextLines:      Int             = 1,
    nameHighlightSpan: Option[SrcSpan] = None,
    highlightExpr:     Boolean         = false
  ): Option[String] =
    currentSourceFile.flatMap { file =>
      Try {
        // Get cached file contents
        val lines = sourceFileCache
          .get()
          .getOrElseUpdate(
            file,
            Source.fromFile(file).getLines().toArray
          )

        // Calculate the line range with context
        val startLine = math.max(0, span.start.line - contextLines - 1) // 0-based index
        val endLine = math.min(lines.length - 1, span.end.line + contextLines - 1) // 0-based index

        // Extract the lines
        val codeLines = lines.slice(startLine, endLine + 1)

        // Format with line numbers
        val lineNumbers =
          (startLine + 1 to endLine + 1)
            .map(i => {
              val numStr = i.toString.reverse.padTo(4, ' ').reverse
              s"${Colors.Cyan}$numStr |${Colors.Reset}"
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

        processedLines.mkString("\n")
      }.toOption
    }

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
    val result = scala.collection.mutable.ListBuffer[String]()

    for (((line, num), idx) <- codeLines.zip(lineNumbers).zipWithIndex) {
      val lineNum = idx + startLine + 1 // 1-based line number

      // Check if this is a line with a highlight
      val isHighlightLine = lineNum == highlightSpan.start.line

      if isHighlightLine then
        if highlightExpr then
          // Highlight entire line
          val highlightedLine = s"$num ${Colors.Red}$line${Colors.Reset}"
          result += highlightedLine
        else
          // Highlight just the specific identifier
          val (beforeText, targetText, afterText) = splitLineAtSpan(line, highlightSpan)
          val highlightedText = s"${Colors.Red}${Colors.Bold}$targetText${Colors.Reset}"
          val formattedLine   = s"$num $beforeText$highlightedText$afterText"
          result += formattedLine

          // Add an underline below the highlighted text
          val indentSize = num.length + 1 + (highlightSpan.start.col - 1)
          val underline = " " * indentSize +
            s"${Colors.Green}${Colors.Bold}" +
            "^" * targetText.length +
            Colors.Reset
          result += underline
      else
        // Regular line without highlighting
        result += s"$num $line"
    }

    result.toList

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
