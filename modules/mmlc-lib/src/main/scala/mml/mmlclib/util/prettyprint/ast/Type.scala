package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintTypeSpec(
  typeSpec:        Option[TypeSpec],
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false,
  indent:          Int     = 0
): String =
  typeSpec match {
    case Some(TypeRef(sp, name, resolvedAs)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val resolvedStr = resolvedAs match
        case None => "(unresolved)"
        case Some(td: TypeDef) => s" => TypeDef(${td.name})"
        case Some(ta: TypeAlias) => s" => TypeAlias(${ta.name})"
      s"TypeRef $name$spanStr$resolvedStr"

    case Some(TypeApplication(sp, base, args)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeApplication$spanStr\n" +
        s"  base: ${prettyPrintTypeSpec(Some(base), showSourceSpans, showTypes, indent)}\n" +
        s"  args: ${args.map(arg => prettyPrintTypeSpec(Some(arg), showSourceSpans, showTypes, indent)).mkString(", ")}"

    case Some(TypeFn(sp, paramTypes, returnType)) =>
      val spanStr   = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val indentStr = "  " * (indent + 1)
      s"TypeFn$spanStr\n" +
        s"${indentStr}params: ${paramTypes.map(p => prettyPrintTypeSpec(Some(p), showSourceSpans, showTypes, indent + 1)).mkString(", ")}\n" +
        s"${indentStr}return: ${prettyPrintTypeSpec(Some(returnType), showSourceSpans, showTypes, indent + 1)}"

    case Some(TypeTuple(sp, elements)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeTuple$spanStr\n" +
        s"  elements: ${elements.map(e => prettyPrintTypeSpec(Some(e), showSourceSpans, showTypes, indent)).mkString(", ")}"

    case Some(TypeStruct(sp, fields)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeStruct$spanStr\n" +
        fields
          .map { case (name, tp) =>
            s"  $name: ${prettyPrintTypeSpec(Some(tp), showSourceSpans, showTypes, indent)}"
          }
          .mkString("\n")

    case Some(TypeRefinement(sp, id, expr)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeRefinement$spanStr${id.map(i => s" ($i)").getOrElse("")}\n" +
        prettyPrintExpr(expr, 1, showSourceSpans, showTypes)

    case Some(Union(sp, types)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"Union$spanStr\n" +
        types
          .map(t => prettyPrintTypeSpec(Some(t), showSourceSpans, showTypes, indent))
          .mkString(" | ")

    case Some(Intersection(sp, types)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"Intersection$spanStr\n" +
        types
          .map(t => prettyPrintTypeSpec(Some(t), showSourceSpans, showTypes, indent))
          .mkString(" & ")

    case Some(TypeUnit(sp)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeUnit$spanStr"

    case Some(TypeSeq(sp, inner)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeSeq$spanStr\n" +
        s"  inner: ${prettyPrintTypeSpec(Some(inner), showSourceSpans, showTypes, indent)}"

    case Some(TypeGroup(sp, types)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeGroup$spanStr\n" +
        types
          .map(t => prettyPrintTypeSpec(Some(t), showSourceSpans, showTypes, indent))
          .mkString(", ")

    case Some(inv: InvalidType) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(inv.span)}" else ""
      s"InvalidType$spanStr\n" +
        s"  original: ${prettyPrintTypeSpec(Some(inv.originalType), showSourceSpans, showTypes, indent)}"

    case Some(NativePrimitive(sp, llvmType)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"@native:$llvmType$spanStr"

    case Some(NativePointer(sp, llvmType)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"@native:*$llvmType$spanStr"

    case Some(NativeStruct(sp, fields)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      if fields.isEmpty then s"@native:{}$spanStr"
      else
        val indentStr = "  " * (indent + 1)
        val fieldStrs = fields
          .map { case (name, tp) =>
            s"$indentStr$name: ${prettyPrintTypeSpec(Some(tp), showSourceSpans, showTypes, indent + 1)}"
          }
          .mkString(",\n")
        s"@native:{\n$fieldStrs\n${"  " * indent}}$spanStr"

    case Some(TypeVariable(sp, name)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"$name$spanStr"

    case Some(TypeScheme(sp, vars, bodyType)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val varsStr = if vars.nonEmpty then s"∀${vars.mkString(" ")}. " else ""
      s"$varsStr${prettyPrintTypeSpec(Some(bodyType), showSourceSpans, showTypes, indent)}$spanStr"

    case None =>
      "None"
  }
