package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintCandidates(candidates: List[Resolvable]): String =
  if candidates.isEmpty then "[]"
  else s"[${candidates.map(c => s"${c.getClass.getSimpleName} ${c.name}").mkString(", ")}]"

def prettyPrintTerm(
  term:            Term,
  indent:          Int,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  term match {
    case Placeholder(sp, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}Placeholder $spanStr$typeStr"

    case ref: Ref =>
      val spanStr = if showSourceSpans then printSourceSpan(ref.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(ref.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(ref.typeAsc)}\n" +
            s"${indentStr}  resolvedAs: ${ref.resolvedAs
                .fold("None")(m => s"${m.getClass.getSimpleName} ${m.name}")}"
        else ""

      // Add resolvedAs display if not showing types and resolvedAs is present
      val resolvedStr =
        if !showTypes then
          ref.resolvedAs.fold("")(r =>
            s"\n${indentStr}  resolvedAs: ${r.getClass.getSimpleName} ${r.name}"
          )
        else ""

      s"${indentStr}Ref ${ref.name} $spanStr$typeStr$resolvedStr\n" +
        s"${indentStr}  candidates: ${prettyPrintCandidates(ref.candidates)}"

    case Hole(sp, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}Hole $spanStr$typeStr"

    case e: Expr => prettyPrintExpr(e, indent, showSourceSpans, showTypes)

    case TermGroup(sp, inner, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then s"\n${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}" else ""

      s"${indentStr}GroupTerm$spanStr$typeStr\n" +
        prettyPrintExpr(inner, indent + 1, showSourceSpans, showTypes)

    case Cond(sp, cond, ifTrue, ifFalse, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}Cond $spanStr$typeStr\n" +
        s"${indentStr}  cond:\n${prettyPrintExpr(cond, indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  ifTrue:\n${prettyPrintExpr(ifTrue, indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  ifFalse:\n${prettyPrintExpr(ifFalse, indent + 2, showSourceSpans, showTypes)}"

    case Tuple(sp, elements, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      val tupleIndentStr          = "  " * indent // Indent for the Tuple line itself
      val elementContentIndentStr = "  " * (indent + 1) // Indent for content *within* the Expr

      s"${tupleIndentStr}Tuple $spanStr$typeStr\n" +
        elements.toList.zipWithIndex
          .map { case (e, i) =>
            // Generate the expression string with indent + 1 so children are indented correctly
            val exprStrRaw = prettyPrintExpr(e, indent + 1, showSourceSpans, showTypes)
            val lines      = exprStrRaw.linesIterator.toList

            if lines.isEmpty then s"${tupleIndentStr} $i:<empty expr>" // Handle empty case
            else
              // First line from prettyPrintExpr starts with elementContentIndentStr
              val firstLineRaw = lines.head
              val restOfLines  = lines.tail

              // Remove the elementContentIndentStr prefix from the first line
              // Example: "      Expr[...]" becomes "Expr[...]"
              val strippedFirstLine = firstLineRaw.stripPrefix(elementContentIndentStr)

              // Prepend the index marker using the tuple's indent level
              // Example: "    " + " 0:" + "Expr[...]" becomes "     0:Expr[...]"
              val finalFirstLine = s"${tupleIndentStr} $i:$strippedFirstLine"

              // Join back with the rest of the lines (which are already correctly indented)
              (finalFirstLine :: restOfLines).mkString("\n")
          }
          .mkString("\n")

    case App(sp, fn, arg, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""
      // Recursively pretty-print the function part
      val fnStr  = prettyPrintTerm(fn, indent + 2, showSourceSpans, showTypes)
      val argStr = prettyPrintExpr(arg, indent + 2, showSourceSpans, showTypes)
      s"${indentStr}App $spanStr$typeStr\n" +
        s"${indentStr}  fn:\n$fnStr\n" +
        s"${indentStr}  arg:\n$argStr"

    // Literal values
    case LiteralInt(sp, value) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"${indentStr}LiteralInt $value $spanStr"

    case LiteralString(sp, value) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"""${indentStr}LiteralString "$value" $spanStr"""

    case LiteralBool(sp, value) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"${indentStr}LiteralBool $value $spanStr"

    case LiteralFloat(sp, value) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"${indentStr}LiteralFloat $value $spanStr"

    case LiteralUnit(sp) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"${indentStr}LiteralUnit $spanStr"

    case NativeImpl(sp, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}NativeImpl $spanStr$typeStr"

    case TermError(sp, message, failedCode) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"${indentStr}TermError $spanStr\n" +
        s"""${indentStr}  "$message"""" +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")
  }
