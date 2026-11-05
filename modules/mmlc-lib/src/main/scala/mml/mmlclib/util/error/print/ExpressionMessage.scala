package mml.mmlclib.util.error.print

import mml.mmlclib.semantic.*

/** Shared wording for expression, ownership, and generated destruction requirements. */
def expressionMessage(error: ExpressionError): String = error match
  case ExpressionProblem.NotCloneable => "This type cannot be cloned"
  case ExpressionProblem.IntegerOutOfRange => "Integer literal is outside the Int32 range"
  case ExpressionProblem.EmptyExpression => "Expected an expression, got empty terms"
  case ExpressionProblem.InvalidStructure => "Invalid expression structure"
  case ExpressionProblem.InvalidGroup => "Group must resolve to a single expression"
  case ExpressionProblem.OutsideExpression => "Unexpected terms outside expression context"
  case ExpressionProblem.GroupWithoutOperator =>
    "Unexpected group after expression - expected an operator"
  case ExpressionProblem.TermWithoutOperator =>
    "Unexpected term after expression - expected an operator"
  case ExpressionProblem.NonCallableApplication => "These terms cannot be applied to a non-function"
  case OwnershipError.BorrowedSink =>
    "An ownership sink cannot receive a value with borrowed ownership"
  case OwnershipError.GlobalTransfer =>
    "A global value cannot transfer ownership; use an explicit ^ copy"
  case OwnershipError.CallOnceRequiresConsuming =>
    "A call-once function requires a consuming parameter"
  case OwnershipError.ResultOwnershipMismatch =>
    "Callable alternatives must agree on result ownership"
  case OwnershipError.ParameterOwnershipMismatch =>
    "Callable alternatives must agree on consuming parameters"
  case OwnershipError.FieldCallRequiresOwnership =>
    "Calling this field consumes its payload and requires an owned aggregate"
  case OwnershipError.CallRequiresOwnership =>
    "Calling this function consumes its environment and requires ownership"
  case OwnershipError.CaptureAlternatives =>
    "This capture cannot establish ownership for its non-owning alternatives"
  case OwnershipError.InvalidExpressionShape =>
    "Ownership analysis requires a single-term expression"
  case DestructionError.MissingStructFieldDestructor =>
    "Missing registered destructor for struct field"
  case DestructionError.NonUnitResult => "Destruction result must be Unit"
  case DestructionError.MissingOperand => "Destruction requires an operand"
  case DestructionError.OperandResultTypeMismatch =>
    "Destruction operand expression type disagrees with its result"
  case DestructionError.UnresolvedOperand => "Unresolved destruction operand reference"
  case DestructionError.OperandReferenceTypeMismatch =>
    "Destruction operand reference type mismatch"
  case DestructionError.NonFunctionClosure => "DestroyClosure requires a function value"
  case DestructionError.NonPointerDispatch => "Dispatch requires RawPtr"
  case DestructionError.NonPointerTransfer => "Environment transfer requires RawPtr"
  case DestructionError.NonPointerDestruction => "Environment destruction requires RawPtr"
  case DestructionError.DuplicateFieldCleanup => "Duplicate field cleanup"
  case DestructionError.LayoutIdentityMismatch => "Environment layout identity mismatch"
  case DestructionError.FieldIdentityMismatch => "Environment fields require unique identities"
  case DestructionError.FieldOrderMismatch => "Field cleanup must follow layout order"
  case DestructionError.MissingDestructorPointer => "Closure layout requires a destructor pointer"
  case DestructionError.InvalidBorrowedFields =>
    "Borrowed environment fields must exist and have no cleanup"
  case DestructionError.DestructorSlotCleanup => "Cannot destroy destructor slot"
  case DestructionError.NonFunctionField => "Closure field cleanup requires a function value"
  case DestructionError.FieldDestructorMismatch =>
    "Field cleanup must use its registered destructor"
  case ExpressionProblem.MissingBindingIdentity(parameter) =>
    s"Missing binding identity for '${parameter.name}'"
  case ExpressionProblem.ClosureLayout(error) => closureEnvironmentMessage(error)
  case OwnershipError.StoredArgumentRequiresOwnership =>
    "A stored consuming argument requires ownership on every path; implicit cloning is not allowed"
  case OwnershipError.CloneReturnsBorrowed =>
    "Clone must return an independently owned value; returning its borrowed input is invalid"
  case DestructionError.MissingFieldDestructor(field) =>
    s"Missing destructor for field '${field.name}'"
  case DestructionError.IncompatibleSignature(id) => s"Incompatible destructor signature: $id"
  case DestructionError.ParameterSignatureMismatch(id) =>
    s"Destructor parameter disagrees with its signature: $id"
  case DestructionError.MissingFunctionBody(id) => s"Destructor target has no function body: $id"
  case DestructionError.NonFunctionTarget(id) => s"Destructor target is not a function: $id"
  case DestructionError.MissingTarget(id) => s"Missing destructor target: $id"
  case DestructionError.MissingRegisteredFieldDestructor(name, field) =>
    s"Missing registered destructor '$name' for field '${field.name}'"
  case DestructionError.MissingFieldCleanup(field) =>
    s"Missing required field cleanup: ${field.name}"
  case DestructionError.MissingField(id) => s"Missing destruction field: $id"
  case DestructionError.MissingLayout(id) => s"Missing closure environment layout: $id"
  case DestructionError.BorrowedEnvironment(id) =>
    s"Destructor refers to a borrowed closure environment: $id"
  case DestructionError.MissingMoveMetadata(id) => s"Missing move-lambda metadata for layout: $id"
