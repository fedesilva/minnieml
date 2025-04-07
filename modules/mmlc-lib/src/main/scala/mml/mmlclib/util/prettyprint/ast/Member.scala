package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintMember(
  member:          Member,
  indent:          Int,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  member match {
    case MemberError(span, message, failedCode) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      s"${indentStr}MemberError $spanStr\n" +
        s"""${indentStr}  "$message"""".stripMargin +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")

    case fn: FnDef =>
      val spanStr = if showSourceSpans then printSourceSpan(fn.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(fn.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(fn.typeAsc)}"
        else ""

      s"${indentStr}FnDef ${fn.name}$spanStr$typeStr\n" +
        fn.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 1)}\n").getOrElse("") +
        s"${indentStr}  params: ${prettyPrintParams(fn.params, indent + 1, showSourceSpans, showTypes)}\n" +
        prettyPrintExpr(fn.body, indent + 1, showSourceSpans, showTypes)

    case bnd: Bnd =>
      val spanStr = if showSourceSpans then printSourceSpan(bnd.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(bnd.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(bnd.typeAsc)}"
        else ""

      s"${indentStr}Bnd ${bnd.name}$spanStr$typeStr\n" +
        bnd.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        prettyPrintExpr(bnd.value, indent + 2, showSourceSpans, showTypes)

    case BinOpDef(span, name, param1, param2, prec, assoc, body, typeSpec, typeAsc, docComment) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}BinOpDef $name $spanStr$typeStr\n" +
        docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  param1: ${prettyPrintParams(Seq(param1), indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  param2: ${prettyPrintParams(Seq(param2), indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  precedence: $prec\n" +
        s"${indentStr}  associativity: $assoc\n" +
        prettyPrintExpr(body, indent + 2, showSourceSpans, showTypes)

    case UnaryOpDef(span, name, param, precedence, assoc, body, typeSpec, typeAsc, docComment) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      s"${indentStr}UnaryOpDef $name $spanStr$typeStr\n" +
        docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  param: ${prettyPrintParams(Seq(param), indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  precedence: $precedence\n" +
        s"${indentStr}  associativity: $assoc\n" +
        prettyPrintExpr(body, indent + 2, showSourceSpans, showTypes)
  }

def prettyPrintParams(
  params:          Seq[FnParam],
  indent:          Int,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  params
    .map { case FnParam(span, name, typeSpec, typeAsc, doc) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""

      val docStr = doc.map(d => s"\n${prettyPrintDocComment(d, indent + 2)}").getOrElse("")
      s"${name}$spanStr$docStr$typeStr"
    }
    .mkString(", ")
