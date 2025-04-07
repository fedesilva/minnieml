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
      s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(expr.typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(expr.typeAsc)}"
    else ""

  val termsStr =
    expr.terms.map(prettyPrintTerm(_, indent + 1, showSourceSpans, showTypes)).mkString("\n")

  s"${indentStr}Expr$spanStr\n" +
    (if typeStr.nonEmpty then s"$typeStr\n" else "") +
    (if termsStr.nonEmpty then s"$termsStr" else "")
