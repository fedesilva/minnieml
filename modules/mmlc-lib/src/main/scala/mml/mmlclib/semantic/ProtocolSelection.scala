package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.TypeQueries

/** Candidate discovery and signature evidence are independent of expression checking. */
object ProtocolSelection:

  /** A protocol operation justified by a generic body's declared requirement. The signature ID
    * remains the target until monomorphization replaces the rigid variable with a concrete type.
    */
  case class ConstrainedCandidate(
    matched:      SignatureMatch,
    protocolType: TypeVariable,
    callableType: TypeFn
  ):
    def signature: ProtocolSignature = matched.registered.signature

  /** Resolve signature evidence against lexical requirements and their validated prerequisites.
    * Evidence belongs to the same variable binder; concrete instances cannot justify a rigid type.
    */
  def constrainedCandidates(
    matches:      List[SignatureMatch],
    requirements: List[TypeVariableConstraints],
    registry:     ProtocolRegistry,
    index:        ResolvablesIndex
  ): List[ConstrainedCandidate] =

    matches.flatMap { matched =>
      val registered = matched.registered
      matched.evidence match
        case SignatureEvidence.Concrete(tpe, _) =>
          TypeQueries
            .canonical(tpe, index)
            .collect { case variable: TypeVariable => variable }
            .filter(variable =>
              registered.protocol.id
                .exists(declaredRequirement(_, variable, requirements, registry))
            )
            .flatMap { variable =>
              val callable = matched.memberCall.map(_.callable).orElse {
                for
                  binder <- registered.protocol.typeParameter.resolvedId
                  declared <- signatureType(registered.signature)
                yield TypeSubstitution(declared, Map(binder -> variable))
              }
              callable.map(ConstrainedCandidate(matched, variable, _))
            }
            .toList
        case _ => Nil
    }

  /** Check a requirement at a generic use. Rigid arguments require lexical evidence; concrete
    * arguments require an accepted instance. Concrete registrations cannot justify a rigid
    * variable.
    */
  def satisfiesRequirement(
    protocolId:   String,
    tpe:          Type,
    requirements: List[TypeVariableConstraints],
    registry:     ProtocolRegistry,
    index:        ResolvablesIndex
  ): Boolean =
    TypeQueries.canonical(tpe, index) match
      case Some(variable: TypeVariable) =>
        declaredRequirement(protocolId, variable, requirements, registry)
      case Some(concrete) => registry.usableInstance(protocolId, concrete, index).isRight
      case None => false

  private def declaredRequirement(
    protocolId:   String,
    variable:     TypeVariable,
    requirements: List[TypeVariableConstraints],
    registry:     ProtocolRegistry
  ): Boolean =

    variable.resolvedId.exists { binder =>
      requirements.exists { requirement =>
        requirement.variable.resolvedId.contains(binder) &&
        requirement.constraints.exists(_.resolvedId.exists(registry.implies(_, protocolId)))
      }
    }

  /** Preserve ordinary declarations and registered signatures, including signatures with no
    * instances. The reference already carries the lexical and operator-arity decisions.
    */
  def candidates(
    ref:      Ref,
    registry: ProtocolRegistry,
    index:    ResolvablesIndex
  ): Either[ProtocolLookupError, CandidateSet] =
    val ids = (ref.candidateIds ++ ref.resolvedId).distinct
    ids.foldLeftM(CandidateSet(Nil, Nil)) { (current, id) =>
      index.lookup(id).toRight(ProtocolLookupError.MissingNode(id)).flatMap {
        case _: ProtocolSignature =>
          registry.signatureCandidates(id, index).map { signature =>
            current.copy(signatures = current.signatures :+ signature)
          }
        case _: Bnd | _: FnParam =>
          current.copy(declarationIds = current.declarationIds :+ id).asRight
        case _ => ProtocolLookupError.WrongNode(id, "callable or value declaration").asLeft
      }
    }

  /** Match registered signatures using source type evidence, without selecting by instance count. A
    * generic member solves its local arguments alongside the receiver. Conflicting receiver
    * requirements from local uses retain their types and written sites for instance diagnostics.
    */
  def matchSignatures(
    choices:        CandidateSet,
    applications:   List[ApplicationEvidence],
    expectedType:   Option[Type],
    index:          ResolvablesIndex,
    uses:           List[UseEvidence]                = Nil,
    expectedSource: SourceOrigin                     = SourceOrigin.Synth,
    explicitTypes:  Option[List[Type]]               = None,
    refine:         GenericInference.RefineArguments = (_, known, _) => known
  ): List[SignatureMatch] =
    choices.signatures.map { registered =>
      if registered.signature.typeParams.nonEmpty then
        matchGenericMember(
          registered,
          applications,
          expectedType,
          index,
          uses,
          expectedSource,
          explicitTypes,
          refine
        )
      else
        matchProtocolParameter(registered, applications, expectedType, index, uses, expectedSource)
    }

  /** The callable type at this use. arguments contains only the member-local types, in
    * specialization order. substitution also binds the protocol parameter to the receiver.
    */
  case class MemberCall(callable: TypeFn, arguments: List[Type], substitution: Map[String, Type])

  private def matchGenericMember(
    registered:     ProtocolSignatureCandidates,
    applications:   List[ApplicationEvidence],
    expected:       Option[Type],
    index:          ResolvablesIndex,
    uses:           List[UseEvidence],
    expectedSource: SourceOrigin,
    explicit:       Option[List[Type]],
    refine:         GenericInference.RefineArguments
  ): SignatureMatch =
    val signature  = registered.signature
    val receiver   = registered.protocol.typeParameter
    val parameters = receiver :: signature.typeParams
    val declared   = signatureType(signature)
    val inferred = declared
      .toRight(GenericInference.Failure.NotCallable(signature.returnType))
      .flatMap { callable =>
        GenericInference
          .inferCallable(
            callable,
            parameters,
            UseEvidence(applications, expected).genericCall,
            uses.map(_.genericCall),
            explicit,
            signature.typeParams,
            index,
            refine
          )
          .map { substitution =>
            MemberCall(
              TypeSubstitution(callable, substitution),
              signature.typeParams.map(TypeSubstitution(_, substitution)),
              substitution
            )
          }
      }
    val evidence = inferred match
      case Right(call) =>
        SignatureEvidence.Concrete(TypeSubstitution(receiver, call.substitution), Nil)
      case Left(_: GenericInference.Failure.MissingArguments) => SignatureEvidence.Unknown
      case Left(_) if uses.nonEmpty =>
        // The first failed comparison can name a callback binder unified with the receiver.
        // Recheck the initializer and later uses with only receiver equality relaxed.
        declared.fold[SignatureEvidence](SignatureEvidence.Incompatible) { callable =>
          conflictingReceiverEvidence(
            callable,
            receiver,
            signature.typeParams,
            explicit,
            UseEvidence(applications, expected, expectedSource),
            uses,
            index
          )
        }
      case Left(_) => SignatureEvidence.Incompatible
    SignatureMatch(
      registered,
      evidence,
      implementationIds(registered, evidence, index),
      Some(inferred)
    )

  /** Looks for conflicting receiver types in the available evidence from a stored member's
    * initializer and later uses. The solver shares their member-local types and records each
    * receiver demand at its written site. A fixed parameter mismatch rules out the candidate.
    */
  private def conflictingReceiverEvidence(
    callable: TypeFn,
    receiver: TypeVariable,
    locals:   List[TypeVariable],
    explicit: Option[List[Type]],
    initial:  UseEvidence,
    later:    List[UseEvidence],
    index:    ResolvablesIndex
  ): SignatureEvidence =
    val recovered = GenericInference.inferUses(
      callable,
      receiver :: locals,
      initial.locatedCall,
      later.map(_.locatedCall),
      explicit,
      locals,
      index,
      Some(receiver)
    )
    recovered.toOption.fold[SignatureEvidence](SignatureEvidence.Incompatible) { solution =>
      val requirements = solution.observations.map(o => ProtocolTypeRequirement(o.tpe, o.source))
      val ordered      = distinctRequirements(requirements, index)
      ordered match
        case first :: rest
            if rest
              .exists(r => !TypeCompatibility.same(first.concreteType, r.concreteType, index)) =>
          SignatureEvidence.Conflicting(ordered, solution.substitution)
        case _ => SignatureEvidence.Incompatible
    }

  private def signatureType(signature: ProtocolSignature): Option[TypeFn] =
    signature.params.traverse(_.typeAsc).map { inputs =>
      val parameters = NonEmptyList
        .fromList(inputs)
        .getOrElse(NonEmptyList.one(TypeRef(signature.source, "Unit", stdlibTypeId("Unit"))))
      TypeFn(signature.source, parameters, signature.returnType)
    }

  private def matchProtocolParameter(
    registered:     ProtocolSignatureCandidates,
    applications:   List[ApplicationEvidence],
    expectedType:   Option[Type],
    index:          ResolvablesIndex,
    uses:           List[UseEvidence],
    expectedSource: SourceOrigin
  ): SignatureMatch =
    val initial = inferInstanceType(
      registered.protocol,
      registered.signature,
      applications,
      expectedType,
      index,
      expectedSource = expectedSource
    )
    val gathered = uses.foldLeft(initial) { (known, use) =>
      inferInstanceType(
        registered.protocol,
        registered.signature,
        use.applications,
        use.expectedType,
        index,
        known,
        use.source
      )
    }

    val evidence = gathered match
      case SignatureEvidence.Concrete(tpe, requirements) =>
        SignatureEvidence.Concrete(tpe, distinctRequirements(requirements.reverse, index))
      case SignatureEvidence.Conflicting(requirements, localTypes) =>
        SignatureEvidence.Conflicting(distinctRequirements(requirements.reverse, index), localTypes)
      case other => other

    SignatureMatch(registered, evidence, implementationIds(registered, evidence, index))

  private def implementationIds(
    registered: ProtocolSignatureCandidates,
    evidence:   SignatureEvidence,
    index:      ResolvablesIndex
  ): List[String] = evidence match
    case SignatureEvidence.Concrete(tpe, _) =>
      val key = ProtocolRegistry.typeKey(tpe, index).toOption
      registered.implementations.filter(i => key.contains(i.typeKey)).flatMap(_.binding.id)
    case _ => Nil

  /** Retains the first occurrence of each concrete requirement at its written site. Repeated
    * prefixes from uses of a stored callable can contribute the same site and type.
    */
  private def distinctRequirements(
    requirements: List[ProtocolTypeRequirement],
    index:        ResolvablesIndex
  ): List[ProtocolTypeRequirement] =

    val empty = (Set.empty[(SourceOrigin, ProtocolTypeKey)], List.empty[ProtocolTypeRequirement])
    val (_, retained) = requirements.foldLeft(empty) { case ((seen, result), requirement) =>
      ProtocolRegistry.typeKey(requirement.concreteType, index).toOption match
        case Some(key) =>
          val identity = (requirement.source, key)
          if seen.contains(identity) then (seen, result)
          else (seen + identity, requirement :: result)

        case None => (seen, requirement :: result)
    }

    retained.reverse

  /** Infers the protocol parameter for a signature without member-local binders. Known arguments
    * and the expected remaining type supply the evidence. For `choose(seed: Int, value: 'T): 'T`,
    * the second argument can establish 'T even when the first argument leaves it unknown. A nullary
    * `make(): 'T` needs an expected result; the number of registered instances supplies no type
    * evidence. The initial evidence carries constraints from other uses of the same binding;
    * incompatible evidence cannot become compatible again.
    */
  def inferInstanceType(
    protocol:       Protocol,
    signature:      ProtocolSignature,
    applications:   List[ApplicationEvidence],
    expectedType:   Option[Type],
    index:          ResolvablesIndex,
    initial:        SignatureEvidence = SignatureEvidence.Unknown,
    expectedSource: SourceOrigin      = SourceOrigin.Synth
  ): SignatureEvidence =
    signatureType(signature).fold[SignatureEvidence](SignatureEvidence.Incompatible) { callable =>
      def matchType(
        wanted:   Type,
        actual:   Type,
        source:   SourceOrigin,
        evidence: SignatureEvidence
      ): SignatureEvidence =
        if evidence == SignatureEvidence.Incompatible then evidence
        else
          (TypeQueries.canonical(wanted, index), TypeQueries.canonical(actual, index)) match
            case (Some(variable: TypeVariable), Some(concrete))
                if isProtocolParameter(variable, protocol) =>
              evidence match
                case SignatureEvidence.Unknown =>
                  SignatureEvidence.Concrete(
                    concrete,
                    List(ProtocolTypeRequirement(actual, source))
                  )

                case SignatureEvidence.Concrete(known, requirements) =>
                  val combined = ProtocolTypeRequirement(actual, source) :: requirements
                  if TypeCompatibility.same(known, concrete, index) then
                    SignatureEvidence.Concrete(known, combined)
                  else SignatureEvidence.Conflicting(combined)

                case SignatureEvidence.Conflicting(requirements, localTypes) =>
                  SignatureEvidence.Conflicting(
                    ProtocolTypeRequirement(actual, source) :: requirements,
                    localTypes
                  )

                case SignatureEvidence.Incompatible => evidence

            case (Some(left: TypeFn), Some(right: TypeFn))
                if left.paramTypes.size == right.paramTypes.size =>
              matchChildren(left, right, source, evidence)

            case (Some(left: TypeTuple), Some(right: TypeTuple))
                if left.elements.size == right.elements.size =>
              matchChildren(left, right, source, evidence)

            case (Some(left), Some(right)) if TypeCompatibility.same(left, right, index) => evidence
            case _ => SignatureEvidence.Incompatible

      def matchChildren(
        wanted:   Type,
        actual:   Type,
        source:   SourceOrigin,
        evidence: SignatureEvidence
      ): SignatureEvidence =
        TypeTraversal.children(wanted).zip(TypeTraversal.children(actual)).foldLeft(evidence) {
          case (current, (left, right)) => matchType(left, right, source, current)
        }

      def consume(
        remaining: Type,
        arguments: List[ApplicationEvidence],
        evidence:  SignatureEvidence
      ): SignatureEvidence = arguments match

        case Nil => expectedType.fold(evidence)(matchType(remaining, _, expectedSource, evidence))

        case application :: rest =>
          val constrained = application.calleeAscriptions.foldLeft(evidence) {
            (current, expected) =>
              matchType(remaining, expected, expected.source, current)
          }
          TypeQueries.canonical(remaining, index) match

            case Some(function: TypeFn) =>
              val next = application.argumentType
                .fold(constrained)(
                  matchType(function.paramTypes.head, _, application.source, constrained)
                )
              val result = remainingCallableType(function, function.source)
              consume(result, rest, next)

            case Some(variable: TypeVariable) if isProtocolParameter(variable, protocol) =>
              constrained match

                case SignatureEvidence.Concrete(known, _) => consume(known, arguments, constrained)

                case SignatureEvidence.Unknown =>
                  (arguments, expectedType) match
                    // One supplied argument and a non-callable result determine a complete
                    // returned function. Longer chains can have different invocation boundaries.
                    case (List(ApplicationEvidence(Some(input), _, source, _)), Some(result))
                        if !TypeQueries.canonical(result, index).exists(_.isInstanceOf[TypeFn]) =>
                      val concrete = TypeFn(variable.source, NonEmptyList.one(input), result)
                      SignatureEvidence.Concrete(
                        concrete,
                        List(
                          ProtocolTypeRequirement(concrete, expectedSource),
                          ProtocolTypeRequirement(concrete, source)
                        )
                      )
                    case _ => SignatureEvidence.Unknown

                case SignatureEvidence.Conflicting(_, _) | SignatureEvidence.Incompatible =>
                  constrained

            case _ => SignatureEvidence.Incompatible

      consume(callable, applications, initial)
    }

  private def isProtocolParameter(variable: TypeVariable, protocol: Protocol): Boolean =
    variable.resolvedId.nonEmpty && variable.resolvedId == protocol.typeParameter.resolvedId

  /** One local use constrains the same callable as every other use of its binding. Its source
    * identifies the use supplying the expected remaining type, including a typed consumer.
    */
  case class UseEvidence(
    applications: List[ApplicationEvidence],
    expectedType: Option[Type],
    source:       SourceOrigin = SourceOrigin.Synth
  ):
    def genericCall: GenericInference.Call =
      GenericInference.Call(
        applications.map(_.inferenceType),
        expectedType,
        applications.map(_.calleeAscriptions)
      )

    def locatedCall: GenericInference.Call =
      genericCall.copy(argumentSources = applications.map(_.source), resultSource = source)

  /** Check concrete callable alternatives against every independently known local constraint. */
  def matchesUses(tpe: Type, uses: List[UseEvidence], index: ResolvablesIndex): Boolean =

    def matches(actual: Type, expected: Type): Boolean =
      TypeCompatibility.same(actual, expected, index)

    def consume(remaining: Type, applications: List[ApplicationEvidence]): Option[Type] =
      applications match
        case Nil => remaining.some

        case application :: rest =>
          TypeQueries.canonical(remaining, index).collect { case fn: TypeFn => fn }.flatMap { fn =>
            val compatible = application.calleeAscriptions.forall(matches(remaining, _)) &&
              application.argumentType.forall(matches(fn.paramTypes.head, _))
            val result = remainingCallableType(fn, fn.source)
            Option.when(compatible)(result).flatMap(consume(_, rest))
          }

    uses.forall { use =>
      consume(tpe, use.applications).exists { remaining =>
        use.expectedType.forall(matches(remaining, _))
      }
    }

  /** One supplied argument and its source site, with annotations on the callee at this boundary.
    * Each written annotation retains its own source when constraining the current callable.
    */
  case class ApplicationEvidence(
    argumentType:      Option[Type],
    calleeAscriptions: List[Type],
    source:            SourceOrigin = SourceOrigin.Synth,
    genericScheme:     Option[Type] = None
  ):
    def inferenceType: Option[Type] = argumentType.orElse(genericScheme)

  /** Type evidence for one signature and the stable implementation IDs matching that evidence. A
    * concrete type can have no implementation; expression checking still decides applicability.
    */
  case class SignatureMatch(
    registered:        ProtocolSignatureCandidates,
    evidence:          SignatureEvidence,
    implementationIds: List[String],
    inference:         Option[Either[GenericInference.Failure, MemberCall]] = None
  ):
    def memberCall: Option[MemberCall] = inference.flatMap(_.toOption)

  /** Lexically visible ordinary declarations and protocol signatures, before type selection. */
  case class CandidateSet(
    declarationIds: List[String],
    signatures:     List[ProtocolSignatureCandidates]
  )

  /** Requirements on one protocol parameter retain the argument and annotation sites that supply
    * them. Concrete requirements agree on one type; Conflicting retains different required types.
    * Conflicting retains solved member-local arguments for checking the signature's requirements.
    * Incompatible rejects a fixed parameter or callable shape independently of those requirements.
    * Evidence does not choose an implementation or use the number of registered instances.
    */
  enum SignatureEvidence derives CanEqual:
    case Unknown
    case Concrete(tpe: Type, requirements: List[ProtocolTypeRequirement])
    case Conflicting(
      requirements: List[ProtocolTypeRequirement],
      localTypes:   Map[String, Type] = Map.empty
    )
    case Incompatible
