package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def visibilityToString(visibility: Visibility): String =
  visibility match
    case Visibility.Public => "pub"
    case Visibility.Protected => "prot"
    case Visibility.Private => "priv"

/** Extract a simple name representation from a TypeSpec */
def typeSpecToSimpleName(typeSpec: Type): String =
  typeSpec match
    case TypeRef(_, name, _, _) => name
    case NativePrimitive(_, llvmType, _) => s"@native[t=$llvmType]"
    case NativePointer(_, llvmType, _) => s"@native[t=*$llvmType]"
    case NativeStruct(_, fields, _) => s"@native {${fields.size} fields}"
    case TypeUnit(_) => "()"
    case TypeFn(_, params, ret) =>
      s"(${params.map(typeSpecToSimpleName).mkString(" -> ")}) -> ${typeSpecToSimpleName(ret)}"
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
    case ParsingMemberError(span, message, failedCode) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      s"${indentStr}MemberError $spanStr\n" +
        s"""${indentStr}  "$message"""".stripMargin +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")

    case ParsingIdError(span, message, failedCode, invalidId) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      s"${indentStr}IdError $spanStr\n" +
        s"""${indentStr}  "$message"""".stripMargin +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")

    case bnd: Bnd =>
      val spanStr = if showSourceSpans then printSourceSpan(bnd.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(bnd.typeSpec, showSourceSpans, showTypes, indent + 1)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(bnd.typeAsc, showSourceSpans, showTypes, indent + 1)}"
        else ""
      val visStr = visibilityToString(bnd.visibility)

      // For bindings with meta (functions/operators), show additional info
      val metaStr = bnd.meta match
        case Some(meta) =>
          val originStr = meta.origin match
            case BindingOrigin.Function => "fn"
            case BindingOrigin.Operator => "op"
          val nameStr =
            if meta.originalName != bnd.name then s" (${meta.originalName})" else ""
          val arityStr = meta.arity match
            case CallableArity.Nullary => "nullary"
            case CallableArity.Unary => "unary"
            case CallableArity.Binary => "binary"
            case CallableArity.Nary(nParams) => s"nary($nParams)"
          val assocStr = meta.associativity
            .map {
              case Associativity.Left => "left"
              case Associativity.Right => "right"
            }
            .getOrElse("none")
          s" [$originStr $arityStr prec=${meta.precedence} assoc=$assocStr]$nameStr"
        case None => ""

      s"${indentStr}$visStr Bnd ${bnd.name}$metaStr$spanStr$typeStr\n" +
        // bnd.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        prettyPrintExpr(bnd.value, indent + 2, showSourceSpans, showTypes)

    case ta @ TypeAlias(_, _, _, _, _, _, _, _) =>
      val spanStr = if showSourceSpans then printSourceSpan(ta.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(ta.typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(ta.typeAsc)}\n" +
            s"${indentStr}  typeRef: ${prettyPrintTypeSpec(Some(ta.typeRef))}"
        else ""
      val visStr     = visibilityToString(ta.visibility)
      val targetName = typeSpecToSimpleName(ta.typeRef)

      s"${indentStr}$visStr TypeAlias ${ta.name} -> $targetName$spanStr$typeStr"

    case td @ TypeDef(_, _, _, _, _, _, _) =>
      val spanStr = if showSourceSpans then printSourceSpan(td.span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(td.typeSpec, showSourceSpans, showTypes, indent + 1)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(td.typeAsc, showSourceSpans, showTypes, indent + 1)}"
        else ""
      val visStr = visibilityToString(td.visibility)
      val nativeStr = td.typeSpec match
        case Some(_: NativeType) => " @native"
        case _ => ""

      s"${indentStr}$visStr TypeDef ${td.name}$nativeStr$spanStr$typeStr"
    // td.docComment.map(doc => s"\n${prettyPrintDocComment(doc, indent + 2)}").getOrElse("")

    case tr @ TypeStruct(_, _, _, _, _, _) =>
      val spanStr = if showSourceSpans then printSourceSpan(tr.span) else ""
      val visStr  = visibilityToString(tr.visibility)
      val fieldsStr =
        if tr.fields.isEmpty then "{}"
        else
          val fieldLines = tr.fields
            .map { field =>
              s"${indentStr}  ${field.name}: " +
                s"${prettyPrintTypeSpec(Some(field.typeSpec), showSourceSpans, showTypes, indent + 1)}"
            }
            .mkString(",\n")
          s"{\n$fieldLines\n$indentStr}"
      s"${indentStr}$visStr TypeRecord ${tr.name} $fieldsStr$spanStr"

    case dup: DuplicateMember =>
      val spanStr = if showSourceSpans then printSourceSpan(dup.span) else ""
      s"${indentStr}DuplicateMember $spanStr\n" +
        s"${indentStr}  firstOccurrence: ${dup.firstOccurrence.getClass.getSimpleName} ${dup.firstOccurrence match {
            case d: Decl => d.name
            case _ => "<unnamed>"
          }}\n" +
        s"${indentStr}  original:\n" +
        prettyPrintMember(dup.originalMember, indent + 2, showSourceSpans, showTypes)

    case inv: InvalidMember =>
      val spanStr = if showSourceSpans then printSourceSpan(inv.span) else ""
      s"${indentStr}InvalidMember $spanStr\n" +
        s"""${indentStr}  reason: "${inv.reason}"\n""" +
        s"${indentStr}  original:\n" +
        prettyPrintMember(inv.originalMember, indent + 2, showSourceSpans, showTypes)
  }

def prettyPrintParams(
  params:          Seq[FnParam],
  indent:          Int,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  params
    .map { case FnParam(span, name, typeSpec, typeAsc, _, _, consuming) =>
      val spanStr = if showSourceSpans then printSourceSpan(span) else ""
      val typeStr =
        if showTypes then
          s"\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
            s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
        else ""
      val consumingPrefix = if consuming then "~" else ""
      s"$consumingPrefix${name}$spanStr$typeStr"
    }
    .mkString(", ")
