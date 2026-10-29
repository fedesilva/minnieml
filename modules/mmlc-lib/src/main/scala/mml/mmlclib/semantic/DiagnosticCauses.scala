package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.errors.DiagnosticId

/** Result failures known so far, and whether any result dependency has unattributed evidence.
  * Complete evidence can support a rule's consequence proof; it does not prove availability.
  */
enum CauseEvidence derives CanEqual:
  case Complete(roots: Vector[DiagnosticId])
  case Incomplete(roots: Vector[DiagnosticId])

  def knownRoots: Vector[DiagnosticId] = this match
    case Complete(roots) => roots
    case Incomplete(roots) => roots

  def isComplete: Boolean = this match
    case Complete(_) => true
    case Incomplete(_) => false

  def combine(other: CauseEvidence): CauseEvidence =
    if knownRoots.isEmpty && other.knownRoots.isEmpty then
      if isComplete && other.isComplete then CauseEvidence.empty else CauseEvidence.unknown
    else
      val roots = (knownRoots ++ other.knownRoots).distinct.sortBy(_.value)
      if isComplete && other.isComplete then Complete(roots) else Incomplete(roots)

  /** Incomplete evidence preserves attribution but cannot justify hiding a new diagnostic. */
  def provenRoots: Vector[DiagnosticId] = if isComplete then knownRoots else Vector.empty

object CauseEvidence:

  val empty:   CauseEvidence = CauseEvidence.Complete(Vector.empty)
  val unknown: CauseEvidence = CauseEvidence.Incomplete(Vector.empty)

