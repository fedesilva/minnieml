package mml.mmlclib.util.error.print

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.DeclarationQueries.sourceName
import mml.mmlclib.semantic.*

/** Shared wording for protocol declaration and selection failures. */
def protocolMessage(error: ProtocolError): String =
  import ProtocolError.*
  error match

    case UnknownProtocol(ref) =>
      s"Unknown protocol '${ref.nameNode.value}'"

    case DuplicateSignature(signature, _) =>
      s"Duplicate protocol signature '${signature.meta.originalName}'"

    case InvalidSignature(signature, detail) =>
      s"Invalid protocol signature '${signature.meta.originalName}': ${protocolSignatureMessage(detail)}"

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
      s"Protocol '${protocol.name}' has no signature for instance member '${sourceName(binding)}'"

    case DuplicateMember(binding, _) =>
      s"Duplicate protocol instance member '${sourceName(binding)}'"

    case MemberShapeMismatch(binding, signature) =>
      val kind =
        if signature.meta.origin == BindingOrigin.Operator then "an operator" else "a function"
      val count = signature.params.size
      val noun  = if count == 1 then "parameter" else "parameters"
      s"Instance member '${sourceName(binding)}' must be $kind with $count $noun, " +
        s"as required by protocol signature '${signature.meta.originalName}'"

    case SignatureTypeMismatch(_, expected, actual) =>
      s"Protocol signature type mismatch: expected ${showType(expected)}, got ${showType(actual)}"

    case OwnershipMismatch(param, expectedConsuming) =>
      val ownership = if expectedConsuming then "consuming (~)" else "non-consuming"
      s"Parameter '${param.name}' must be $ownership to match its protocol signature"

    case PrecedenceMismatch(binding, expected, actual) =>
      s"Instance operator '${sourceName(binding)}' must use protocol signature precedence " +
        s"$expected, got $actual"

    case AssociativityMismatch(binding, expected, actual) =>
      s"Instance operator '${sourceName(binding)}' must use protocol signature associativity " +
        s"${associativityName(expected)}, got ${associativityName(actual)}"

    case InvalidInstanceMember(binding, detail) =>
      s"Invalid protocol instance member '${sourceName(binding)}': ${protocolMemberMessage(detail)}"

    case RegistryFailure(_, reason) =>
      s"Protocol registry error: ${registryMessage(reason)}"

    case OperatorGroupingConflict(_, other) =>
      s"Operator '${sourceName(other)}' has conflicting precedence or " +
        "associativity for the same arity"

    case MissingInstance(ref, protocol, concreteType) =>
      s"No instance of protocol '${protocol.name}' for ${showType(concreteType)} " +
        s"provides '${ref.name}'"

    case IncompatibleArguments(ref) =>
      s"No signature of '${ref.name}' matches the arguments and expected type"

    case InsufficientTypeInformation(ref, alternatives) =>
      s"Not enough type information to select '${ref.name}'; add a type annotation" +
        formatAlternatives(alternatives)

    case AmbiguousSelection(ref, alternatives) =>
      s"More than one signature of '${ref.name}' matches the arguments and expected type" +
        formatAlternatives(alternatives)

/** Show every remaining concrete choice when alternatives are available. */
private def formatAlternatives(alternatives: List[ProtocolAlternative]): String =
  if alternatives.isEmpty then ""
  else
    alternatives
      .map(protocolAlternativeDescription)
      .sorted
      .map(description => s"  $description")
      .mkString("\nAlternatives:\n", "\n", "")

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

/** A callable alternative displayed with its source name and concrete type. */
def protocolAlternativeDescription(alternative: ProtocolAlternative): String =
  val owner = alternative.protocolName.fold("")(name => s"$name.")
  s"$owner${alternative.name}: ${showType(alternative.functionType)}"

private def protocolSignatureMessage(error: ProtocolSignatureError): String = error match
  case ProtocolSignatureError.DefaultBody => "default bodies are not supported"
  case ProtocolSignatureError.MissingParameterType => "parameter types are required"
  case ProtocolSignatureError.DuplicateParameter => "parameter names must be distinct"
  case ProtocolSignatureError.UnboundTypeVariable =>
    "only the protocol's type parameter may appear in a signature"

private def protocolMemberMessage(error: ProtocolMemberError): String = error match
  case ProtocolMemberError.MissingCallableMetadata => "callable metadata is required"
  case ProtocolMemberError.MissingFunctionBody => "an ordinary function body is required"
