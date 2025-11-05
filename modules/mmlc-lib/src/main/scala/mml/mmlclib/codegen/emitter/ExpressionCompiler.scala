package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.NativeOpRegistry

/** Handles code generation for expressions, terms, and operators. */

/** Helper for string escaping - converts to LLVM IR format using hex escapes */
private def escapeString(str: String): String = {
  str.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\0A"
    case '\r' => "\\0D"
    case '\t' => "\\09"
    case c if c < 0x20 || c > 0x7e => f"\\${c.toInt}%02X"
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

    // FIXME: Generalize, String is just a native record
    // FIXME: Uses so many hardcoded types!
    case LiteralString(_, value) => {
      // Add the string to the constants (to be emitted at the module level later)
      val (newState, constName) = state.addStringConstant(value)
      val strLen                = value.length

      // Emit code for string length
      // val lengthReg       = newState.nextRegister
      // val lengthLine      = emitAdd(lengthReg, "i64", "0", strLen.toString)
      // val stateWithLength = newState.withRegister(lengthReg + 1).emit(lengthLine)

      // Emit GEP instruction to get a pointer to the string data
      val ptrReg = newState.nextRegister
      val gepLine = emitGetElementPtr(
        ptrReg,
        s"[$strLen x i8]",
        s"[$strLen x i8]*",
        s"@$constName",
        List(("i64", "0"), ("i64", "0"))
      )
      val stateWithPtr = newState.withRegister(ptrReg + 1).emit(gepLine)

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
      val lenStoreLine      = emitStore(s"$strLen", "i64", s"%$lenPtrReg")
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
          // Global reference - get actual type from typeSpec
          ref.typeSpec match {
            case Some(typeSpec) =>
              getLlvmType(typeSpec, state) match {
                case Right(llvmType) =>
                  val reg  = state.nextRegister
                  val line = emitLoad(reg, llvmType, s"@${ref.name}")
                  CompileResult(reg, state.withRegister(reg + 1).emit(line), false).asRight
                case Left(err) =>
                  Left(err)
              }
            case None =>
              Left(
                CodeGenError(
                  s"Missing type information for global reference '${ref.name}' - TypeChecker should have provided this"
                )
              )
          }
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

        // Handle condition based on its type
        condOp = if condRes.isLiteral then condRes.register.toString else s"%${condRes.register}"

        // Check if condition result is boolean (from boolean operations) or integer
        (stateAfterCondition, branchCondition) =
          // Boolean operations (and, or, not) have nativeOp attributes and return i1 type
          if condRes.register > 0 && !condRes.isLiteral then {
            // Non-literal result - likely from boolean operation, use directly as i1
            (condRes.state, condOp)
          } else {
            // Literal or other - compare with 0 using actual type from condition
            val compareReg   = mergeBB
            val compareState = condRes.state.withRegister(mergeBB + 1)

            // Get the actual LLVM type from the condition's typeSpec
            val condType = cond.typeSpec match {
              case Some(typeSpec) =>
                getLlvmType(typeSpec, compareState) match {
                  case Right(llvmType) => llvmType
                  case Left(_) => "i32" // Temporary fallback only if type resolution fails
                }
              case None => "i32" // Temporary fallback for missing type information
            }

            val stateAfterCompare =
              compareState.emit(s"  %$compareReg = icmp ne $condType $condOp, 0")
            (stateAfterCompare, s"%$compareReg")
          }

        // Branch based on condition
        stateAfterBranch = stateAfterCondition.emit(
          s"  br i1 $branchCondition, label %then$thenBB, label %else$elseBB"
        )

        // Then block
        thenState = stateAfterBranch.emit(s"then$thenBB:")
        thenRes <- compileExpr(ifTrue, thenState, functionScope)
        thenValue =
          if thenRes.isLiteral then thenRes.register.toString else s"%${thenRes.register}"
        stateAfterThenBranch = thenRes.state.emit(s"  br label %merge$mergeBB")

        // Else block
        elseState = stateAfterThenBranch.emit(s"else$elseBB:")
        elseRes <- compileExpr(ifFalse, elseState, functionScope)
        elseValue =
          if elseRes.isLiteral then elseRes.register.toString else s"%${elseRes.register}"
        stateAfterElseBranch = elseRes.state.emit(s"  br label %merge$mergeBB")

        // Merge block with phi node
        resultReg = stateAfterElseBranch.nextRegister

        // Get the type from the then branch result (both branches should have same type)
        phiType <- ifTrue.typeSpec match {
          case Some(typeSpec) => getLlvmType(typeSpec, stateAfterElseBranch)
          case None =>
            Left(
              CodeGenError(
                "Missing type information for conditional expression - TypeChecker should have provided this"
              )
            )
        }

        finalState = stateAfterElseBranch
          .withRegister(resultReg + 1)
          .emit(s"merge$mergeBB:")
          .emit(
            s"  %$resultReg = phi $phiType [ $thenValue, %then$thenBB ], [ $elseValue, %else$elseBB ]"
          )
      } yield CompileResult(resultReg, finalState, false)
    }

    case NativeImpl(_, _, _, _) => {
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
      compileBinaryOp(op, left, right, state, functionScope)
    case List(op: Ref, arg) if op.resolvedAs.exists(_.isInstanceOf[UnaryOpDef]) =>
      compileUnaryOp(op, arg, state, functionScope)
    case _ =>
      CodeGenError(s"Invalid expression structure: ${expr.terms}").asLeft
  }
}

