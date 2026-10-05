package mml.mmlclib.ast

/** A native library name and its location in the module header. */
case class LinkEntry(span: SrcSpan, name: String) extends FromSource:
  def source: SourceOrigin = SourceOrigin.Loc(span)

/** The optional module header declaring native library dependencies. */
case class LinkDirective(span: SrcSpan, entries: List[LinkEntry]) extends FromSource:
  def source: SourceOrigin = SourceOrigin.Loc(span)

  /** Renders the directive for AST output, LLVM IR comments, and parser diagnostics. */
  def syntax: String =
    entries.map(entry => s"\"${entry.name}\"").mkString("@link[", ", ", "];")
