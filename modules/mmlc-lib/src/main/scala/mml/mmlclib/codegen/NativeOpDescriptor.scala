package mml.mmlclib.codegen

/** Descriptor for native LLVM operations that can be referenced by user-defined operators.
  *
  * This provides a mapping from user-specified selectors (e.g., "add", "sub") to the actual LLVM
  * instruction templates. The codegen uses this to emit proper LLVM IR without hardcoded types.
  *
  * @param selector
  *   The identifier used in @native[op=selector] annotations
  * @param template
  *   The LLVM instruction template string for code generation
  */
case class NativeOpDescriptor(
  selector: String,
  template: String
)

/** Registry of native operators available for user-defined operators. */
object NativeOpRegistry:

  /** Map from selector name to descriptor for binary operations. */
  val binaryOps: Map[String, NativeOpDescriptor] = Map(
    "add" -> NativeOpDescriptor("add", "%result = add %type %left, %right"),
    "sub" -> NativeOpDescriptor("sub", "%result = sub %type %left, %right"),
    "mul" -> NativeOpDescriptor("mul", "%result = mul %type %left, %right"),
    "sdiv" -> NativeOpDescriptor("sdiv", "%result = sdiv %type %left, %right"),
    "and" -> NativeOpDescriptor("and", "%result = and %type %left, %right"),
    "or" -> NativeOpDescriptor("or", "%result = or %type %left, %right"),
    "xor" -> NativeOpDescriptor("xor", "%result = xor %type %left, %right"),
    "icmp_eq" -> NativeOpDescriptor("icmp_eq", "%result = icmp eq %type %left, %right"),
    "icmp_ne" -> NativeOpDescriptor("icmp_ne", "%result = icmp ne %type %left, %right"),
    "icmp_slt" -> NativeOpDescriptor("icmp_slt", "%result = icmp slt %type %left, %right"),
    "icmp_sle" -> NativeOpDescriptor("icmp_sle", "%result = icmp sle %type %left, %right"),
    "icmp_sgt" -> NativeOpDescriptor("icmp_sgt", "%result = icmp sgt %type %left, %right"),
    "icmp_sge" -> NativeOpDescriptor("icmp_sge", "%result = icmp sge %type %left, %right"),
    "shl" -> NativeOpDescriptor("shl", "%result = shl %type %left, %right"),
    "lshr" -> NativeOpDescriptor("lshr", "%result = lshr %type %left, %right"),
    "ashr" -> NativeOpDescriptor("ashr", "%result = ashr %type %left, %right")
  )

  /** Map from selector name to descriptor for unary operations. */
  val unaryOps: Map[String, NativeOpDescriptor] = Map(
    "neg" -> NativeOpDescriptor("neg", "%result = sub %type 0, %operand"),
    "not" -> NativeOpDescriptor("not", "%result = xor %type 1, %operand")
  )

  /** Look up a binary operation descriptor by selector.
    *
    * @param selector
    *   the operation selector from @native[op=selector]
    * @return
    *   Some(descriptor) if found, None otherwise
    */
  def getBinaryOp(selector: String): Option[NativeOpDescriptor] =
    binaryOps.get(selector)

  /** Look up a unary operation descriptor by selector.
    *
    * @param selector
    *   the operation selector from @native[op=selector]
    * @return
    *   Some(descriptor) if found, None otherwise
    */
  def getUnaryOp(selector: String): Option[NativeOpDescriptor] =
    unaryOps.get(selector)
