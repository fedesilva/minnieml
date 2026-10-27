package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.DiagnosticId

/** Stores identities, associations, and declaration-failure causes. Lookups read current nodes from
  * the supplied index.
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
  private val memberBindings:     Map[(String, String), String],
  private val failedSignatures:   Map[String, Vector[DiagnosticId]],
  private val failedInstances:    Map[String, Vector[DiagnosticId]]
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

  /** Keep rejected signature names attributable to their declaration errors. For duplicate `f`
    * signatures, a later use of `f` carries these roots instead of reporting a missing registry ID.
    * This records failure only; it does not register the rejected protocol as a valid declaration.
    */
  def rejectProtocol(
    id:     String,
    causes: Vector[DiagnosticId],
    index:  ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolRegistry] =
    for
      declaration <- indexedProtocol(id, index)
      ids <- declaration.signatures.traverse(member => member.id.toRight(MissingId(member)))
    yield copy(failedSignatures = failedSignatures ++ ids.map(_ -> causes))

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

  /** Reserve a failed instance's identity for duplicate detection without offering implementations.
    * An incomplete Num Bool instance must not prevent Num Int addition elsewhere in the module.
    */
  def rejectInstance(
    id:     String,
    causes: Vector[DiagnosticId]
  ): Either[ProtocolLookupError, ProtocolRegistry] =
    instanceKeys
      .get(id)
      .toRight(MissingInstanceId(id))
      .as(copy(failedInstances = failedInstances.updated(id, causes)))

  /** Explain a missing implementation only when that exact instance has a recorded rejection. An
    * absent instance, or a type without concrete identity, supplies no failure evidence.
    */
  def instanceFailureCauses(
    protocolId:   String,
    concreteType: Type,
    index:        ResolvablesIndex
  ): Vector[DiagnosticId] =
    instanceKey(protocolId, concreteType, index).toOption
      .flatMap(instances.get)
      .flatMap(failedInstances.get)
      .getOrElse(Vector.empty)

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

  /** All executable implementations of one signature, for editor reference search across types. */
  def memberBindingIds(signatureId: String): Set[String] =
    memberBindings.collect { case ((_, id), bindingId) if id == signatureId => bindingId }.toSet

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
      _ <- failedInstances.get(instanceId).map(RejectedDeclaration(_)).toLeft(())
      _ <- signature(key.protocolId, signatureId, index)
      id <- memberBindings
        .get((instanceId, signatureId))
        .toRight(MissingMemberBinding(instanceId, signatureId))
      binding <- indexedBinding(id, index)
    yield binding

  /** Read a signature and every usable concrete implementation from the current index. For Num's +,
    * this can return the Int and Float bindings; neither is selected here. A signature with no
    * instances still returns its protocol and signature with an empty implementation list. Rejected
    * instances reserve their keys but contribute no implementation. An unmarked instance with a
    * missing member association remains an internal registry error.
    */
  def signatureCandidates(
    signatureId: String,
    index:       ResolvablesIndex
  ): Either[ProtocolLookupError, ProtocolSignatureCandidates] =
    for
      _ <- failedSignatures.get(signatureId).map(RejectedDeclaration(_)).toLeft(())
      owner <- signatureOwners
        .get(signatureId)
        .toRight(WrongNode(signatureId, "registered signature"))
      declaration <- protocol(owner, index)
      required <- signature(owner, signatureId, index)
      implementations <- instanceKeys.toList
        .filter { case (id, key) => key.protocolId == owner && !failedInstances.contains(id) }
        .sortBy(_._1)
        .traverse { case (instanceId, key) =>
          memberBinding(instanceId, signatureId, index)
            .map(binding => ProtocolImplementation(instanceId, key.concreteType, binding))
        }
    yield ProtocolSignatureCandidates(declaration, required, implementations)

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

    def matchesArity(node: Resolvable): Boolean =
      val meta = ProtocolRegistry.metadata(node)
      operatorArity match
        case Some(arity) => meta.exists(m => m.origin == BindingOrigin.Operator && m.arity == arity)
        case None => !meta.exists(_.origin == BindingOrigin.Operator)

    lookupCandidates(name, scope, index, matchesArity)

  /** Retain every candidate for the nearest lexical name. Expression grouping selects operator
    * arity later, so a source reference can initially contain both unary and binary signatures.
    */
  def visibleCandidates(
    name:  String,
    scope: ProtocolLookupScope,
    index: ResolvablesIndex
  ): Either[ProtocolLookupError, List[ProtocolCandidate]] =
    lookupCandidates(name, scope, index, _ => true)

  private def lookupCandidates(
    name:    String,
    scope:   ProtocolLookupScope,
    index:   ResolvablesIndex,
    accepts: Resolvable => Boolean
  ): Either[ProtocolLookupError, List[ProtocolCandidate]] =

    def matchesName(node: Resolvable): Boolean = node match
      case binding: Bnd => binding.name == name || ProtocolRegistry.sourceName(binding) == name
      case _ => ProtocolRegistry.sourceName(node) == name

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
            val named = nodes.filter((_, node) => matchesName(node))
            if named.isEmpty then search(outer)
            else
              val matching         = named.filter((_, node) => accepts(node))
              val (failed, usable) = matching.partition((id, _) => failedSignatures.contains(id))
              if usable.nonEmpty || failed.isEmpty then usable.traverse(candidate)
              else
                val causes = failed.flatMap((id, _) => failedSignatures(id)).distinct.toVector
                RejectedDeclaration(causes).asLeft
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

  private val unitTypeId = stdlibTypeId("Unit")

  val empty: ProtocolRegistry =
    ProtocolRegistry(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)

  /** Lowered bodies remain module bindings, but their source declarations are instance members. */
  def loweredMemberIds(module: Module): Set[String] =
    module.members.collect { case instance: ProtocolInstance => instance.memberIds }.flatten.toSet

  /** Source names and callable attributes are shared by lexical lookup and operator grouping. */
  def sourceName(node: Resolvable): String =
    metadata(node).map(_.originalName).getOrElse(node.name)

  /** Functions and operators expose the same attributes whether executable or required. */
  def metadata(node: Resolvable): Option[BindingMeta] = node match
    case binding:   Bnd => binding.meta
    case signature: ProtocolSignature => signature.meta.some
    case _ => None

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
                  if unitTypeId.contains(id) then ProtocolTypeKey.Unit
                  else ProtocolTypeKey.Nominal(id)
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

