package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintExpr(
  expr:            Expr,
  indent:          Int,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  val spanStr   = if showSourceSpans then printSourceSpan(expr.span) else ""
  val typeStr =
    if showTypes then
      s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(expr.typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(expr.typeAsc)}"
    else ""

  s"${indentStr}Expr$spanStr$typeStr\n" +
    expr.terms.map(prettyPrintTerm(_, indent + 2, showSourceSpans, showTypes)).mkString("\n")
