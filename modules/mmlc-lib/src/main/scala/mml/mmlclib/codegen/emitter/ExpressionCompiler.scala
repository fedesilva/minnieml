package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Handles code generation for expressions, terms, and operators. */

/** Helper for string escaping */
private def escapeString(str: String): String = {
  str.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c => c.toString
  }
}

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
def compileTerm(
  term:          Term,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  term match {
    case LiteralInt(_, value) =>
      CompileResult(value, state, true).asRight

    case LiteralString(_, value) => {
      // Add the string to the constants (to be emitted at the module level later)
      val (newState, constName) = state.addStringConstant(value)
      val strLen                = value.length

      // Emit code for string length
      val lengthReg       = newState.nextRegister
      val lengthLine      = emitAdd(lengthReg, "i64", "0", strLen.toString)
      val stateWithLength = newState.withRegister(lengthReg + 1).emit(lengthLine)

      // Emit GEP instruction to get a pointer to the string data
      val ptrReg = stateWithLength.nextRegister
      val gepLine = emitGetElementPtr(
        ptrReg,
        s"[$strLen x i8]",
        s"[$strLen x i8]*",
        s"@$constName",
        List(("i64", "0"), ("i64", "0"))
      )
      val stateWithPtr = stateWithLength.withRegister(ptrReg + 1).emit(gepLine)

      // Allocate and initialize a String struct
      val allocReg       = stateWithPtr.nextRegister
      val allocLine      = s"  %$allocReg = alloca %String"
      val stateWithAlloc = stateWithPtr.withRegister(allocReg + 1).emit(allocLine)

      // Store the length field
      val lenPtrReg = stateWithAlloc.nextRegister
      val lenPtrLine = emitGetElementPtr(
        lenPtrReg,
        "%String",
        "%String*",
        s"%$allocReg",
        List(("i32", "0"), ("i32", "0"))
      )
      val stateWithLenPtr   = stateWithAlloc.withRegister(lenPtrReg + 1).emit(lenPtrLine)
      val lenStoreLine      = emitStore(s"%$lengthReg", "i64", s"%$lenPtrReg")
      val stateWithLenStore = stateWithLenPtr.emit(lenStoreLine)

      // Store the data field
      val dataPtrReg = stateWithLenStore.nextRegister
      val dataPtrLine = emitGetElementPtr(
        dataPtrReg,
        "%String",
        "%String*",
        s"%$allocReg",
        List(("i32", "0"), ("i32", "1"))
      )
      val stateWithDataPtr   = stateWithLenStore.withRegister(dataPtrReg + 1).emit(dataPtrLine)
      val dataStoreLine      = emitStore(s"%$ptrReg", "i8*", s"%$dataPtrReg")
      val stateWithDataStore = stateWithDataPtr.emit(dataStoreLine)

      // Load the String struct
      val resultReg  = stateWithDataStore.nextRegister
      val loadLine   = emitLoad(resultReg, "%String", s"%$allocReg")
      val finalState = stateWithDataStore.withRegister(resultReg + 1).emit(loadLine)

      CompileResult(resultReg, finalState, false, "String").asRight
    }

    case ref: Ref => {
      // Check if reference exists in the function's local scope
      functionScope.get(ref.name) match {
        case Some(paramReg) =>
          // Reference to a function parameter
          CompileResult(paramReg, state, false).asRight
        case None =>
          // Global reference
          val reg  = state.nextRegister
          val line = emitLoad(reg, "i32", s"@${ref.name}")
          CompileResult(reg, state.withRegister(reg + 1).emit(line), false).asRight
      }
    }

    case TermGroup(_, expr, _) =>
      compileExpr(expr, state, functionScope)

    case e: Expr =>
      compileExpr(e, state, functionScope)

    case app: App =>
      compileApp(app, state, functionScope)

    case Cond(_, cond, ifTrue, ifFalse, _, _) => {
      // Generate LLVM IR for conditionals
      for {
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
      } yield CompileResult(resultReg, finalState, false)
    }

    case NativeImpl(_, _, _) => {
      // Native implementation - handled at function level
      CompileResult(0, state, false).asRight
    }

    case other =>
      CodeGenError(s"Unsupported term: $other").asLeft
  }
}

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
def compileExpr(
  expr:          Expr,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  expr.terms match {
    case List(term) =>
      compileTerm(term, state, functionScope)
    case List(left, op: Ref, right) if op.resolvedAs.exists(_.isInstanceOf[BinOpDef]) =>
      compileBinaryOp(op.name, left, right, state, functionScope)
    case List(op: Ref, arg) if op.resolvedAs.exists(_.isInstanceOf[UnaryOpDef]) =>
      compileUnaryOp(op.name, arg, state, functionScope)
    case _ =>
      CodeGenError(s"Invalid expression structure: ${expr.terms}").asLeft
  }
}

