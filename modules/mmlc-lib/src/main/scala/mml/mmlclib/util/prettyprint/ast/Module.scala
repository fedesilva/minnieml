package mml.mmlclib.util.prettyprint.ast

import mml.mmlclib.ast.*

def prettyPrintModule(
  module:          Module,
  indent:          Int     = 2,
  showSourceSpans: Boolean = false,
  showTypes:       Boolean = false
): String =
  val indentStr = "  " * indent
  val spanStr   = if showSourceSpans then printSourceSpan(module.span) else ""
  val header    = s"${indentStr}${module.visibility} Module$spanStr ${module.name}"
  val docStr =
    module.docComment.map(doc => s"\n${prettyPrintDocComment(doc, indent + 1)}").getOrElse("")
  val membersStr =
    module.members.map(prettyPrintMember(_, indent + 1, showSourceSpans, showTypes)).mkString("\n")
  s"$header$docStr\n$membersStr"

def prettyPrintDocComment(doc: DocComment, indent: Int): String =
  val indentStr = "  " * indent
  s"${indentStr}DocComment${printSourceSpan(doc.span)}\n${indentStr}  ${doc.text}"
