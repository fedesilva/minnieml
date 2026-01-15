package mml.mmlclib.ast

case class Module(
  span:        SrcSpan,
  name:        String,
  visibility:  Visibility,
  members:     List[Member],
  docComment:  Option[DocComment] = None,
  sourcePath:  Option[String]     = None,
  resolvables: ResolvablesIndex   = ResolvablesIndex()
) extends AstNode,
      FromSource,
      Member
