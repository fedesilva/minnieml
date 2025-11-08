package mml.mmlclib.ast

case class Module(
  span:       SrcSpan,
  name:       String,
  visibility: ModVisibility,
  members:    List[Member],
  docComment: Option[DocComment] = None
) extends AstNode,
      FromSource
