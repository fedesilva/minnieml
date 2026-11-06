package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.TypeQueries

/** Candidate discovery and signature evidence are independent of expression checking. */
object ProtocolSelection:

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

  /** Match registered signatures using source type evidence, without selecting by instance count.
    * Local uses accumulate constraints on one protocol parameter before implementation lookup.
    * Conflicting requirements retain the types and source sites needed to explain the failure.
    * Returned requirements follow evidence encounter order, with repeated source/type pairs
    * removed.
    */
  def matchSignatures(
    choices:        CandidateSet,
    applications:   List[ApplicationEvidence],
    expectedType:   Option[Type],
    index:          ResolvablesIndex,
    uses:           List[UseEvidence] = Nil,
    expectedSource: SourceOrigin      = SourceOrigin.Synth
  ): List[SignatureMatch] =
    choices.signatures.map { registered =>
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
          SignatureEvidence.Concrete(tpe, orderedRequirements(requirements, index))
        case SignatureEvidence.Conflicting(requirements) =>
          SignatureEvidence.Conflicting(orderedRequirements(requirements, index))
        case other => other

      val implementations = evidence match
        case SignatureEvidence.Concrete(tpe, _) =>
          val key = ProtocolRegistry.typeKey(tpe, index).toOption
          registered.implementations
            .filter(implementation => key.contains(implementation.typeKey))
            .flatMap(_.binding.id)
        case _ => Nil
      SignatureMatch(registered, evidence, implementations)
    }

  /** Publish newest-first inference requirements in evidence order, retaining distinct source
    * sites. Repeated supplied prefixes at the same site and type contribute one requirement.
    */
  private def orderedRequirements(
    requirements: List[ProtocolTypeRequirement],
    index:        ResolvablesIndex
  ): List[ProtocolTypeRequirement] =

    val empty = (Set.empty[(SourceOrigin, ProtocolTypeKey)], List.empty[ProtocolTypeRequirement])
    val (_, retained) = requirements.reverseIterator.foldLeft(empty) {
      case ((seen, result), requirement) =>
        ProtocolRegistry.typeKey(requirement.concreteType, index).toOption match
          case Some(key) =>
            val identity = (requirement.source, key)
            if seen.contains(identity) then (seen, result)
            else (seen + identity, requirement :: result)

          case None => (seen, requirement :: result)
    }

    retained.reverse

  /** Infer the protocol parameter from known arguments and the expected remaining signature. For
    * `choose(seed: Int, value: 'T): 'T`, the second argument can establish 'T even when the first
    * application is ambiguous. A nullary `make(): 'T` needs an expected result; the number of
    * registered instances supplies no type evidence. The initial evidence carries constraints from
    * other uses of the same binding; incompatible evidence cannot become compatible again.
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
    val parameters = signature.params.traverse(_.typeAsc)
    parameters.fold[SignatureEvidence](SignatureEvidence.Incompatible) { types =>
      val inputs = NonEmptyList
        .fromList(types)
        .getOrElse(
          NonEmptyList.one(TypeRef(signature.source, "Unit", stdlibTypeId("Unit")))
        )
      val callable = TypeFn(signature.source, inputs, signature.returnType)

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
                if variable.name == protocol.typeParameter.name =>
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

                case SignatureEvidence.Conflicting(requirements) =>
                  SignatureEvidence.Conflicting(
                    ProtocolTypeRequirement(actual, source) :: requirements
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

            case Some(variable: TypeVariable) if variable.name == protocol.typeParameter.name =>
              constrained match

                case SignatureEvidence.Concrete(known, _) => consume(known, arguments, constrained)

                case SignatureEvidence.Unknown =>
                  (arguments, expectedType) match
                    // One supplied argument and a non-callable result determine a complete
                    // returned function. Longer chains can have different invocation boundaries.
                    case (List(ApplicationEvidence(Some(input), _, source)), Some(result))
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

                case SignatureEvidence.Conflicting(_) | SignatureEvidence.Incompatible =>
                  constrained

            case _ => SignatureEvidence.Incompatible

      consume(callable, applications, initial)
    }

  /** One local use constrains the same callable as every other use of its binding. Its source
    * identifies the use supplying the expected remaining type, including a typed consumer.
    */
  case class UseEvidence(
    applications: List[ApplicationEvidence],
    expectedType: Option[Type],
    source:       SourceOrigin = SourceOrigin.Synth
  )

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
    source:            SourceOrigin = SourceOrigin.Synth
  )

  /** Type evidence for one signature and the stable implementation IDs matching that evidence. A
    * concrete type can have no implementation; expression checking still decides applicability.
    */
  case class SignatureMatch(
    registered:        ProtocolSignatureCandidates,
    evidence:          SignatureEvidence,
    implementationIds: List[String]
  )

  /** Lexically visible ordinary declarations and protocol signatures, before type selection. */
  case class CandidateSet(
    declarationIds: List[String],
    signatures:     List[ProtocolSignatureCandidates]
  )

  /** Requirements on one protocol parameter retain the argument and annotation sites that supply
    * them. Concrete requirements agree on one type; Conflicting retains different required types.
    * Incompatible rejects a fixed parameter or callable shape independently of those requirements.
    * Evidence does not choose an implementation or use the number of registered instances.
    */
  enum SignatureEvidence derives CanEqual:
    case Unknown
    case Concrete(tpe: Type, requirements: List[ProtocolTypeRequirement])
    case Conflicting(requirements: List[ProtocolTypeRequirement])
    case Incompatible
