package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Stores identities and associations only. Every lookup reads nodes from the supplied index.
  *
  * Registration proceeds from protocol, to concrete instance, to each member's executable binding.
  * For example: register Num, register Num Int, then associate Num's + signature ID with the Int
  * addition binding ID. Signature checking and matching bodies to members belong to the caller;
  * this registry records the resulting associations without copying executable bodies.
  *
  * If type checking replaces the addition binding in ResolvablesIndex, memberBinding reads that
  * updated binding. The registry still contains the same binding ID, not an outdated AST copy.
  * Registration returns a new registry; existing registry values remain unchanged.
  */
final case class ProtocolRegistry private (
  private val protocolSignatures: Map[String, List[String]],
  private val signatureOwners:    Map[String, String],
  private val instances:          Map[ProtocolInstanceKey, String],
  private val instanceKeys:       Map[String, ProtocolInstanceKey],
  private val memberBindings:     Map[(String, String), String]
):

  import ProtocolLookupError.*

  /** Register a protocol whose signature nodes already have indexed identities. */
  def registerProtocol(
    id:    String,
    index: ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolRegistry] =
    for
      _ <- Either.cond(!protocolSignatures.contains(id), (), DuplicateProtocol(id))
      declaration <- indexedProtocol(id, index)
      ids <- declaration.signatures.traverse(member => member.id.toRight(MissingId(member)))
      _ <- ids.foldLeftM(Set.empty[String]) { (seen, signatureId) =>
        if seen.contains(signatureId) || signatureOwners.contains(signatureId) then
          DuplicateSignature(signatureId).asLeft
        else indexedSignature(signatureId, index).as(seen + signatureId)
      }
    yield copy(
      protocolSignatures = protocolSignatures.updated(id, ids),
      signatureOwners    = signatureOwners ++ ids.map(_ -> id)
    )

  /** Register instance identity independently of signature checking and body lowering. Registering
    * Num Count after Num Int fails as a duplicate when Count aliases Int, even if the two instance
    * declarations were assigned different IDs.
    */
  def registerInstance(
    instance: ProtocolInstance,
    index:    ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolRegistry] =
    for
      id <- instance.id.toRight(MissingId(instance))
      protocolId <- instance.protocol.resolvedId.toRight(MissingId(instance.protocol))
      _ <- protocol(protocolId, index)
      key <- instanceKey(protocolId, instance.concreteType, index)
      _ <- Either.cond(!instanceKeys.contains(id), (), DuplicateInstanceId(id))
      _ <- Either.cond(!instances.contains(key), (), DuplicateInstance(key))
    yield copy(
      instances    = instances.updated(key, id),
      instanceKeys = instanceKeys.updated(id, key)
    )

  /** Associate a signature with its ordinary executable binding by stable ID. For Num Int's +,
    * instanceId identifies Num Int, signatureId identifies Num's + signature, and bindingId
    * identifies the Bnd containing the Int implementation's Lambda.
    */
  def registerMemberBinding(
    instanceId:  String,
    signatureId: String,
    bindingId:   String,
    index:       ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolRegistry] =
    val key = (instanceId, signatureId)
    for
      instance <- instanceKeys.get(instanceId).toRight(MissingInstanceId(instanceId))
      _ <- signature(instance.protocolId, signatureId, index)
      _ <- indexedBinding(bindingId, index)
      _ <- Either.cond(
        !memberBindings.contains(key),
        (),
        DuplicateMemberBinding(instanceId, signatureId)
      )
    yield copy(memberBindings = memberBindings.updated(key, bindingId))

  /** Read a registered protocol from the supplied index. An indexed but unregistered protocol
    * returns MissingProtocol; a registered ID whose node is absent returns MissingNode.
    */
  def protocol(id: String, index: ResolvablesIndex): Either[ProtocolLookupError, Protocol] =
    protocolSignatures.get(id).toRight(MissingProtocol(id)).flatMap(_ => indexedProtocol(id, index))

  /** Read a signature owned by the registered protocol. For example, Num's + signature cannot be
    * retrieved using Clone's protocol ID. The supplied index provides the current signature.
    */
  def signature(
    protocolId:  String,
    signatureId: String,
    index:       ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolSignature] =
    for
      _ <- protocol(protocolId, index)
      _ <- Either.cond(
        signatureOwners.get(signatureId).contains(protocolId),
        (),
        MissingSignature(protocolId, signatureId)
      )
      signature <- indexedSignature(signatureId, index)
    yield signature

  /** Return the instance declaration's ID, e.g. the registered Num Int instance for Num Count when
    * Count aliases Int. This does not return a member implementation; use memberBinding.
    */
  def instance(
    protocolId:   String,
    concreteType: Type,
    index:        ResolvablesIndex
  ): Either[ProtocolLookupError, String] =
    for
      _ <- protocol(protocolId, index)
      key <- instanceKey(protocolId, concreteType, index)
      id <- instances.get(key).toRight(MissingInstance(key))
    yield id

  /** Read the binding associated with this instance and one of its protocol's signatures.
    * Registering Num Int alone is insufficient: its + signature must also be associated with an
    * implementation via registerMemberBinding. The supplied index provides the current binding.
    */
  def memberBinding(
    instanceId:  String,
    signatureId: String,
    index:       ResolvablesIndex
  ): Either[ProtocolLookupError, Bnd] =
    for
      key <- instanceKeys.get(instanceId).toRight(MissingInstanceId(instanceId))
      _ <- signature(key.protocolId, signatureId, index)
      id <- memberBindings
        .get((instanceId, signatureId))
        .toRight(MissingMemberBinding(instanceId, signatureId))
      binding <- indexedBinding(id, index)
    yield binding

  /** Select the nearest lexical name, then filter operator arity without selecting an instance.
    * None requests an ordinary function/value name; Some requests an operator of that arity. For
    * candidates("-", Some(CallableArity.Unary), scope, index), retain unary - declarations and
    * discard binary ones. Two matching protocol declarations both remain candidates, even if their
    * precedence differs; precedence coherence is checked by a later compiler phase.
    */
  def candidates(
    name:          String,
    operatorArity: Option[CallableArity],
    scope:         ProtocolLookupScope,
    index:         ResolvablesIndex
  ): Either[ProtocolLookupError, List[ProtocolCandidate]] =

    def sourceName(node: Resolvable): String = node match
      case binding:   Bnd => binding.meta.map(_.originalName).getOrElse(binding.name)
      case signature: ProtocolSignature => signature.meta.originalName
      case other => other.name

    def matchesArity(node: Resolvable): Boolean =
      val meta = node match
        case binding:   Bnd => binding.meta
        case signature: ProtocolSignature => signature.meta.some
        case _ => None
      operatorArity match
        case Some(arity) => meta.exists(m => m.origin == BindingOrigin.Operator && m.arity == arity)
        case None => !meta.exists(_.origin == BindingOrigin.Operator)

    def candidate(id: String, node: Resolvable): Either[ProtocolLookupError, ProtocolCandidate] =
      node match
        case _: ProtocolSignature =>
          signatureOwners.get(id).toRight(WrongNode(id, "registered signature")).flatMap { owner =>
            signature(owner, id, index).as(ProtocolCandidate.Signature(owner, id))
          }
        case _: Bnd | _: FnParam => ProtocolCandidate.Declaration(id).asRight
        case _ => WrongNode(id, "callable or value declaration").asLeft

    def search(levels: List[List[String]]): Either[ProtocolLookupError, List[ProtocolCandidate]] =
      levels match
        case Nil => List.empty[ProtocolCandidate].asRight
        case ids :: outer =>
          ids.distinct.traverse(id => indexed(id, index).map(id -> _)).flatMap { nodes =>
            val named = nodes.filter((_, node) => sourceName(node) == name)
            if named.isEmpty then search(outer)
            else named.filter((_, node) => matchesArity(node)).traverse(candidate)
          }

    search(scope.levels)

  private def instanceKey(
    protocolId: String,
    tpe:        Type,
    index:      ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolInstanceKey] =
    ProtocolRegistry.typeKey(tpe, index).map(ProtocolInstanceKey(protocolId, _))

  private def indexed(
    id:    String,
    index: ResolvablesIndex
  ): Either[ProtocolLookupError, Resolvable] =
    index.lookup(id).toRight(MissingNode(id)).flatMap { node =>
      Either.cond(node.id.contains(id), node, WrongNode(id, "matching stable identity"))
    }

  private def indexedProtocol(
    id:    String,
    index: ResolvablesIndex
  ): Either[ProtocolLookupError, Protocol] =
    indexed(id, index).flatMap {
      case node: Protocol => node.asRight
      case _ => WrongNode(id, "protocol").asLeft
    }

  private def indexedSignature(
    id:    String,
    index: ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolSignature] =
    indexed(id, index).flatMap {
      case node: ProtocolSignature => node.asRight
      case _ => WrongNode(id, "signature").asLeft
    }

  private def indexedBinding(
    id:    String,
    index: ResolvablesIndex
  ): Either[ProtocolLookupError, Bnd] =
    indexed(id, index).flatMap {
      case node: Bnd => node.asRight
      case _ => WrongNode(id, "binding").asLeft
    }

