package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Build clone calls only for types with an available duplication contract. */
private[semantic] object CloneCalls:
  def build(
    operand: Expr,
    tpe:     Type,
    index:   ResolvablesIndex,
    phase:   String,
    resolve: (String, String) => Option[String]
  ): Either[SemanticError, Expr] =
    val target = for
      canonical <- TypeUtils.canonical(tpe, index)
      name <- TypeUtils.getTypeName(canonical)
      function <- TypeUtils.cloneFnFor(name, index)
      id <- resolve(name, function)
    yield (function, id)
    target
      .toRight(
        SemanticError.InvalidExpression(operand, "This type cannot be cloned", phase)
      )
      .map { (name, id) =>
        val functionType = TypeFn(SourceOrigin.Synth, NonEmptyList.one(tpe), tpe)
        val ref  = Ref(SourceOrigin.Synth, name, resolvedId = id.some, typeSpec = functionType.some)
        val call = App(SourceOrigin.Synth, ref, operand, typeSpec = tpe.some)
        Expr(SourceOrigin.Synth, List(call), typeSpec = tpe.some)
      }
