package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def memberVisibilityToString(visibility: MemberVisibility): String =
  visibility match
    case MemberVisibility.Public => "pub"
    case MemberVisibility.Protected => "prot"
    case MemberVisibility.Private => "priv"

/** Extract a simple name representation from a TypeSpec */
def typeSpecToSimpleName(typeSpec: TypeSpec): String =
  typeSpec match
    case TypeRef(_, name, _) => name
    case NativeTypeImpl(_) => "@native"
    case TypeUnit(_) => "()"
    case TypeFn(_, params, ret) => s"(${params.map(typeSpecToSimpleName).mkString(" -> ")}) -> ${typeSpecToSimpleName(ret)}"
    case TypeTuple(_, elems) => s"(${elems.map(typeSpecToSimpleName).mkString(", ")})"
    case _ => typeSpec.getClass.getSimpleName

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
      val visStr = memberVisibilityToString(fn.visibility)

      s"${indentStr}FnDef $visStr ${fn.name}$spanStr$typeStr\n" +
        // fn.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 1)}\n").getOrElse("") +
        s"${indentStr}  params: ${prettyPrintParams(fn.params, indent + 1, showSourceSpans, showTypes)}\n" +
        prettyPrintExpr(fn.body, indent + 1, showSourceSpans, showTypes)

    case bnd: Bnd =>
      val spanStr = if showSourceSpans then printSourceSpan(bnd.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(bnd.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(bnd.typeAsc)}"
        else ""
      val visStr = memberVisibilityToString(bnd.visibility)

      s"${indentStr}Bnd $visStr ${bnd.name}$spanStr$typeStr\n" +
        // bnd.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        prettyPrintExpr(bnd.value, indent + 2, showSourceSpans, showTypes)

    case BinOpDef(
          visibility,
          span,
          name,
          param1,
          param2,
          prec,
          assoc,
          body,
          typeSpec,
          typeAsc,
          docComment
        ) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""
      val visStr = memberVisibilityToString(visibility)

      s"${indentStr}BinOpDef $visStr $name $spanStr$typeStr\n" +
        // docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  param1: ${prettyPrintParams(Seq(param1), indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  param2: ${prettyPrintParams(Seq(param2), indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  precedence: $prec\n" +
        s"${indentStr}  associativity: $assoc\n" +
        prettyPrintExpr(body, indent + 2, showSourceSpans, showTypes)

    case unop @ UnaryOpDef(_, _, _, _, _, _, _, _, _, _) =>
      val spanStr = if showSourceSpans then printSourceSpan(unop.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(unop.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(unop.typeAsc)}"
        else ""
      val visStr = memberVisibilityToString(unop.visibility)

      s"${indentStr}UnaryOpDef $visStr ${unop.name} $spanStr$typeStr\n" +
        // unop.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  param: ${prettyPrintParams(Seq(unop.param), indent + 2, showSourceSpans, showTypes)}\n" +
        s"${indentStr}  precedence: ${unop.precedence}\n" +
        s"${indentStr}  associativity: ${unop.assoc}\n" +
        prettyPrintExpr(unop.body, indent + 2, showSourceSpans, showTypes)

    case ta @ TypeAlias(_, _, _, _, _, _, _) =>
      val spanStr = if showSourceSpans then printSourceSpan(ta.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(ta.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(ta.typeAsc)}\n" +
            s"${indentStr}  typeRef: ${prettyPrintTypeSpec(Some(ta.typeRef))}"
        else ""
      val visStr = memberVisibilityToString(ta.visibility)
      val targetName = typeSpecToSimpleName(ta.typeRef)

      s"${indentStr}TypeAlias $visStr ${ta.name} -> $targetName$spanStr$typeStr"

    case td @ TypeDef(_, _, _, _, _, _) =>
      val spanStr = if showSourceSpans then printSourceSpan(td.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(td.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(td.typeAsc)}"
        else ""
      val visStr = memberVisibilityToString(td.visibility)
      val nativeStr = td.typeSpec match
        case Some(NativeTypeImpl(_)) => " @native"
        case _ => ""

      s"${indentStr}TypeDef $visStr ${td.name}$nativeStr$spanStr$typeStr"
    // td.docComment.map(doc => s"\n${prettyPrintDocComment(doc, indent + 2)}").getOrElse("")
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

      // val docStr = doc.map(d => s"\n${prettyPrintDocComment(d, indent + 2)}").getOrElse("")
      // s"${name}$spanStr$docStr$typeStr"
      s"${name}$spanStr$typeStr"
    }
    .mkString(", ")
