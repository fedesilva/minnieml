package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  emitCall,
  emitGetElementPtr,
  emitLoad,
  emitStore,
  getLlvmType,
  getMmlTypeName
}

// ============================================================================
// Hole Compilation
// ============================================================================

private val holeFunctionName       = "__mml_sys_hole"
private val holeFunctionParamTypes = List("i64", "i64", "i64", "i64")

/** Compiles a hole expression (???) to LLVM IR.
  *
  * Holes emit a call to __mml_sys_hole with source location, then produce an undefined value of the
  * expected type.
  */
def compileHole(hole: Hole, state: CodeGenState): Either[CodeGenError, CompileResult] =
  val span = hole.span
  val stateWithDecl =
    state.withFunctionDeclaration(holeFunctionName, "void", holeFunctionParamTypes)
  val args = List(
    ("i64", span.start.line.toString),
    ("i64", span.start.col.toString),
    ("i64", span.end.line.toString),
    ("i64", span.end.col.toString)
  )
  val stateAfterCall = stateWithDecl.emit(emitCall(None, None, holeFunctionName, args))

  hole.typeSpec match
    case Some(typeSpec) =>
      getLlvmType(typeSpec, stateAfterCall) match
        case Right("void") =>
          CompileResult(0, stateAfterCall, false, "Unit").asRight
        case Right(llvmType) =>
          val allocaReg = stateAfterCall.nextRegister
          val stateWithAlloca = stateAfterCall
            .withRegister(allocaReg + 1)
            .emit(s"  %$allocaReg = alloca $llvmType")
          val loadReg = stateWithAlloca.nextRegister
          val stateWithLoad = stateWithAlloca
            .withRegister(loadReg + 1)
            .emit(emitLoad(loadReg, llvmType, s"%$allocaReg"))

          getMmlTypeName(typeSpec) match
            case Some(typeName) =>
              CompileResult(loadReg, stateWithLoad, false, typeName).asRight
            case None =>
              Left(
                CodeGenError(
                  s"Could not determine MML type name for hole from spec: $typeSpec",
                  Some(hole)
                )
              )
        case Left(err) => Left(err)
    case None =>
      Left(
        CodeGenError(
          "Missing type information for hole - TypeChecker should have provided this",
          Some(hole)
        )
      )

// ============================================================================
// String Literal Compilation
// ============================================================================

/** Compiles a string literal to LLVM IR.
  *
  * Creates a String struct with length and data pointer fields. The string data is stored as a
  * global constant.
  */
// FIXME: Generalize, String is just a native record
// FIXME: Uses so many hardcoded types!
def compileLiteralString(
  lit:   LiteralString,
  state: CodeGenState
): Either[CodeGenError, CompileResult] =
  val typeSpec = lit.typeSpec match
    case Some(ts) => Right(ts)
    case None => Left(CodeGenError("LiteralString missing type specification", Some(lit)))

  typeSpec.flatMap { ts =>
    // Add the string to the constants (to be emitted at the module level later)
    val (newState, constName) = state.addStringConstant(lit.value)
    val strLen                = lit.value.length

    for
      // Get field-specific TBAA tags for struct field accesses
      (stateWithLenTag, lenTag) <- TbaaEmitter.getTbaaStructFieldTag(ts, 0, newState)
      (stateWithDataTag, dataTag) <- TbaaEmitter.getTbaaStructFieldTag(ts, 1, stateWithLenTag)
    yield
      // Emit GEP instruction to get a pointer to the string data
      val ptrReg = stateWithDataTag.nextRegister
      val gepLine = emitGetElementPtr(
        ptrReg,
        s"[$strLen x i8]",
        s"[$strLen x i8]*",
        s"@$constName",
        List(("i64", "0"), ("i64", "0"))
      )
      val stateWithPtr = stateWithDataTag.withRegister(ptrReg + 1).emit(gepLine)

      // Allocate and initialize a String struct
      val allocReg       = stateWithPtr.nextRegister
      val allocLine      = s"  %$allocReg = alloca %struct.String"
      val stateWithAlloc = stateWithPtr.withRegister(allocReg + 1).emit(allocLine)

      // Store the length field (field 0, offset 0, type int)
      val lenPtrReg = stateWithAlloc.nextRegister
      val lenPtrLine = emitGetElementPtr(
        lenPtrReg,
        "%struct.String",
        "%struct.String*",
        s"%$allocReg",
        List(("i32", "0"), ("i32", "0"))
      )
      val stateWithLenPtr   = stateWithAlloc.withRegister(lenPtrReg + 1).emit(lenPtrLine)
      val lenStoreLine      = emitStore(s"$strLen", "i64", s"%$lenPtrReg", Some(lenTag))
      val stateWithLenStore = stateWithLenPtr.emit(lenStoreLine)

      // Store the data field (field 1, offset 8, type pointer)
      val dataPtrReg = stateWithLenStore.nextRegister
      val dataPtrLine = emitGetElementPtr(
        dataPtrReg,
        "%struct.String",
        "%struct.String*",
        s"%$allocReg",
        List(("i32", "0"), ("i32", "1"))
      )
      val stateWithDataPtr   = stateWithLenStore.withRegister(dataPtrReg + 1).emit(dataPtrLine)
      val dataStoreLine      = emitStore(s"%$ptrReg", "i8*", s"%$dataPtrReg", Some(dataTag))
      val stateWithDataStore = stateWithDataPtr.emit(dataStoreLine)

      // Load the String struct (no TBAA tag for aggregate load)
      val resultReg  = stateWithDataStore.nextRegister
      val loadLine   = emitLoad(resultReg, "%struct.String", s"%$allocReg", None)
      val finalState = stateWithDataStore.withRegister(resultReg + 1).emit(loadLine)

      CompileResult(resultReg, finalState, false, "String")
  }
