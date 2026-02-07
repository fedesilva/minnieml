package mml.mmlclib.codegen.emitter.expression

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{
  CodeGenError,
  CodeGenState,
  CompileResult,
  getLlvmType,
  getMmlTypeName
}

// ============================================================================
// Template Extraction Helpers
// ============================================================================

/** Extracts native operator template from a Ref if it resolves to a native operator. */
def extractNativeOpTemplate(
  ref:         Ref,
  resolvables: ResolvablesIndex
): Option[(Bnd, String, CallableArity)] =
  ref.resolvedId.flatMap(resolvables.lookup) match
    case Some(bnd: Bnd) =>
      bnd.meta match
        case Some(m) if m.arity == CallableArity.Binary || m.arity == CallableArity.Unary =>
          extractTemplate(bnd).map(tpl => (bnd, tpl, m.arity))
        case _ => None
    case _ => None

/** Extracts native function template from a Ref (for LLVM intrinsics). */
def extractFunctionTemplate(ref: Ref, resolvables: ResolvablesIndex): Option[(Bnd, String)] =
  ref.resolvedId.flatMap(resolvables.lookup) match
    case Some(bnd: Bnd) if bnd.meta.forall(_.origin != BindingOrigin.Operator) =>
      extractTemplate(bnd).map((bnd, _))
    case _ => None

// ============================================================================
// Template Helpers
// ============================================================================

/** Extracts NativeImpl template from a binding's lambda body. */
private def extractTemplate(bnd: Bnd): Option[String] =
  bnd.value.terms.collectFirst { case lambda: Lambda => lambda.body }.flatMap { body =>
    body.terms.collectFirst { case NativeImpl(_, _, _, Some(tpl), _) => tpl }
  }

/** Gets native operator template from a resolved binding (function-style API for compileApp). */
def getNativeOpTemplate(resolvedAs: Option[Resolvable]): Option[String] =
  resolvedAs match
    case Some(bnd: Bnd)
        if bnd.meta
          .exists(m => m.arity == CallableArity.Binary || m.arity == CallableArity.Unary) =>
      extractTemplate(bnd)
    case _ => None

/** Gets function template from a resolved binding (for LLVM intrinsics). */
def getFunctionTemplate(resolvedAs: Option[Resolvable]): Option[String] =
  resolvedAs match
    case Some(bnd: Bnd) => extractTemplate(bnd)
    case _ => None

/** Substitutes template placeholders with actual values.
  *
  * Placeholders:
  *   - %type: LLVM type of first operand
  *   - %operand: single operand (unary)
  *   - %operand1, %operand2, ...: multiple operands (binary+)
  */
def substituteTemplate(tpl: String, llvmType: String, operands: List[String]): String =
  val withType = tpl.replace("%type", llvmType)
  operands match
    case List(op) => withType.replace("%operand", op)
    case ops =>
      ops.zipWithIndex.foldLeft(withType) { case (t, (op, i)) =>
        t.replace(s"%operand${i + 1}", op)
      }

// ============================================================================
// Name Resolution Helpers
// ============================================================================

/** Gets the resolved name for a Ref - mangles non-native function/value names. */
def getResolvedName(ref: Ref, state: CodeGenState): String =
  ref.resolvedId.flatMap(state.resolvables.lookup) match
    case Some(bnd: Bnd) =>
      if isNativeBinding(bnd) then bnd.name
      else state.mangleName(bnd.name)
    case _ => ref.name

/** Checks if a binding is a native function (has NativeImpl body). */
def isNativeBinding(bnd: Bnd): Boolean =
  bnd.value.terms match
    case List(lambda: Lambda) =>
      lambda.body.terms match
        case List(_: NativeImpl) => true
        case _ => false
    case _ => false

// ============================================================================
// Operator Application
// ============================================================================

/** Gets MML type name for operator return type. */
def getMmlTypeForOp(opRef: Ref): Option[String] =
  opRef.typeSpec.flatMap {
    case TypeFn(_, _, returnType) => getMmlTypeName(returnType)
    case _ => None
  }

/** Applies a binary operation using its native template. */
def applyBinaryOp(
  opRef:    Ref,
  left:     Term,
  leftRes:  CompileResult,
  rightRes: CompileResult
): Either[CodeGenError, CompileResult] =
  extractNativeOpTemplate(opRef, rightRes.state.resolvables) match
    case Some((_, tpl, _)) =>
      val resultReg = rightRes.state.nextRegister

      val leftOp  = leftRes.operandStr
      val rightOp = rightRes.operandStr

      left.typeSpec match
        case Some(typeSpec) =>
          getLlvmType(typeSpec, rightRes.state) match
            case Right(llvmType) =>
              val instruction = substituteTemplate(tpl, llvmType, List(leftOp, rightOp))
              val line        = s"  %$resultReg = $instruction"
              getMmlTypeForOp(opRef) match
                case Some(typeName) =>
                  CompileResult(
                    resultReg,
                    rightRes.state.withRegister(resultReg + 1).emit(line),
                    false,
                    typeName
                  ).asRight
                case None =>
                  Left(
                    CodeGenError(
                      s"Could not determine return type for binary operator '${opRef.name}'",
                      Some(opRef)
                    )
                  )
            case Left(err) => Left(err)
        case None =>
          Left(CodeGenError(s"Missing type information for binary operator operand", Some(left)))
    case None =>
      Left(
        CodeGenError(s"No native template found for binary operator '${opRef.name}'", Some(opRef))
      )

/** Applies a unary operation using its native template. */
def applyUnaryOp(
  opRef:  Ref,
  arg:    Term,
  argRes: CompileResult
): Either[CodeGenError, CompileResult] =
  extractNativeOpTemplate(opRef, argRes.state.resolvables) match
    case Some((_, tpl, _)) =>
      val resultReg = argRes.state.nextRegister
      val argOp     = argRes.operandStr

      arg.typeSpec match
        case Some(typeSpec) =>
          getLlvmType(typeSpec, argRes.state) match
            case Right(llvmType) =>
              val instruction = substituteTemplate(tpl, llvmType, List(argOp))
              val line        = s"  %$resultReg = $instruction"
              getMmlTypeForOp(opRef) match
                case Some(typeName) =>
                  CompileResult(
                    resultReg,
                    argRes.state.withRegister(resultReg + 1).emit(line),
                    false,
                    typeName
                  ).asRight
                case None =>
                  Left(
                    CodeGenError(
                      s"Could not determine return type for unary operator '${opRef.name}'",
                      Some(opRef)
                    )
                  )
            case Left(err) => Left(err)
        case None =>
          Left(CodeGenError(s"Missing type information for unary operator operand", Some(arg)))
    case None =>
      Left(
        CodeGenError(s"No native template found for unary operator '${opRef.name}'", Some(opRef))
      )

// ============================================================================
// String Helpers
// ============================================================================

/** Helper for string escaping - converts to LLVM IR format using hex escapes. */
def escapeString(str: String): String =
  str.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\0A"
    case '\r' => "\\0D"
    case '\t' => "\\09"
    case c if c < 0x20 || c > 0x7e => f"\\${c.toInt}%02X"
    case c => c.toString
  }
