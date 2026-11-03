package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Selects executable Clone members through the registry's instance associations. */
private[semantic] object CloneTargets:

  val protocolId: String = BindingIds.declaration("stdlib", "Clone", "protocol")
  val signatureId: String =
    BindingIds.protocolSignature(protocolId, OpMangling.mangleOp("^", 1))

  /** Returns the current member binding for the type's registered Clone instance. Missing or
    * rejected instances and unavailable indexed bindings supply no target; callers decide whether
    * to report unavailable duplication or omit an optional clone operation.
    */
  def forType(tpe: Type, index: ResolvablesIndex, registry: ProtocolRegistry): Option[Bnd] =
    (for
      instance <- registry.instance(protocolId, tpe, index)
      binding <- registry.memberBinding(instance, signatureId, index)
    yield binding).toOption
