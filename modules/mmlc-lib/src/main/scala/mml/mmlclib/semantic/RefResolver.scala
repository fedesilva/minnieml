package mml.mmlclib.semantic

import cats.data.State
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticId, DiagnosticStore}

object RefResolver:

  private val phaseName = "mml.mmlclib.semantic.RefResolver"

  private type Resolution[A] = State[DiagnosticStore, A]

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

  /** Resolve all references and retain each failed occurrence alongside its diagnostic roots. */
  def rewriteModule(state: CompilerState): CompilerState =
    val (diagnostics, members) = state.module.members
      .traverse(resolveMember(_, state.module))
      .run(state.diagnostics)
      .value

    state.copy(diagnostics = diagnostics).withModule(state.module.copy(members = members))

  private def resolveMember(member: Member, module: Module): Resolution[Member] = member match
    case binding: Bnd =>
      resolveExpr(binding.value, binding, module)
        .map(value => binding.copy(value = value))
    case other => other.pure[Resolution]

  private def resolveExpr(
    expr:        Expr,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam] = Nil
  ): Resolution[Expr] =
    expr.terms
      .traverse(resolveTerm(_, member, module, extraParams))
      .map(terms => expr.copy(terms = terms))

  private def resolveTerm(
    term:        Term,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Resolution[Term] = term match

    case ref: Ref =>
      resolveRef(ref, member, module, extraParams).map(_.asTerm)

    case app: App =>
      resolveApp(app, member, module, extraParams).map(_.asTerm)

    case lambda: Lambda =>
      resolveLambda(lambda, member, module, extraParams).widen

    case group: TermGroup =>
      resolveExpr(group.inner, member, module, extraParams)
        .map(inner => group.copy(inner = inner))

    case expr: Expr =>
      resolveExpr(expr, member, module, extraParams).widen

    case tuple: Tuple =>
      tuple.elements
        .traverse(resolveExpr(_, member, module, extraParams))
        .map(elements => tuple.copy(elements = elements))

    case cond: Cond =>
      for
        predicate <- resolveExpr(cond.cond, member, module, extraParams)
        yes <- resolveExpr(cond.ifTrue, member, module, extraParams)
        no <- resolveExpr(cond.ifFalse, member, module, extraParams)
      yield cond.copy(cond = predicate, ifTrue = yes, ifFalse = no)

    case other => other.pure[Resolution]

  private def resolveRef(
    ref:         Ref,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Resolution[ResolvedCallee] = ref.qualifier match

    case Some(qualifier) =>
      resolveTerm(qualifier, member, module, extraParams)
        .map(updated => ResolvedCallee(ref.copy(qualifier = updated.some)))

    case None =>
      val candidates = lookupRefs(ref, member, module, extraParams)
      if candidates.isEmpty then
        DiagnosticStore
          .report(SemanticError.UndefinedRef(ref, member, phaseName))
          .map(causes => ResolvedCallee(ref, causes))
      else
        val ids = candidates.flatMap(_.id)
        val updated =
          if candidates.length == 1 then ref.copy(candidateIds = ids, resolvedId = ids.headOption)
          else ref.copy(candidateIds                           = ids)
        ResolvedCallee(updated).pure[Resolution]

  private def resolveLambda(
    lambda:      Lambda,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Resolution[Lambda] =
    resolveExpr(lambda.body, member, module, lambda.params ++ extraParams)
      .map(body => lambda.copy(body = body))

  private def resolveApp(
    app:         App,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Resolution[ResolvedCallee] =
    // A parser-lowered local binding is in scope in its initializer, enabling recursion.
    val argParams = app.fn match
      case lambda: Lambda => lambda.params ++ extraParams
      case _ => extraParams

    for
      fn <- resolveCallee(app.fn, member, module, extraParams)
      arg <- resolveExpr(app.arg, member, module, argParams)
    yield ResolvedCallee(app.copy(fn = fn.value, arg = arg), fn.causes)

  private def resolveCallee(
    callee:      Callee,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Resolution[ResolvedCallee] = callee match
    case ref:    Ref => resolveRef(ref, member, module, extraParams)
    case app:    App => resolveApp(app, member, module, extraParams)
    case lambda: Lambda =>
      resolveLambda(lambda, member, module, extraParams).map(ResolvedCallee(_))

  /** Returns matching parameters or module declarations, preserving overload candidates. */
  private def lookupRefs(
    ref:         Ref,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): List[Resolvable] =
    val enclosing = extraParams.find(_.name == ref.name).toList
    val parameters = member match
      case binding: Bnd =>
        binding.value.terms.headOption match
          case Some(lambda: Lambda) => lambda.params.filter(_.name == ref.name)
          case _ => Nil
      case _ => Nil

    if enclosing.nonEmpty then enclosing
    else if parameters.nonEmpty then parameters
    else
      module.members.collect {
        case binding: Bnd
            if binding.name == ref.name || binding.meta.exists(_.originalName == ref.name) =>
          binding
      }
