package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Protocol declaration, instance, and selection errors retain their source for every consumer. */
enum ProtocolError:
  case UnknownProtocol(ref: ProtocolRef)
  case DuplicateSignature(signature: ProtocolSignature, first: ProtocolSignature)
  case InvalidSignature(signature: ProtocolSignature, detail: String)
  case InvalidInstanceType(instance: ProtocolInstance)
  case DuplicateInstance(instance: ProtocolInstance, firstId: String)
  case MissingMember(instance: ProtocolInstance, signature: ProtocolSignature)
  case UnexpectedMember(binding: Bnd, protocol: Protocol)
  case DuplicateMember(binding: Bnd, first: Bnd)
  case MemberShapeMismatch(binding: Bnd, signature: ProtocolSignature)
  case SignatureTypeMismatch(node: FromSource, expected: Type, actual: Type)
  case OwnershipMismatch(param: FnParam, expectedConsuming: Boolean)
  case PrecedenceMismatch(binding: Bnd, expected: Int, actual: Int)
  case AssociativityMismatch(binding: Bnd, expected: Associativity, actual: Associativity)
  case InvalidInstanceMember(binding: Bnd, detail: String)
  case RegistryFailure(node: FromSource, reason: ProtocolLookupError)
  case OperatorGroupingConflict(first: Resolvable, other: Resolvable)
  case MissingInstance(ref: Ref, protocol: Protocol, concreteType: Type)
  case IncompatibleArguments(ref: Ref)
  case InsufficientTypeInformation(ref: Ref)
  case AmbiguousSelection(ref: Ref)

  /** The declaration, annotation, ownership marker, or reference that caused the error. */
  def source: SourceOrigin = this match
    case UnknownProtocol(ref) => ref.source
    case DuplicateSignature(signature, _) => signature.source
    case InvalidSignature(signature, _) => signature.source
    case InvalidInstanceType(instance) => instance.concreteType.source
    case DuplicateInstance(instance, _) => instance.source
    case MissingMember(instance, _) => instance.source
    case UnexpectedMember(binding, _) => binding.source
    case DuplicateMember(binding, _) => binding.source
    case MemberShapeMismatch(binding, _) => binding.source
    case SignatureTypeMismatch(node, _, _) => node.source
    case OwnershipMismatch(param, _) => param.source
    case PrecedenceMismatch(binding, _, _) => binding.source
    case AssociativityMismatch(binding, _, _) => binding.source
    case InvalidInstanceMember(binding, _) => binding.source
    case RegistryFailure(node, _) => node.source
    case OperatorGroupingConflict(_, other) => other.nameNode.source
    case MissingInstance(ref, _, _) => ref.source
    case IncompatibleArguments(ref) => ref.source
    case InsufficientTypeInformation(ref) => ref.source
    case AmbiguousSelection(ref) => ref.source

  /** Describe the failed protocol rule without exposing internal AST representations. */
  def message: String = this match

    case UnknownProtocol(ref) =>
      s"Unknown protocol '${ref.nameNode.value}'"

    case DuplicateSignature(signature, _) =>
      s"Duplicate protocol signature '${signature.meta.originalName}'"

    case InvalidSignature(signature, detail) =>
      s"Invalid protocol signature '${signature.meta.originalName}': $detail"

    case InvalidInstanceType(instance) =>
      s"Protocol '${instance.protocol.nameNode.value}' requires a concrete instance type, " +
        s"got ${showType(instance.concreteType)}"

    case DuplicateInstance(instance, _) =>
      s"Duplicate instance of protocol '${instance.protocol.nameNode.value}' " +
        s"for ${showType(instance.concreteType)}"

    case MissingMember(instance, signature) =>
      s"Instance of protocol '${instance.protocol.nameNode.value}' " +
        s"is missing member '${signature.meta.originalName}'"

    case UnexpectedMember(binding, protocol) =>
      s"Protocol '${protocol.name}' has no signature for instance member '${memberName(binding)}'"

    case DuplicateMember(binding, _) =>
      s"Duplicate protocol instance member '${memberName(binding)}'"

    case MemberShapeMismatch(binding, signature) =>
      val kind =
        if signature.meta.origin == BindingOrigin.Operator then "an operator" else "a function"
      val count = signature.params.size
      val noun  = if count == 1 then "parameter" else "parameters"
      s"Instance member '${memberName(binding)}' must be $kind with $count $noun, " +
        s"as required by protocol signature '${signature.meta.originalName}'"

    case SignatureTypeMismatch(_, expected, actual) =>
      s"Protocol signature type mismatch: expected ${showType(expected)}, got ${showType(actual)}"

    case OwnershipMismatch(param, expectedConsuming) =>
      val ownership = if expectedConsuming then "consuming (~)" else "non-consuming"
      s"Parameter '${param.name}' must be $ownership to match its protocol signature"

    case PrecedenceMismatch(binding, expected, actual) =>
      s"Instance operator '${memberName(binding)}' must use protocol signature precedence " +
        s"$expected, got $actual"

    case AssociativityMismatch(binding, expected, actual) =>
      s"Instance operator '${memberName(binding)}' must use protocol signature associativity " +
        s"${associativityName(expected)}, got ${associativityName(actual)}"

    case InvalidInstanceMember(binding, detail) =>
      s"Invalid protocol instance member '${memberName(binding)}': $detail"

    case RegistryFailure(_, reason) =>
      s"Protocol registry error: ${registryMessage(reason)}"

    case OperatorGroupingConflict(_, other) =>
      s"Operator '${ProtocolRegistry.sourceName(other)}' has conflicting precedence or " +
        "associativity for the same arity"

    case MissingInstance(ref, protocol, concreteType) =>
      s"No instance of protocol '${protocol.name}' for ${showType(concreteType)} " +
        s"provides '${ref.name}'"

    case IncompatibleArguments(ref) =>
      s"No signature of '${ref.name}' matches the arguments and expected type"

    case InsufficientTypeInformation(ref) =>
      s"Not enough type information to select '${ref.name}'; add a type annotation"

    case AmbiguousSelection(ref) =>
      s"More than one signature of '${ref.name}' matches the arguments and expected type"

  private def memberName(binding: Bnd): String =
    binding.meta.fold(binding.name)(_.originalName)

  private def associativityName(associativity: Associativity): String = associativity match
    case Associativity.Left => "left"
    case Associativity.Right => "right"

  private def registryMessage(reason: ProtocolLookupError): String = reason match
    case ProtocolLookupError.RejectedDeclaration(_) => "declaration is invalid"
    case ProtocolLookupError.MissingId(_) => "declaration has no assigned identity"
    case ProtocolLookupError.MissingNode(id) => s"declaration '$id' is absent from the index"
    case ProtocolLookupError.WrongNode(id, expected) => s"declaration '$id' must be a $expected"
    case ProtocolLookupError.MissingProtocol(id) => s"protocol '$id' is not registered"
    case ProtocolLookupError.MissingSignature(_, id) => s"signature '$id' is not registered"
    case ProtocolLookupError.MissingInstance(_) => "no instance is registered for the concrete type"
    case ProtocolLookupError.MissingInstanceId(id) => s"instance '$id' is not registered"
    case ProtocolLookupError.MissingMemberBinding(_, id) =>
      s"signature '$id' has no registered instance member"
    case ProtocolLookupError.DuplicateProtocol(id) => s"protocol '$id' is already registered"
    case ProtocolLookupError.DuplicateSignature(id) => s"signature '$id' is already registered"
    case ProtocolLookupError.DuplicateInstance(_) =>
      "an instance is already registered for the concrete type"
    case ProtocolLookupError.DuplicateInstanceId(id) => s"instance '$id' is already registered"
    case ProtocolLookupError.DuplicateMemberBinding(_, id) =>
      s"signature '$id' already has a registered instance member"
    case ProtocolLookupError.InvalidConcreteType(tpe) =>
      s"${showType(tpe)} is not a supported concrete instance type"
