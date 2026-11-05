package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Failure reasons paired with the offending expression by SemanticError. */
sealed trait ExpressionError derives CanEqual

enum ExpressionProblem extends ExpressionError:
  case NotCloneable
  case IntegerOutOfRange
  case EmptyExpression
  case InvalidStructure
  case InvalidGroup
  case OutsideExpression
  case GroupWithoutOperator
  case TermWithoutOperator
  case NonCallableApplication
  case MissingBindingIdentity(parameter: FnParam)
  case ClosureLayout(error: ClosureEnvironmentError)

enum OwnershipError extends ExpressionError:
  case BorrowedSink
  case GlobalTransfer
  case CallOnceRequiresConsuming
  case ResultOwnershipMismatch
  case ParameterOwnershipMismatch
  case FieldCallRequiresOwnership
  case CallRequiresOwnership
  case CaptureAlternatives
  case InvalidExpressionShape
  case StoredArgumentRequiresOwnership
  case CloneReturnsBorrowed

enum DestructionError extends ExpressionError:
  case MissingStructFieldDestructor
  case NonUnitResult
  case MissingOperand
  case OperandResultTypeMismatch
  case UnresolvedOperand
  case OperandReferenceTypeMismatch
  case NonFunctionClosure
  case NonPointerDispatch
  case NonPointerTransfer
  case NonPointerDestruction
  case DuplicateFieldCleanup
  case LayoutIdentityMismatch
  case FieldIdentityMismatch
  case FieldOrderMismatch
  case MissingDestructorPointer
  case InvalidBorrowedFields
  case DestructorSlotCleanup
  case NonFunctionField
  case FieldDestructorMismatch
  case MissingFieldDestructor(field: Field)
  case IncompatibleSignature(targetId: String)
  case ParameterSignatureMismatch(targetId: String)
  case MissingFunctionBody(targetId: String)
  case NonFunctionTarget(targetId: String)
  case MissingTarget(targetId: String)
  case MissingRegisteredFieldDestructor(name: String, field: Field)
  case MissingFieldCleanup(field: Field)
  case MissingField(fieldId: String)
  case MissingLayout(layoutId: String)
  case BorrowedEnvironment(layoutId: String)
  case MissingMoveMetadata(layoutId: String)
