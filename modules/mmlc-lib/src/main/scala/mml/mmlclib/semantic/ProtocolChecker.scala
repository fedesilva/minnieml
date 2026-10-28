package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

/** Validates protocol declarations and turns instance members into ordinary functions with concrete
  * types. The registry connects each protocol signature to its instance implementations; later
  * phases resolve references and type-check the function bodies.
  */
object ProtocolChecker:

  private val phaseName = "mml.mmlclib.semantic.ProtocolChecker"

  /** Register signatures before instances, so source order cannot change the available protocols.
    * For Eq Int, every occurrence of Eq's 'T becomes Int. Written annotations must agree with that
    * signature; omitted annotations inherit it. Bodies remain ordinary Lambdas for later checking.
    */
  def rewriteModule(state: CompilerState): CompilerState =
    val module    = state.module
    val protocols = module.members.collect { case protocol: Protocol => protocol }
    val initial = ProtocolCheckState(ProtocolRegistry.empty, module.resolvables, state.diagnostics)
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

  /** Registers valid signatures and records the causes of rejected declarations, so later uses can
    * report the original declaration error rather than a missing registry entry.
    */
  private def registerProtocol(
    current:  ProtocolCheckState,
    protocol: Protocol
  ): ProtocolCheckState =
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

  /** Checks the supported signature form: required parameter types, distinct parameter names, no
    * default body, and no type variables other than the protocol's declared parameter.
    */
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

  /** Resolves an instance's protocol and reserves its concrete type before checking its members.
    * Lowering turns those members into ordinary functions: for Render Int, a member using 'T
    * receives Int parameter and return types wherever 'T occurs. The instance retains references to
    * its implementations. Invalid declarations remain in the module with their diagnostics.
    */
  private def lowerInstance(
    instance:  ProtocolInstance,
    ordinal:   Int,
    protocols: List[Protocol],
    current:   ProtocolCheckState
  ): ProtocolCheckState =
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

  /** Checks the complete member set before registering implementations. Successful members become
    * module-level bindings, and the instance stores their IDs instead of nested bodies. An already
    * lowered instance supplies its bodies through those IDs when the phase runs again.
    */
  private def lowerMembers(
    instance: ProtocolInstance,
    ordinal:  Int,
    protocol: Protocol,
    current:  ProtocolCheckState
  ): ProtocolCheckState =

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

  /** Selects the required signature by source name, distinguishing unary and binary operators. */
  private def matchingSignature(
    member:   ProtocolInstanceMember,
    protocol: Protocol
  ): Option[ProtocolSignature] =
    val named = protocol.signatures.filter(_.name == ProtocolRegistry.sourceName(member.binding))
    named.find(signature => memberName(signature) == memberName(member)).orElse {
      // A unique same-name signature lets checkMember report a shape mismatch.
      named match
        case signature :: Nil => signature.some
        case _ => None
    }

  /** Checks a member's function/operator form, parameter count, and agreement with the instantiated
    * signature before producing its ordinary binding. Expression type checking belongs to a later
    * phase; success here establishes the member's declared contract.
    */
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

  /** Substitutes the instance's concrete type into a signature, retaining the source declaration
    * for ownership requirements and callable metadata. Inherited types carry derived source
    * origins.
    */
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

  /** Compares written types, consuming-parameter markers, and explicit operator attributes with the
    * required signature. Omitted type annotations and operator attributes inherit its requirements.
    */
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

  /** Gives an implementation its private name and concrete function type while preserving binding
    * and parameter identities. Written annotations remain intact; omitted annotations receive the
    * instantiated types. The body remains a Lambda for ordinary checking and code generation.
    */
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
    // A bare FFI implementation names the source function, not its private implementation binding.
    val terms = lambda.body.terms.map {
      case native: NativeImpl if native.nativeTpl.isEmpty =>
        native.copy(
          nativeSymbol = native.nativeSymbol.orElse(ProtocolRegistry.sourceName(binding).some)
        )
      case term => term
    }
    val body = lambda.copy(
      params   = params,
      body     = lambda.body.copy(terms = terms),
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

  /** Compares resolved type identities, so aliases can satisfy the same requirement. Types already
    * marked invalid keep their original diagnostics without an additional signature mismatch.
    */
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

  /** Checks explicitly written precedence and associativity against the protocol's shared grouping
    * rules. Omitted attributes use the signature's values when the binding is lowered.
    */
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

  /** Associates an instance and required signature with the implementation's stable ID. The updated
    * binding enters the lookup index before registration, so the registry sees its concrete form.
    */
  private def registerBinding(
    instance:       ProtocolInstance,
    implementation: CheckedMember,
    current:        ProtocolCheckState
  ): ProtocolCheckState =
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

  /** Replaces the named protocol type variable throughout a type, including nested function types.
    */
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

  /** Detects type variables that the protocol's single declared parameter does not bind. */
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

  /** Matching and duplicate-detection key. Functions use their source name alone; operators also
    * use their parameter count, so unary and binary forms of the same symbol remain distinct.
    */
  private case class MemberName(name: String, operatorArity: Option[Int]) derives CanEqual

  /** The source protocol signature paired with its parameter and return types after substituting
    * the instance's concrete type for the protocol's type parameter.
    *
    * In this example, `render` has parameter types `String` and `Int`, and return type `Unit`:
    *
    * {{{
    * protocol Render 'T =
    *   fn render(label: String, value: 'T): Unit;
    * ;
    *
    * instance Render Int =
    *   fn render(label, value) =
    *     print label;
    *     println (int_to_str value);
    *   ;
    * ;
    * }}}
    */
  private case class ConcreteSignature(
    signature:  ProtocolSignature,
    paramTypes: List[Type],
    returnType: Type
  )

  /** A lowered implementation paired with the source signature it satisfies. Its declaration has
    * passed the instance checks; its body still awaits ordinary expression type checking.
    */
  private case class CheckedMember(binding: Bnd, signature: ProtocolSignature)

  /** Immutable working state of ProtocolChecker */
  private case class ProtocolCheckState(
    registry:    ProtocolRegistry,
    index:       ResolvablesIndex,
    diagnostics: DiagnosticStore,
    members:     Vector[Member] = Vector.empty
  ):

    /** Records protocol errors and returns their diagnostic IDs for dependent failures to
      * reference.
      */
    def report(errors: List[ProtocolError]): (ProtocolCheckState, Vector[DiagnosticId]) =
      val (updated, causes) = DiagnosticStore
        .reportAll(errors.map(SemanticError.ProtocolErrorFound(_, phaseName)))
        .run(diagnostics)
        .value
      (copy(diagnostics = updated), causes)

    def addError(error: ProtocolError): ProtocolCheckState = report(List(error))._1

    /** Keeps the declaration and reports any supplied failures without changing registry
      * membership. This also preserves instances whose protocol or concrete type is already
      * invalid.
      */
    def retain(instance: ProtocolInstance, failures: List[ProtocolError]): ProtocolCheckState =
      report(failures)._1.copy(members = members :+ instance)

    /** Marks a registered instance as failed while retaining its declaration and diagnostic causes.
      * Its identity stays reserved for duplicate detection, but it cannot supply implementations.
      */
    def reject(instance: ProtocolInstance, failures: List[ProtocolError]): ProtocolCheckState =
      val (reported, causes) = report(failures)
      val rejection = instance.id
        .toRight(ProtocolLookupError.MissingId(instance))
        .flatMap(registry.rejectInstance(_, causes))
      rejection match
        case Right(updated) =>
          reported.copy(registry = updated, members = members :+ instance)
        case Left(error) =>
          reported.retain(instance, List(ProtocolError.RegistryFailure(instance, error)))
