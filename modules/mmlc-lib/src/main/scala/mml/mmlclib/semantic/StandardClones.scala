package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Native duplication is declared by Clone instances whose bodies call named FFI bindings. */
def injectStandardClones(module: Module): Module =
  val source       = SourceOrigin.Synth
  val variable     = TypeVariable(source, "T")
  val protocolId   = BindingIds.declaration("stdlib", "Clone", "protocol")
  val operatorName = OpMangling.mangleOp("^", 1)
  val signature = ProtocolSignature(
    source     = source,
    nameNode   = Name.synth("^"),
    params     = List(FnParam(source, Name.synth("value"), typeAsc = variable.some)),
    returnType = variable,
    meta = BindingMeta(
      kind         = BindingKind.Operator(95, Associativity.Right),
      arity        = CallableArity.Unary,
      originalName = "^",
      mangledName  = operatorName
    ),
    id = BindingIds.protocolSignature(protocolId, operatorName).some
  )
  val protocol = Protocol(
    source        = source,
    nameNode      = Name.synth("Clone"),
    typeParameter = variable,
    signatures    = List(signature),
    id            = protocolId.some
  )
  val nativeTypes = List("String", "Buffer", "Rng", "IntArray", "StringArray", "FloatArray")
  val declarations = nativeTypes.flatMap { typeName =>
    val tpe        = TypeRef(source, typeName, stdlibTypeId(typeName))
    val helperName = s"clone_$typeName"
    val helperId   = BindingIds.declaration("stdlib", helperName)
    val native = NativeImpl(
      source,
      memEffect    = MemEffect.Alloc.some,
      nativeSymbol = s"__clone_$typeName".some
    )
    val helper = Bnd(
      source   = source,
      nameNode = Name.synth(helperName),
      value = Expr(
        source,
        List(
          Lambda(
            source,
            List(FnParam(source, Name.synth("value"), typeAsc = tpe.some)),
            Expr(source, List(native)),
            Nil,
            typeAsc = tpe.some
          )
        )
      ),
      typeAsc = tpe.some,
      meta = BindingMeta(
        kind         = BindingKind.Function,
        arity        = CallableArity.Unary,
        originalName = helperName,
        mangledName  = helperName
      ).some,
      id = helperId.some
    )
    val instanceId = s"stdlib::instance::Clone::$typeName"
    val body = Expr(
      source,
      List(
        Ref(source, helperName, resolvedId = helperId.some),
        Ref(source, "value")
      )
    )
    val member = Bnd(
      source   = source,
      nameNode = Name.synth("^"),
      value = Expr(
        source,
        List(
          Lambda(
            source,
            List(FnParam(source, Name.synth("value"))),
            body,
            Nil
          )
        )
      ),
      id = s"$instanceId::$operatorName".some
    )
    val instance = ProtocolInstance(
      source       = source,
      protocol     = ProtocolRef(source, Name.synth("Clone"), protocolId.some),
      concreteType = tpe,
      members      = List(ProtocolInstanceMember(member, BindingOrigin.Operator)),
      id           = instanceId.some
    )
    List(helper, instance)
  }

  ResolvablesIndexer.refresh(module.copy(members = protocol :: declarations ::: module.members))
