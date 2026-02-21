package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

object RefResolver:

  private val phaseName = "mml.mmlclib.semantic.RefResolver"

  /** Resolve all references in a module, accumulating errors in the state. */
  def rewriteModule(state: CompilerState): CompilerState =
    val (errors, members) =
      state.module.members.foldLeft((List.empty[SemanticError], List.empty[Member])) {
        case ((accErrors, accMembers), member) =>
          resolveMember(member, state.module) match
            case Left(errs) =>
              // Important: Use the rewritten member with InvalidExpression nodes, not the original
              val rewrittenMember = rewriteMemberWithInvalidExpressions(member, state.module)
              (accErrors ++ errs, accMembers :+ rewrittenMember)
            case Right(updated) => (accErrors, accMembers :+ updated)
      }
    state.addErrors(errors).withModule(state.module.copy(members = members))

  /** Rewrite a member to use InvalidExpression nodes for undefined references */
  private def rewriteMemberWithInvalidExpressions(member: Member, module: Module): Member =
    member match
      case bnd: Bnd =>
        // Handle Bnd with Lambda - rewrite lambda body
        val rewrittenValue = bnd.value.terms match
          case (lambda: Lambda) :: rest =>
            val rewrittenBody   = rewriteExprWithInvalidExpressions(lambda.body, bnd, module)
            val rewrittenLambda = lambda.copy(body = rewrittenBody)
            val rewrittenRest   = rest.map(rewriteTermWithInvalidExpressions(_, bnd, module))
            bnd.value.copy(terms = rewrittenLambda :: rewrittenRest)
          case _ =>
            rewriteExprWithInvalidExpressions(bnd.value, bnd, module)
        bnd.copy(value = rewrittenValue)
      case _ => member

  /** Rewrite a single term to use InvalidExpression for undefined references */
  private def rewriteTermWithInvalidExpressions(
    term:        Term,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam] = Nil
  ): Term =
    term match
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            val updatedQualifier =
              rewriteTermWithInvalidExpressions(qualifier, member, module, extraParams)
            ref.copy(qualifier = Some(updatedQualifier))
          case None =>
            val candidates = lookupRefs(ref, member, module, extraParams)
            if candidates.isEmpty then
              InvalidExpression(
                source       = ref.source,
                originalExpr = Expr(ref.source, List(ref)),
                typeSpec     = ref.typeSpec,
                typeAsc      = ref.typeAsc
              )
            else
              val ids = candidates.flatMap(_.id)
              if candidates.length == 1 then
                ref.copy(candidateIds    = ids, resolvedId = ids.headOption)
              else ref.copy(candidateIds = ids)
      case e: Expr =>
        rewriteExprWithInvalidExpressions(e, member, module, extraParams)
      case other => other

  /** Rewrite expression to use InvalidExpression for undefined references */
  private def rewriteExprWithInvalidExpressions(
    expr:        Expr,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam] = Nil
  ): Expr =
    val rewrittenTerms = expr.terms.map {
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            val updatedQualifier =
              rewriteTermWithInvalidExpressions(qualifier, member, module, extraParams)
            ref.copy(qualifier = Some(updatedQualifier))
          case None =>
            val candidates = lookupRefs(ref, member, module, extraParams)
            if candidates.isEmpty then
              // Create InvalidExpression wrapping the undefined ref
              InvalidExpression(
                source       = ref.source,
                originalExpr = Expr(ref.source, List(ref)),
                typeSpec     = ref.typeSpec,
                typeAsc      = ref.typeAsc
              )
            else
              val ids = candidates.flatMap(_.id)
              if candidates.length == 1 then
                ref.copy(candidateIds    = ids, resolvedId = ids.headOption)
              else ref.copy(candidateIds = ids)

      case group: TermGroup =>
        group.copy(inner =
          rewriteExprWithInvalidExpressions(group.inner, member, module, extraParams)
        )

      case e: Expr =>
        rewriteExprWithInvalidExpressions(e, member, module, extraParams)

      case t: Tuple =>
        t.copy(elements =
          t.elements.map(e => rewriteExprWithInvalidExpressions(e, member, module, extraParams))
        )

      case cond: Cond =>
        cond.copy(
          cond    = rewriteExprWithInvalidExpressions(cond.cond, member, module, extraParams),
          ifTrue  = rewriteExprWithInvalidExpressions(cond.ifTrue, member, module, extraParams),
          ifFalse = rewriteExprWithInvalidExpressions(cond.ifFalse, member, module, extraParams)
        )

      case app: App =>
        val newFn  = rewriteAppFnWithInvalidExpressions(app.fn, member, module, extraParams)
        val newArg = rewriteExprWithInvalidExpressions(app.arg, member, module, extraParams)
        app.copy(fn = newFn, arg = newArg)

      case lambda: Lambda =>
        val newParams = lambda.params ++ extraParams
        val newBody   = rewriteExprWithInvalidExpressions(lambda.body, member, module, newParams)
        lambda.copy(body = newBody)

      case term => term
    }
    expr.copy(terms = rewrittenTerms)

  /** Rewrite App.fn to use InvalidExpression for undefined references */
  private def rewriteAppFnWithInvalidExpressions(
    fn:          Ref | App | Lambda,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Ref | App | Lambda =
    fn match
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            val updatedQualifier =
              rewriteTermWithInvalidExpressions(qualifier, member, module, extraParams)
            ref.copy(qualifier = Some(updatedQualifier))
          case None =>
            val candidates = lookupRefs(ref, member, module, extraParams)
            val ids        = candidates.flatMap(_.id)
            if candidates.length == 1 then ref.copy(candidateIds = ids, resolvedId = ids.headOption)
            else ref.copy(candidateIds                           = ids)
      case app: App =>
        val newFn  = rewriteAppFnWithInvalidExpressions(app.fn, member, module, extraParams)
        val newArg = rewriteExprWithInvalidExpressions(app.arg, member, module, extraParams)
        app.copy(fn = newFn, arg = newArg)
      case lambda: Lambda =>
        val newParams = lambda.params ++ extraParams
        val newBody   = rewriteExprWithInvalidExpressions(lambda.body, member, module, newParams)
        lambda.copy(body = newBody)

  /** Resolve references in a module member. */
  private def resolveMember(member: Member, module: Module): Either[List[SemanticError], Member] =
    member match
      case bnd: Bnd =>
        // Handle Bnd with Lambda - resolve lambda body
        bnd.value.terms match
          case (lambda: Lambda) :: rest =>
            for
              resolvedBody <- resolveExpr(lambda.body, bnd, module)
              resolvedRest <- rest.traverse(resolveTerm(_, bnd, module))
              updatedLambda = lambda.copy(body = resolvedBody)
            yield bnd.copy(value = bnd.value.copy(terms = updatedLambda :: resolvedRest))
          case _ =>
            resolveExpr(bnd.value, bnd, module).map(updatedExpr => bnd.copy(value = updatedExpr))

      case _ =>
        member.asRight[List[SemanticError]]

  /** Resolve references in a single term */
  private def resolveTerm(
    term:        Term,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam] = Nil
  ): Either[List[SemanticError], Term] =
    term match
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            resolveTerm(qualifier, member, module, extraParams)
              .map(updatedQualifier => ref.copy(qualifier = Some(updatedQualifier)))
          case None =>
            val candidates = lookupRefs(ref, member, module, extraParams)
            if candidates.isEmpty then
              List(SemanticError.UndefinedRef(ref, member, phaseName)).asLeft
            else
              val ids = candidates.flatMap(_.id)
              if candidates.length == 1 then
                ref.copy(candidateIds = ids, resolvedId = ids.headOption).asRight
              else ref.copy(candidateIds = ids).asRight
      case e: Expr =>
        resolveExpr(e, member, module, extraParams)
      case other =>
        other.asRight

  /** Returns all members (bindings, functions, operators) whose name matches the reference.
    */
  private def lookupRefs(
    ref:         Ref,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): List[Resolvable] =
    if ref.qualifier.isDefined then return Nil

    def collectMembers =
      module.members.collect {
        // Match Bnd by name or originalName from meta (for operators)
        case bnd: Bnd
            if bnd.name == ref.name ||
              bnd.meta.exists(_.originalName == ref.name) =>
          bnd
      }

    // Check extra params first (from enclosing lambdas)
    val fromExtra = extraParams.filter(_.name == ref.name)
    if fromExtra.nonEmpty then return fromExtra

    // Extract params from Bnd with Lambda
    val params = member match
      case bnd: Bnd =>
        bnd.value.terms.headOption match
          case Some(lambda: Lambda) => lambda.params.filter(_.name == ref.name)
          case _ => Nil
      case _ => Nil

    if params.nonEmpty then params else collectMembers

  /** Resolve references in an expression.
    *
    * Returns either a list of errors or a new expression with resolved references.
    */
  private def resolveExpr(
    expr:        Expr,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam] = Nil
  ): Either[List[SemanticError], Expr] =

    expr.terms.traverse {

      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            resolveTerm(qualifier, member, module, extraParams)
              .map(updatedQualifier => ref.copy(qualifier = Some(updatedQualifier)))
          case None =>
            val candidates = lookupRefs(ref, member, module, extraParams)
            if candidates.isEmpty then
              List(SemanticError.UndefinedRef(ref, member, phaseName)).asLeft
            else
              val ids = candidates.flatMap(_.id)
              if candidates.length == 1 then
                ref.copy(candidateIds = ids, resolvedId = ids.headOption).asRight
              else ref.copy(candidateIds = ids).asRight

      case group: TermGroup =>
        resolveExpr(group.inner, member, module, extraParams)
          .map(updatedExpr => group.copy(inner = updatedExpr))

      case e: Expr =>
        resolveExpr(e, member, module, extraParams)
          .map(updatedExpr => e.copy(terms = updatedExpr.terms))

      case t: Tuple =>
        t.elements
          .traverse(e => resolveExpr(e, member, module, extraParams))
          .map(newElems => t.copy(elements = newElems))

      case cond: Cond =>
        for
          newCond <- resolveExpr(cond.cond, member, module, extraParams)
          newIfTrue <- resolveExpr(cond.ifTrue, member, module, extraParams)
          newIfFalse <- resolveExpr(cond.ifFalse, member, module, extraParams)
        yield cond.copy(cond = newCond, ifTrue = newIfTrue, ifFalse = newIfFalse)

      case app: App =>
        for
          newFn <- resolveAppFn(app.fn, member, module, extraParams)
          newArg <- resolveExpr(app.arg, member, module, extraParams)
        yield app.copy(fn = newFn, arg = newArg)

      case lambda: Lambda =>
        // Add lambda params to scope when resolving body
        val newParams = lambda.params ++ extraParams
        resolveExpr(lambda.body, member, module, newParams)
          .map(newBody => lambda.copy(body = newBody))

      case term =>
        term.asRight[List[SemanticError]]

    } map { updatedTerms =>
      expr.copy(terms = updatedTerms)
    }

  /** Resolve references in App.fn (which can be Ref | App | Lambda) */
  private def resolveAppFn(
    fn:          Ref | App | Lambda,
    member:      Member,
    module:      Module,
    extraParams: List[FnParam]
  ): Either[List[SemanticError], Ref | App | Lambda] =
    fn match
      case ref: Ref =>
        ref.qualifier match
          case Some(qualifier) =>
            resolveTerm(qualifier, member, module, extraParams)
              .map(updatedQualifier => ref.copy(qualifier = Some(updatedQualifier)))
          case None =>
            val candidates = lookupRefs(ref, member, module, extraParams)
            if candidates.isEmpty then
              List(SemanticError.UndefinedRef(ref, member, phaseName)).asLeft
            else
              val ids = candidates.flatMap(_.id)
              if candidates.length == 1 then
                ref.copy(candidateIds = ids, resolvedId = ids.headOption).asRight
              else ref.copy(candidateIds = ids).asRight
      case app: App =>
        for
          newFn <- resolveAppFn(app.fn, member, module, extraParams)
          newArg <- resolveExpr(app.arg, member, module, extraParams)
        yield app.copy(fn = newFn, arg = newArg)
      case lambda: Lambda =>
        val newParams = lambda.params ++ extraParams
        resolveExpr(lambda.body, member, module, newParams)
          .map(newBody => lambda.copy(body = newBody))
