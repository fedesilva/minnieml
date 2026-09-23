package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Failed values do not establish ownership contracts. Independent continuations remain usable. */
final case class ValueAvailability private (
  unavailable: Set[String],
  index:       ResolvablesIndex
):

  def isAvailable(term: Term): Boolean = term match

    case _: InvalidExpression | _: TermError => false
    case _: NativeImpl | _: DataConstructor => true
    case expr: Expr =>
      expr.terms match
        case List(term) => isAvailable(term)
        case _ => false
    case group: TermGroup => isAvailable(group.inner)

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

    def check(current: Type, seen: Set[String]): Boolean =
      val referencedIds = current match
        case ref: TypeRef => ref.resolvedId.toSet
        case _ => Set.empty[String]

      referencedIds.exists(seen.contains) || TypeUtils.canonical(current, index).exists {
        case _:  InvalidType => false
        case fn: TypeFn =>
          val visited = seen ++ referencedIds
          fn.paramTypes.forall(check(_, visited)) && check(fn.returnType, visited)
        case tuple: TypeTuple => tuple.elements.forall(check(_, seen ++ referencedIds))
        case _ => true
      }

    check(tpe, Set.empty)

object ValueAvailability:

  val empty: ValueAvailability = ValueAvailability(Set.empty, ResolvablesIndex())

  def fromModule(module: Module): ValueAvailability =

    val definitions = module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
      val locals = TermTraversal
        .collect(binding.value) { case app: App =>
          app.fn match
            case scope: Lambda => scope.params.headOption.flatMap(_.id).map(_ -> app.arg).toList
            case _ => Nil
        }
        .flatten
      binding.id.map(_ -> binding.value).toList ++ locals
    }

    @scala.annotation.tailrec
    def discover(current: ValueAvailability): ValueAvailability =

      val failed = definitions.collect {
        case (id, value) if !current.isAvailable(value) => id
      }.toSet
      val next = current.copy(unavailable = current.unavailable ++ failed)
      if next.unavailable == current.unavailable then next else discover(next)

    discover(ValueAvailability(Set.empty, module.resolvables))
