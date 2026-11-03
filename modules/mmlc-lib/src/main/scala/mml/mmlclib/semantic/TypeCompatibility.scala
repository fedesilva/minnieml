package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.TypeQueries

/** Concrete type equality follows aliases while preserving nominal declaration identity. */
object TypeCompatibility:

  /** Function and tuple components use the same identity rule. Native primitives also support
    * programmatically constructed signatures whose leaves have no nominal declaration.
    */
  def same(left: Type, right: Type, index: ResolvablesIndex): Boolean =
    (ProtocolRegistry.typeKey(left, index), ProtocolRegistry.typeKey(right, index)) match
      case (Right(a), Right(b)) => a == b
      case _ =>
        (TypeQueries.canonical(left, index), TypeQueries.canonical(right, index)) match
          case (Some(a: NativePrimitive), Some(b: NativePrimitive)) => a.llvmType == b.llvmType
          case (Some(a: TypeFn), Some(b: TypeFn)) if a.paramTypes.size == b.paramTypes.size =>
            sameChildren(a, b, index)
          case (Some(a: TypeTuple), Some(b: TypeTuple)) if a.elements.size == b.elements.size =>
            sameChildren(a, b, index)
          case _ => false

  private def sameChildren(left: Type, right: Type, index: ResolvablesIndex): Boolean =
    TypeTraversal
      .children(left)
      .zip(TypeTraversal.children(right))
      .forall { case (a, b) => same(a, b, index) }