/** Compiles a binary operation by evaluating both sides and then applying the operation.
  *
  * @param opRef
  *   the operator reference containing AST resolution information
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
  opRef:         Ref,
  left:          Term,
  right:         Term,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  for {
    leftCompileResult <- compileTerm(left, state, functionScope)
    rightCompileResult <- compileTerm(right, leftCompileResult.state, functionScope)
    result <- applyBinaryOp(opRef, left, right, leftCompileResult, rightCompileResult)
  } yield result
}

/** Compiles a unary operation by evaluating the argument and then applying the operation.
  *
  * @param opRef
  *   the operator reference containing AST resolution information
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
  opRef:         Ref,
  arg:           Term,
  state:         CodeGenState,
  functionScope: Map[String, Int] = Map.empty
): Either[CodeGenError, CompileResult] = {
  for {
    argCompileResult <- compileTerm(arg, state, functionScope)
    result <- applyUnaryOp(opRef, arg, argCompileResult)
  } yield result
}

/** Applies a binary operation on compiled operands.
  *
  * @param opRef
  *   the operator reference containing AST resolution information
  * @param left
  *   the left operand term
  * @param right
  *   the right operand term
  * @param leftRes
  *   the compiled left operand
  * @param rightRes
  *   the compiled right operand
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
private def applyBinaryOp(
  opRef:    Ref,
  left:     Term,
  right:    Term,
  leftRes:  CompileResult,
  rightRes: CompileResult
): Either[CodeGenError, CompileResult] = {
  // Extract native operation from AST resolution
  getNativeOperator(opRef.resolvedAs) match {
    case Some(nativeOp) =>
      // Look up the native operation descriptor
      NativeOpRegistry.getBinaryOp(nativeOp) match {
        case Some(descriptor) =>
          val resultReg = rightRes.state.nextRegister
          val leftOp =
            if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
          val rightOp =
            if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"

          // Get the LLVM type from the left operand's typeSpec
          left.typeSpec match {
            case Some(typeSpec) =>
              getLlvmType(typeSpec, rightRes.state) match {
                case Right(llvmType) =>
                  // Substitute template variables with actual values
                  val instruction = descriptor.template
                    .replace("%result", s"%$resultReg")
                    .replace("%type", llvmType)
                    .replace("%left", leftOp)
                    .replace("%right", rightOp)

                  val line = s"  $instruction"
                  CompileResult(
                    resultReg,
                    rightRes.state.withRegister(resultReg + 1).emit(line),
                    false
                  ).asRight
                case Left(err) => Left(err)
              }
            case None =>
              Left(
                CodeGenError(
                  s"Missing type information for binary operator operand - TypeChecker should have provided this"
                )
              )
          }
        case None =>
          Left(CodeGenError(s"Native operation '$nativeOp' not found in registry"))
      }
    case None =>
      Left(
        CodeGenError(
          s"No native operation found for binary operator '${opRef.name}' - operator definition should have @native annotation"
        )
      )
  }
}

/** Applies a unary operation on a compiled operand.
  *
  * @param opRef
  *   the operator reference containing AST resolution information
  * @param arg
  *   the operand term
  * @param argRes
  *   the compiled operand
  * @return
  *   Either a CodeGenError or a CompileResult with the updated state.
  */
