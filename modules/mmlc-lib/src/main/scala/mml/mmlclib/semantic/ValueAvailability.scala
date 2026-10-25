package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Failed values do not establish ownership contracts. Independent continuations remain usable. */
final case class ValueAvailability private (
  unavailable:                       Set[String],
  index:                             ResolvablesIndex,
  private val invalidTypes:          Set[String],
  private val namedTypeAvailability: Map[String, Boolean]
):

  /** A generated local inherits its initializer's availability and keeps its resolved identity. */
  def withBinding(parameter: FnParam, value: Expr): ValueAvailability =
    val failed = parameter.id.filter(_ => !isAvailable(value)).toSet
    copy(unavailable = unavailable ++ failed, index = index.updated(parameter))

  /** Add one checked binding to an available prefix; failures require dependency rediscovery. */
  def updatedModule(module: Module, checkedBinding: Bnd): ValueAvailability =
    val current        = withIndex(module.resolvables)
    val unchangedTypes = index.resolvableTypes eq module.resolvables.resolvableTypes
    val availableDeclaration =
      checkedBinding.typeSpec.orElse(checkedBinding.typeAsc).exists(current.typeAvailable)
    if unchangedTypes && unavailable.isEmpty && availableDeclaration &&
      ValueAvailability.definitions(checkedBinding).forall((_, value) => current.isAvailable(value))
    then current
    else ValueAvailability.fromModule(module, current)

  /** Explicit failed type nodes remain distinct from missing or cyclic type information. */
  def containsInvalidType(tpe: Type): Boolean = TypeUtils.containsInvalidType(tpe, invalidTypes)

  private def withIndex(next: ResolvablesIndex): ValueAvailability =
    if index.resolvableTypes eq next.resolvableTypes then copy(index = next)
    else
      val failed = TypeUtils.invalidTypeIds(next)
      val available = next.resolvableTypes.map { (id, declaration) =>
        val reference = TypeRef(SourceOrigin.Synth, declaration.name, Some(id))
        id -> ValueAvailability.canonicalTypeAvailable(reference, next, Map.empty)
      }
      copy(index = next, invalidTypes = failed, namedTypeAvailability = available)

  /** Usable capturing lambdas require an environment even before metadata is attached. */
  def needsClosureEnvironment(lambda: Lambda): Boolean =
    lambda.captures.nonEmpty && isAvailable(lambda)

  def isAvailable(term: Term): Boolean = term match

    case _: InvalidExpression | _: ParseTermError => false
    case _: NativeImpl | _: DataConstructor => true
    case expr: Expr =>
      expr.terms match
        case List(term) => isAvailable(term)
        case _ => false
    case group:   TermGroup => isAvailable(group.inner)
    case yielded: YieldOwnership => isAvailable(yielded.value) && isAvailable(yielded.owns)
    case binding: BindOwnership => isAvailable(binding.body)

    case ref: Ref =>
      val sourceAvailable = ref.qualifier match
        case Some(qualifier) => isAvailable(qualifier)
        case None => ref.resolvedId.exists(id => !unavailable.contains(id))
      val valueType = ref.typeSpec.orElse(
        ref.resolvedId
          .flatMap(index.lookup)
          .collect { case value: Typeable => value }
          .flatMap(value => value.typeSpec.orElse(value.typeAsc))
      )
      sourceAvailable && valueType.exists(typeAvailable)

    case app: App =>
      app.fn match
        case scope: Lambda => isAvailable(scope.body)
        case _ =>
          app.typeSpec.exists(typeAvailable) && isAvailable(app.fn) && isAvailable(app.arg)

    case lambda: Lambda =>
      lambda.params.forall(param => param.typeSpec.orElse(param.typeAsc).exists(typeAvailable)) &&
      lambda.captures.forall(capture => isAvailable(capture.ref)) && isAvailable(lambda.body)

    case cond: Cond =>
      isAvailable(cond.cond) && isAvailable(cond.ifTrue) && isAvailable(cond.ifFalse)
    case tuple: Tuple => tuple.elements.forall(isAvailable)
    case other => other.typeSpec.exists(typeAvailable)

  private def typeAvailable(tpe: Type): Boolean =
    !containsInvalidType(tpe) &&
      ValueAvailability.canonicalTypeAvailable(tpe, index, namedTypeAvailability)

object ValueAvailability:

  val empty: ValueAvailability =
    ValueAvailability(Set.empty, ResolvablesIndex(), Set.empty, Map.empty)

  /** Each named result describes a complete traversal; cycle-local results cannot be reused. */
  private def canonicalTypeAvailable(
    tpe:   Type,
    index: ResolvablesIndex,
    named: Map[String, Boolean]
  ): Boolean =

    def check(current: Type, seen: Set[String]): Boolean =
      val referencedId = current match
        case ref: TypeRef => ref.resolvedId
        case _ => None

      referencedId.exists(seen.contains) || referencedId.flatMap(named.get).getOrElse {
        TypeUtils.canonical(current, index).exists {
          case fn: TypeFn =>
            val visited = seen ++ referencedId
            fn.paramTypes.forall(check(_, visited)) && check(fn.returnType, visited)
          case tuple: TypeTuple => tuple.elements.forall(check(_, seen ++ referencedId))
          case _ => true
        }
      }

    check(tpe, Set.empty)

  def fromModule(module: Module): ValueAvailability =
    fromModule(module, empty)

  /** Reuse named type failures while rebuilding value availability for the supplied module. */
  def fromModule(module: Module, previous: ValueAvailability): ValueAvailability =

    val values = module.members.collect { case binding: Bnd => binding }.flatMap(definitions)

    @scala.annotation.tailrec
    def discover(current: ValueAvailability): ValueAvailability =

      val failed = values.collect {
        case (id, value) if !current.isAvailable(value) => id
      }.toSet
      val next = current.copy(unavailable = current.unavailable ++ failed)
      if next.unavailable == current.unavailable then next else discover(next)

    discover(previous.withIndex(module.resolvables).copy(unavailable = Set.empty))

  private def definitions(binding: Bnd): List[(String, Expr)] =
    val locals = TermTraversal
      .collect(binding.value) {
        case app: App =>
          app.fn match
            case scope: Lambda => scope.params.headOption.flatMap(_.id).map(_ -> app.arg).toList
            case _ => Nil
        case local: BindOwnership =>
          List(local.valueParam, local.witnessParam)
            .flatMap(_.id.map(_ -> local.initializer))
      }
      .flatten
    binding.id.map(_ -> binding.value).toList ++ locals
