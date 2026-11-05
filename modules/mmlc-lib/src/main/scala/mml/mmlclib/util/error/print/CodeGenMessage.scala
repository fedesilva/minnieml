package mml.mmlclib.util.error.print

import mml.mmlclib.codegen.emitter.CodeGenReason

/** Shared wording for code-generation failures. */
def codeGenMessage(reason: CodeGenReason): String = reason match
  case CodeGenReason.MissingCallableBinding =>
    "Missing callable binding"
  case CodeGenReason.MissingCallableIdentity =>
    "Missing callable binding identity"
  case CodeGenReason.MissingCallableSignature =>
    "Missing callable signature"
  case CodeGenReason.ExpectedEmittedCallable =>
    "Expected an emitted callable"
  case CodeGenReason.ClosureLayout(error) =>
    closureEnvironmentMessage(error)
  case CodeGenReason.CaptureHasDestruction =>
    "Cannot omit a capture with payload destruction"
  case CodeGenReason.MissingRawEnvironmentDestructor =>
    "Missing raw environment destructor"
  case CodeGenReason.MissingDestructionField =>
    "Missing destruction field"
  case CodeGenReason.MissingClosureLayout =>
    "Missing closure environment layout"
  case CodeGenReason.MissingDestructionTargetType =>
    "Missing destruction target type"
  case CodeGenReason.MissingDestructionTarget =>
    "Missing destruction target"
  case CodeGenReason.KnownCallableIdentityMismatch =>
    "Known callable reference identity mismatch"
  case CodeGenReason.LocalCallableIdentityMismatch =>
    "Local callable reference identity mismatch"
  case CodeGenReason.MissingLocalBinding(ref) =>
    s"Missing local binding or unresolved reference '${ref.name}'"
  case CodeGenReason.MissingGlobalNominalType(ref) =>
    s"Could not determine MML type name for global reference '${ref.name}'"
  case CodeGenReason.MissingGlobalType(ref) =>
    s"Missing type information for global reference '${ref.name}' - TypeChecker should have provided this"
  case CodeGenReason.OwnershipResultWithoutBinding =>
    "Ownership result requires an enclosing ownership binding"
  case CodeGenReason.NativeExpression =>
    "NativeImpl encountered in expression context - this is a malformed AST or compiler bug"
  case CodeGenReason.UnsupportedTerm(other) =>
    s"Unsupported term: ${other.getClass.getSimpleName}"
  case CodeGenReason.InvalidLambdaType(other) =>
    s"Lambda missing TypeFn typeSpec, got: $other"
  case CodeGenReason.MissingClosureBindingIdentity(param) =>
    s"Missing semantic ID for closure binding '${param.name}'"
  case CodeGenReason.MissingPhysicalEnvironment =>
    "Missing physical closure environment plan"
  case CodeGenReason.MissingRuntimeCapture =>
    "Missing runtime capture field"
  case CodeGenReason.MissingEnvironmentDestructor =>
    "Missing closure environment destructor"
  case CodeGenReason.MissingCaptureCloneTarget =>
    "Missing capture clone target"
  case CodeGenReason.CaptureWitnessOutOfScope =>
    "Conditional capture witness is outside its creation scope"
  case CodeGenReason.MissingSelectionQualifier(ref) =>
    s"Selection ref missing qualifier for '${ref.name}'"
  case CodeGenReason.UnknownSelectionField(ref) =>
    s"Unknown struct field '${ref.name}' for selection"
  case CodeGenReason.MissingSelectedFieldType(ref) =>
    s"Could not determine type name for selected field '${ref.name}'"
  case CodeGenReason.NonStructSelection(ref) =>
    s"Selection base is not a struct for field '${ref.name}'"
  case CodeGenReason.MissingSelectionBaseType(ref) =>
    s"Missing type information for selection base '${ref.name}'"
  case CodeGenReason.InvalidExpressionStructure =>
    s"Invalid expression structure"
  case CodeGenReason.MissingLocalCallableTarget =>
    "Missing local callable target"
  case CodeGenReason.MissingProvenCallableTarget =>
    "Missing proven callable target"
  case CodeGenReason.MissingBindingAnnotation(bnd) =>
    s"Type annotation missing for binding '${bnd.name}'"
  case CodeGenReason.MissingFunctionTemplate =>
    "Missing function template"
  case CodeGenReason.MissingTemplateFunctionType =>
    "Missing template function type"
  case CodeGenReason.NonFunctionConstructor(other) =>
    s"Expected function type for constructor, got ${other.getClass.getSimpleName}"
  case CodeGenReason.MissingConstructorType(bnd) =>
    s"Missing type specification for constructor '${bnd.name}'"
  case CodeGenReason.NonStructConstructorResult =>
    s"Constructor return type is not a struct"
  case CodeGenReason.ConstructorParameterCount(structDef) =>
    s"Constructor parameter count does not match struct fields for '${structDef.name}'"
  case CodeGenReason.MissingConstructorParameter(param) =>
    s"Missing constructor parameter '${param.name}' in scope"
  case CodeGenReason.UnboundLoopBorrowClosure =>
    "Borrow closure on a loopified path must be bound to a local name before use"
  case CodeGenReason.NestedLoopBorrowClosure =>
    "Borrow closure on a loopified path cannot capture another loop-local borrow closure"
  case CodeGenReason.EscapingLoopBorrowClosure =>
    "Borrow closure on a loopified path may survive across iterations; use ~{...} or keep it iteration-local"
  case CodeGenReason.NonLocalLoopBorrowClosure =>
    "Borrow closure on a loopified path must stay iteration-local; add ~{...} or restructure"
  case CodeGenReason.ForwardedLoopBorrowClosure =>
    "Borrow closure on a loopified path must be called directly, not forwarded as a value"
  case CodeGenReason.CapturingDirectTailRecursion =>
    "Plain direct tail-recursive functions cannot load captures from a closure env"
  case CodeGenReason.MissingTailRecursiveConditionType =>
    "Missing type information for tail-recursive condition"
  case CodeGenReason.OwnershipWitnessWithoutBinding =>
    "Ownership witness requires a value binding"
  case CodeGenReason.UnknownNominalType(typeName) =>
    s"Unknown MML type name '$typeName'"
  case CodeGenReason.MissingKnownCallableEntry =>
    "Missing known callable entry"
  case CodeGenReason.MissingPlannedLocalCallable =>
    "Missing planned local callable"
  case CodeGenReason.UnsupportedTargetAbi(targetTriple) =>
    s"Unsupported target C ABI: $targetTriple"
  case CodeGenReason.StructFieldErrors(errors) =>
    s"Failed to resolve struct fields: ${errors.map(e => codeGenMessage(e.reason)).mkString(", ")}"
  case CodeGenReason.MissingFunctionType(bnd) =>
    s"Missing function type specification for '${bnd.name}'"
  case CodeGenReason.MissingStringBindingType(bnd) =>
    s"Missing type specification for string literal in binding '${bnd.name}'"
  case CodeGenReason.MissingIntBindingType(bnd) =>
    s"Missing type specification for int literal in binding '${bnd.name}'"
  case CodeGenReason.MissingBoolBindingType(bnd) =>
    s"Missing type specification for bool literal in binding '${bnd.name}'"
  case CodeGenReason.MissingBindingType(bnd) =>
    s"Missing type specification for binding '${bnd.name}'"
  case CodeGenReason.BareNativePrimitive(np) =>
    s"Compiler Bug: Bare NativePrimitive(${np.llvmType}) reached codegen without a nominal wrapper"
  case CodeGenReason.BareNativePointer(np) =>
    s"Compiler Bug: Bare NativePointer(${np.llvmType}) reached codegen without a nominal wrapper"
  case CodeGenReason.MissingNominalName(other) =>
    s"Cannot extract nominal name from ${other.getClass.getSimpleName}"
  case CodeGenReason.InvalidOwnershipScope =>
    "Ownership result requires an immediate unary scope"
  case CodeGenReason.MissingOwnershipResult =>
    "Missing ownership result at scope exit"
  case CodeGenReason.MissingOwnershipResultType =>
    "Missing ownership result type"
  case CodeGenReason.ParameterRegisterCount =>
    "Parameter register count does not match runtime parameter count"
  case CodeGenReason.MissingParameterType(param) =>
    s"Missing type information for parameter '${param.name}'"
  case CodeGenReason.ParameterTypeCount =>
    "Source parameter count does not match lowered parameter types"
  case CodeGenReason.RecursiveLayout(typ) =>
    s"Recursive value layout: $typ"
  case CodeGenReason.MissingLayout(td) =>
    s"Missing layout for ${td.name}"
  case CodeGenReason.UnresolvedLayout(ref) =>
    s"Unresolved layout: ${ref.name}"
  case CodeGenReason.UnsupportedLayout(typ) =>
    s"Unsupported storage layout: $typ"
  case CodeGenReason.UnsupportedLlvmStorage(typ) =>
    s"Unsupported LLVM storage type: $typ"
  case CodeGenReason.ImmediateLambdaArity =>
    "Immediate lambda application requires a nullary or unary lambda and one argument"
  case CodeGenReason.NativeOperatorArity(actual) =>
    s"Native operator called with wrong number of arguments: $actual"
  case CodeGenReason.MissingBinaryOperandType =>
    s"Missing type information for binary operator operand"
  case CodeGenReason.MissingBinaryReturnType(fnRef) =>
    s"Could not determine return type for binary operator '${fnRef.name}'"
  case CodeGenReason.MissingUnaryOperandType =>
    s"Missing type information for unary operator operand"
  case CodeGenReason.MissingUnaryReturnType(fnRef) =>
    s"Could not determine return type for unary operator '${fnRef.name}'"
  case CodeGenReason.MissingCallableResultType =>
    "Missing callable result type"
  case CodeGenReason.MissingArgumentType =>
    s"Missing type information for function argument - TypeChecker should have provided this"
  case CodeGenReason.MissingStaticClosureResultType =>
    s"Could not determine MML type for static closure call result"
  case CodeGenReason.IndirectCallArity(fnRef, expected, actual) =>
    s"Indirect call '${fnRef.name}' argument count: expected $expected, received $actual"
  case CodeGenReason.MissingIndirectReturnType(fnRef) =>
    s"Missing return type for indirect call '${fnRef.name}'"
  case CodeGenReason.MissingIndirectResultType =>
    s"Could not determine MML type for indirect call result"
  case CodeGenReason.MissingConditionType =>
    "Missing type information for conditional expression - TypeChecker should have provided this"
  case CodeGenReason.MissingHoleSpan =>
    "Hole expression is missing source span"
  case CodeGenReason.MissingHoleNominalType(typeSpec) =>
    s"Could not determine MML type name for hole from spec: $typeSpec"
  case CodeGenReason.MissingHoleType =>
    "Missing type information for hole - TypeChecker should have provided this"
  case CodeGenReason.MissingStringLiteralType =>
    "LiteralString missing type specification"
  case CodeGenReason.MissingBinaryTemplate(opRef) =>
    s"No native template found for binary operator '${opRef.name}'"
  case CodeGenReason.MissingUnaryTemplate(opRef) =>
    s"No native template found for unary operator '${opRef.name}'"
  case CodeGenReason.NonNativeType(name) =>
    s"Cannot determine LLVM type for non-native type: $name"
  case CodeGenReason.UnresolvedType(name) =>
    s"Unresolved type reference: $name (TypeRef with no resolvedId - probably typechecker bug)"
  case CodeGenReason.InlineNativeStruct =>
    "Unexpected inline native struct"
  case CodeGenReason.MissingLlvmTypeMapping(other) =>
    s"No LLVM type mapping for TypeSpec: ${other.getClass.getSimpleName}"
  case CodeGenReason.FieldIndexOutOfBounds(fieldIndex, structName, fieldCount) =>
    s"Field index $fieldIndex out of bounds for struct '$structName' (has $fieldCount fields)"
  case CodeGenReason.NonStructType(name, other) =>
    s"Type '$name' is not a struct (got ${other.map(_.getClass.getSimpleName)})"
  case CodeGenReason.UnresolvedStructType(name) =>
    s"Unresolved type reference '$name'"
  case CodeGenReason.ExpectedStructType(other) =>
    s"Expected struct type, got ${other.getClass.getSimpleName}"