/** Result-directed cause propagation through resolved definitions and local bindings. */
final case class DiagnosticCauses private (
  bindings: Map[String, CauseEvidence],
  index:    ResolvablesIndex,
  types:    Map[String, CauseEvidence]
):

  private def combine(values: Iterable[CauseEvidence]): CauseEvidence =
    values.foldLeft(CauseEvidence.empty)(_.combine(_))

  private def carrier(roots: Vector[DiagnosticId]): CauseEvidence =
    if roots.isEmpty then CauseEvidence.unknown
    else CauseEvidence.Complete(roots.distinct.sortBy(_.value))

  /** Attribution only: known roots survive even when another dependency is unexplained. */
  def knownRootsOf(term: Term): Vector[DiagnosticId] = evidenceOf(term).knownRoots

  /** Roots usable by a rule that proves its new diagnostic depends on this failed result. */
  def provenRootsOf(term: Term): Vector[DiagnosticId] = evidenceOf(term).provenRoots

  /** Type attribution alone does not establish a complete explanation or a usable type. */
  def knownRootsOfType(tpe: Type): Vector[DiagnosticId] = evidenceOfType(tpe).knownRoots

  /** Type roots available for a consequence proof only when all dependencies are explained. */
  def provenRootsOfType(tpe: Type): Vector[DiagnosticId] = evidenceOfType(tpe).provenRoots

  /** Distinguish a known failed type from an unresolved or unsupported type dependency. */
  def evidenceOfType(tpe: Type): CauseEvidence = typeEvidence(tpe, Set.empty)

  private def annotations(value: Typeable): CauseEvidence =
    val calleeAscriptions = value match
      case app: App => app.calleeAscriptions
      case _ => Nil
    combine(
      (value.typeSpec.toList ++ value.typeAsc.toList ++ calleeAscriptions).map(evidenceOfType)
    )

  /** A generated local inherits its initializer's evidence without depending on its name. */
  def withBinding(parameter: FnParam, value: Expr): DiagnosticCauses =
    val evidence = evidenceOf(value).combine(annotations(parameter))
    copy(
      bindings = parameter.id.fold(bindings)(id => bindings.updated(id, evidence)),
      index    = index.updated(parameter)
    )

  /** Reuse complete, failure-free summaries as one binding acquires checked types. */
  def updatedModule(module: Module, checkedBinding: Bnd): DiagnosticCauses =
    if index.resolvableTypes != module.resolvables.resolvableTypes then
      DiagnosticCauses.fromModule(module)
    else
      val updated     = copy(index = module.resolvables)
      val definitions = DiagnosticCauses.definitions(checkedBinding)
      val previous = checkedBinding.id.flatMap(index.lookup).collect { case binding: Bnd =>
        DiagnosticCauses.definitions(binding).map(_.id).toSet
      }
      val identitiesPreserved = previous.contains(definitions.map(_.id).toSet)
      val evidencePreserved = identitiesPreserved && definitions.forall { definition =>
        bindings.getOrElse(definition.id, CauseEvidence.empty) == CauseEvidence.empty &&
        updated
          .evidenceOf(definition.value)
          .combine(updated.annotations(definition.declaration)) == CauseEvidence.empty
      }

      if evidencePreserved then updated
      else DiagnosticCauses.fromModule(module, types)

  /** Follow the result, excluding discarded initializers unless their bound values are used. */
  def evidenceOf(term: Term): CauseEvidence =
    val result = term match

      case invalid: InvalidExpression => carrier(invalid.causes)
      case error:   ParseTermError => carrier(error.causes)

      case expr: Expr =>
        expr.terms match
          case List(value) => evidenceOf(value)
          // A flat failed region has no established result or application structure.
          case terms => combine(terms.map(evidenceOf)).combine(CauseEvidence.unknown)

      case group:   TermGroup => evidenceOf(group.inner)
      case yielded: YieldOwnership => evidenceOf(yielded.value).combine(evidenceOf(yielded.owns))

      case binding: BindOwnership =>
        withBinding(binding.valueParam, binding.initializer)
          .withBinding(binding.witnessParam, binding.initializer)
          .evidenceOf(binding.body)

      case app: App =>
        app.fn match
          case scope: Lambda =>
            val context = scope.params.headOption.fold(this)(withBinding(_, app.arg))
            context.evidenceOf(scope.body)
          case _ => evidenceOf(app.fn).combine(evidenceOf(app.arg))

      case ref: Ref =>
        val source = ref.qualifier match
          case Some(qualifier) => evidenceOf(qualifier)
          case None =>
            ref.resolvedId.fold(CauseEvidence.unknown) { id =>
              bindings.getOrElse(id, CauseEvidence.empty)
            }
        val declaration = ref.resolvedId
          .flatMap(index.lookup)
          .map {
            case value: Typeable => annotations(value)
            case field: Field => evidenceOfType(field.typeSpec)
            case _ => CauseEvidence.empty
          }
          .getOrElse(CauseEvidence.empty)
        source.combine(declaration)

      case lambda: Lambda =>
        combine(lambda.params.map(annotations))
          .combine(combine(lambda.captures.map(capture => evidenceOf(capture.ref))))
          .combine(evidenceOf(lambda.body))

      case cond: Cond =>
        evidenceOf(cond.cond).combine(evidenceOf(cond.ifTrue)).combine(evidenceOf(cond.ifFalse))

      case tuple:       Tuple => combine(tuple.elements.toList.map(evidenceOf))
      case destruction: Destruction => evidenceOf(destruction.operand)
      case _: LiteralValue | _: Placeholder | _: Hole | _: DataConstructor | _: NativeImpl =>
        CauseEvidence.empty

    // A failed carrier is authoritative; partial editor types do not qualify its result evidence.
    term match
      case _: InvalidExpression | _: ParseTermError => result
      case _ => result.combine(annotations(term))

  private def typeEvidence(tpe: Type, seen: Set[String]): CauseEvidence = tpe match

    case error:   ParseTypeError => carrier(error.causes)
    case invalid: TypeResolutionError => carrier(invalid.causes)

    case ref: TypeRef =>
      ref.resolvedId.flatMap(types.get) match
        case Some(evidence) => evidence
        case None =>
          ref.resolvedId match
            case Some(id) if seen.contains(id) =>
              // Nominal/structural recursion can terminate here; an alias-only cycle is unresolved.
              if TypeUtils.canonical(ref, index).isEmpty then CauseEvidence.unknown
              else CauseEvidence.empty

            case Some(id) =>
              val next = seen + id
              index.lookupType(id) match
                case Some(alias: TypeAlias) => typeEvidence(alias.typeRef, next)
                case Some(definition: TypeDef) =>
                  combine(definition.typeSpec.toList.map(typeEvidence(_, next)))
                case Some(struct: TypeStruct) =>
                  combine(struct.fields.map(field => typeEvidence(field.typeSpec, next)))
                case None => CauseEvidence.unknown

            case None => CauseEvidence.unknown

    case function: TypeFn =>
      combine((function.paramTypes.toList :+ function.returnType).map(typeEvidence(_, seen)))

    case tuple: TypeTuple => combine(tuple.elements.map(typeEvidence(_, seen)))
    case group: TypeGroup => combine(group.types.map(typeEvidence(_, seen)))

    case application: TypeApplication =>
      combine((application.base :: application.args).map(typeEvidence(_, seen)))

    case union:        Union => combine(union.types.map(typeEvidence(_, seen)))
    case intersection: Intersection => combine(intersection.types.map(typeEvidence(_, seen)))
    case scheme:       TypeScheme => typeEvidence(scheme.bodyType, seen)
    case native: NativeStruct => combine(native.fields.map(field => typeEvidence(field._2, seen)))
    case struct: TypeStruct =>
      combine(struct.fields.map(field => typeEvidence(field.typeSpec, seen)))
    case record: TypeOpenRecord => combine(record.fields.map(field => typeEvidence(field._2, seen)))

    // Refinement validity is not established by the current type system.
    case _: TypeRefinement => CauseEvidence.unknown
    case _: NativePrimitive | _: NativePointer | _: TypeUnit | _: TypeVariable =>
      CauseEvidence.empty

