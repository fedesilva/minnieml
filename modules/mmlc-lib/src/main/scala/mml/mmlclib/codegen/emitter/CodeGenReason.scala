package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*

/** Failed emission prerequisites, unsupported representations, and retained nested failures. */
enum CodeGenReason derives CanEqual:
  case MissingCallableBinding
  case MissingCallableIdentity
  case MissingCallableSignature
  case ExpectedEmittedCallable
  case ClosureLayout(error: ClosureEnvironmentError)
  case CaptureHasDestruction
  case MissingRawEnvironmentDestructor
  case MissingDestructionField
  case MissingClosureLayout
  case MissingDestructionTargetType
  case MissingDestructionTarget
  case KnownCallableIdentityMismatch
  case LocalCallableIdentityMismatch
  case MissingLocalBinding(ref: Ref)
  case MissingGlobalNominalType(ref: Ref)
  case MissingGlobalType(ref: Ref)
  case OwnershipResultWithoutBinding
  case NativeExpression
  case UnsupportedTerm(other: Term)
  case InvalidLambdaType(other: Option[Type])
  case MissingClosureBindingIdentity(param: FnParam)
  case MissingPhysicalEnvironment
  case MissingRuntimeCapture
  case MissingEnvironmentDestructor
  case MissingCaptureCloneTarget
  case CaptureWitnessOutOfScope
  case MissingSelectionQualifier(ref: Ref)
  case UnknownSelectionField(ref: Ref)
  case MissingSelectedFieldType(ref: Ref)
  case NonStructSelection(ref: Ref)
  case MissingSelectionBaseType(ref: Ref)
  case InvalidExpressionStructure
  case MissingLocalCallableTarget
  case MissingProvenCallableTarget
  case MissingBindingAnnotation(bnd: Bnd)
  case MissingFunctionTemplate
  case MissingTemplateFunctionType
  case NonFunctionConstructor(other: Type)
  case MissingConstructorType(bnd: Bnd)
  case NonStructConstructorResult
  case ConstructorParameterCount(structDef: TypeStruct)
  case MissingConstructorParameter(param: FnParam)
  case UnboundLoopBorrowClosure
  case NestedLoopBorrowClosure
  case EscapingLoopBorrowClosure
  case NonLocalLoopBorrowClosure
  case ForwardedLoopBorrowClosure
  case CapturingDirectTailRecursion
  case MissingTailRecursiveConditionType
  case OwnershipWitnessWithoutBinding
  case UnknownNominalType(typeName: String)
  case MissingKnownCallableEntry
  case MissingPlannedLocalCallable
  case UnsupportedTargetAbi(targetTriple: String)
  case StructFieldErrors(errors: List[CodeGenError])
  case MissingFunctionType(bnd: Bnd)
  case MissingStringBindingType(bnd: Bnd)
  case MissingIntBindingType(bnd: Bnd)
  case MissingBoolBindingType(bnd: Bnd)
  case MissingBindingType(bnd: Bnd)
  case BareNativePrimitive(np: NativePrimitive)
  case BareNativePointer(np: NativePointer)
  case MissingNominalName(other: Type)
  case InvalidOwnershipScope
  case MissingOwnershipResult
  case MissingOwnershipResultType
  case ParameterRegisterCount
  case MissingParameterType(param: FnParam)
  case ParameterTypeCount
  case RecursiveLayout(typ: String)
  case MissingLayout(td: TypeDef)
  case UnresolvedLayout(ref: TypeRef)
  case UnsupportedLayout(typ: Type)
  case UnsupportedLlvmStorage(typ: String)
  case ImmediateLambdaArity
  case NativeOperatorArity(actual: Int)
  case MissingBinaryOperandType
  case MissingBinaryReturnType(fnRef: Ref)
  case MissingUnaryOperandType
  case MissingUnaryReturnType(fnRef: Ref)
  case MissingCallableResultType
  case MissingArgumentType
  case MissingStaticClosureResultType
  case IndirectCallArity(fnRef: Ref, expected: Int, actual: Int)
  case MissingIndirectReturnType(fnRef: Ref)
  case MissingIndirectResultType
  case MissingConditionType
  case MissingHoleSpan
  case MissingHoleNominalType(typeSpec: Type)
  case MissingHoleType
  case MissingStringLiteralType
  case MissingBinaryTemplate(opRef: Ref)
  case MissingUnaryTemplate(opRef: Ref)
  case NonNativeType(name: String)
  case UnresolvedType(name: String)
  case InlineNativeStruct
  case MissingLlvmTypeMapping(other: Type)
  case FieldIndexOutOfBounds(fieldIndex: Int, structName: String, fieldCount: Int)
  case NonStructType(name: String, other: Option[Type])
  case UnresolvedStructType(name: String)
  case ExpectedStructType(other: Type)
