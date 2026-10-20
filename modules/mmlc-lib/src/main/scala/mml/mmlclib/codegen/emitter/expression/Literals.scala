package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  emitCall,
  emitGetElementPtr,
  emitLoad,
  getLlvmType,
  getNominalTypeName
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
  hole.spanOpt match
    case None =>
      Left(CodeGenError("Hole expression is missing source span", Some(hole)))
    case Some(span) =>
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
              val (stateWithAlias, aliasTag, noaliasTag) =
                AliasScopeEmitter.getAliasScopeTags(typeSpec, stateWithAlloca)
              val loadLine =
                emitLoad(
                  loadReg,
                  llvmType,
                  s"%$allocaReg",
                  aliasScope = aliasTag,
                  noalias    = noaliasTag
                )
              val stateWithLoad = stateWithAlias
                .withRegister(loadReg + 1)
                .emit(loadLine)

              getNominalTypeName(typeSpec) match
                case Right(typeName) =>
                  CompileResult(loadReg, stateWithLoad, false, typeName).asRight
                case Left(_) =>
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
  lit.typeSpec.toRight(CodeGenError("LiteralString missing type specification", lit.some)).map {
    _ =>
      val (withConstant, constName) = state.addStringConstant(lit.value)
      val strLen                    = lit.value.length
      val ptrReg                    = withConstant.nextRegister
      val lengthReg                 = ptrReg + 1
      val resultReg                 = lengthReg + 1
      val gep = emitGetElementPtr(
        ptrReg,
        s"[$strLen x i8]",
        s"[$strLen x i8]*",
        s"@$constName",
        List(("i64", "0"), ("i64", "0"))
      )
      val result = withConstant
        .withRegister(resultReg + 1)
        .emitAll(
          List(
            gep,
            s"  %$lengthReg = insertvalue %struct.String undef, i32 $strLen, 0",
            s"  %$resultReg = insertvalue %struct.String %$lengthReg, ptr %$ptrReg, 1"
          )
        )
      CompileResult(resultReg, result, false, "String")
  }