object DiagnosticCauses:

  private case class Definition(id: String, value: Expr, declaration: Typeable)

  val empty: DiagnosticCauses = DiagnosticCauses(Map.empty, ResolvablesIndex(), Map.empty)

  private def definitions(binding: Bnd): List[Definition] =
    val locals = TermTraversal
      .collect(binding.value) {
        case app: App =>
          app.fn match
            case scope: Lambda => scope.params.headOption.toList.map(_ -> app.arg)
            case _ => Nil
        case local: BindOwnership =>
          List(local.valueParam -> local.initializer, local.witnessParam -> local.initializer)
      }
      .flatten
      .flatMap { (parameter, value) =>
        parameter.id.map(id => Definition(id, value, parameter))
      }
    binding.id.map(id => Definition(id, binding.value, binding)).toList ++ locals

  /** Resolve forward and recursive binding dependencies to a monotone fixed point. */
  def fromModule(module: Module): DiagnosticCauses =
    val uncached = DiagnosticCauses(Map.empty, module.resolvables, Map.empty)
    // Each nominal entry includes all reachable fields, including mutually recursive fields.
    val types = module.resolvables.resolvableTypes.keysIterator.map { id =>
      val reference = TypeRef(SourceOrigin.Synth, id, Some(id))
      id -> uncached.evidenceOfType(reference)
    }.toMap
    fromModule(module, types)

  private def fromModule(module: Module, types: Map[String, CauseEvidence]): DiagnosticCauses =
    val definitions = module.members
      .collect { case binding: Bnd => binding }
      .flatMap(DiagnosticCauses.definitions)

    // Each iteration only adds existing root IDs or incomplete evidence, so the finite map settles.
    @scala.annotation.tailrec
    def discover(current: DiagnosticCauses): DiagnosticCauses =
      val bindings = definitions.foldLeft(current.bindings) { case (known, definition) =>
        val evidence = current
          .evidenceOf(definition.value)
          .combine(current.annotations(definition.declaration))
        val combined = known.getOrElse(definition.id, CauseEvidence.empty).combine(evidence)
        if combined == CauseEvidence.empty then known
        else known.updated(definition.id, combined)
      }
      if bindings == current.bindings then current
      else discover(current.copy(bindings = bindings))

    discover(DiagnosticCauses(Map.empty, module.resolvables, types))
