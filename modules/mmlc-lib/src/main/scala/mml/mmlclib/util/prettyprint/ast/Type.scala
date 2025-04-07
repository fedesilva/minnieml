package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintTypeSpec(typeSpec: Option[TypeSpec]): String =
  typeSpec match {
    case Some(TypeName(sp, name)) =>
      s"TypeName $name ${printSourceSpan(sp)}"
    case Some(TypeApplication(sp, base, args)) =>
      s"TypeApplication ${printSourceSpan(sp)}\n" +
        s"  base: ${prettyPrintTypeSpec(Some(base))}\n" +
        s"  args: ${args.map(arg => prettyPrintTypeSpec(Some(arg))).mkString(", ")}"
    case Some(TypeFn(sp, paramTypes, returnType)) =>
      s"TypeFn ${printSourceSpan(sp)}\n" +
        s"  params: ${paramTypes.map(p => prettyPrintTypeSpec(Some(p))).mkString(", ")}\n" +
        s"  return: ${prettyPrintTypeSpec(Some(returnType))}"
    case Some(TypeTuple(sp, elements)) =>
      s"TypeTuple ${printSourceSpan(sp)}\n" +
        s"  elements: ${elements.map(e => prettyPrintTypeSpec(Some(e))).mkString(", ")}"
    case Some(TypeStruct(sp, fields)) =>
      s"TypeStruct ${printSourceSpan(sp)}\n" +
        fields
          .map { case (name, tp) => s"  $name: ${prettyPrintTypeSpec(Some(tp))}" }
          .mkString("\n")
    case Some(TypeRefinement(sp, id, expr)) =>
      s"TypeRefinement ${printSourceSpan(sp)}${id.map(i => s" ($i)").getOrElse("")}\n" +
        prettyPrintExpr(expr, 1)
    case Some(Union(sp, types)) =>
      s"Union ${printSourceSpan(sp)}\n" +
        types.map(t => prettyPrintTypeSpec(Some(t))).mkString(" | ")
    case Some(Intersection(sp, types)) =>
      s"Intersection ${printSourceSpan(sp)}\n" +
        types.map(t => prettyPrintTypeSpec(Some(t))).mkString(" & ")
    case Some(TypeUnit(sp)) =>
      s"TypeUnit ${printSourceSpan(sp)}"
    case Some(TypeSeq(sp, inner)) =>
      s"TypeSeq ${printSourceSpan(sp)}\n" +
        s"  inner: ${prettyPrintTypeSpec(Some(inner))}"
    case Some(TypeGroup(sp, types)) =>
      s"TypeGroup ${printSourceSpan(sp)}\n" +
        types.map(t => prettyPrintTypeSpec(Some(t))).mkString(", ")
    case Some(lt: LiteralType) =>
      s"LiteralType: ${lt.typeName}"
    case None =>
      "None"
  }
