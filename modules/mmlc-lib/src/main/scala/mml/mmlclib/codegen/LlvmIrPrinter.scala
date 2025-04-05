package mml.mmlclib.codegen

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Represents an error that occurred during code generation. */
case class CodeGenError(message: String)

/** LLVM IR Printer for MML.
  *
  * Traverses our nested AST and generates corresponding LLVM IR. Handles constant folding,
  * binary/unary operations, and binding initialization.
  */
object LlvmIrPrinter:

  /** Represents the state during code generation.
    *
    * @param nextRegister
    *   the next available register number
    * @param output
    *   the list of emitted LLVM IR lines (in reverse order)
    * @param initializers
    *   list of initializer function names for global ctors
    */
  case class CodeGenState(
    nextRegister: Int          = 0,
    output:       List[String] = List.empty,
    initializers: List[String] = List.empty
  ):
    /** Returns a new state with an updated register counter. */
    def withRegister(reg: Int): CodeGenState =
      copy(nextRegister = reg)

    /** Emits a single line of LLVM IR code, returning the updated state. */
    def emit(line: String): CodeGenState =
      copy(output = line :: output)

    /** Emits multiple lines of LLVM IR code at once. */
    def emitAll(lines: List[String]): CodeGenState =
      copy(output = lines.reverse ::: output)

    /** Adds an initializer function to the list. */
    def addInitializer(fnName: String): CodeGenState =
      copy(initializers = fnName :: initializers)

    /** Returns the complete LLVM IR as a single string. */
    def result: String =
      output.reverse.mkString("\n")

  /** Represents the result of compiling a term or expression.
    *
    * @param register
    *   the register holding the result (or literal value)
    * @param state
    *   the updated code generation state
    * @param isLiteral
    *   indicates whether the result is a literal value
    */
  case class CompileResult(register: Int, state: CodeGenState, isLiteral: Boolean = false)

  /** Compiles a binary operation.
    *
    * Compiles left and right operands and then emits the appropriate LLVM IR instruction. Performs
    * constant folding when both operands are literals.
    *
    * @param op
    *   the operator (e.g. "+", "-", "*", "/")
    * @param left
    *   the left operand term
    * @param right
    *   the right operand term
    * @param state
    *   the current code generation state
    * @return
    *   Either a CodeGenError or a CompileResult with the updated state.
    */
  private def compileBinaryOp(
    op:    String,
    left:  Term,
    right: Term,
    state: CodeGenState
  ): Either[CodeGenError, CompileResult] =
    for
      leftRes <- compileTerm(left, state)
      rightRes <- compileTerm(right, leftRes.state)
      result <-
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
            case "^" => CodeGenError("Power operator not yet implemented").asLeft
            case _ => CodeGenError(s"Unknown operator: $op").asLeft
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
              CodeGenError("Power operator not yet implemented").asLeft
            case _ =>
              CodeGenError(s"Unknown operator: $op").asLeft
    yield result

  /** Compiles a unary operation.
    *
    * Compiles the operand and emits the corresponding LLVM IR instruction. Applies constant folding
    * if the operand is a literal.
    *
    * @param op
    *   the operator (e.g. "-", "+", "!")
    * @param arg
    *   the operand term
    * @param state
    *   the current code generation state
    * @return
    *   Either a CodeGenError or a CompileResult with the updated state.
    */
  private def compileUnaryOp(
    op:    String,
    arg:   Term,
    state: CodeGenState
  ): Either[CodeGenError, CompileResult] =
    for
      argRes <- compileTerm(arg, state)
      result <-
        if argRes.isLiteral then
          op match
            case "-" => CompileResult(-argRes.register, argRes.state, true).asRight
            case "+" => CompileResult(argRes.register, argRes.state, true).asRight
            case "!" =>
              CompileResult(if argRes.register == 0 then 1 else 0, argRes.state, true).asRight
            case _ => CodeGenError(s"Unknown unary operator: $op").asLeft
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
              CodeGenError(s"Unknown unary operator: $op").asLeft
    yield result

  /** Compiles a term (the smallest unit in an expression).
    *
    * Terms include literals, references, grouped expressions, or nested expressions.
    *
    * @param term
    *   the term to compile
    * @param state
    *   the current code generation state
    * @return
    *   Either a CodeGenError or a CompileResult for the term.
    */
  private def compileTerm(term: Term, state: CodeGenState): Either[CodeGenError, CompileResult] =
    term match
      case LiteralInt(_, value) =>
        CompileResult(value, state, true).asRight
      case ref: Ref =>
        val reg  = state.nextRegister
        val line = s"  %$reg = load i32, i32* @${ref.name}"
        CompileResult(reg, state.withRegister(reg + 1).emit(line), false).asRight
      case TermGroup(_, expr, _) =>
        compileExpr(expr, state)
      case e: Expr =>
        compileExpr(e, state)
      case other =>
        CodeGenError(s"Unsupported term: $other").asLeft

  /** Compiles an expression.
    *
    * Dispatches based on the structure of the expression:
    *   - A single-term expression is compiled directly.
    *   - A binary operation (with exactly three terms: left, operator, right) is handled via
    *     compileBinaryOp.
    *   - A unary operation (with two terms: operator and argument) is handled via compileUnaryOp.
    *
    * @param expr
    *   the expression to compile
    * @param state
    *   the current code generation state
    * @return
    *   Either a CodeGenError or a CompileResult for the expression.
    */
  private def compileExpr(expr: Expr, state: CodeGenState): Either[CodeGenError, CompileResult] =
    expr.terms match
      case List(term) =>
        compileTerm(term, state)
      case List(left, op: Ref, right) if op.resolvedAs.exists(_.isInstanceOf[BinOpDef]) =>
        compileBinaryOp(op.name, left, right, state)
      case List(op: Ref, arg) if op.resolvedAs.exists(_.isInstanceOf[UnaryOpDef]) =>
        compileUnaryOp(op.name, arg, state)
      case _ =>
        CodeGenError(s"Invalid expression structure: ${expr.terms}").asLeft

  /** Compiles a binding (variable declaration).
    *
    * For literal initializations, emits a direct global assignment. For non-literal
    * initializations, emits a global initializer function.
    *
    * IMPORTANT: To avoid duplicating computation at the top level, if the binding is non-literal,
    * we discard the instructions produced by the first compile of the expression and recompile it
    * within the initializer function.
    *
    * @param bnd
    *   the binding to compile
    * @param state
    *   the current code generation state (before compiling the binding)
    * @return
    *   Either a CodeGenError or the updated CodeGenState.
    */
  private def compileBinding(bnd: Bnd, state: CodeGenState): Either[CodeGenError, CodeGenState] =
    val origState = state
    compileExpr(bnd.value, state).flatMap { compileRes =>
      if compileRes.isLiteral then
        Right(compileRes.state.emit(s"@${bnd.name} = global i32 ${compileRes.register}"))
      else
        // Discard the instructions from the initial compilation by using the original state.
        val initFnName = s"_init_global_${bnd.name}"
        val state2 = origState
          .emit(s"@${bnd.name} = global i32 0")
          .emit(s"define internal void @$initFnName() {")
          .emit(s"entry:")
        compileExpr(bnd.value, state2.withRegister(0)).map { compileRes2 =>
          compileRes2.state
            .emit(s"  store i32 %${compileRes2.register}, i32* @${bnd.name}")
            .emit("  ret void")
            .emit("}")
            .emit("")
            .addInitializer(initFnName)
        }
    }

  /** Compiles an entire module into LLVM IR.
    *
    * Emits the module header (module ID and target triple) first, then compiles each binding.
    *
    * @param module
    *   the module containing definitions and bindings
    * @return
    *   Either a CodeGenError or the complete LLVM IR as a String.
    */
  def printModule(module: Module): Either[CodeGenError, String] =
    val moduleHeader = List(
      s"; ModuleID = '${module.name}'",
      "target triple = \"x86_64-unknown-unknown\"",
      ""
    )
    val initialState = CodeGenState().emitAll(moduleHeader)
    module.members
      .foldLeft(initialState.asRight[CodeGenError]) { (stateE, member) =>
        stateE.flatMap { state =>
          member match
            case bnd: Bnd => compileBinding(bnd, state)
            case _ => state.asRight
        }
      }
      .map { finalState =>
        // Add the global ctors array with all initializers if any exist
        if finalState.initializers.nonEmpty then
          val initSize = finalState.initializers.size
          val initializerLines =
            s"@llvm.global_ctors = appending global [$initSize x { i32, void ()*, i8* }] [" ::
              finalState.initializers.reverse
                .map { fnName =>
                  s"  { i32, void ()*, i8* } { i32 65535, void ()* @$fnName, i8* null }"
                }
                .mkString(",\n") ::
              "]" ::
              "" ::
              Nil
          finalState.emitAll(initializerLines).result
        else finalState.result
      }
