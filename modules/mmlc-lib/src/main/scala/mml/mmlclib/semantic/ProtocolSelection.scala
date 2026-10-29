package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*

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
    */
  def matchSignatures(
    choices:      CandidateSet,
    applications: List[ApplicationEvidence],
    expectedType: Option[Type],
    index:        ResolvablesIndex,
    uses:         List[UseEvidence] = Nil
  ): List[SignatureMatch] =
    choices.signatures.map { registered =>
      val initial = inferInstanceType(
        registered.protocol,
        registered.signature,
        applications,
        expectedType,
        index
      )
      val evidence = uses.foldLeft(initial) { (known, use) =>
        inferInstanceType(
          registered.protocol,
          registered.signature,
          use.applications,
          use.expectedType,
          index,
          known
        )
      }
      val implementations = evidence match
        case SignatureEvidence.Concrete(tpe) =>
          val key = ProtocolRegistry.typeKey(tpe, index).toOption
          registered.implementations
            .filter(implementation => key.contains(implementation.typeKey))
            .flatMap(_.binding.id)
        case _ => Nil
      SignatureMatch(registered, evidence, implementations)
    }

  /** Infer the protocol parameter from known arguments and the expected remaining signature. For
    * `choose(seed: Int, value: 'T): 'T`, the second argument can establish 'T even when the first
    * application is ambiguous. A nullary `make(): 'T` needs an expected result; the number of
    * registered instances supplies no type evidence. The initial evidence carries constraints from
    * other uses of the same binding; incompatible evidence cannot become compatible again.
    */
  def inferInstanceType(
    protocol:     Protocol,
    signature:    ProtocolSignature,
    applications: List[ApplicationEvidence],
    expectedType: Option[Type],
    index:        ResolvablesIndex,
    initial:      SignatureEvidence = SignatureEvidence.Unknown
  ): SignatureEvidence =
    val parameters = signature.params.traverse(_.typeAsc)
    parameters.fold[SignatureEvidence](SignatureEvidence.Incompatible) { types =>
      val inputs = NonEmptyList
        .fromList(types)
        .getOrElse(
          NonEmptyList.one(TypeRef(signature.source, "Unit", stdlibTypeId("Unit")))
        )
      val callable = TypeFn(signature.source, inputs, signature.returnType)

      def matchType(wanted: Type, actual: Type, evidence: SignatureEvidence): SignatureEvidence =
        if evidence == SignatureEvidence.Incompatible then evidence
        else
          (TypeUtils.canonical(wanted, index), TypeUtils.canonical(actual, index)) match
            case (Some(variable: TypeVariable), Some(concrete))
                if variable.name == protocol.typeParameter.name =>
              evidence match
                case SignatureEvidence.Unknown => SignatureEvidence.Concrete(concrete)
                case SignatureEvidence.Concrete(known) =>
                  if TypeCompatibility.same(known, concrete, index) then evidence
                  else SignatureEvidence.Incompatible
                case SignatureEvidence.Incompatible => evidence

            case (Some(left: TypeFn), Some(right: TypeFn))
                if left.paramTypes.size == right.paramTypes.size =>
              matchChildren(left, right, evidence)

            case (Some(left: TypeTuple), Some(right: TypeTuple))
                if left.elements.size == right.elements.size =>
              matchChildren(left, right, evidence)

            case (Some(left), Some(right)) if TypeCompatibility.same(left, right, index) => evidence
            case _ => SignatureEvidence.Incompatible

      def matchChildren(
        wanted:   Type,
        actual:   Type,
        evidence: SignatureEvidence
      ): SignatureEvidence =
        TypeTraversal.children(wanted).zip(TypeTraversal.children(actual)).foldLeft(evidence) {
          case (current, (left, right)) => matchType(left, right, current)
        }

      def consume(
        remaining: Type,
        arguments: List[ApplicationEvidence],
        evidence:  SignatureEvidence
      ): SignatureEvidence = arguments match

        case Nil => expectedType.fold(evidence)(matchType(remaining, _, evidence))

        case application :: rest =>
          val constrained = application.calleeAscriptions.foldLeft(evidence) {
            (current, expected) =>
              matchType(remaining, expected, current)
          }
          TypeUtils.canonical(remaining, index) match

            case Some(function: TypeFn) =>
              val next = application.argumentType
                .fold(constrained)(matchType(function.paramTypes.head, _, constrained))
              val result = remainingCallableType(function, function.source)
              consume(result, rest, next)

            case Some(variable: TypeVariable) if variable.name == protocol.typeParameter.name =>
              constrained match

                case SignatureEvidence.Concrete(known) => consume(known, arguments, constrained)

                case SignatureEvidence.Unknown =>
                  (arguments, expectedType) match
                    // One supplied argument and a non-callable result determine a complete
                    // returned function. Longer chains can have different invocation boundaries.
                    case (List(ApplicationEvidence(Some(input), _)), Some(result))
                        if !TypeUtils.canonical(result, index).exists(_.isInstanceOf[TypeFn]) =>
                      SignatureEvidence.Concrete(
                        TypeFn(variable.source, NonEmptyList.one(input), result)
                      )
                    case _ => SignatureEvidence.Unknown

                case SignatureEvidence.Incompatible => constrained

            case _ => SignatureEvidence.Incompatible

      consume(callable, applications, initial)
    }

  /** One local use constrains the same callable as every other use of its binding. */
  case class UseEvidence(applications: List[ApplicationEvidence], expectedType: Option[Type])

  /** Check concrete callable alternatives against every independently known local constraint. */
  def matchesUses(tpe: Type, uses: List[UseEvidence], index: ResolvablesIndex): Boolean =

    def matches(actual: Type, expected: Type): Boolean =
      TypeCompatibility.same(actual, expected, index)

    def consume(remaining: Type, applications: List[ApplicationEvidence]): Option[Type] =
      applications match
        case Nil => remaining.some

        case application :: rest =>
          TypeUtils.canonical(remaining, index).collect { case fn: TypeFn => fn }.flatMap { fn =>
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

  /** Source annotations constrain the current callee before this argument is consumed. */
  case class ApplicationEvidence(argumentType: Option[Type], calleeAscriptions: List[Type])

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

  /** Evidence for the protocol parameter: Unknown has no concrete anchor, Concrete records the type
    * required by arguments or annotations, and Incompatible means those requirements conflict.
    * Evidence does not choose an implementation or use the number of registered instances.
    */
  enum SignatureEvidence derives CanEqual:
    case Unknown
    case Concrete(tpe: Type)
    case Incompatible
