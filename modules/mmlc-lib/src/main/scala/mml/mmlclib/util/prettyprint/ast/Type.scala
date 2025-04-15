package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintTypeSpec(
  typeSpec:        Option[TypeSpec],
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  typeSpec match {
    case Some(TypeName(sp, name)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeName $name$spanStr"
    case Some(TypeApplication(sp, base, args)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeApplication$spanStr\n" +
        s"  base: ${prettyPrintTypeSpec(Some(base), showSourceSpans, showTypes)}\n" +
        s"  args: ${args.map(arg => prettyPrintTypeSpec(Some(arg), showSourceSpans, showTypes)).mkString(", ")}"
    case Some(TypeFn(sp, paramTypes, returnType)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeFn$spanStr\n" +
        s"  params: ${paramTypes.map(p => prettyPrintTypeSpec(Some(p), showSourceSpans, showTypes)).mkString(", ")}\n" +
        s"  return: ${prettyPrintTypeSpec(Some(returnType), showSourceSpans, showTypes)}"
    case Some(TypeTuple(sp, elements)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeTuple$spanStr\n" +
        s"  elements: ${elements.map(e => prettyPrintTypeSpec(Some(e), showSourceSpans, showTypes)).mkString(", ")}"
    case Some(TypeStruct(sp, fields)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeStruct$spanStr\n" +
        fields
          .map { case (name, tp) =>
            s"  $name: ${prettyPrintTypeSpec(Some(tp), showSourceSpans, showTypes)}"
          }
          .mkString("\n")
    case Some(TypeRefinement(sp, id, expr)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeRefinement$spanStr${id.map(i => s" ($i)").getOrElse("")}\n" +
        prettyPrintExpr(expr, 1, showSourceSpans, showTypes)
    case Some(Union(sp, types)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"Union$spanStr\n" +
        types.map(t => prettyPrintTypeSpec(Some(t), showSourceSpans, showTypes)).mkString(" | ")
    case Some(Intersection(sp, types)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"Intersection$spanStr\n" +
        types.map(t => prettyPrintTypeSpec(Some(t), showSourceSpans, showTypes)).mkString(" & ")
    case Some(TypeUnit(sp)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeUnit$spanStr"
    case Some(TypeSeq(sp, inner)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeSeq$spanStr\n" +
        s"  inner: ${prettyPrintTypeSpec(Some(inner), showSourceSpans, showTypes)}"
    case Some(TypeGroup(sp, types)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"TypeGroup$spanStr\n" +
        types.map(t => prettyPrintTypeSpec(Some(t), showSourceSpans, showTypes)).mkString(", ")
    case Some(lt: LiteralType) =>
      s"LiteralType: ${lt.typeName}"
    case Some(NativeTypeImpl(sp)) =>
      val spanStr = if showSourceSpans then s" ${printSourceSpan(sp)}" else ""
      s"NativeTypeImpl$spanStr"
    case None =>
      "None"
  }