object ProtocolRegistry:

  private val unitTypeId = BindingIds.declaration("stdlib", "Unit", "typedef")

  val empty: ProtocolRegistry =
    ProtocolRegistry(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)

  /** Resolve aliases through declaration IDs; never use source spelling or native layout as
    * identity. Grouping such as (Int) does not change the key, and the built-in Unit declaration
    * shares its key with TypeUnit. A user-defined nominal type named Unit remains distinct.
    *
    * Alias expansion tracks visited aliases so a cycle fails instead of looping. Unresolved
    * references, type variables, polymorphic schemes, and unsupported type forms fail explicitly:
    * none provides the concrete identity required to register an instance in this slice.
    */
  def typeKey(tpe: Type, index: ResolvablesIndex): Either[ProtocolLookupError, ProtocolTypeKey] =

    def loop(current: Type, aliases: Set[String]): Either[ProtocolLookupError, ProtocolTypeKey] =
      current match
        case ref: TypeRef =>
          ref.resolvedId.flatMap(index.lookupType) match
            case Some(alias: TypeAlias)
                if alias.id == ref.resolvedId && !alias.id.exists(aliases.contains) =>
              loop(alias.typeSpec.getOrElse(alias.typeRef), aliases ++ alias.id)
            case Some(declaration: TypeDef) if declaration.id == ref.resolvedId =>
              declaration.id
                .map(id =>
                  if id == unitTypeId then ProtocolTypeKey.Unit else ProtocolTypeKey.Nominal(id)
                )
                .toRight(ProtocolLookupError.InvalidConcreteType(current))
            case Some(struct: TypeStruct) if struct.id == ref.resolvedId =>
              struct.id
                .map(ProtocolTypeKey.Nominal(_))
                .toRight(ProtocolLookupError.InvalidConcreteType(current))
            case _ => ProtocolLookupError.InvalidConcreteType(current).asLeft
        case struct: TypeStruct =>
          struct.id
            .filter(id =>
              index.lookupType(id).exists {
                case node: TypeStruct => node.id.contains(id)
                case _ => false
              }
            )
            .map(ProtocolTypeKey.Nominal(_))
            .toRight(ProtocolLookupError.InvalidConcreteType(current))
        case TypeGroup(_, List(inner)) => loop(inner, aliases)
        case TypeScheme(_, Nil, body) => loop(body, aliases)
        case _:        TypeUnit => ProtocolTypeKey.Unit.asRight
        case function: TypeFn =>
          for
            params <- function.paramTypes.toList.traverse(loop(_, aliases))
            result <- loop(function.returnType, aliases)
          yield ProtocolTypeKey.Function(params, result)
        case tuple: TypeTuple =>
          tuple.elements.traverse(loop(_, aliases)).map(ProtocolTypeKey.Tuple(_))
        case _ => ProtocolLookupError.InvalidConcreteType(current).asLeft

    loop(tpe, Set.empty)