/** Current nodes returned by a signature lookup; the registry itself retains only their IDs. */
case class ProtocolSignatureCandidates(
  protocol:        Protocol,
  signature:       ProtocolSignature,
  implementations: List[ProtocolImplementation]
)

/** One concrete meaning of a signature, before arguments and expected types select it. */
case class ProtocolImplementation(instanceId: String, typeKey: ProtocolTypeKey, binding: Bnd)

/** The pair that must be unique: one protocol and one concrete type. Num Int and Clone Int share a
  * type key but have different protocol IDs and instance keys.
  */
case class ProtocolInstanceKey(protocolId: String, concreteType: ProtocolTypeKey)

enum ProtocolLookupError:
  case RejectedDeclaration(causes: Vector[DiagnosticId])
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

  def id: String = this match
    case Declaration(bindingId) => bindingId
    case Signature(_, signatureId) => signatureId

/** Lexical levels run from innermost to outermost; each level contains visible definition IDs. For
  * List(List(localCombineId), List(semigroupCombineId, otherCombineId)), a local parameter named
  * combine hides both outer signatures. Without that local name, both signatures survive. Callers
  * supply visible signature or binding IDs, not the IDs of their containing protocols.
  */
case class ProtocolLookupScope(levels: List[List[String]]):

  def within(params: List[FnParam]): ProtocolLookupScope =
    copy(levels = params.flatMap(_.id) :: levels)

object ProtocolLookupScope:

  /** Instance bodies are indexed executable bindings, but source names expose their signatures. */
  def module(module: Module): ProtocolLookupScope =

    val instanceBindings = ProtocolRegistry.loweredMemberIds(module)
    val visible = module.members.flatMap {
      case binding:  Bnd if !binding.id.exists(instanceBindings.contains) => binding.id.toList
      case protocol: Protocol => protocol.signatures.flatMap(_.id)
      case _ => Nil
    }

    ProtocolLookupScope(List(visible))
