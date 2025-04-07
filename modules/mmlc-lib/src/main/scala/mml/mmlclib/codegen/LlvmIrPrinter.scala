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
    * @param functionScope
    *   optional map of local function parameters to their registers
    * @return
    *   Either a CodeGenError or a CompileResult with the updated state.
    */
  private def compileBinaryOp(
    op:            String,
    left:          Term,
    right:         Term,
    state:         CodeGenState,
    functionScope: Map[String, Int] = Map.empty
  ): Either[CodeGenError, CompileResult] =
    for
      leftRes <- compileTerm(left, state, functionScope)
      rightRes <- compileTerm(right, leftRes.state, functionScope)
      result <- compileBinaryOp(op, leftRes, rightRes)
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
    * @param functionScope
    *   optional map of local function parameters to their registers
    * @return
    *   Either a CodeGenError or a CompileResult with the updated state.
    */
  private def compileUnaryOp(
    op:            String,
    arg:           Term,
    state:         CodeGenState,
    functionScope: Map[String, Int] = Map.empty
  ): Either[CodeGenError, CompileResult] =
    for
      argRes <- compileTerm(arg, state, functionScope)
      result <- compileUnaryOp(op, argRes)
    yield result

  /** Compiles a term (the smallest unit in an expression).
    *
    * Terms include literals, references, grouped expressions, or nested expressions.
    *
    * @param term
    *   the term to compile
    * @param state
    *   the current code generation state
    * @param functionScope
    *   optional map of local function parameters to their registers
    * @return
    *   Either a CodeGenError or a CompileResult for the term.
    */
  private def compileTerm(
    term:          Term,
    state:         CodeGenState,
    functionScope: Map[String, Int] = Map.empty
  ): Either[CodeGenError, CompileResult] =
    term match
      case LiteralInt(_, value) =>
        CompileResult(value, state, true).asRight
      case ref: Ref =>
        // Check if reference exists in the function's local scope
        functionScope.get(ref.name) match
          case Some(paramReg) =>
            // Reference to a function parameter
            CompileResult(paramReg, state, false).asRight
          case None =>
            // Global reference
            val reg  = state.nextRegister
            val line = s"  %$reg = load i32, i32* @${ref.name}"
            CompileResult(reg, state.withRegister(reg + 1).emit(line), false).asRight
      case TermGroup(_, expr, _) =>
        compileExpr(expr, state, functionScope)
      case e: Expr =>
        compileExpr(e, state, functionScope)
      case app: App =>
        compileApp(app, state, functionScope)
      case Cond(_, cond, ifTrue, ifFalse, _, _) =>
        // Generate LLVM IR for conditionals
        for
          condRes <- compileExpr(cond, state, functionScope)

          // Create basic blocks
          thenBB  = state.nextRegister
          elseBB  = thenBB + 1
          mergeBB = elseBB + 1

          // Compare condition against 0 (false)
          compareReg   = mergeBB
          compareState = condRes.state.withRegister(mergeBB + 1)
          condOp = if condRes.isLiteral then condRes.register.toString else s"%${condRes.register}"
          _      = compareState.emit(s"  %$compareReg = icmp ne i32 $condOp, 0")

          // Branch based on condition
          _ = compareState.emit(s"  br i1 %$compareReg, label %then$thenBB, label %else$elseBB")

          // Then block
          thenState = compareState.emit(s"then$thenBB:")
          thenRes <- compileExpr(ifTrue, thenState, functionScope)
          thenValue =
            if thenRes.isLiteral then thenRes.register.toString else s"%${thenRes.register}"
          _ = thenRes.state.emit(s"  br label %merge$mergeBB")

          // Else block
          elseState = thenRes.state.emit(s"else$elseBB:")
          elseRes <- compileExpr(ifFalse, elseState, functionScope)
          elseValue =
            if elseRes.isLiteral then elseRes.register.toString else s"%${elseRes.register}"
          _ = elseRes.state.emit(s"  br label %merge$mergeBB")

          // Merge block with phi node
          resultReg = elseRes.state.nextRegister
          finalState = elseRes.state
            .withRegister(resultReg + 1)
            .emit(s"merge$mergeBB:")
            .emit(
              s"  %$resultReg = phi i32 [ $thenValue, %then$thenBB ], [ $elseValue, %else$elseBB ]"
            )
        yield CompileResult(resultReg, finalState, false)

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
    * @param functionScope
    *   optional map of local function parameters to their registers
    * @return
    *   Either a CodeGenError or a CompileResult for the expression.
    */
  private def compileExpr(
    expr:          Expr,
    state:         CodeGenState,
    functionScope: Map[String, Int] = Map.empty
  ): Either[CodeGenError, CompileResult] =
    expr.terms match
      case List(term) =>
        compileTerm(term, state, functionScope)
      case List(left, op: Ref, right) if op.resolvedAs.exists(_.isInstanceOf[BinOpDef]) =>
        compileBinaryOp(op.name, left, right, state, functionScope)
      case List(op: Ref, arg) if op.resolvedAs.exists(_.isInstanceOf[UnaryOpDef]) =>
        compileUnaryOp(op.name, arg, state, functionScope)
      case _ =>
        CodeGenError(s"Invalid expression structure: ${expr.terms}").asLeft

  /** Compiles a binary operation using the compile results of left and right operands.
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
  private def compileBinaryOp(
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

  /** Compiles a unary operation using the compile result of the operand.
    *
    * @param op
    *   the operator (e.g. "-", "+", "!")
    * @param argRes
    *   the compiled operand
    * @return
    *   Either a CodeGenError or a CompileResult with the updated state.
    */
  private def compileUnaryOp(
    op:     String,
    argRes: CompileResult
  ): Either[CodeGenError, CompileResult] =
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

  /** Compiles a function application.
    *
    * Handles function calls in MML, including nested applications for curried functions. For
    * example, `mult 2 2` is represented as App(App(Ref(mult), Expr(2)), Expr(2)).
    *
    * @param app
    *   the function application to compile
    * @param state
    *   the current code generation state
    * @param functionScope
    *   optional map of local function parameters to their registers
    * @return
    *   Either a CodeGenError or a CompileResult for the function application.
    */
  private def compileApp(
    app:           App,
    state:         CodeGenState,
    functionScope: Map[String, Int] = Map.empty
  ): Either[CodeGenError, CompileResult] =
    // Helper function to collect all arguments from nested App nodes
    // This handles curried applications (e.g., `mult 2 2` => App(App(Ref(mult), 2), 2))
    def collectArgsAndFunction(
      app:  App,
      args: List[Expr] = List.empty
    ): (Ref, List[Expr]) =
      app.fn match
        case ref:       Ref => (ref, app.arg :: args)
        case nestedApp: App =>
          collectArgsAndFunction(nestedApp, app.arg :: args)

    // Extract the function reference and all arguments
    val (fnRef, allArgs) = collectArgsAndFunction(app)

    // Compile all arguments
    allArgs
      .foldLeft((List.empty[String], state).asRight[CodeGenError]) {
        case (Right((compiledArgs, currentState)), arg) =>
          compileExpr(arg, currentState, functionScope).map { argRes =>
            val argOp =
              if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
            (compiledArgs :+ argOp, argRes.state)
          }
        case (Left(err), _) => Left(err)
      }
      .flatMap { case (compiledArgs, finalState) =>
        // Generate function call with all arguments
        val resultReg = finalState.nextRegister
        // Include the type prefix for each argument
        val typedArgs = compiledArgs.map(arg => s"i32 $arg").mkString(", ")
        val callLine  = s"  %$resultReg = call i32 @${fnRef.name}($typedArgs)"

        Right(
          CompileResult(
            resultReg,
            finalState.withRegister(resultReg + 1).emit(callLine),
            false
          )
        )
      }

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

  /** Compiles a function definition into LLVM IR.
    *
    * Creates a proper function definition with parameters, compiles the body, and adds a return
    * instruction.
    *
    * @param fn
    *   the function definition to compile
    * @param state
    *   the current code generation state
    * @return
    *   Either a CodeGenError or the updated CodeGenState.
    */
  private def compileFnDef(fn: FnDef, state: CodeGenState): Either[CodeGenError, CodeGenState] = {
    // For now, we'll assume i32 return type and i32 parameters for simplicity
    // In a more complete implementation, we would derive types from typeSpec/typeAsc

    // Generate function declaration with parameters
    val paramDecls = fn.params.zipWithIndex
      .map { case (param, idx) =>
        s"i32 %${idx}"
      }
      .mkString(", ")

    val functionDecl = s"define i32 @${fn.name}($paramDecls) {"
    val entryLine    = "entry:"

    // Setup function body state with initial lines
    val bodyState = state
      .emit(functionDecl)
      .emit(entryLine)
      .withRegister(0) // Reset register counter for local function scope

    // Create a scope map for function parameters
    val paramScope = fn.params.zipWithIndex.map { case (param, idx) =>
      val regNum    = idx
      val allocLine = s"  %${param.name}_ptr = alloca i32"
      val storeLine = s"  store i32 %${idx}, i32* %${param.name}_ptr"
      val loadLine  = s"  %${regNum} = load i32, i32* %${param.name}_ptr"

      // We'll emit the alloca/store/load sequence for each parameter
      bodyState.emit(allocLine).emit(storeLine).emit(loadLine)

      (param.name, regNum)
    }.toMap

    // Register count starts after parameter setup
    val updatedState = bodyState.withRegister(fn.params.size)

    // Compile the function body with the parameter scope
    compileExpr(fn.body, updatedState, paramScope).flatMap { bodyRes =>
      // Add return instruction with the result of the function body
      val returnOp =
        if bodyRes.isLiteral then bodyRes.register.toString
        else s"%${bodyRes.register}"

      val returnLine = s"  ret i32 ${returnOp}"

      // Close function and add empty line
      Right(
        bodyRes.state
          .emit(returnLine)
          .emit("}")
          .emit("")
      )
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
            case fn:  FnDef => compileFnDef(fn, state)
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