/** The comparable part of a type used to find a protocol instance in a map.
  *
  * Comparing AST nodes directly would include source positions and written names. Instead, a
  * nominal type uses its resolved declaration ID, and an alias uses its target's key. For example,
  * if Count aliases Int, Count and Int have the same key. Separate nominal types Metres and Seconds
  * have different keys even if both have the LLVM representation i64.
  *
  * Function and tuple keys compare their component types recursively. Thus an alias of the function
  * type Int -> Int has the same key as that function type written directly. These keys describe
  * identity only; they do not select a protocol or prove that an instance exists.
  */
enum ProtocolTypeKey derives CanEqual:
  case Nominal(id: String)
  case Unit
  case Function(params: List[ProtocolTypeKey], result: ProtocolTypeKey)
  case Tuple(elements: List[ProtocolTypeKey])

/** The pair that must be unique: one protocol and one concrete type. Num Int and Clone Int share a
  * type key but have different protocol IDs and instance keys.
  */
case class ProtocolInstanceKey(protocolId: String, concreteType: ProtocolTypeKey)

enum ProtocolLookupError:
  case MissingId(node: AstNode)
  case MissingNode(id: String)
  case WrongNode(id: String, expected: String)
  case MissingProtocol(id: String)
  case MissingSignature(protocolId: String, signatureId: String)
  case MissingInstance(key: ProtocolInstanceKey)
  case MissingInstanceId(id: String)
  case MissingMemberBinding(instanceId: String, signatureId: String)
  case DuplicateProtocol(id: String)
  case DuplicateSignature(id: String)
  case DuplicateInstance(key: ProtocolInstanceKey)
  case DuplicateInstanceId(id: String)
  case DuplicateMemberBinding(instanceId: String, signatureId: String)
  case InvalidConcreteType(tpe: Type)

/** A visible ordinary value or a protocol's callable requirement, before instance selection.
  * Looking up Num's + returns Signature(numId, plusSignatureId), even without a Num Int instance.
  * That lets the later type checker distinguish a missing instance from an undefined name.
  */
enum ProtocolCandidate derives CanEqual:
  case Declaration(bindingId: String)
  case Signature(protocolId: String, signatureId: String)

/** Lexical levels run from innermost to outermost; each level contains visible definition IDs. For
  * List(List(localCombineId), List(semigroupCombineId, otherCombineId)), a local parameter named
  * combine hides both outer signatures. Without that local name, both signatures survive. Callers
  * supply visible signature or binding IDs, not the IDs of their containing protocols.
  */
case class ProtocolLookupScope(levels: List[List[String]])
