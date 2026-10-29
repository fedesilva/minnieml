package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Inject Num and its concrete Int/Float instances alongside the free integer and Boolean
  * operators. ProtocolChecker validates and lowers these instances exactly as source instances. For
  * example, both `1 + 2` and `1.0 + 2.0` resolve through Num's binary + signature.
  */
def injectStandardOperators(module: Module): Module =
  val parameter = TypeVariable(SourceOrigin.Synth, "T")
  val numeric   = numericOperators(parameter, standardType("Bool"))
  val protocol = Protocol(
    source        = SourceOrigin.Synth,
    nameNode      = Name.synth("Num"),
    typeParameter = parameter,
    signatures = numeric.map { definition =>
      val operator = definition.operator
      ProtocolSignature(
        source     = SourceOrigin.Synth,
        nameNode   = Name.synth(operator.name),
        params     = operator.parameters(parameter.some),
        returnType = definition.resultType,
        meta       = operator.metadata,
        id         = BindingIds.protocolSignature(numProtocolId, operator.metadata.mangledName).some
      )
    },
    id = numProtocolId.some
  )
  // Int keeps the standard operator IDs used by compiler-recognized operations such as negation.
  // Float has separate instance IDs so the two implementations cannot collide.
  val integer = numericInstance(
    "Int",
    numeric,
    _.integerTemplate,
    operator => standardOperatorId(operator.name, operator.parameterNames.size)
  )
  val floating = numericInstance(
    "Float",
    numeric,
    _.floatTemplate,
    operator => s"${numInstanceId("Float")}::${operator.metadata.mangledName}"
  )
  val free = List(
    freeBinary("%", 80, "srem %type %operand1, %operand2", "Int"),
    freeBinary("<<", 55, "shl %type %operand1, %operand2", "Int"),
    freeBinary(">>", 55, "ashr %type %operand1, %operand2", "Int"),
    freeUnary("+", "add %type 0, %operand", "Int"),
    freeBinary("and", 40, "and %type %operand1, %operand2", "Bool"),
    freeBinary("or", 30, "or %type %operand1, %operand2", "Bool"),
    freeUnary("not", "xor %type 1, %operand", "Bool")
  )
  ResolvablesIndexer.refresh(
    module.copy(members = List(protocol, integer, floating) ++ free ++ module.members)
  )

/** The exact implementation identity permits MinInt folding only after Int negation is selected. */
private[semantic] val standardIntNegationId: String = standardOperatorId("-", 1)

private val numProtocolId = BindingIds.declaration("stdlib", "Num", "protocol")

private def numInstanceId(typeName: String): String = s"stdlib::instance::Num::$typeName"

private def standardOperatorId(name: String, arity: Int): String =
  BindingIds.declaration("stdlib", OpMangling.mangleOp(name, arity))

private def standardType(name: String): TypeRef =
  TypeRef(SourceOrigin.Synth, name, stdlibTypeId(name))

private case class StandardOperator(
  name:           String,
  precedence:     Int,
  associativity:  Associativity,
  parameterNames: List[String]
):
  def parameters(tpe: Option[Type]): List[FnParam] =
    parameterNames.map(name => FnParam(SourceOrigin.Synth, Name.synth(name), typeAsc = tpe))

  def metadata: BindingMeta =
    BindingMeta(
      origin        = BindingOrigin.Operator,
      arity         = CallableArity.fromParameterCount(parameterNames.size),
      precedence    = precedence,
      associativity = associativity.some,
      originalName  = name,
      mangledName   = OpMangling.mangleOp(name, parameterNames.size)
    )

private case class NumericOperator(
  operator:        StandardOperator,
  resultType:      Type,
  integerTemplate: String,
  floatTemplate:   String
)

private def binaryOperator(name: String, precedence: Int): StandardOperator =
  StandardOperator(name, precedence, Associativity.Left, List("a", "b"))

private def unaryOperator(name: String): StandardOperator =
  StandardOperator(name, 95, Associativity.Right, List("a"))

