package mml.mmlclib.codegen.emitter

import cats.syntax.all.*

/** Handles code generation for binary and unary operators. */

/** Emits code for a binary operation using the compile results of left and right operands.
  *
  * @param op
  *   the operator (e.g. "+", "-", "*", "/")
  * @param leftRes
  *   the compiled left operand
  * @param rightRes
  *   the compiled right operand
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
def emitBinaryOp(
  op:       String,
  leftRes:  CompileResult,
  rightRes: CompileResult
): Either[CodeGenError, CompileResult] =
  if leftRes.isLiteral && rightRes.isLiteral then
    op match
      case "+" =>
        CompileResult(leftRes.register + rightRes.register, rightRes.state, true).asRight
      case "-" =>
        CompileResult(leftRes.register - rightRes.register, rightRes.state, true).asRight
      case "*" =>
        CompileResult(leftRes.register * rightRes.register, rightRes.state, true).asRight
      case "/" =>
        CompileResult(leftRes.register / rightRes.register, rightRes.state, true).asRight
      case "^" => CodeGenError("Power operator not yet implemented", None).asLeft
      case _ => CodeGenError(s"Unknown operator: $op", None).asLeft
  else
    op match
      case "+" =>
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = s"  %$resultReg = add i32 $leftOp, $rightOp"
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case "-" =>
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = s"  %$resultReg = sub i32 $leftOp, $rightOp"
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case "*" =>
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = s"  %$resultReg = mul i32 $leftOp, $rightOp"
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case "/" =>
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = s"  %$resultReg = sdiv i32 $leftOp, $rightOp"
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case "^" =>
        CodeGenError("Power operator not yet implemented", None).asLeft
      case _ =>
        CodeGenError(s"Unknown operator: $op", None).asLeft

/** Emits code for a unary operation using the compile result of the operand.
  *
  * @param op
  *   the operator (e.g. "-", "+", "!")
  * @param argRes
  *   the compiled operand
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
def emitUnaryOp(
  op:     String,
  argRes: CompileResult
): Either[CodeGenError, CompileResult] =
  if argRes.isLiteral then
    op match
      case "-" => CompileResult(-argRes.register, argRes.state, true).asRight
      case "+" => CompileResult(argRes.register, argRes.state, true).asRight
      case "!" =>
        CompileResult(if argRes.register == 0 then 1 else 0, argRes.state, true).asRight
      case _ => CodeGenError(s"Unknown unary operator: $op", None).asLeft
  else
    op match
      case "-" =>
        val resultReg = argRes.state.nextRegister
        val argOp =
          if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
        val line = s"  %$resultReg = sub i32 0, $argOp"
        CompileResult(
          resultReg,
          argRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case "+" =>
        val resultReg = argRes.state.nextRegister
        val argOp =
          if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
        val line = s"  %$resultReg = add i32 $argOp, 0"
        CompileResult(
          resultReg,
          argRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case "!" =>
        val resultReg = argRes.state.nextRegister
        val argOp =
          if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
        val line = s"  %$resultReg = xor i32 1, $argOp"
        CompileResult(
          resultReg,
          argRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      case _ =>
        CodeGenError(s"Unknown unary operator: $op", None).asLeft
