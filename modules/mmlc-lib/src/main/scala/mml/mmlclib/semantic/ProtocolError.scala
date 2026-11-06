package mml.mmlclib.semantic

import cats.data.NonEmptyList
import mml.mmlclib.ast.*

/** Protocol declaration, instance, and selection errors retain their source for every consumer. */
enum ProtocolError:
  case UnknownProtocol(ref: ProtocolRef)
  case DuplicateSignature(signature: ProtocolSignature, first: ProtocolSignature)
  case InvalidSignature(signature: ProtocolSignature, detail: ProtocolSignatureError)
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
  case InvalidInstanceMember(binding: Bnd, detail: ProtocolMemberError)
  case RegistryFailure(node: FromSource, reason: ProtocolLookupError)
  case OperatorGroupingConflict(first: Resolvable, other: Resolvable)
  case MissingInstance(
    ref:          Ref,
    protocol:     Protocol,
    concreteType: Type,
    useSource:    Option[SourceOrigin] = None
  )
  case IncompatibleArguments(ref: Ref)
  case ConflictingUses(
    ref:         Ref,
    bindingName: String,
    conflicts:   NonEmptyList[ProtocolUseConflict]
  )
  case InsufficientTypeInformation(ref: Ref, alternatives: List[ProtocolAlternative] = Nil)
  case AmbiguousSelection(ref: Ref, alternatives: List[ProtocolAlternative] = Nil)

  /** The source location selected for this error. */
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
    case MissingInstance(ref, _, _, useSource) => useSource.getOrElse(ref.source)
    case IncompatibleArguments(ref) => ref.source
    case ConflictingUses(ref, _, _) => ref.source
    case InsufficientTypeInformation(ref, _) => ref.source
    case AmbiguousSelection(ref, _) => ref.source

/** A concrete callable choice uses source names and a type, never its lowered symbol spelling. */
case class ProtocolAlternative(protocolName: Option[String], name: String, functionType: Type)

/** A concrete protocol type required by an argument, annotation, or expected result at its use. */
case class ProtocolTypeRequirement(concreteType: Type, source: SourceOrigin) extends FromSource

/** Incompatible requirements for one protocol. Missing instances retain their enclosing use sites
  * so each affected call can report the required implementation.
  */
case class ProtocolUseConflict(
  protocol:         Protocol,
  requirements:     NonEmptyList[ProtocolTypeRequirement],
  missingInstances: List[ProtocolTypeRequirement]
)

/** Failed requirements for a protocol signature. */
enum ProtocolSignatureError:
  case DefaultBody, MissingParameterType, DuplicateParameter, UnboundTypeVariable

/** Failed requirements for an instance implementation. */
enum ProtocolMemberError:
  case MissingCallableMetadata, MissingFunctionBody
