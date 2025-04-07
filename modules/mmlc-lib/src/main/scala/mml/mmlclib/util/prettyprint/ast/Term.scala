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
    case MehRef(sp, typeSpec, typeAsc) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}MehRef $spanStr$typeStr"

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

      s"${indentStr}Tuple $spanStr$typeStr\n" +
        elements.toList
          .map(e => prettyPrintExpr(e, indent + 2, showSourceSpans, showTypes))
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

    case TermError(sp, message, failedCode) =>
      val spanStr = if showSourceSpans then printSourceSpan(sp) else ""
      s"${indentStr}TermError $spanStr\n" +
        s"""${indentStr}  "$message"""" +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")
  }
