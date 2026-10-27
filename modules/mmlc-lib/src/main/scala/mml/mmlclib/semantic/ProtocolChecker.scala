package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

/** Checks instance signatures and gives their existing bodies concrete, ordinary function types. */
object ProtocolChecker:

  private val phaseName = "mml.mmlclib.semantic.ProtocolChecker"

  /** Register signatures before instances, so source order cannot change the available protocols.
    * For Eq Int, every occurrence of Eq's 'T becomes Int. Written annotations must agree with that
    * signature; omitted annotations inherit it. Bodies remain ordinary Lambdas for later checking.
    */
  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val protocols  = module.members.collect { case protocol: Protocol => protocol }
    val initial    = CheckedModule(ProtocolRegistry.empty, module.resolvables, state.diagnostics)
    val registered = protocols.foldLeft(initial)(registerProtocol)

    // Lowered bodies are read through their IDs when this phase receives an already lowered module.
    val memberIds = ProtocolRegistry.loweredMemberIds(module)
    val declarations = module.members.filterNot {
      case binding: Bnd => binding.id.exists(memberIds.contains)
      case _ => false
    }
    val checked = declarations.zipWithIndex.foldLeft(registered) {
      case (current, (instance: ProtocolInstance, ordinal)) =>
        lowerInstance(instance, ordinal, protocols, current)
      case (current, (member, _)) => current.copy(members = current.members :+ member)
    }
    state
      .copy(protocols = checked.registry, diagnostics = checked.diagnostics)
      .withModule(ResolvablesIndexer.refresh(module.copy(members = checked.members.toList)))

  private def registerProtocol(current: CheckedModule, protocol: Protocol): CheckedModule =
    val duplicates = protocol.signatures
      .groupBy(signature => memberName(signature))
      .values
      .toList
      .flatMap {
        case first :: rest => rest.map(ProtocolError.DuplicateSignature(_, first))
        case Nil => Nil
      }
    val errors = duplicates ++ protocol.signatures.flatMap(signatureErrors(_, protocol))
    if errors.nonEmpty then
      val (reported, causes) = current.report(errors)
      val rejection = protocol.id
        .toRight(ProtocolLookupError.MissingId(protocol))
        .flatMap(reported.registry.rejectProtocol(_, causes, current.index))
      rejection match
        case Right(registry) => reported.copy(registry = registry)
        case Left(error) => reported.addError(ProtocolError.RegistryFailure(protocol, error))
    else
      val registration = protocol.id
        .toRight(ProtocolLookupError.MissingId(protocol))
        .flatMap(current.registry.registerProtocol(_, current.index))
      registration match
        case Right(registry) => current.copy(registry = registry)
        case Left(error) => current.addError(ProtocolError.RegistryFailure(protocol, error))

  private def signatureErrors(
    signature: ProtocolSignature,
    protocol:  Protocol
  ): List[ProtocolError] =
    val types = signature.params.flatMap(_.typeAsc) :+ signature.returnType
    val details =
      Option.when(signature.defaultBody.nonEmpty)("default bodies are not supported").toList ++
        Option.when(signature.params.exists(_.typeAsc.isEmpty))("parameter types are required") ++
        Option.when(signature.params.groupBy(_.name).exists(_._2.size > 1))(
          "parameter names must be distinct"
        ) ++
        Option.when(types.exists(hasUnboundVariable(_, protocol.typeParameter.name)))(
          "only the protocol's type parameter may appear in a signature"
        )
    details.map(ProtocolError.InvalidSignature(signature, _))

  private def lowerInstance(
    instance:  ProtocolInstance,
    ordinal:   Int,
    protocols: List[Protocol],
    current:   CheckedModule
  ): CheckedModule =
    protocols.find(_.name == instance.protocol.nameNode.value) match

      case None => current.retain(instance, List(ProtocolError.UnknownProtocol(instance.protocol)))

      case Some(protocol) =>
        val resolved = instance.copy(protocol = instance.protocol.copy(resolvedId = protocol.id))
        val registered =
          protocol.id.exists(id => current.registry.protocol(id, current.index).isRight)
        if !registered || TypeUtils.containsInvalidType(instance.concreteType, current.index) then
          current.retain(resolved, Nil)
        else
          current.registry.registerInstance(resolved, current.index) match

            case Left(ProtocolLookupError.InvalidConcreteType(_)) =>
              current.retain(resolved, List(ProtocolError.InvalidInstanceType(resolved)))

            case Left(ProtocolLookupError.DuplicateInstance(_)) =>
              val previous = protocol.id
                .toRight(ProtocolLookupError.MissingId(protocol))
                .flatMap(current.registry.instance(_, resolved.concreteType, current.index))
              val error = previous.fold(
                ProtocolError.RegistryFailure(resolved, _),
                ProtocolError.DuplicateInstance(resolved, _)
              )
              current.retain(resolved, List(error))

            case Left(error) =>
              current.retain(resolved, List(ProtocolError.RegistryFailure(resolved, error)))

            case Right(registry) =>
              lowerMembers(resolved, ordinal, protocol, current.copy(registry = registry))

  private def lowerMembers(
    instance: ProtocolInstance,
    ordinal:  Int,
    protocol: Protocol,
    current:  CheckedModule
  ): CheckedModule =

    val bodies =
      if instance.memberIds.isEmpty then instance.members.asRight[ProtocolError]
      else
        instance.memberIds.traverse { id =>
          current.index
            .lookup(id)
            .collect { case binding: Bnd => binding }
            .toRight(ProtocolError.RegistryFailure(instance, ProtocolLookupError.MissingNode(id)))
            .flatMap { binding =>
              binding.meta
                .map(meta => ProtocolInstanceMember(binding, meta.origin))
                .toRight(
                  ProtocolError.InvalidInstanceMember(binding, "callable metadata is required")
                )
            }
        }
    bodies match

      case Left(error) => current.reject(instance, List(error))

      case Right(members) =>
        val matched = members.map(member => member -> matchingSignature(member, protocol))
        val missing = protocol.signatures
          .filterNot { signature =>
            matched.exists(_._2.exists(_.id == signature.id))
          }
          .map(ProtocolError.MissingMember(instance, _))
        val duplicates = members
          .groupBy(member => memberName(member))
          .values
          .toList
          .flatMap {
            case first :: rest =>
              rest.map(member => ProtocolError.DuplicateMember(member.binding, first.binding))
            case Nil => Nil
          }
        val checked = matched.map {

          case (member, Some(signature)) =>
            checkMember(member, signature, protocol, instance, ordinal, current.index)
              .map(CheckedMember(_, signature))

          case (member, None) =>
            List(ProtocolError.UnexpectedMember(member.binding, protocol)).asLeft[CheckedMember]
        }
        val (failures, implementations) = checked.separate
        val errors                      = missing ++ duplicates ++ failures.flatten

        if errors.nonEmpty then current.reject(instance, errors)
        else
          val withBindings = implementations.foldLeft(current) { (registered, implementation) =>
            registerBinding(instance, implementation, registered)
          }
          val header = instance.copy(
            members   = Nil,
            memberIds = implementations.flatMap(_.binding.id)
          )
          withBindings.copy(
            members = withBindings.members ++ (header :: implementations.map(_.binding))
          )

  private def matchingSignature(
    member:   ProtocolInstanceMember,
    protocol: Protocol
  ): Option[ProtocolSignature] =
    val named = protocol.signatures.filter(_.name == ProtocolRegistry.sourceName(member.binding))
    named.find(signature => memberName(signature) == memberName(member)).orElse {
      named match
        case signature :: Nil => signature.some
        case _ => None
    }

  private def checkMember(
    member:    ProtocolInstanceMember,
    signature: ProtocolSignature,
    protocol:  Protocol,
    instance:  ProtocolInstance,
    ordinal:   Int,
    index:     ResolvablesIndex
  ): Either[List[ProtocolError], Bnd] =
    val binding = member.binding
    binding.value.terms match
      case (lambda: Lambda) :: Nil =>
        val shapeMatches = member.origin == signature.meta.origin &&
          lambda.params.size == signature.params.size
        if !shapeMatches then List(ProtocolError.MemberShapeMismatch(binding, signature)).asLeft
        else
          for
            expected <- instantiateSignature(signature, protocol, instance.concreteType)
            errors = instanceMemberErrors(member, lambda, expected, index)
            _ <- Either.cond(errors.isEmpty, (), errors)
          yield lowerBinding(binding, lambda, expected, ordinal)

      case _ =>
        List(
          ProtocolError.InvalidInstanceMember(binding, "an ordinary function body is required")
        ).asLeft

  private def instantiateSignature(
    signature:    ProtocolSignature,
    protocol:     Protocol,
    concreteType: Type
  ): Either[List[ProtocolError], ConcreteSignature] =
    def instantiate(tpe: Type): Type =
      inheritedType(substitute(tpe, protocol.typeParameter.name, concreteType))

    signature.params
      .traverse(_.typeAsc.map(instantiate))
      .toRight(List(ProtocolError.InvalidSignature(signature, "parameter types are required")))
      .map(types => ConcreteSignature(signature, types, instantiate(signature.returnType)))

  private def instanceMemberErrors(
    member:   ProtocolInstanceMember,
    lambda:   Lambda,
    expected: ConcreteSignature,
    index:    ResolvablesIndex
  ): List[ProtocolError] =
    val binding = member.binding
    val parameterErrors = lambda.params.zip(expected.paramTypes).flatMap { (param, required) =>
      param.typeAsc.toList.flatMap(typeErrors(param, _, required, index))
    }
    val ownershipErrors = lambda.params.zip(expected.signature.params).collect {
      case (actual, required) if actual.consuming != required.consuming =>
        ProtocolError.OwnershipMismatch(actual, required.consuming)
    }
    val returnErrors = (binding.typeAsc.toList ++ lambda.typeAsc).distinct
      .flatMap(typeErrors(binding, _, expected.returnType, index))
    parameterErrors ++ ownershipErrors ++ returnErrors ++
      metadataErrors(member, expected.signature.meta)

  private def lowerBinding(
    binding:  Bnd,
    lambda:   Lambda,
    expected: ConcreteSignature,
    ordinal:  Int
  ): Bnd =
    val params = lambda.params.zip(expected.paramTypes).map { (param, required) =>
      param.copy(typeAsc = param.typeAsc.orElse(required.some), typeSpec = required.some)
    }
    val callableParams = NonEmptyList
      .fromList(expected.paramTypes)
      .getOrElse(
        NonEmptyList.one(TypeRef(binding.source, "Unit", unitTypeId))
      )
    val callable = TypeFn(binding.source, callableParams, expected.returnType)
    val name     = s"__instance_${ordinal}_${expected.signature.meta.mangledName}"
    val body = lambda.copy(
      params   = params,
      typeAsc  = lambda.typeAsc.orElse(expected.returnType.some),
      typeSpec = callable.some
    )
    binding.copy(
      visibility = Visibility.Private,
      nameNode   = binding.nameNode.copy(value = name),
      value      = binding.value.copy(terms = List(body), typeSpec = callable.some),
      typeAsc    = binding.typeAsc.orElse(expected.returnType.some),
      typeSpec   = callable.some,
      meta       = expected.signature.meta.copy(mangledName = name).some
    )

  private def typeErrors(
    node:     FromSource,
    written:  Type,
    expected: Type,
    index:    ResolvablesIndex
  ): List[ProtocolError] =
    if TypeUtils.containsInvalidType(written, index) ||
      TypeUtils.containsInvalidType(expected, index)
    then Nil
    else
      val keys = for
        actualKey <- ProtocolRegistry.typeKey(written, index)
        expectedKey <- ProtocolRegistry.typeKey(expected, index)
      yield actualKey == expectedKey
      Option
        .unless(keys.contains(true))(
          ProtocolError.SignatureTypeMismatch(node, expected, written)
        )
        .toList

  private def metadataErrors(
    member:   ProtocolInstanceMember,
    expected: BindingMeta
  ): List[ProtocolError] =
    val precedence = member.writtenPrecedence
      .filter(_ != expected.precedence)
      .map(actual => ProtocolError.PrecedenceMismatch(member.binding, expected.precedence, actual))
    val associativity = for
      actual <- member.writtenAssociativity
      required <- expected.associativity
      if actual != required
    yield ProtocolError.AssociativityMismatch(member.binding, required, actual)
    precedence.toList ++ associativity

  private def registerBinding(
    instance:       ProtocolInstance,
    implementation: CheckedMember,
    current:        CheckedModule
  ): CheckedModule =
    val binding = implementation.binding
    val index   = current.index.updated(binding)
    val registration = for
      instanceId <- instance.id.toRight(ProtocolLookupError.MissingId(instance))
      signatureId <- implementation.signature.id
        .toRight(ProtocolLookupError.MissingId(implementation.signature))
      bindingId <- binding.id.toRight(ProtocolLookupError.MissingId(binding))
      registry <- current.registry.registerMemberBinding(instanceId, signatureId, bindingId, index)
    yield registry
    registration match
      case Right(registry) => current.copy(registry = registry, index = index)
      case Left(error) => current.addError(ProtocolError.RegistryFailure(binding, error))

  private def substitute(tpe: Type, parameter: String, concrete: Type): Type =
    TypeTraversal.rewrite(tpe) {
      case variable: TypeVariable if variable.name == parameter => concrete
    }

  /** Inherited annotations retain their diagnostic origin without becoming written type syntax. */
  private def inheritedType(tpe: Type): Type = tpe match
    case ref:      TypeRef => ref.copy(source = ref.source.derived)
    case function: TypeFn => function.copy(source = function.source.derived)
    case group:    TypeGroup => group.copy(source = group.source.derived)
    case tuple:    TypeTuple => tuple.copy(source = tuple.source.derived)
    case unit:     TypeUnit => unit.copy(source = unit.source.derived)
    case struct:   TypeStruct => struct.copy(source = struct.source.derived)
    case other => other

  private def hasUnboundVariable(tpe: Type, parameter: String): Boolean =
    TypeTraversal.exists(tpe) {
      case variable: TypeVariable => variable.name != parameter
      case _ => false
    }

  private def memberName(signature: ProtocolSignature): MemberName =
    MemberName(
      signature.name,
      Option.when(signature.meta.origin == BindingOrigin.Operator)(signature.params.size)
    )

  private def memberName(member: ProtocolInstanceMember): MemberName =
    MemberName(ProtocolRegistry.sourceName(member.binding), member.operatorParameterCount)

  private val unitTypeId = stdlibTypeId("Unit")

  private case class MemberName(name: String, operatorArity: Option[Int]) derives CanEqual

  private case class ConcreteSignature(
    signature:  ProtocolSignature,
    paramTypes: List[Type],
    returnType: Type
  )

  private case class CheckedMember(binding: Bnd, signature: ProtocolSignature)

  private case class CheckedModule(
    registry:    ProtocolRegistry,
    index:       ResolvablesIndex,
    diagnostics: DiagnosticStore,
    members:     Vector[Member] = Vector.empty
  ):

    def report(errors: List[ProtocolError]): (CheckedModule, Vector[DiagnosticId]) =
      val (updated, causes) = DiagnosticStore
        .reportAll(errors.map(SemanticError.ProtocolErrorFound(_, phaseName)))
        .run(diagnostics)
        .value
      (copy(diagnostics = updated), causes)

    def addError(error: ProtocolError): CheckedModule = report(List(error))._1

    def retain(instance: ProtocolInstance, failures: List[ProtocolError]): CheckedModule =
      report(failures)._1.copy(members = members :+ instance)

    def reject(instance: ProtocolInstance, failures: List[ProtocolError]): CheckedModule =
      val (reported, causes) = report(failures)
      val rejection = instance.id
        .toRight(ProtocolLookupError.MissingId(instance))
        .flatMap(registry.rejectInstance(_, causes))
      rejection match
        case Right(updated) =>
          reported.copy(registry = updated, members = members :+ instance)
        case Left(error) =>
          reported.retain(instance, List(ProtocolError.RegistryFailure(instance, error)))
