package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def printSourcePoint(sp: SrcPoint): String =
  s"[${sp.line}:${sp.col}]"

def printSourceSpan(span: SrcSpan): String =
  s"[${printSourcePoint(span.start)}, ${printSourcePoint(span.end)}]"

/** Helper function to pretty print a list of AST nodes
  */
def prettyPrintList[T <: AstNode](
  nodes:           List[T],
  indent:          Int     = 2,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  nodes
    .map(node => prettyPrintAst(node, indent, showSourceSpans, showTypes))
    .mkString("[", ", ", "]")

def prettyPrintAst(
  astNode:         AstNode,
  indent:          Int     = 2,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  astNode match
    case m: Module => prettyPrintModule(m, indent, showSourceSpans, showTypes)
    case m: Member => prettyPrintMember(m, indent, showSourceSpans, showTypes)
    case e: Expr => prettyPrintExpr(e, indent, showSourceSpans, showTypes)
    case t: Term => prettyPrintTerm(t, indent, showSourceSpans, showTypes)
    case d: DocComment => prettyPrintDocComment(d, indent)
    case t: TypeSpec => prettyPrintTypeSpec(Some(t))
    case p: FnParam => prettyPrintParams(Seq(p), indent, showSourceSpans, showTypes)
