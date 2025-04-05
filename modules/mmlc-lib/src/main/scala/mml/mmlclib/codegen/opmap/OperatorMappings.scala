package mml.mmlclib.codegen.opmap

/** Maps MML operator symbols to their corresponding LLVM instructions.
  * Supports arithmetic, comparison, and logical operators.
  */
case class OperatorMapping(
  mmlOperator:     String,
  llvmInstruction: String
)

/** Standard operator mappings for arithmetic and boolean operations.
  */
object OperatorMappings {
  // Binary operators
  val binaryOperators: Map[String, OperatorMapping] = Map(
    // Arithmetic operators
    "+" -> OperatorMapping("+", "add"),
    "-" -> OperatorMapping("-", "sub"),
    "*" -> OperatorMapping("*", "mul"),
    "/" -> OperatorMapping("/", "sdiv"),
    
    // Comparison operators
    "==" -> OperatorMapping("==", "icmp eq"),
    "!=" -> OperatorMapping("!=", "icmp ne"),
    "<" -> OperatorMapping("<", "icmp slt"),
    ">" -> OperatorMapping(">", "icmp sgt"),
    "<=" -> OperatorMapping("<=", "icmp sle"),
    ">=" -> OperatorMapping(">=", "icmp sge"),
    
    // Logical operators
    "&&" -> OperatorMapping("&&", "and"),
    "||" -> OperatorMapping("||", "or")
  )

  // Unary operators
  val unaryOperators: Map[String, OperatorMapping] = Map(
    "-" -> OperatorMapping("-", "sub"),
    "+" -> OperatorMapping("+", "add"),
    "!" -> OperatorMapping("!", "xor") // Logical NOT implemented as XOR with 1
  )

  def getBinaryOp(op: String): Option[OperatorMapping] = binaryOperators.get(op)
  def getUnaryOp(op:  String): Option[OperatorMapping] = unaryOperators.get(op)
}