/** Compiles a binary operation by evaluating both sides and then applying the operation.
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
def compileBinaryOp(
  op:            String,
  left:          Term,
  right:         Term,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  for {
    leftCompileResult <- compileTerm(left, state, functionScope)
    rightCompileResult <- compileTerm(right, leftCompileResult.state, functionScope)
    result <- applyBinaryOp(op, leftCompileResult, rightCompileResult)
  } yield result
}

/** Compiles a unary operation by evaluating the argument and then applying the operation.
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
def compileUnaryOp(
  op:            String,
  arg:           Term,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  for {
    argCompileResult <- compileTerm(arg, state, functionScope)
    result <- applyUnaryOp(op, argCompileResult)
  } yield result
}

/** Applies a binary operation on compiled operands.
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
private def applyBinaryOp(
  op:       String,
  leftRes:  CompileResult,
  rightRes: CompileResult
): Either[CodeGenError, CompileResult] = {
  if leftRes.isLiteral && rightRes.isLiteral then {
    op match {
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
    }
  } else {
    op match {
      case "+" => {
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = emitAdd(resultReg, "i32", leftOp, rightOp)
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case "-" => {
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = emitSub(resultReg, "i32", leftOp, rightOp)
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case "*" => {
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = emitMul(resultReg, "i32", leftOp, rightOp)
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case "/" => {
        val resultReg = rightRes.state.nextRegister
        val leftOp =
          if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
        val rightOp =
          if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"
        val line = emitSDiv(resultReg, "i32", leftOp, rightOp)
        CompileResult(
          resultReg,
          rightRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case "^" =>
        CodeGenError("Power operator not yet implemented").asLeft
      case _ =>
        CodeGenError(s"Unknown operator: $op").asLeft
    }
  }
}

/** Applies a unary operation on a compiled operand.
  *
  * @param op
  *   the operator (e.g. "-", "+", "!")
  * @param argRes
  *   the compiled operand
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
private def applyUnaryOp(
  op:     String,
  argRes: CompileResult
): Either[CodeGenError, CompileResult] = {
  if argRes.isLiteral then {
    op match {
      case "-" => CompileResult(-argRes.register, argRes.state, true).asRight
      case "+" => CompileResult(argRes.register, argRes.state, true).asRight
      case "!" =>
        CompileResult(if argRes.register == 0 then 1 else 0, argRes.state, true).asRight
      case _ => CodeGenError(s"Unknown unary operator: $op").asLeft
    }
  } else {
    op match {
      case "-" => {
        val resultReg = argRes.state.nextRegister
        val argOp =
          if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
        val line = emitSub(resultReg, "i32", "0", argOp)
        CompileResult(
          resultReg,
          argRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case "+" => {
        val resultReg = argRes.state.nextRegister
        val argOp =
          if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
        val line = emitAdd(resultReg, "i32", argOp, "0")
        CompileResult(
          resultReg,
          argRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case "!" => {
        val resultReg = argRes.state.nextRegister
        val argOp =
          if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
        val line = emitXor(resultReg, "i32", "1", argOp)
        CompileResult(
          resultReg,
          argRes.state.withRegister(resultReg + 1).emit(line),
          false
        ).asRight
      }
      case _ =>
        CodeGenError(s"Unknown unary operator: $op").asLeft
    }
  }
}

/** Compiles a function application.
  *
  * Handles function calls in MML, including nested applications for curried functions. For example,
  * `mult 2 2` is represented as App(App(Ref(mult), Expr(2)), Expr(2)).
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
def compileApp(
  app:           App,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  // Helper function to collect all arguments from nested App nodes
  // This handles curried applications (e.g., `mult 2 2` => App(App(Ref(mult), 2), 2))
  def collectArgsAndFunction(
    app:  App,
    args: List[Expr] = List.empty
  ): (Ref, List[Expr]) = {
    app.fn match {
      case ref:       Ref => (ref, app.arg :: args)
      case nestedApp: App =>
        collectArgsAndFunction(nestedApp, app.arg :: args)
    }
  }

  // Extract the function reference and all arguments
  val (fnRef, allArgs) = collectArgsAndFunction(app)

  // Detect if this is a call to a native function like 'print'
  val isNativeFunction =
    fnRef.name == "print" || fnRef.name == "println" || fnRef.name == "readline"

  // Compile all arguments
  allArgs
    .foldLeft((List.empty[(String, String)], state).asRight[CodeGenError]) {
      case (Right((compiledArgs, currentState)), arg) =>
        compileExpr(arg, currentState, functionScope).map { argRes =>
          val argOp =
            if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"
          val argType = if argRes.typeName == "String" then "%String" else "i32"
          (compiledArgs :+ (argOp, argType), argRes.state)
        }
      case (Left(err), _) => Left(err)
    }
    .flatMap { case (compiledArgs, finalState) =>
      val resultReg = finalState.nextRegister

      // If this is a native function, use appropriate types
      if isNativeFunction then {

        // Check which native function we're calling to determine return type
        if fnRef.name == "print" || fnRef.name == "println" then {
          // Void functions - call without assigning to a register
          val args     = compiledArgs.map { case (value, typ) => (typ, value) }
          val callLine = emitCall(None, None, fnRef.name, args)

          // Return a constant 0 for all void function calls
          val constReg = resultReg
          val loadZero = emitAdd(constReg, "i32", "0", "0")

          Right(
            CompileResult(
              constReg,
              finalState.withRegister(constReg + 1).emit(callLine).emit(loadZero),
              false,
              "Int" // Return type is Int for void functions (we return 0)
            )
          )
        } else {
          // Other native functions (like readline) with actual return types
          val returnType = "%String" // For readline
          val args       = compiledArgs.map { case (value, typ) => (typ, value) }
          val callLine   = emitCall(Some(resultReg), Some(returnType), fnRef.name, args)

          Right(
            CompileResult(
              resultReg,
              finalState.withRegister(resultReg + 1).emit(callLine),
              false,
              "String" // Return type is String for readline
            )
          )
        }
      } else {
        // Regular function call (non-native)
        val args     = compiledArgs.map { case (value, _) => ("i32", value) }
        val callLine = emitCall(Some(resultReg), Some("i32"), fnRef.name, args)

        Right(
          CompileResult(
            resultReg,
            finalState.withRegister(resultReg + 1).emit(callLine),
            false
          )
        )
      }
    }
}
