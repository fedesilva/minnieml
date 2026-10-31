package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Build clone calls only for types with an available duplication contract. */
private[semantic] object CloneCalls:

  val protocolId: String = BindingIds.declaration("stdlib", "Clone", "protocol")
  val signatureId: String =
    BindingIds.protocolSignature(protocolId, OpMangling.mangleOp("^", 1))

  /** Select a concrete member by type identity; missing or rejected instances supply no target. */
  def target(tpe: Type, index: ResolvablesIndex, registry: ProtocolRegistry): Option[Bnd] =
    (for
      instance <- registry.instance(protocolId, tpe, index)
      binding <- registry.memberBinding(instance, signatureId, index)
    yield binding).toOption

  /** Build a typed call to the selected member, reporting unavailable duplication at the operand.
    */
  def build(
    operand:  Expr,
    tpe:      Type,
    index:    ResolvablesIndex,
    phase:    String,
    registry: ProtocolRegistry
  ): Either[SemanticError, Expr] =
    val target = CloneCalls.target(tpe, index, registry)
    target
      .toRight(
        SemanticError.InvalidExpression(operand, "This type cannot be cloned", phase)
      )
      .map { target =>
        val functionType = TypeFn(SourceOrigin.Synth, NonEmptyList.one(tpe), tpe)
        val ref = Ref(
          SourceOrigin.Synth,
          target.name,
          resolvedId = target.id,
          typeSpec   = functionType.some
        )
        val call = App(SourceOrigin.Synth, ref, operand, typeSpec = tpe.some)
        Expr(SourceOrigin.Synth, List(call), typeSpec = tpe.some)
      }
