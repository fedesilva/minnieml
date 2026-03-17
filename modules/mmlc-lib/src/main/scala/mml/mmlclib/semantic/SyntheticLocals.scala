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
    owner:    SyntheticOwner,
    name:     String,
    typeSpec: Option[Type] = None,
    typeAsc:  Option[Type] = None
  ): Local =
    val param = SyntheticLocals.param(owner, name, typeSpec = typeSpec, typeAsc = typeAsc)
    Local(param, ref(param, typeSpec = typeSpec, typeAsc = typeAsc))
