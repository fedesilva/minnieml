package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintTypeSpec(
  typeSpec:        Option[Type],
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false,
  indent:          Int     = 0
): String =
  typeSpec match {
    case Some(TypeRef(sp, name, resolvedId, _)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val resolvedStr = resolvedId match
        case None => "(unresolved)"
        case Some(id) => s" => $id"
      s"TypeRef $name$spanStr$resolvedStr"

    case Some(TypeApplication(sp, base, args)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeApplication$spanStr\n" +
        s"  base: ${prettyPrintTypeSpec(Some(base), showSourceSpans, showTypes, indent)}\n" +
        s"  args: ${args.map(arg => prettyPrintTypeSpec(Some(arg), showSourceSpans, showTypes, indent)).mkString(", ")}"

    case Some(tf: TypeFn) =>
      formatTypeSpecInline(tf, showSourceSpans, showTypes)

    case Some(TypeTuple(sp, elements)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeTuple$spanStr\n" +
        s"  elements: ${elements.map(e => prettyPrintTypeSpec(Some(e), showSourceSpans, showTypes, indent)).mkString(", ")}"

    case Some(TypeOpenRecord(sp, fields)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeOpenRecord$spanStr\n" +
        fields
          .map { case (name, tp) =>
            s"  $name: ${prettyPrintTypeSpec(Some(tp), showSourceSpans, showTypes, indent)}"
          }
          .mkString("\n")

    case Some(tr: TypeStruct) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(tr.span)}" else ""
      val visStr  = visibilityToString(tr.visibility)
      val fieldsStr =
        if tr.fields.isEmpty then "{}"
        else
          val indentStr = "  " * (indent + 1)
          val fieldLines = tr.fields
            .map { field =>
              s"$indentStr${field.name}: " +
                s"${prettyPrintTypeSpec(Some(field.typeSpec), showSourceSpans, showTypes, indent + 1)}"
            }
            .mkString(",\n")
          s"{\n$fieldLines\n${"  " * indent}}"
      s"$visStr TypeRecord ${tr.name} $fieldsStr$spanStr"

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

    case Some(NativePrimitive(sp, llvmType, _)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"@native[t=$llvmType]$spanStr"

    case Some(NativePointer(sp, llvmType, _)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"@native[t=*$llvmType]$spanStr"

    case Some(NativeStruct(sp, fields, _)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      if fields.isEmpty then s"@native {}$spanStr"
      else
        val indentStr = "  " * (indent + 1)
        val fieldStrs = fields
          .map { case (name, tp) =>
            s"$indentStr$name: ${prettyPrintTypeSpec(Some(tp), showSourceSpans, showTypes, indent + 1)}"
          }
          .mkString(",\n")
        s"@native {\n$fieldStrs\n${"  " * indent}}$spanStr"

    case Some(TypeVariable(sp, name)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"$name$spanStr"

    case Some(TypeScheme(sp, vars, bodyType)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val varsStr = if vars.nonEmpty then s"∀${vars.mkString(" ")}. " else ""
      s"$varsStr${prettyPrintTypeSpec(Some(bodyType), showSourceSpans, showTypes, indent)}$spanStr"

    case None =>
      "None"

    case _ => ???

  }

private def formatTypeSpecInline(
  typeSpec:        Type,
  showSourceSpans: Boolean,
  showTypes:       Boolean
): String =
  typeSpec match
    case tf: TypeFn =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(tf.span)}" else ""
      s"${formatTypeFnInline(tf, showSourceSpans, showTypes)}$spanStr"
    case TypeGroup(sp, types) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val inner   = types.map(formatTypeSpecInline(_, showSourceSpans, showTypes)).mkString(", ")
      s"($inner)$spanStr"
    case TypeScheme(sp, vars, bodyType) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val varsStr = if vars.nonEmpty then s"∀${vars.mkString(" ")}. " else ""
      s"$varsStr${formatTypeSpecInline(bodyType, showSourceSpans, showTypes)}$spanStr"
    case TypeRef(sp, name, resolvedId, _) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      val resolvedStr = resolvedId match
        case None => "(unresolved)"
        case Some(id) => s" => $id"
      s"TypeRef $name$spanStr$resolvedStr"
    case TypeUnit(sp) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"()$spanStr"
    case TypeVariable(sp, name) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"$name$spanStr"
    case NativePrimitive(sp, llvmType, _) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"@native[t=$llvmType]$spanStr"
    case NativePointer(sp, llvmType, _) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"@native[t=*$llvmType]$spanStr"
    case other =>
      prettyPrintTypeSpec(Some(other), showSourceSpans, showTypes)

private def formatTypeFnInline(
  typeFn:          TypeFn,
  showSourceSpans: Boolean,
  showTypes:       Boolean
): String =
  val paramsStr =
    if typeFn.paramTypes.isEmpty then "()"
    else
      typeFn.paramTypes
        .map(formatArrowParamType(_, showSourceSpans, showTypes))
        .mkString(" -> ")
  val returnStr = formatArrowReturnType(typeFn.returnType, showSourceSpans, showTypes)
  s"$paramsStr -> $returnStr"

private def formatArrowParamType(
  typeSpec:        Type,
  showSourceSpans: Boolean,
  showTypes:       Boolean
): String =
  typeSpec match
    case _: TypeFn | _: TypeScheme | _: Union | _: Intersection | _: TypeRefinement =>
      s"(${formatTypeSpecInline(typeSpec, showSourceSpans, showTypes)})"
    case _ =>
      formatTypeSpecInline(typeSpec, showSourceSpans, showTypes)

private def formatArrowReturnType(
  typeSpec:        Type,
  showSourceSpans: Boolean,
  showTypes:       Boolean
): String =
  typeSpec match
    case _: Union | _: Intersection | _: TypeRefinement | _: TypeScheme =>
      s"(${formatTypeSpecInline(typeSpec, showSourceSpans, showTypes)})"
    case _ =>
      formatTypeSpecInline(typeSpec, showSourceSpans, showTypes)
