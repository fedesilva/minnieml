package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Build clone calls only for types with an available duplication contract. */
private[semantic] object CloneCalls:
  def build(
    operand:          Expr,
    tpe:              Type,
    index:            ResolvablesIndex,
    phase:            String,
    generatingModule: Option[String] = None
  ): Either[SemanticError, Expr] =
    val target = MemoryFunctions.clone(tpe, index, generatingModule)
    target
      .toRight(
        SemanticError.InvalidExpression(operand, "This type cannot be cloned", phase)
      )
      .map { target =>
        val functionType = TypeFn(SourceOrigin.Synth, NonEmptyList.one(tpe), tpe)
        val ref = Ref(
          SourceOrigin.Synth,
          target.name,
          resolvedId = target.id.some,
          typeSpec   = functionType.some
        )
        val call = App(SourceOrigin.Synth, ref, operand, typeSpec = tpe.some)
        Expr(SourceOrigin.Synth, List(call), typeSpec = tpe.some)
      }
