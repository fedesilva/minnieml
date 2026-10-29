package mml.mmlclib.semantic

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

object RefResolver:

  /** Resolve all references and retain each failed occurrence alongside its diagnostic roots. */
  def rewriteModule(state: CompilerState): CompilerState =
    val scope = ProtocolLookupScope.module(state.module)
    val (diagnostics, members) = state.module.members
      .traverse { member =>
        resolveMember(member, Context(member, state.module, state.protocols, scope))
      }
      .run(state.diagnostics)
      .value

    state.copy(diagnostics = diagnostics).withModule(state.module.copy(members = members))

  private val phaseName = "mml.mmlclib.semantic.RefResolver"

  private type Resolution[A] = State[DiagnosticStore, A]

  private case class Context(
    member:    Member,
    module:    Module,
    protocols: ProtocolRegistry,
    scope:     ProtocolLookupScope
  ):

    def within(params: List[FnParam]): Context = copy(scope = scope.within(params))

  /** A failed callee stays intact inside the smallest application that can carry its error. */
  private case class ResolvedCallee(value: Callee, causes: Vector[DiagnosticId] = Vector.empty):

    def asTerm: Term =
      if causes.isEmpty then value
      else
        InvalidExpression(
          source       = value.source,
          originalExpr = Expr(value.source, List(value)),
          typeSpec     = value.typeSpec,
          typeAsc      = value.typeAsc,
          causes       = causes
        )

  private def resolveMember(member: Member, context: Context): Resolution[Member] = member match
    case binding: Bnd =>
      resolveExpr(binding.value, context)
        .map(value => binding.copy(value = value))
    case other => other.pure[Resolution]

  private def resolveExpr(expr: Expr, context: Context): Resolution[Expr] =
    expr.terms
      .traverse(resolveTerm(_, context))
      .map(terms => expr.copy(terms = terms))

  private def resolveTerm(term: Term, context: Context): Resolution[Term] = term match

    case ref: Ref =>
      resolveRef(ref, context).map(_.asTerm)

    case app: App =>
      resolveApp(app, context).map(_.asTerm)

    case lambda: Lambda =>
      resolveLambda(lambda, context).widen

    case group: TermGroup =>
      resolveExpr(group.inner, context)
        .map(inner => group.copy(inner = inner))

    case expr: Expr =>
      resolveExpr(expr, context).widen

    case tuple: Tuple =>
      tuple.elements
        .traverse(resolveExpr(_, context))
        .map(elements => tuple.copy(elements = elements))

    case cond: Cond =>
      for
        predicate <- resolveExpr(cond.cond, context)
        yes <- resolveExpr(cond.ifTrue, context)
        no <- resolveExpr(cond.ifFalse, context)
      yield cond.copy(cond = predicate, ifTrue = yes, ifFalse = no)

    case other => other.pure[Resolution]

  private def resolveRef(ref: Ref, context: Context): Resolution[ResolvedCallee] =
    ref.qualifier match

      case Some(qualifier) =>
        resolveTerm(qualifier, context)
          .map(updated => ResolvedCallee(ref.copy(qualifier = updated.some)))

      case None =>
        val candidates = context.protocols
          .visibleCandidates(ref.name, context.scope, context.module.resolvables)
        candidates match
          case Left(ProtocolLookupError.RejectedDeclaration(causes)) =>
            ResolvedCallee(ref, causes).pure[Resolution]

          case Left(error) =>
            DiagnosticStore
              .report(
                SemanticError
                  .ProtocolErrorFound(ProtocolError.RegistryFailure(ref, error), phaseName)
              )
              .map(causes => ResolvedCallee(ref, causes))

          case Right(Nil) =>
            DiagnosticStore
              .report(SemanticError.UndefinedRef(ref, context.member, phaseName))
              .map(causes => ResolvedCallee(ref, causes))

          case Right(visible) =>
            val resolvedId = visible match
              case ProtocolCandidate.Declaration(id) :: Nil => id.some
              case _ => None
            val updated = ref.copy(candidateIds = visible.map(_.id), resolvedId = resolvedId)
            ResolvedCallee(updated).pure[Resolution]

  private def resolveLambda(lambda: Lambda, context: Context): Resolution[Lambda] =
    resolveExpr(lambda.body, context.within(lambda.params))
      .map(body => lambda.copy(body = body))

  private def resolveApp(app: App, context: Context): Resolution[ResolvedCallee] =
    // A parser-lowered local binding is in scope in its initializer, enabling recursion.
    val argContext = app.fn match
      case lambda: Lambda => context.within(lambda.params)
      case _ => context

    for
      fn <- resolveCallee(app.fn, context)
      arg <- resolveExpr(app.arg, argContext)
    yield ResolvedCallee(app.copy(fn = fn.value, arg = arg), fn.causes)

  private def resolveCallee(callee: Callee, context: Context): Resolution[ResolvedCallee] =
    callee match
      case ref:    Ref => resolveRef(ref, context)
      case app:    App => resolveApp(app, context)
      case lambda: Lambda =>
        resolveLambda(lambda, context).map(ResolvedCallee(_))
