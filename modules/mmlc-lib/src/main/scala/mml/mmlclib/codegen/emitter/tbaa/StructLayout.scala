package mml.mmlclib.codegen.emitter.tbaa

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.*

/** Type-based access to the shared target layout for metadata clients. */
object StructLayout:

  def sizeOf(typeSpec: Type, resolvables: ResolvablesIndex): Either[CodeGenError, Int] =
    val state = CodeGenState(resolvables = resolvables)
    state.layout.fromType(typeSpec, state).map(_.size)

  def alignOf(typeSpec: Type, resolvables: ResolvablesIndex): Either[CodeGenError, Int] =
    val state = CodeGenState(resolvables = resolvables)
    state.layout.fromType(typeSpec, state).map(_.alignment)