private def numericOperators(parameter: Type, boolean: Type): List[NumericOperator] =
  val arithmetic = List(
    ("+", 60, "add %type %operand1, %operand2", "fadd %type %operand1, %operand2"),
    ("-", 60, "sub %type %operand1, %operand2", "fsub %type %operand1, %operand2"),
    ("*", 80, "mul %type %operand1, %operand2", "fmul %type %operand1, %operand2"),
    ("/", 80, "sdiv %type %operand1, %operand2", "fdiv %type %operand1, %operand2")
  ).map { (name, precedence, integer, floating) =>
    NumericOperator(binaryOperator(name, precedence), parameter, integer, floating)
  }
  val comparisons = List(
    ("==", "icmp eq %type %operand1, %operand2", "fcmp oeq %type %operand1, %operand2"),
    ("!=", "icmp ne %type %operand1, %operand2", "fcmp une %type %operand1, %operand2"),
    ("<", "icmp slt %type %operand1, %operand2", "fcmp olt %type %operand1, %operand2"),
    (">", "icmp sgt %type %operand1, %operand2", "fcmp ogt %type %operand1, %operand2"),
    ("<=", "icmp sle %type %operand1, %operand2", "fcmp ole %type %operand1, %operand2"),
    (">=", "icmp sge %type %operand1, %operand2", "fcmp oge %type %operand1, %operand2")
  ).map { (name, integer, floating) =>
    NumericOperator(binaryOperator(name, 50), boolean, integer, floating)
  }
  // Float negation preserves subtraction from positive zero, including its signed-zero behavior.
  val negation = NumericOperator(
    unaryOperator("-"),
    parameter,
    "sub %type 0, %operand",
    "fsub %type 0.0, %operand"
  )
  arithmetic ++ comparisons :+ negation

private def numericInstance(
  typeName:    String,
  definitions: List[NumericOperator],
  template:    NumericOperator => String,
  bindingId:   StandardOperator => String
): ProtocolInstance =
  val members = definitions.map { definition =>
    val operator = definition.operator
    ProtocolInstanceMember(
      binding =
        nativeOperator(operator, template(definition), None, None, bindingId(operator), None),
      origin = BindingOrigin.Operator
    )
  }
  ProtocolInstance(
    source       = SourceOrigin.Synth,
    protocol     = ProtocolRef(SourceOrigin.Synth, Name.synth("Num"), numProtocolId.some),
    concreteType = standardType(typeName),
    members      = members,
    id           = numInstanceId(typeName).some
  )

private def freeBinary(name: String, precedence: Int, template: String, typeName: String): Bnd =
  freeOperator(binaryOperator(name, precedence), template, standardType(typeName))

private def freeUnary(name: String, template: String, typeName: String): Bnd =
  freeOperator(unaryOperator(name), template, standardType(typeName))

private def freeOperator(operator: StandardOperator, template: String, tpe: Type): Bnd =
  nativeOperator(
    operator,
    template,
    tpe.some,
    tpe.some,
    standardOperatorId(operator.name, operator.parameterNames.size),
    operator.metadata.some
  )

/** Missing annotations and metadata are supplied by the protocol signature for instance members. */
private def nativeOperator(
  operator:      StandardOperator,
  template:      String,
  parameterType: Option[Type],
  returnType:    Option[Type],
  id:            String,
  metadata:      Option[BindingMeta]
): Bnd =
  val source = SourceOrigin.Synth
  val body   = Expr(source, List(NativeImpl(source, nativeTpl = template.some)))
  val lambda = Lambda(
    source   = source,
    params   = operator.parameters(parameterType),
    body     = body,
    captures = Nil,
    typeAsc  = returnType
  )
  Bnd(
    source   = source,
    nameNode = Name.synth(metadata.fold(operator.name)(_.mangledName)),
    value    = Expr(source, List(lambda)),
    typeAsc  = returnType,
    meta     = metadata,
    id       = id.some
  )
