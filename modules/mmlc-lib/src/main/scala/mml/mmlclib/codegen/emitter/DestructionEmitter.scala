package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.{getResolvedName, isNativeBinding}

/** Lower explicit destruction operands once, preserving field order and target ABI. */
def compileDestruction(
  node:  Destruction,
  state: CodeGenState,
  scope: Map[String, ScopeEntry]
): Either[CodeGenError, CompileResult] =
  compileExpr(node.operand, state, scope).flatMap { operand =>
    node match
      case d: DestroyClosure =>
        destroyClosureValue(operand.operandStr, d.targetId, operand.state, d)
          .map(st => CompileResult(0, st, false, "Unit", exitBlock = operand.exitBlock))
      case _: DispatchClosureDestructor =>
        val st        = operand.state
        val ptr       = operand.operandStr
        val cmp       = st.nextRegister
        val dtor      = cmp + 1
        val freeLabel = s"closure_free_${cmp}_dtor"
        val endLabel  = s"closure_free_${cmp}_end"
        val result = st
          .withRegister(dtor + 1)
          .emit(s"  %$cmp = icmp eq ptr $ptr, null")
          .emit(s"  br i1 %$cmp, label %$endLabel, label %$freeLabel")
          .emit(s"$freeLabel:")
          .emit(s"  %$dtor = load ptr, ptr $ptr")
          .emit(s"  call void %$dtor(ptr $ptr)")
          .emit(s"  br label %$endLabel")
          .emit(s"$endLabel:")
        CompileResult(0, result, false, "Unit", exitBlock = endLabel.some).asRight
      case d: DestroyClosureEnvironment =>
        operand.state.resolvables.lookupType(d.layoutId) match
          case Some(layout: TypeStruct) =>
            d.fields
              .foldLeft(operand.state.asRight[CodeGenError]) { (result, cleanup) =>
                result.flatMap { st =>
                  layout.fields.zipWithIndex.find(_._1.id.contains(cleanup.fieldId)) match
                    case Some((field, index)) =>
                      getLlvmType(field.typeSpec, st).flatMap { llvmType =>
                        val gep   = st.nextRegister
                        val value = gep + 1
                        val loaded = st
                          .withRegister(value + 1)
                          .emit(
                            emitGetElementPtr(
                              gep,
                              s"%struct.${layout.name}",
                              "ptr",
                              operand.operandStr,
                              List(("i32", "0"), ("i32", index.toString))
                            )
                          )
                          .emit(s"  %$value = load $llvmType, ptr %$gep")
                        cleanup match
                          case _: FieldCleanup.Closure =>
                            destroyClosureValue(s"%$value", cleanup.targetId, loaded, d)
                          case _: FieldCleanup.Value =>
                            callDestructor(cleanup.targetId, s"%$value", llvmType, loaded, d)
                      }
                    case None => CodeGenError("Missing destruction field", d.some).asLeft
                }
              }
              .flatMap { st =>
                callDestructor("stdlib::bnd::mml_free_raw", operand.operandStr, "ptr", st, d)
                  .map(done => CompileResult(0, done, false, "Unit", exitBlock = operand.exitBlock))
              }
          case _ => CodeGenError("Missing closure environment layout", d.some).asLeft
  }

private def destroyClosureValue(
  value:    String,
  targetId: String,
  state:    CodeGenState,
  node:     Destruction
): Either[CodeGenError, CodeGenState] =
  val reg = state.nextRegister
  val extracted = state
    .withRegister(reg + 1)
    .emit(emitExtractValue(reg, "{ ptr, ptr }", value, 1))
  callDestructor(targetId, s"%$reg", "ptr", extracted, node)

private def callDestructor(
  targetId: String,
  value:    String,
  llvmType: String,
  state:    CodeGenState,
  node:     Destruction
): Either[CodeGenError, CodeGenState] =
  state.resolvables.lookup(targetId) match
    case Some(b: Bnd) =>
      val ref     = Ref(node.source, b.name, resolvedId = targetId.some, typeSpec = b.typeSpec)
      val name    = getResolvedName(ref, state)
      val native  = isNativeBinding(b)
      val rawArgs = List((value, llvmType))
      val (args, lowered) =
        if native then state.abi.lowerArgs(rawArgs, state)
        else (rawArgs, state)
      val withDeclaration =
        if native then lowered.withFunctionDeclaration(name, "void", args.map(_._2))
        else lowered
      withDeclaration.emit(emitCall(None, None, name, args.map((op, tpe) => (tpe, op)))).asRight
    case _ => CodeGenError("Missing destruction target", node.some).asLeft
