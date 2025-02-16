package mml.mmlclib.codegen

import mml.mmlclib.ast.*

/** LLVM IR Printer for MML */
object LlvmIrPrinter:
  /** Represents the state of code generation */
  case class CodeGenState(
    nextRegister:    Int              = 0,
    nextLabel:       Int              = 0,
    stringConstants: Map[String, Int] = Map.empty,
    globalInits:     List[String]     = List.empty,
    output:          List[String]     = List.empty
  ):
    def withRegister(reg: Int): CodeGenState = copy(nextRegister = reg)
    def withLabel(label: Int):  CodeGenState = copy(nextLabel = label)
    def withString(str: String, idx: Int): CodeGenState =
      copy(stringConstants = stringConstants + (str -> idx))
    def withGlobalInit(init: String): CodeGenState =
      copy(globalInits = init :: globalInits)
    def emit(line: String):           CodeGenState = copy(output = line :: output)
    def emitAll(lines: List[String]): CodeGenState = copy(output = lines.reverse ::: output)
    def result:                       String       = output.reverse.mkString("\n")

  /** Result of an expression or term compilation */
  case class CompileResult(register: Int, state: CodeGenState)

  /** Convert MML type to LLVM type string */
  private def llvmType(typeSpec: Option[TypeSpec]): String =
    typeSpec match
      case Some(LiteralIntType(_)) => "i32"
      case Some(LiteralFloatType(_)) => "float"
      case Some(LiteralBoolType(_)) => "i1"
      case Some(LiteralStringType(_)) => "i8*"
      case Some(LiteralUnitType(_)) => "void"
      case Some(TypeName(_, name)) => "i32" // Default to i32 for now
      case None => "i32" // Default type
      case _ => throw CodeGenError(s"Unsupported type: $typeSpec")

  /** Print string constant and return its index */
  private def addStringConstant(str: String, state: CodeGenState): CompileResult =
    state.stringConstants.get(str) match
      case Some(idx) => CompileResult(idx, state)
      case None =>
        val idx     = state.stringConstants.size
        val escaped = escapeString(str)
        val declaration =
          s"@str.$idx = private unnamed_addr constant [${str.length + 1} x i8] c\"$escaped\\00\""
        CompileResult(idx, state.withString(str, idx).emit(declaration))

  /** Escape string for LLVM IR */
  private def escapeString(str: String): String =
    str.flatMap {
      case '\n' => "\\0A"
      case '\r' => "\\0D"
      case '\t' => "\\09"
      case '\"' => "\\22"
      case '\\' => "\\\\"
      case c if c.isControl => f"\\${c.toInt}%02X"
      case c => c.toString
    }

  /** Print binary operation */
  private def compileBinaryOp(
    op:    String,
    left:  Term,
    right: Term,
    state: CodeGenState
  ): CompileResult =
    val CompileResult(leftReg, state1)  = compileTerm(left, state)
    val CompileResult(rightReg, state2) = compileTerm(right, state1)
    val resultReg                       = state2.nextRegister

    val leftType  = llvmType(left.typeSpec)
    val rightType = llvmType(right.typeSpec)

    val instruction = (op, leftType) match
      case ("+", "float") => "fadd"
      case ("-", "float") => "fsub"
      case ("*", "float") => "fmul"
      case ("/", "float") => "fdiv"
      case ("+", _) => "add"
      case ("-", _) => "sub"
      case ("*", _) => "mul"
      case ("/", _) => "sdiv"
      case _ => throw CodeGenError(s"Unknown operator: $op")

    val line = s"  %$resultReg = $instruction $leftType %$leftReg, %$rightReg"
    CompileResult(resultReg, state2.withRegister(resultReg + 1).emit(line))

  /** Print term (atomic expression) */
  private def compileTerm(term: Term, state: CodeGenState): CompileResult = term match
    case LiteralInt(_, value) =>
      val reg  = state.nextRegister
      val line = s"  %$reg = add i32 $value, 0"
      CompileResult(reg, state.withRegister(reg + 1).emit(line))

    case LiteralFloat(_, value) =>
      val reg  = state.nextRegister
      val line = s"  %$reg = fadd float ${value}f, 0.0"
      CompileResult(reg, state.withRegister(reg + 1).emit(line))

    case LiteralBool(_, value) =>
      val reg  = state.nextRegister
      val line = s"  %$reg = add i1 ${if value then 1 else 0}, 0"
      CompileResult(reg, state.withRegister(reg + 1).emit(line))

    case LiteralString(_, value) =>
      val CompileResult(strIdx, state1) = addStringConstant(value, state)
      val reg                           = state1.nextRegister
      val line =
        s"  %$reg = getelementptr [${value.length + 1} x i8], [${value.length + 1} x i8]* @str.$strIdx, i64 0, i64 0"
      CompileResult(reg, state1.withRegister(reg + 1).emit(line))

    case LiteralUnit(_) =>
      CompileResult(-1, state) // void type doesn't need a register

    case ref: Ref =>
      val reg = state.nextRegister
      val line =
        s"  %$reg = load ${llvmType(term.typeSpec)}, ${llvmType(term.typeSpec)}* @$ref.name"
      CompileResult(reg, state.withRegister(reg + 1).emit(line))

    case GroupTerm(_, expr, _) =>
      compileExpr(expr, state)

    case other =>
      throw CodeGenError(s"Unsupported term: $other")

  /** Print expression */
  private def compileExpr(expr: Expr, state: CodeGenState): CompileResult = expr.terms match
    case List(term) =>
      compileTerm(term, state)

    case left :: Ref(_, op, _, _, _) :: right :: Nil =>
      compileBinaryOp(op, left, right, state)

    case fn :: args =>
      // Function call
      val fnName = fn match
        case Ref(_, name, _, _, _) => name
        case _ => throw CodeGenError("Function reference expected")

      val (argRegs, finalState) = args.foldLeft((List.empty[Int], state)) {
        case ((regs, st), arg) =>
          val CompileResult(reg, newState) = compileTerm(arg, st)
          (reg :: regs, newState)
      }

      val returnType = llvmType(expr.typeSpec)
      val resultReg  = finalState.nextRegister

      val argList = argRegs.reverse
        .zip(args)
        .map { case (reg, arg) =>
          s"${llvmType(arg.typeSpec)} %$reg"
        }
        .mkString(", ")

      if returnType != "void" then
        val line = s"  %$resultReg = call $returnType @$fnName($argList)"
        CompileResult(resultReg, finalState.withRegister(resultReg + 1).emit(line))
      else
        val line = s"  call $returnType @$fnName($argList)"
        CompileResult(-1, finalState.emit(line))

    case Nil =>
      throw CodeGenError("Empty expression")

  /** Print conditional expression */
  private def compileCond(cond: Cond, state: CodeGenState): CompileResult =
    val CompileResult(condReg, state1) = compileExpr(cond.cond, state)
    val thenLabel                      = state1.nextLabel
    val elseLabel                      = thenLabel + 1
    val endLabel                       = elseLabel + 1

    val branchLine = s"  br i1 %$condReg, label %then$thenLabel, label %else$elseLabel"
    val state2     = state1.withLabel(endLabel + 1).emit(branchLine)

    val state3                         = state2.emit(s"then$thenLabel:")
    val CompileResult(thenReg, state4) = compileExpr(cond.ifTrue, state3)
    val state5                         = state4.emit(s"  br label %end$endLabel")

    val state6                         = state5.emit(s"else$elseLabel:")
    val CompileResult(elseReg, state7) = compileExpr(cond.ifFalse, state6)
    val state8                         = state7.emit(s"  br label %end$endLabel")

    val state9     = state8.emit(s"end$endLabel:")
    val resultReg  = state9.nextRegister
    val resultType = llvmType(cond.typeSpec)
    val phiLine =
      s"  %$resultReg = phi $resultType [ %$thenReg, %then$thenLabel ], [ %$elseReg, %else$elseLabel ]"

    CompileResult(resultReg, state9.withRegister(resultReg + 1).emit(phiLine))

  /** Print function definition */
  private def compileFunction(fn: FnDef, state: CodeGenState): CodeGenState =
    val returnType = llvmType(fn.typeSpec)
    val params     = fn.params.map(p => s"${llvmType(p.typeSpec)} %${p.name}").mkString(", ")

    val header = s"define $returnType @${fn.name}($params) {"
    val state1 = state.withRegister(fn.params.length).emit(header).emit("entry:")

    val CompileResult(resultReg, state2) = compileExpr(fn.body, state1)

    val returnLine =
      if returnType != "void" then s"  ret $returnType %$resultReg"
      else "  ret void"

    state2.emitAll(List(returnLine, "}"))

  /** Print binding */
  private def compileBinding(bnd: Bnd, state: CodeGenState): CodeGenState =
    val varType = llvmType(bnd.typeSpec)

    bnd.value.terms match {
      case List(LiteralString(_, value)) =>
        val CompileResult(strIdx, state1) = addStringConstant(value, state)
        state1.emit(s"@${bnd.name} = global [${value.length + 1} x i8]* @str.$strIdx")

      case List(LiteralInt(_, value)) =>
        state.emit(s"@${bnd.name} = global i32 $value")

      case List(LiteralFloat(_, value)) =>
        state.emit(s"@${bnd.name} = global float ${value}f")

      case List(LiteralBool(_, value)) =>
        state.emit(s"@${bnd.name} = global i1 ${if value then 1 else 0}")

      case _ =>
        // For complex expressions, we need to create a global initializer function
        val initFnName                      = s"__init_global_${bnd.name}"
        val CompileResult(valueReg, state1) = compileExpr(bnd.value, state)

        // Create global with a default value
        val state2 = state1.emit(s"@${bnd.name} = global $varType 0")

        // Create initializer function
        val initLines = List(
          s"define internal void @$initFnName() {",
          s"  entry:",
          s"  store $varType %$valueReg, $varType* @${bnd.name}",
          s"  ret void",
          s"}"
        )

        // Add to global constructors
        val state3 = state2.emitAll(initLines)
        state3.withGlobalInit(s"  call void @$initFnName()")
    }

  /** Print module */
  def printModule(module: Module): String =
    val moduleHeader = List(
      s"; ModuleID = '${module.name}'",
      "target triple = \"x86_64-unknown-unknown\"",
      ""
    )

    val initialState = CodeGenState().emitAll(moduleHeader)

    // First emit all globals and functions
    val state1 = module.members.foldLeft(initialState) {
      case (state, fn: FnDef) => compileFunction(fn, state)
      case (state, bnd: Bnd) => compileBinding(bnd, state)
      case (state, _) => state
    }

    // If we have global initializations, create a global constructor
    val finalState = if state1.globalInits.nonEmpty then
      val initLines = List(
        "",
        "@llvm.global_ctors = appending global [1 x { i32, void ()*, i8* }] [",
        "  { i32, void ()*, i8* } { i32 65535, void ()* @__global_init, i8* null }",
        "]",
        "",
        "define internal void @__global_init() {",
        "  entry:"
      ) ++ state1.globalInits ++ List(
        "  ret void",
        "}"
      )
      state1.emitAll(initLines)
    else state1

    finalState.result

case class CodeGenError(message: String) extends Exception(message)
