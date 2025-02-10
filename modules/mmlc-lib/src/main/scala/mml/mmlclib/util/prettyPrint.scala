package mml.mmlclib.util
import mml.mmlclib.ast.*

def printSourcePoint(sp: SourcePoint): String =
  s"[${sp.line}:${sp.col}]"

def printSourceSpan(span: SourceSpan): String =
  s"[${{ printSourcePoint(span.start) }}, ${printSourcePoint(span.end)}]"

def prettyPrintAst(astNode: AstNode, indent: Int = 2): String =
  astNode match
    case m: Module => prettyPrintModule(m, indent)
    case m: Member => prettyPrintMember(m, indent)
    case e: Expr => prettyPrintExpr(e, indent)
    case t: Term => prettyPrintTerm(t, indent)
    case _ => s"Can't pretty print that: ${astNode.getClass.getSimpleName} \n $astNode"

def prettyPrintModule(module: Module, indent: Int = 2): String =
  val indentStr = "  " * indent
  val header =
    s"${indentStr}${module.visibility} Module${printSourceSpan(module.span)} ${module.name}"
  val membersStr = module.members.map(prettyPrintMember(_, indent + 2)).mkString("\n")
  s"$header\n$membersStr"

def prettyPrintMember(member: Member, indent: Int): String =
  val indentStr = "  " * indent
  member match {
    case MemberError(span, message, failedCode) =>
      s"${indentStr}MemberError ${printSourceSpan(span)} -> ${span.end.line}:${span.end.col}\n" +
        s"""$indentStr  "$message"""".stripMargin +
        failedCode.map(code => s"${indentStr}  $code").getOrElse("")

    case fn: FnDef =>
      s"${indentStr}FnDef ${fn.name}${printSourceSpan(fn.span)}  \n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(fn.typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(fn.typeAsc)}\n" +
        s"${indentStr}  params:\n${prettyPrintParams(fn.params, indent + 2)}\n" +
        prettyPrintExpr(fn.body, indent + 2)

    case bnd: Bnd =>
      s"${indentStr}Bnd ${bnd.name}${printSourceSpan(bnd.span)}\n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(bnd.typeSpec)}\n" +
        s"${indentStr}  typeAsc: ${prettyPrintTypeSpec(bnd.typeAsc)}\n" +
        prettyPrintExpr(bnd.value, indent + 2)

  }

def prettyPrintTypeSpec(typeSpec: Option[TypeSpec]): String =
  typeSpec match
    case Some(TypeName(sp, name)) =>
      s"TypeName $name ${printSourceSpan(sp)}"
    case Some(lt: LiteralType) => s"Literal: ${lt.typeName}"
    case Some(ts: FromSource) => f"UnknownTypeSpec(${ts})"
    case None => "None"

def prettyPrintParams(params: Seq[FnParam], indent: Int): String =
  val indentStr = "  " * indent
  params
    .map { case FnParam(span, name, typeSpec, typeAsc, doc) =>
      s"${indentStr}${name}${printSourceSpan(span)} : ${prettyPrintTypeSpec(typeSpec)}"
    }
    .mkString("\n")

def prettyPrintExpr(expr: Expr, indent: Int): String =
  val indentStr = "  " * indent
  val termsStr  = expr.terms.map(prettyPrintTerm(_, indent + 2)).mkString("\n")
  s"${indentStr}Expr\n${indentStr}  typeSpec: ${prettyPrintTypeSpec(expr.typeSpec)}\n$termsStr"

def prettyPrintTerm(term: Term, indent: Int): String =
  val indentStr = "  " * indent
  term match {

    case MehRef(sp, typeSpec, typeAsc) =>
      s"${indentStr}MehRef ${printSourceSpan(sp)} \n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}"

    case Ref(sp, name, typeSpec, typeAsc) =>
      s"${indentStr}Ref $name ${printSourceSpan(sp)} \n${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}"

    case LiteralInt(sp, value) =>
      s"${indentStr}LiteralInt $value"

    case LiteralString(sp, value) =>
      s"${indentStr}LiteralString \"$value\""

    case LiteralBool(sp, value) =>
      s"${indentStr}LiteralBool $value"

    case LiteralFloat(sp, value) =>
      s"${indentStr}LiteralFloat $value"

    case GroupTerm(sp, inner, typeAsc) =>
      s"${indentStr}GroupTerm\n${prettyPrintExpr(inner, indent + 2)}"

    case Cond(sp, cond, ifTrue, ifFalse, typeSpec, typAsc) =>
      s"${indentStr}Cond ${printSourceSpan(sp)} ${{ printSourceSpan(sp) }}  \n" +
        s"${indentStr}  typeSpec: ${prettyPrintTypeSpec(typeSpec)}\n" +
        s"${indentStr}  cond:\n${prettyPrintExpr(cond, indent + 2)}\n" +
        s"${indentStr}  ifTrue:\n${prettyPrintExpr(ifTrue, indent + 2)}\n" +
        s"${indentStr}  ifFalse:\n${prettyPrintExpr(ifFalse, indent + 2)}"

    case LiteralUnit(sp) =>
      s"${indentStr}LiteralUnit"

    case u => s"${indentStr}UnknownTerm($u)"
  }
