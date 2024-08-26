package mml.mmlclib.ast.translator

import mml.mmlclib.ast.AstNode

trait AstTranslator[F[_], T]:
  def translate(input: T): F[AstNode]

