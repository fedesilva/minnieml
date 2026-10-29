package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Inject ToString for scalar values, strings, and Unit. Each result is an owned String. Instance
  * members keep explicit native symbols so protocol lowering preserves the runtime ABI.
  */
def injectStandardConversions(module: Module): Module =
  val source     = SourceOrigin.Synth
  val parameter  = TypeVariable(source, "T")
  val protocolId = BindingIds.declaration("stdlib", "ToString", "protocol")
  val signature = ProtocolSignature(
    source     = source,
    nameNode   = Name.synth("to_str"),
    params     = List(FnParam(source, Name.synth("value"), typeAsc = parameter.some)),
    returnType = TypeRef(source, "String", stdlibTypeId("String")),
    meta = BindingMeta(
      origin        = BindingOrigin.Function,
      arity         = CallableArity.Unary,
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = "to_str",
      mangledName   = "to_str"
    ),
    id = BindingIds.protocolSignature(protocolId, "to_str").some
  )
  val protocol = Protocol(
    source        = source,
    nameNode      = Name.synth("ToString"),
    typeParameter = parameter,
    signatures    = List(signature),
    id            = protocolId.some
  )
  val instances = List(
    "Int" -> "int_to_str",
    "Float" -> "float_to_str",
    "Int64" -> "int64_to_str",
    "Int8" -> "int8_to_str",
    "Int16" -> "int16_to_str",
    "Double" -> "double_to_str",
    "Bool" -> "bool_to_str",
    "Char" -> "char_to_str",
    "SizeT" -> "sizet_to_str",
    "Unit" -> "unit_to_str",
    "String" -> "__clone_String",
    "CString" -> "cstring_to_str"
  ).map { (typeName, nativeSymbol) =>
    stringConversionInstance(protocolId, typeName, nativeSymbol)
  }

  ResolvablesIndexer.refresh(
    module.copy(members = protocol :: instances ::: module.members)
  )

/** A native member borrows its input and returns a newly owned String. ProtocolChecker supplies its
  * concrete signature and ordinary callable metadata before type checking.
  */
private def stringConversionInstance(
  protocolId:   String,
  typeName:     String,
  nativeSymbol: String
): ProtocolInstance =
  val source     = SourceOrigin.Synth
  val instanceId = s"stdlib::instance::ToString::$typeName"
  val native = NativeImpl(
    source       = source,
    memEffect    = MemEffect.Alloc.some,
    nativeSymbol = nativeSymbol.some
  )
  val lambda = Lambda(
    source   = source,
    params   = List(FnParam(source, Name.synth("value"))),
    body     = Expr(source, List(native)),
    captures = Nil
  )
  val binding = Bnd(
    source   = source,
    nameNode = Name.synth("to_str"),
    value    = Expr(source, List(lambda)),
    id       = s"$instanceId::to_str".some
  )
  ProtocolInstance(
    source       = source,
    protocol     = ProtocolRef(source, Name.synth("ToString"), protocolId.some),
    concreteType = TypeRef(source, typeName, stdlibTypeId(typeName)),
    members      = List(ProtocolInstanceMember(binding, BindingOrigin.Function)),
    id           = instanceId.some
  )
