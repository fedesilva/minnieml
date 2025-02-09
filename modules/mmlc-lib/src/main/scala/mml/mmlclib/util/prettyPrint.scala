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
        s"${indentStr}  typeSpec: ${fn.typeSpec.getOrElse("None")}\n" +
        s"${indentStr}  params: [${fn.params.mkString(", ")}]\n" +
        prettyPrintExpr(fn.body, indent + 2)

    case bnd: Bnd =>
      s"${indentStr}Bnd ${bnd.name}${printSourceSpan(bnd.span)}\n" +
        s"${indentStr}  typeSpec: ${bnd.typeSpec.getOrElse("None")}\n" +
        prettyPrintExpr(bnd.value, indent + 2)

  }

def prettyPrintExpr(expr: Expr, indent: Int): String =
  val indentStr = "  " * indent
  val termsStr  = expr.terms.map(prettyPrintTerm(_, indent + 2)).mkString("\n")
  s"${indentStr}Expr\n${indentStr}  typeSpec: ${expr.typeSpec.getOrElse("None")}\n$termsStr"

def prettyPrintTerm(term: Term, indent: Int): String =
  val indentStr = "  " * indent
  term match {
    case Ref(sp, name, typeSpec) =>
      s"${indentStr}Ref $name\n${indentStr}  typeSpec: ${typeSpec.getOrElse("None")}"

    case LiteralInt(sp, value) =>
      s"${indentStr}LiteralInt $value"

    case LiteralString(sp, value) =>
      s"${indentStr}LiteralString \"$value\""

    case LiteralBool(sp, value) =>
      s"${indentStr}LiteralBool $value"

    case LiteralFloat(sp, value) =>
      s"${indentStr}LiteralFloat $value"

    case GroupTerm(sp, inner) =>
      s"${indentStr}GroupTerm\n${prettyPrintExpr(inner, indent + 2)}"

    case LiteralUnit(sp) =>
      s"${indentStr}LiteralUnit"

    case u => s"${indentStr}UnknownTerm($u)"
  }
