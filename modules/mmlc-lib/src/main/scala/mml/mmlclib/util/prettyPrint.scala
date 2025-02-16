package mml.mmlclib.util
import mml.mmlclib.ast.*
import cats.data.NonEmptyList

def printSourcePoint(sp: SourcePoint): String =
  s"[${sp.line}:${sp.col}]"

def printSourceSpan(span: SourceSpan): String =
  s"[${printSourcePoint(span.start)}, ${printSourcePoint(span.end)}]"

def prettyPrintAst(astNode: AstNode, indent: Int = 2): String =
  astNode match
    case m: Module => prettyPrintModule(m, indent)
    case m: Member => prettyPrintMember(m, indent)
    case e: Expr => prettyPrintExpr(e, indent)
    case t: Term => prettyPrintTerm(t, indent)
    case d: DocComment => prettyPrintDocComment(d, indent)
    case t: TypeSpec => prettyPrintTypeSpec(Some(t))
    case p: FnParam => prettyPrintParams(Seq(p), indent)

def prettyPrintModule(module: Module, indent: Int = 2): String =
  val indentStr = "  " * indent
  val header =
    s"${indentStr}${module.visibility} Module${printSourceSpan(module.span)} ${module.name}"
  val docStr =
    module.docComment.map(doc => s"\n${prettyPrintDocComment(doc, indent + 2)}").getOrElse("")
  val membersStr = module.members.map(prettyPrintMember(_, indent + 2)).mkString("\n")
  s"$header$docStr\n$membersStr"

def prettyPrintDocComment(doc: DocComment, indent: Int): String =
  val indentStr = "  " * indent
  s"${indentStr}DocComment${printSourceSpan(doc.span)}\n${indentStr}  ${doc.text}"

def prettyPrintMember(member: Member, indent: Int): String =
  val indentStr = "  " * indent
  member match {
    case MemberError(span, message, failedCode) =>
      s"${indentStr}MemberError ${printSourceSpan(span)}\n" +
        s"""${indentStr}  "$message"""".stripMargin +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")

    case fn: FnDef =>
      s"${indentStr}FnDef ${fn.name}${printSourceSpan(fn.span)}\n" +
        fn.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(fn.typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(fn.typeAsc)}\n" +
        s"${indentStr}  params:\n${prettyPrintParams(fn.params, indent + 2)}\n" +
        prettyPrintExpr(fn.body, indent + 2)

    case bnd: Bnd =>
      s"${indentStr}Bnd ${bnd.name}${printSourceSpan(bnd.span)}\n" +
        bnd.docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(bnd.typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(bnd.typeAsc)}\n" +
        prettyPrintExpr(bnd.value, indent + 2)

    case BinOpDef(span, name, param1, param2, prec, assoc, body, typeSpec, typeAsc, docComment) =>
      s"${indentStr}BinOpDef $name ${printSourceSpan(span)}\n" +
        docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}\n" +
        s"${indentStr}  param1: ${prettyPrintParams(Seq(param1), indent)}\n" +
        s"${indentStr}  param2: ${prettyPrintParams(Seq(param2), indent)}\n" +
        s"${indentStr}  precedence: $prec\n" +
        s"${indentStr}  associativity: $assoc\n" +
        prettyPrintExpr(body, indent + 2)

    case UnaryOpDef(span, name, param, precedence, assoc, body, typeSpec, typeAsc, docComment) =>
      s"${indentStr}UnaryOpDef $name ${printSourceSpan(span)}\n" +
        docComment.map(doc => s"${prettyPrintDocComment(doc, indent + 2)}\n").getOrElse("") +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}\n" +
        s"${indentStr}  param: ${prettyPrintParams(Seq(param), indent)}\n" +
        s"${indentStr}  precedence: $precedence\n" +
        s"${indentStr}  associativity: $assoc\n" +
        prettyPrintExpr(body, indent + 2)
  }

def prettyPrintParams(params: Seq[FnParam], indent: Int): String =
  val indentStr = "  " * indent
  params
    .map { case FnParam(span, name, typeSpec, typeAsc, doc) =>
      val docStr = doc.map(d => s"\n${prettyPrintDocComment(d, indent + 2)}").getOrElse("")
      s"${indentStr}${name}${printSourceSpan(span)}$docStr\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"
    }
    .mkString("\n")

def prettyPrintExpr(expr: Expr, indent: Int): String =
  val indentStr = "  " * indent
  s"${indentStr}Expr${printSourceSpan(expr.span)}\n" +
    s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(expr.typeSpec)}\n" +
    s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(expr.typeAsc)}\n" +
    expr.terms.map(prettyPrintTerm(_, indent + 2)).mkString("\n")

def prettyPrintTerm(term: Term, indent: Int): String =
  val indentStr = "  " * indent
  term match {
    case MehRef(sp, typeSpec, typeAsc) =>
      s"${indentStr}MehRef ${printSourceSpan(sp)}\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"

    case Ref(sp, name, typeSpec, typeAsc, resolvedAs) =>
      s"${indentStr}Ref $name ${printSourceSpan(sp)}\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"

    case Hole(sp, typeSpec, typeAsc) =>
      s"${indentStr}Hole ${printSourceSpan(sp)}\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}"

    case e: Expr => prettyPrintExpr(e, indent)

    case GroupTerm(sp, inner, typeAsc) =>
      s"${indentStr}GroupTerm${printSourceSpan(sp)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}\n" +
        prettyPrintExpr(inner, indent + 2)

    case Cond(sp, cond, ifTrue, ifFalse, typeSpec, typeAsc) =>
      s"${indentStr}Cond ${printSourceSpan(sp)}\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}\n" +
        s"${indentStr}  cond:\n${prettyPrintExpr(cond, indent + 2)}\n" +
        s"${indentStr}  ifTrue:\n${prettyPrintExpr(ifTrue, indent + 2)}\n" +
        s"${indentStr}  ifFalse:\n${prettyPrintExpr(ifFalse, indent + 2)}"

    case Tuple(sp, elements, typeSpec, typeAsc) =>
      s"${indentStr}Tuple ${printSourceSpan(sp)}\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(typeAsc)}\n" +
        elements.toList.map(e => prettyPrintExpr(e, indent + 2)).mkString("\n")

    // Literal values
    case LiteralInt(sp, value) =>
      s"${indentStr}LiteralInt ${printSourceSpan(sp)} $value"

    case LiteralString(sp, value) =>
      s"""${indentStr}LiteralString ${printSourceSpan(sp)} "$value""""

    case LiteralBool(sp, value) =>
      s"${indentStr}LiteralBool ${printSourceSpan(sp)} $value"

    case LiteralFloat(sp, value) =>
      s"${indentStr}LiteralFloat ${printSourceSpan(sp)} $value"

    case LiteralUnit(sp) =>
      s"${indentStr}LiteralUnit ${printSourceSpan(sp)}"

    case TermError(sp, message, failedCode) =>
      s"${indentStr}TermError ${printSourceSpan(sp)}\n" +
        s"""${indentStr}  "$message"""" +
        failedCode.map(code => s"\n${indentStr}  $code").getOrElse("")
  }

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
        prettyPrintExpr(expr, 2)
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