private def applyUnaryOp(
  opRef:  Ref,
  arg:    Term,
  argRes: CompileResult
): Either[CodeGenError, CompileResult] = {
  // Extract native operation from AST resolution
  getNativeOperator(opRef.resolvedAs) match {
    case Some(nativeOp) =>
      // Look up the native operation descriptor
      NativeOpRegistry.getUnaryOp(nativeOp) match {
        case Some(descriptor) =>
          val resultReg = argRes.state.nextRegister
          val argOp = if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"

          // Get the LLVM type from the operand's typeSpec
          arg.typeSpec match {
            case Some(typeSpec) =>
              getLlvmType(typeSpec, argRes.state) match {
                case Right(llvmType) =>
                  // Substitute template variables with actual values
                  val instruction = descriptor.template
                    .replace("%result", s"%$resultReg")
                    .replace("%type", llvmType)
                    .replace("%operand", argOp)

                  val line = s"  $instruction"
                  CompileResult(
                    resultReg,
                    argRes.state.withRegister(resultReg + 1).emit(line),
                    false
                  ).asRight
                case Left(err) => Left(err)
              }
            case None =>
              Left(
                CodeGenError(
                  s"Missing type information for unary operator operand - TypeChecker should have provided this"
                )
              )
          }
        case None =>
          Left(CodeGenError(s"Native operation '$nativeOp' not found in registry"))
      }
    case None =>
      Left(
        CodeGenError(
          s"No native operation found for unary operator '${opRef.name}' - operator definition should have @native annotation"
        )
      )
  }
}

/** Helper function to check if a resolved reference is a native operator.
  *
  * @param resolvedAs
  *   the resolved AST node
  * @return
  *   Some(nativeOp) if it's a native operator, None otherwise
  */
