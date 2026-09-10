package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Shared AST construction for closure destructor phases. */
object ClosureDestructorAst:
  /** Install a typed destruction expression as the helper's body. */
  def withBody(binding: Bnd, lambda: Lambda, body: Destruction): Bnd =
    val expression = Expr(SourceOrigin.Synth, List(body), typeSpec = body.typeSpec)
    binding.copy(value = binding.value.copy(terms = List(lambda.copy(body = expression))))
