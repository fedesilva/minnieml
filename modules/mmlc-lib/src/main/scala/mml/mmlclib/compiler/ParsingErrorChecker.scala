package mml.mmlclib.compiler

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}
import mml.mmlclib.semantic.SemanticError

/** Lifts recovered parser errors from members, nested expressions, and written types. */
object ParsingErrorChecker:

  private val phaseName = "mml.mmlclib.compiler.ParsingErrorChecker"

  /** Mirrored annotation slots share one parsed occurrence, even within copied type containers. */
  private final class TypeOccurrence(val error: ParseTypeError):

    override def hashCode(): Int = System.identityHashCode(error)

    override def equals(other: Any): Boolean = other match
      case occurrence: TypeOccurrence => error eq occurrence.error
      case _ => false

  private case class StampedParsing(
    diagnostics: DiagnosticStore,
    typeErrors:  Map[TypeOccurrence, ParseTypeError] = Map.empty
  )

  private case class StampedTypes(ascription: Option[Type], computed: Option[Type])

  private type Stamping[A] = State[StampedParsing, A]

  /** Register fresh parser output without changing recovered syntax or source evidence. */
  def checkModule(state: CompilerState): CompilerState =
    val (stamped, members) = state.module.members
      .traverse(stampMember)
      .run(StampedParsing(state.diagnostics))
      .value
    state.copy(diagnostics = stamped.diagnostics).withModule(state.module.copy(members = members))

  private def report(error: SemanticError): Stamping[Vector[DiagnosticId]] = State { current =>
    val (diagnostics, roots) = current.diagnostics.emit(error)
    (current.copy(diagnostics = diagnostics), roots)
  }

  private def stampTypes(value: Typeable): Stamping[StampedTypes] =
    for
      ascription <- value.typeAsc.traverse(stampType)
      computed <- value.typeSpec.traverse(stampType)
    yield StampedTypes(ascription, computed)

  private def stampMember(member: Member): Stamping[Member] = member match

    case error: ParsingMemberError =>
      report(SemanticError.MemberErrorFound(error, phaseName)).as(error)

    case error: ParsingIdError =>
      report(SemanticError.ParsingIdErrorFound(error, phaseName)).as(error)

    case binding: Bnd =>
      for
        value <- stampExpr(binding.value)
        types <- stampTypes(binding)
      yield binding.copy(value = value, typeAsc = types.ascription, typeSpec = types.computed)

    case alias: TypeAlias =>
      for
        target <- stampType(alias.typeRef)
        types <- stampTypes(alias)
      yield alias.copy(typeRef = target, typeAsc = types.ascription, typeSpec = types.computed)

    case definition: TypeDef =>
      stampTypes(definition).map(types =>
        definition.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case struct: TypeStruct => stampStruct(struct).widen

    case module: Module =>
      module.members.traverse(stampMember).map(members => module.copy(members = members))

    case other => other.pure[Stamping]

  private def stampTypeError(error: ParseTypeError): Stamping[ParseTypeError] =
    val occurrence = TypeOccurrence(error)
    State.inspect[StampedParsing, Option[ParseTypeError]](_.typeErrors.get(occurrence)).flatMap {
      case Some(stamped) => stamped.pure[Stamping]
      case None =>
        report(SemanticError.ParseTypeErrorFound(error, phaseName))
          .map(causes => error.copy(causes = causes))
          .flatTap { stamped =>
            State.modify(current =>
              current.copy(typeErrors = current.typeErrors.updated(occurrence, stamped))
            )
          }
    }

  private def stampType(tpe: Type): Stamping[Type] = tpe match

    case error: ParseTypeError => stampTypeError(error).widen

    case function: TypeFn =>
      for
        params <- function.paramTypes.traverse(stampType)
        result <- stampType(function.returnType)
      yield function.copy(paramTypes = params, returnType = result)

    case application: TypeApplication =>
      for
        base <- stampType(application.base)
        args <- application.args.traverse(stampType)
      yield application.copy(base = base, args = args)

    case tuple: TypeTuple =>
      tuple.elements.traverse(stampType).map(elements => tuple.copy(elements = elements))

    case group: TypeGroup => group.types.traverse(stampType).map(types => group.copy(types = types))
    case union: Union => union.types.traverse(stampType).map(types => union.copy(types = types))
    case intersection: Intersection =>
      intersection.types.traverse(stampType).map(types => intersection.copy(types = types))

    case scheme: TypeScheme =>
      stampType(scheme.bodyType).map(body => scheme.copy(bodyType = body))

    case record: TypeOpenRecord =>
      record.fields
        .traverse { (name, tpe) => stampType(tpe).map(name -> _) }
        .map(fields => record.copy(fields = fields))

    case native: NativeStruct =>
      native.fields
        .traverse { (name, tpe) => stampType(tpe).map(name -> _) }
        .map(fields => native.copy(fields = fields))

    case struct: TypeStruct => stampStruct(struct).widen

    case refinement: TypeRefinement =>
      stampExpr(refinement.expr).map(expr => refinement.copy(expr = expr))

    // Unchanged references retain the parser's shared occurrence identity for type resolution.
    case leaf @ (_: TypeRef | _: TypeResolutionError | _: NativePrimitive | _: NativePointer |
        _: TypeUnit | _: TypeVariable) =>
      leaf.pure[Stamping]

  private def stampStruct(struct: TypeStruct): Stamping[TypeStruct] =
    struct.fields
      .traverse { field =>
        stampType(field.typeSpec).map(tpe => field.copy(typeSpec = tpe))
      }
      .map(fields => struct.copy(fields = fields))

  private def stampParam(param: FnParam): Stamping[FnParam] =
    stampTypes(param).map(types =>
      param.copy(typeAsc = types.ascription, typeSpec = types.computed)
    )

  private def stampExpr(expr: Expr): Stamping[Expr] =
    for
      terms <- expr.terms.traverse(stampTerm)
      types <- stampTypes(expr)
    yield expr.copy(terms = terms, typeAsc = types.ascription, typeSpec = types.computed)

  private def stampRef(ref: Ref): Stamping[Ref] =
    for
      qualifier <- ref.qualifier.traverse(stampTerm)
      types <- stampTypes(ref)
    yield ref.copy(qualifier = qualifier, typeAsc = types.ascription, typeSpec = types.computed)

  private def stampLambda(lambda: Lambda): Stamping[Lambda] =
    for
      params <- lambda.params.traverse(stampParam)
      body <- stampExpr(lambda.body)
      types <- stampTypes(lambda)
    yield lambda.copy(
      params   = params,
      body     = body,
      typeAsc  = types.ascription,
      typeSpec = types.computed
    )

  private def stampApp(app: App): Stamping[App] =
    for
      fn <- stampCallee(app.fn)
      arg <- stampExpr(app.arg)
      types <- stampTypes(app)
    yield app.copy(fn = fn, arg = arg, typeAsc = types.ascription, typeSpec = types.computed)

  private def stampCallee(callee: Callee): Stamping[Callee] = callee match
    case ref:    Ref => stampRef(ref).widen
    case lambda: Lambda => stampLambda(lambda).widen
    case app:    App => stampApp(app).widen

  private def stampTerm(term: Term): Stamping[Term] = term match

    case error: ParseTermError =>
      report(SemanticError.ParseTermErrorFound(error, phaseName))
        .map(causes => error.copy(causes = causes))

    case expr:   Expr => stampExpr(expr).widen
    case ref:    Ref => stampRef(ref).widen
    case lambda: Lambda => stampLambda(lambda).widen
    case app:    App => stampApp(app).widen

    case cond: Cond =>
      for
        predicate <- stampExpr(cond.cond)
        yes <- stampExpr(cond.ifTrue)
        no <- stampExpr(cond.ifFalse)
        types <- stampTypes(cond)
      yield cond.copy(
        cond     = predicate,
        ifTrue   = yes,
        ifFalse  = no,
        typeAsc  = types.ascription,
        typeSpec = types.computed
      )

    case group: TermGroup =>
      for
        inner <- stampExpr(group.inner)
        ascription <- group.typeAsc.traverse(stampType)
      yield group.copy(inner = inner, typeAsc = ascription)

    case tuple: Tuple =>
      for
        elements <- tuple.elements.traverse(stampExpr)
        types <- stampTypes(tuple)
      yield tuple.copy(elements = elements, typeAsc = types.ascription, typeSpec = types.computed)

    case invalid: InvalidExpression =>
      for
        original <- stampExpr(invalid.originalExpr)
        types <- stampTypes(invalid)
      yield invalid.copy(
        originalExpr = original,
        typeAsc      = types.ascription,
        typeSpec     = types.computed
      )

    case yielded: YieldOwnership =>
      for
        value <- stampExpr(yielded.value)
        owns <- stampExpr(yielded.owns)
        types <- stampTypes(yielded)
      yield yielded.copy(
        value    = value,
        owns     = owns,
        typeAsc  = types.ascription,
        typeSpec = types.computed
      )

    case binding: BindOwnership =>
      for
        valueParam <- stampParam(binding.valueParam)
        witnessParam <- stampParam(binding.witnessParam)
        initializer <- stampExpr(binding.initializer)
        body <- stampExpr(binding.body)
        types <- stampTypes(binding)
      yield binding.copy(
        valueParam   = valueParam,
        witnessParam = witnessParam,
        initializer  = initializer,
        body         = body,
        typeAsc      = types.ascription,
        typeSpec     = types.computed
      )

    case destruction: Destruction =>
      for
        operand <- stampExpr(destruction.operand)
        computed <- destruction.typeSpec.traverse(stampType)
      yield destruction match
        case value: DestroyClosure => value.copy(operand = operand, typeSpec = computed)
        case value: DestroyClosureEnvironment => value.copy(operand = operand, typeSpec = computed)
        case value: DisarmClosureEnvironment => value.copy(operand = operand, typeSpec = computed)
        case value: DispatchClosureDestructor => value.copy(operand = operand, typeSpec = computed)

    case value: Placeholder =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: Hole =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: LiteralInt =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: LiteralFloat =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: LiteralString =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: LiteralBool =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: LiteralUnit =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: NativeImpl =>
      stampTypes(value).map(types =>
        value.copy(typeAsc = types.ascription, typeSpec = types.computed)
      )

    case value: DataConstructor =>
      value.typeSpec.traverse(stampType).map(computed => value.copy(typeSpec = computed))