private def getNativeOperator(resolvedAs: Option[Resolvable]): Option[String] = {
  resolvedAs match {
    case Some(binOp: BinOpDef) =>
      binOp.body.terms.collectFirst { case NativeImpl(_, _, _, Some(nativeOp)) =>
        nativeOp
      }
    case Some(unaryOp: UnaryOpDef) =>
      unaryOp.body.terms.collectFirst { case NativeImpl(_, _, _, Some(nativeOp)) =>
        nativeOp
      }
    case _ => None
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

  // Check if this is a native operator first
  getNativeOperator(fnRef.resolvedAs) match {
    case Some(nativeOp) =>
      // Handle native operators with special LLVM instructions
      allArgs match {
        case List(leftArg, rightArg) =>
          // Binary native operator - use same template-based approach as applyBinaryOp
          for {
            leftRes <- compileExpr(leftArg, state, functionScope)
            rightRes <- compileExpr(rightArg, leftRes.state, functionScope)

            // Look up the native operation descriptor
            descriptor <- NativeOpRegistry.getBinaryOp(nativeOp) match {
              case Some(desc) => Right(desc)
              case None => Left(CodeGenError(s"Native operation '$nativeOp' not found in registry"))
            }

            resultReg = rightRes.state.nextRegister
            leftOp =
              if leftRes.isLiteral then leftRes.register.toString else s"%${leftRes.register}"
            rightOp =
              if rightRes.isLiteral then rightRes.register.toString else s"%${rightRes.register}"

            // Get the LLVM type from the left operand's typeSpec
            llvmType <- leftArg.typeSpec match {
              case Some(typeSpec) =>
                getLlvmType(typeSpec, rightRes.state)
              case None =>
                Left(
                  CodeGenError(
                    s"Missing type information for binary operator operand - TypeChecker should have provided this"
                  )
                )
            }

            // Substitute template variables with actual values
            instruction = descriptor.template
              .replace("%result", s"%$resultReg")
              .replace("%type", llvmType)
              .replace("%left", leftOp)
              .replace("%right", rightOp)

            line       = s"  $instruction"
            finalState = rightRes.state.withRegister(resultReg + 1).emit(line)
          } yield CompileResult(
            resultReg,
            finalState,
            false,
            if nativeOp.startsWith("icmp_") then "Bool" else "Int"
          )

        case List(operandArg) =>
          // Unary native operator - use same template-based approach as applyUnaryOp
          for {
            operandRes <- compileExpr(operandArg, state, functionScope)

            // Look up the native operation descriptor
            descriptor <- NativeOpRegistry.getUnaryOp(nativeOp) match {
              case Some(desc) => Right(desc)
              case None => Left(CodeGenError(s"Native operation '$nativeOp' not found in registry"))
            }

            resultReg = operandRes.state.nextRegister
            operandOp =
              if operandRes.isLiteral then operandRes.register.toString
              else s"%${operandRes.register}"

            // Get the LLVM type from the operand's typeSpec
            llvmType <- operandArg.typeSpec match {
              case Some(typeSpec) =>
                getLlvmType(typeSpec, operandRes.state)
              case None =>
                Left(
                  CodeGenError(
                    s"Missing type information for unary operator operand - TypeChecker should have provided this"
                  )
                )
            }

            // Substitute template variables with actual values
            instruction = descriptor.template
              .replace("%result", s"%$resultReg")
              .replace("%type", llvmType)
              .replace("%operand", operandOp)

            line       = s"  $instruction"
            finalState = operandRes.state.withRegister(resultReg + 1).emit(line)
          } yield CompileResult(
            resultReg,
            finalState,
            false,
            if llvmType == "i1" then "Bool" else "Int"
          )

        case _ =>
          Left(
            CodeGenError(
              s"Native operator '$nativeOp' called with wrong number of arguments: ${allArgs.length}"
            )
          )
      }

    case None =>
      // Regular function call - check if this is a nullary function with unit args
      val isNullaryWithUnitArgs = fnRef.resolvedAs match {
        case Some(fnDef: FnDef) if fnDef.params.isEmpty =>
          // Check if all arguments are unit literals (added by ExpressionRewriter)
          allArgs.forall { arg =>
            arg.terms match {
              case List(_: LiteralUnit) => true
              case _ => false
            }
          }
        case _ => false
      }

      if isNullaryWithUnitArgs then {
        // Skip compiling unit arguments for nullary functions
        val resultReg = state.nextRegister

        // Get function return type from the application's typeSpec
        val fnReturnTypeResult = app.typeSpec match {
          case Some(typeSpec) =>
            getLlvmType(typeSpec, state)
          case None =>
            Left(
              CodeGenError(
                s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this"
              )
            )
        }

        fnReturnTypeResult.flatMap { fnReturnType =>
          if fnReturnType == "void" then {
            // Unit functions - call without assigning to a register
            val callLine = emitCall(None, None, fnRef.name, List.empty)

            Right(
              CompileResult(
                0, // No meaningful register for Unit expressions
                state.emit(callLine),
                false,
                "Unit"
              )
            )
          } else {
            // Non-Unit functions - assign result to register
            val callLine = emitCall(Some(resultReg), Some(fnReturnType), fnRef.name, List.empty)

            Right(
              CompileResult(
                resultReg,
                state.withRegister(resultReg + 1).emit(callLine),
                false,
                if fnReturnType == "%String" then "String" else "Int"
              )
            )
          }
        }
      } else {
        // Regular function call with real arguments - compile all arguments and generate call
        allArgs
          .foldLeft((List.empty[(String, String)], state).asRight[CodeGenError]) {
            case (Right((compiledArgs, currentState)), arg) =>
              compileExpr(arg, currentState, functionScope).flatMap { argRes =>
                val argOp =
                  if argRes.isLiteral then argRes.register.toString else s"%${argRes.register}"

                // Get the actual LLVM type from the argument's typeSpec
                arg.typeSpec match {
                  case Some(typeSpec) =>
                    getLlvmType(typeSpec, argRes.state) match {
                      case Right(llvmType) =>
                        Right((compiledArgs :+ (argOp, llvmType), argRes.state))
                      case Left(err) =>
                        Left(err)
                    }
                  case None =>
                    Left(
                      CodeGenError(
                        s"Missing type information for function argument - TypeChecker should have provided this"
                      )
                    )
                }
              }
            case (Left(err), _) => Left(err)
          }
          .flatMap { case (compiledArgs, finalState) =>
            val resultReg = finalState.nextRegister

            // Get function return type from the application's typeSpec
            val fnReturnTypeResult = app.typeSpec match {
              case Some(typeSpec) =>
                getLlvmType(typeSpec, finalState)
              case None =>
                Left(
                  CodeGenError(
                    s"Missing return type information for function application '${fnRef.name}' - TypeChecker should have provided this"
                  )
                )
            }

            fnReturnTypeResult.flatMap { fnReturnType =>
              // Generate function call with proper types
              val args = compiledArgs.map { case (value, typ) => (typ, value) }

              if fnReturnType == "void" then {
                // Unit functions - call without assigning to a register
                val callLine = emitCall(None, None, fnRef.name, args)

                Right(
                  CompileResult(
                    0, // No meaningful register for Unit expressions
                    finalState.emit(callLine),
                    false,
                    "Unit" // Proper type name for Unit functions
                  )
                )
              } else {
                // Non-Unit functions - assign result to register
                val callLine = emitCall(Some(resultReg), Some(fnReturnType), fnRef.name, args)

                Right(
                  CompileResult(
                    resultReg,
                    finalState.withRegister(resultReg + 1).emit(callLine),
                    false,
                    // Determine typeName from LLVM type for backward compatibility
                    if fnReturnType == "%String" then "String" else "Int"
                  )
                )
              }
            }
          }
      }
  }
}
