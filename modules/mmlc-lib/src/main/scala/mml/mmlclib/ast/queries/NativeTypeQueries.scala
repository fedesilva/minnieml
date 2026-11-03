package mml.mmlclib.ast.queries

import cats.syntax.option.*
import mml.mmlclib.ast.*

/** Native representations recorded in type nodes and nominal definitions. */
object NativeTypeQueries:

  /** Returns a canonical native type or the native type directly on its nominal definition. */
  def resolveNativeType(tpe: Type, index: ResolvablesIndex): Option[NativeType] =
    TypeQueries.canonical(tpe, index).flatMap {
      case native: NativeType => native.some

      case other =>
        TypeQueries.declaration(other, index).flatMap {
          case definition: TypeDef =>
            definition.typeSpec.collect { case native: NativeType => native }
          case _ => none
        }
    }

  /** Recognizes typed native pointers and the opaque ptr primitive. */
  def isPointerNativeType(nativeType: NativeType): Boolean = nativeType match
    case _: NativePointer => true
    case NativePrimitive(_, "ptr", _, _) => true
    case _ => false

  /** True when the native representation is a typed pointer or the opaque ptr primitive. */
  def isPointerLike(tpe: Type, index: ResolvablesIndex): Boolean =
    resolveNativeType(tpe, index).exists(isPointerNativeType)
