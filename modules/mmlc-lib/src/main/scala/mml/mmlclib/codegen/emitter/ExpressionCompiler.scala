package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.alias.AliasScopeEmitter
import mml.mmlclib.codegen.emitter.expression.*
import mml.mmlclib.codegen.emitter.tbaa.TbaaEmitter.getTbaaTag

/** Handles code generation for expressions, terms, and operators. */

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
  functionScope: Map[String, (Int, String)] = Map.empty
): Either[CodeGenError, CompileResult] = {
  term match {
    case LiteralInt(_, value) =>
      CompileResult(value, state, true, "Int").asRight

    case LiteralUnit(_) =>
      // Unit is a zero-sized type, just return a dummy result
      CompileResult(0, state, true, "Unit").asRight

    case LiteralBool(_, value) =>
      val literalValue = if value then 1 else 0
      CompileResult(literalValue, state, true, "Bool").asRight

    case hole: Hole =>
      compileHole(hole, state)

    case lit: LiteralString =>
      compileLiteralString(lit, state)

    case ref: Ref if ref.qualifier.isDefined =>
      compileSelectionRef(ref, state, functionScope)

    case ref: Ref => {
      // Check if reference exists in the function's local scope
      functionScope.get(ref.name) match {
        case Some((paramReg, typeName)) =>
          // Reference to a function parameter
          CompileResult(paramReg, state, false, typeName).asRight
        case None =>
          // Global reference - get actual type from typeSpec
          ref.typeSpec match {
            case Some(typeSpec) =>
              getLlvmType(typeSpec, state) match {
                case Right(llvmType) =>
                  val reg                      = state.nextRegister
                  val globalName               = getResolvedName(ref, state)
                  val (stateWithTbaa, tbaaTag) = getTbaaTag(typeSpec, state)
                  val (stateWithAlias, aliasTag, noaliasTag) =
                    AliasScopeEmitter.getAliasScopeTags(typeSpec, stateWithTbaa)
                  val line = emitLoad(
                    reg,
                    llvmType,
                    s"@$globalName",
                    tbaaTag,
                    aliasTag,
                    noaliasTag
                  )

                  getMmlTypeName(typeSpec) match {
                    case Some(typeName) =>
                      CompileResult(
                        reg,
                        stateWithAlias.withRegister(reg + 1).emit(line),
                        false,
                        typeName
                      ).asRight
                    case None =>
                      Left(
                        CodeGenError(
                          s"Could not determine MML type name for global reference '${ref.name}'",
                          Some(ref)
                        )
                      )
                  }
                case Left(err) =>
                  Left(err)
              }
            case None =>
              Left(
                CodeGenError(
                  s"Missing type information for global reference '${ref.name}' - TypeChecker should have provided this",
                  Some(ref)
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

    case Cond(_, condExpr, ifTrue, ifFalse, _, _) =>
      compileCond(condExpr, ifTrue, ifFalse, state, functionScope, compileExpr)

    case impl @ NativeImpl(_, _, _, _) => {
      // Native implementation should be handled at function declaration level (compileBndLambda).
      // If reached here, it implies it's being used as an expression, which is invalid.
      Left(
        CodeGenError(
          "NativeImpl encountered in expression context - this is a malformed AST or compiler bug",
          Some(impl)
        )
      )
    }

    case other =>
      CodeGenError(s"Unsupported term: ${other.getClass.getSimpleName}", Some(other)).asLeft
  }
}

private def compileSelectionRef(
  ref:           Ref,
  state:         CodeGenState,
  functionScope: Map[String, (Int, String)]
): Either[CodeGenError, CompileResult] =
  ref.qualifier match
    case None =>
      Left(CodeGenError(s"Selection ref missing qualifier for '${ref.name}'", Some(ref)))
    case Some(qualifier) =>
      compileTerm(qualifier, state, functionScope).flatMap { qualifierRes =>
        val baseTypeSpec = qualifier.typeSpec
        baseTypeSpec match
          case Some(baseType) =>
            resolveTypeStruct(baseType, state.resolvables) match
              case Some(structDef) =>
                val fieldIndex = structDef.fields.indexWhere(_.name == ref.name)
                if fieldIndex < 0 then
                  Left(
                    CodeGenError(
                      s"Unknown struct field '${ref.name}' for selection",
                      Some(ref)
                    )
                  )
                else
                  val structTypeE = getLlvmType(baseType, qualifierRes.state)
                  val fieldType   = structDef.fields(fieldIndex).typeSpec
                  structTypeE.flatMap { structLlvmType =>
                    val baseValue =
                      if qualifierRes.isLiteral then qualifierRes.register.toString
                      else s"%${qualifierRes.register}"
                    val fieldReg = qualifierRes.state.nextRegister
                    val line =
                      emitExtractValue(fieldReg, structLlvmType, baseValue, fieldIndex)
                    getMmlTypeName(fieldType) match
                      case Some(typeName) =>
                        Right(
                          CompileResult(
                            fieldReg,
                            qualifierRes.state.withRegister(fieldReg + 1).emit(line),
                            false,
                            typeName,
                            qualifierRes.exitBlock
                          )
                        )
                      case None =>
                        Left(
                          CodeGenError(
                            s"Could not determine type name for selected field '${ref.name}'",
                            Some(ref)
                          )
                        )
                  }
              case None =>
                Left(
                  CodeGenError(
                    s"Selection base is not a struct for field '${ref.name}'",
                    Some(ref)
                  )
                )
          case None =>
            Left(
              CodeGenError(
                s"Missing type information for selection base '${ref.name}'",
                Some(ref)
              )
            )
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
  functionScope: Map[String, (Int, String)] = Map.empty
): Either[CodeGenError, CompileResult] = {
  expr.terms match {
    case List(term) =>
      compileTerm(term, state, functionScope)
    case List(left, op: Ref, right) if op.resolvedId.flatMap(state.resolvables.lookup).exists {
          case bnd: Bnd => bnd.meta.exists(_.arity == CallableArity.Binary)
          case _ => false
        } =>
      compileBinaryOp(op, left, right, state, functionScope)
    case List(op: Ref, arg) if op.resolvedId.flatMap(state.resolvables.lookup).exists {
          case bnd: Bnd => bnd.meta.exists(_.arity == CallableArity.Unary)
          case _ => false
        } =>
      compileUnaryOp(op, arg, state, functionScope)
    case _ =>
      CodeGenError(s"Invalid expression structure", Some(expr)).asLeft
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
  functionScope: Map[String, (Int, String)] = Map.empty
): Either[CodeGenError, CompileResult] =
  for
    leftCompileResult <- compileTerm(left, state, functionScope)
    rightCompileResult <- compileTerm(right, leftCompileResult.state, functionScope)
    result <- applyBinaryOp(opRef, left, leftCompileResult, rightCompileResult)
  yield result

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
  functionScope: Map[String, (Int, String)] = Map.empty
): Either[CodeGenError, CompileResult] =
  for
    argCompileResult <- compileTerm(arg, state, functionScope)
    result <- applyUnaryOp(opRef, arg, argCompileResult)
  yield result

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
  functionScope: Map[String, (Int, String)] = Map.empty
): Either[CodeGenError, CompileResult] =
  val (fnOrLambda, allArgs) = collectArgsAndFunction(app)

  fnOrLambda match
    case lambda: Lambda =>
      compileLambdaApp(lambda, allArgs, state, functionScope, compileExpr)

    case ref: Ref =>
      getNativeOpTemplate(ref.resolvedId.flatMap(state.resolvables.lookup)) match
        case Some(tpl) =>
          compileNativeOp(ref, tpl, allArgs, app, state, functionScope, compileExpr)
        case None if isNullaryWithUnitArgs(ref, allArgs, state.resolvables) =>
          compileNullaryCall(ref, app, state)
        case None =>
          compileRegularCall(ref, allArgs, app, state, functionScope, compileExpr)
