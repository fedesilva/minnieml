package mml.mmlclib.semantic

import mml.mmlclib.ast.Lambda

/** Callable flow and its consumers share immutable lambda instances from one module snapshot. */
final class CallableIdentity(val lambda: Lambda):

  override def hashCode(): Int = System.identityHashCode(lambda)

  override def equals(other: Any): Boolean = other match
    case key: CallableIdentity => lambda eq key.lambda
    case _ => false
