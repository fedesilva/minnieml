package mml.mmlclib.semantic

import mml.mmlclib.ast.*

import java.util.UUID

final case class SyntheticOwner(
  moduleName:   String,
  ownerSegment: String,
  ownerName:    String
)

object SyntheticOwner:
  def binding(moduleName: String, bindingName: String): SyntheticOwner =
    SyntheticOwner(moduleName, "bnd", bindingName)

object SyntheticLocals:

  /** Sequence cleanup expressions in declaration order and return Unit. */
  def cleanup(calls: List[Term], owner: SyntheticOwner, unitType: Type): Expr =
    val source = SourceOrigin.Synth
    val result = Expr(source, List(LiteralUnit(source, Some(unitType))), typeSpec = Some(unitType))
    calls.foldRight(result) { (call, rest) =>
      val discard  = param(owner, "_", typeSpec = Some(unitType))
      val scope    = Lambda(source, List(discard), rest, Nil, typeSpec = Some(unitType))
      val argument = Expr(source, List(call), typeSpec = Some(unitType))
      Expr(
        source,
        List(App(source, scope, argument, typeSpec = Some(unitType))),
        typeSpec = Some(unitType)
      )
    }

  final case class Local(
    param: FnParam,
    ref:   Ref
  )

  private def nestedId(owner: SyntheticOwner, name: String): Option[String] =
    Some(
      s"${owner.moduleName}::${owner.ownerSegment}::${owner.ownerName}::$name::" +
        UUID.randomUUID().toString.take(8)
    )

  def param(
    owner:     SyntheticOwner,
    name:      String,
    typeSpec:  Option[Type] = None,
    typeAsc:   Option[Type] = None,
    consuming: Boolean      = false
  ): FnParam =
    FnParam(
      SourceOrigin.Synth,
      Name.synth(name),
      typeSpec  = typeSpec,
      typeAsc   = typeAsc,
      id        = nestedId(owner, name),
      consuming = consuming
    )

  def ref(
    param:    FnParam,
    typeSpec: Option[Type] = None,
    typeAsc:  Option[Type] = None
  ): Ref =
    val resolvedId = param.id
    Ref(
      SourceOrigin.Synth,
      param.name,
      typeAsc      = typeAsc,
      typeSpec     = typeSpec.orElse(param.typeSpec).orElse(param.typeAsc),
      resolvedId   = resolvedId,
      candidateIds = resolvedId.toList
    )

  def local(
    owner:     SyntheticOwner,
    name:      String,
    typeSpec:  Option[Type] = None,
    typeAsc:   Option[Type] = None,
    consuming: Boolean      = false
  ): Local =
    val param = SyntheticLocals.param(
      owner,
      name,
      typeSpec  = typeSpec,
      typeAsc   = typeAsc,
      consuming = consuming
    )
    Local(param, ref(param, typeSpec = typeSpec, typeAsc = typeAsc))
