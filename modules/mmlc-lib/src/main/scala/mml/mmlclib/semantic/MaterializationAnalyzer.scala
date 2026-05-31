package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Computes [[LambdaMeta.isDirect]] for every lambda in the module.
  *
  * A lambda is *direct* when every reference to it (or to its binding) appears as the head of a
  * call chain — the lambda is only ever invoked or partially applied, never used as a first-class
  * value. Direct lambdas can lower as pure scope constructs; non-direct lambdas must materialize as
  * fat-pointer closure values.
  *
  * The field defaults to `false`. This pass walks the AST and flips it to `true` for lambdas whose
  * every reachable use is a call-head use. Multi-use sites join by AND: any value-position use
  * (higher-order argument, return, alias, struct sink) keeps the lambda non-direct.
  *
  * Call-head check: a binding reference is direct iff `depth > 0`. An undersaturated call creates a
  * partial-application closure for the result; it does not require the original callable to
  * materialize as a full function value.
  *
  * Parser-lowered scoped-binding lambdas (`App(Lambda(params=[binder], ...), arg)` shape produced
  * by `let`, statement sequencing, and immediate application) are always recognized as direct: at
  * the immediate App they sit at fn-position depth 1 with arity 1, so the saturation check always
  * passes.
  */
object MaterializationAnalyzer:

  /** Saturation requirement for a lambda binding. */
  private final case class BindingArity(arity: Int)

  private type Bindings     = Map[String, BindingArity]
  private type NonDirectIds = Set[String]

  /** Required call-site depth for a lambda of `arity` to be considered direct. The `max(_, 1)`
    * floor ensures arity-0 lambdas (thunks) need at least one `App.fn` descent before they count as
    * invoked — without it, every thunk ref would satisfy `depth >= 0` and be tagged direct even
    * when used as a first-class value.
    */
  private def saturationFloor(arity: Int): Int = math.max(arity, 1)

  def rewriteModule(state: CompilerState): CompilerState =
    val bindings       = collectLambdaBindings(state.module)
    val nonDirectIds   = collectNonDirect(state.module, bindings)
    val updatedMembers = state.module.members.map(rewriteMember(_, nonDirectIds))
    state.withModule(state.module.copy(members = updatedMembers))

  // ----- Pass 1: lambda binding inventory -----
  //
  // A "lambda binding" is a name bound to a lambda value:
  //   - a top-level Bnd whose value's head term is a Lambda
  //   - a parser-lowered scoped binding `App(Lambda(params=[binder], ...), arg)` whose
  //     arg's head term is a Lambda
  // The map records the binding's id along with the bound lambda's arity, which is the
  // saturation requirement for direct calls.

  private def collectLambdaBindings(module: Module): Bindings =
    module.members.foldLeft(Map.empty: Bindings) {
      case (acc, bnd: Bnd) =>
        bnd.value.terms.headOption match
          case Some(lambda: Lambda) =>
            val withTop = bnd.id.fold(acc)(id => acc + (id -> BindingArity(lambda.params.size)))
            walkBindings(lambda.body, withTop)
          case _ =>
            walkBindings(bnd.value, acc)
      case (acc, _) => acc
    }

  private def walkBindings(expr: Expr, acc: Bindings): Bindings =
    expr.terms.foldLeft(acc)((a, t) => walkBindingsTerm(t, a))

  private def walkBindingsTerm(term: Term, acc: Bindings): Bindings = term match
    case app: App =>
      app.fn match
        case scopedLambda: Lambda if scopedLambda.params.size == 1 =>
          val binder = scopedLambda.params.head
          val withBound = app.arg.terms.headOption match
            case Some(boundLambda: Lambda) =>
              binder.id.fold(acc)(id => acc + (id -> BindingArity(boundLambda.params.size)))
            case _ => acc
          walkBindings(scopedLambda.body, walkBindings(app.arg, withBound))
        case _ =>
          walkBindings(app.arg, walkBindingsAppFn(app.fn, acc))
    case lambda: Lambda => walkBindings(lambda.body, acc)
    case cond:   Cond =>
      walkBindings(cond.ifFalse, walkBindings(cond.ifTrue, walkBindings(cond.cond, acc)))
    case group: TermGroup => walkBindings(group.inner, acc)
    case tuple: Tuple => tuple.elements.toList.foldLeft(acc)((a, e) => walkBindings(e, a))
    case expr:  Expr => walkBindings(expr, acc)
    case _ => acc

  private def walkBindingsAppFn(fn: Ref | App | Lambda, acc: Bindings): Bindings = fn match
    case _:      Ref => acc
    case app:    App => walkBindings(app.arg, walkBindingsAppFn(app.fn, acc))
    case lambda: Lambda => walkBindings(lambda.body, acc)

  // ----- Pass 2: non-direct binding ids -----
  //
  // Walk the AST tracking call-head depth: each descent into `App.fn` increments,
  // every other descent resets to 0. At each Ref whose resolvedId matches a tracked
  // lambda binding, the binding is direct iff `depth > 0`. Any failing use site
  // flips the binding to non-direct.

  private def collectNonDirect(module: Module, bindings: Bindings): NonDirectIds =
    module.members.foldLeft(Set.empty: NonDirectIds) {
      case (acc, bnd: Bnd) =>
        bnd.value.terms.headOption match
          case Some(lambda: Lambda) => visitExpr(lambda.body, depth = 0, bindings, acc)
          case _ => visitExpr(bnd.value, depth = 0, bindings, acc)
      case (acc, _) => acc
    }

  private def visitExpr(
    expr:     Expr,
    depth:    Int,
    bindings: Bindings,
    acc:      NonDirectIds
  ): NonDirectIds = expr.terms match
    case single :: Nil => visitTerm(single, depth, bindings, acc)
    case terms => terms.foldLeft(acc)((a, t) => visitTerm(t, 0, bindings, a))

  private def visitTerm(
    term:     Term,
    depth:    Int,
    bindings: Bindings,
    acc:      NonDirectIds
  ): NonDirectIds = term match
    case ref: Ref =>
      val withQualifier = ref.qualifier.fold(acc)(q => visitTerm(q, 0, bindings, acc))
      ref.resolvedId match
        case Some(id) =>
          bindings.get(id) match
            case Some(_) if depth == 0 => withQualifier + id
            case _ => withQualifier
        case None => withQualifier
    case app: App =>
      visitExpr(app.arg, 0, bindings, visitAppFn(app.fn, depth + 1, bindings, acc))
    case lambda: Lambda => visitExpr(lambda.body, 0, bindings, acc)
    case cond:   Cond =>
      visitExpr(
        cond.ifFalse,
        0,
        bindings,
        visitExpr(cond.ifTrue, 0, bindings, visitExpr(cond.cond, 0, bindings, acc))
      )
    case group: TermGroup => visitExpr(group.inner, depth, bindings, acc)
    case tuple: Tuple =>
      tuple.elements.toList.foldLeft(acc)((a, e) => visitExpr(e, 0, bindings, a))
    case expr: Expr => visitExpr(expr, depth, bindings, acc)
    case _ => acc

  private def visitAppFn(
    fn:       Ref | App | Lambda,
    depth:    Int,
    bindings: Bindings,
    acc:      NonDirectIds
  ): NonDirectIds = fn match
    case ref: Ref => visitTerm(ref, depth, bindings, acc)
    case app: App =>
      visitExpr(app.arg, 0, bindings, visitAppFn(app.fn, depth + 1, bindings, acc))
    case lambda: Lambda =>
      // Lambda literal as the head of a call chain — body is value context.
      visitExpr(lambda.body, 0, bindings, acc)

  // ----- Pass 3: rewrite LambdaMeta.isDirect -----
  //
  // Walk again with depth. For each Lambda node decide whether it is direct:
  //   - top-level Bnd whose head term is a Lambda: direct iff `bnd.id` is not in
  //     `nonDirectIds`
  //   - bound inner lambda (arg.head of a scoped-binding App): direct iff the binder's
  //     id is not in `nonDirectIds`
  //   - scoped-binding wrapper lambda or any other lambda literal: direct iff the
  //     current depth saturates the lambda's arity

  private def rewriteMember(member: Member, nonDirectIds: NonDirectIds): Member = member match
    case bnd: Bnd =>
      bnd.value.terms match
        case (lambda: Lambda) :: rest =>
          val direct  = bnd.id.forall(id => !nonDirectIds.contains(id))
          val newBody = rewriteExpr(lambda.body, depth = 0, nonDirectIds)
          val tagged  = withIsDirect(lambda.copy(body = newBody), direct)
          val newRest = rest.map(t => rewriteTerm(t, 0, nonDirectIds))
          bnd.copy(value = bnd.value.copy(terms = tagged :: newRest))
        case _ =>
          val newValue = rewriteExpr(bnd.value, depth = 0, nonDirectIds)
          if newValue ne bnd.value then bnd.copy(value = newValue) else bnd
    case other => other

  private def rewriteExpr(expr: Expr, depth: Int, nonDirectIds: NonDirectIds): Expr =
    val newTerms = expr.terms match
      case single :: Nil => List(rewriteTerm(single, depth, nonDirectIds))
      case terms => terms.map(t => rewriteTerm(t, 0, nonDirectIds))
    if termsChanged(expr.terms, newTerms) then expr.copy(terms = newTerms) else expr

  private def rewriteTerm(term: Term, depth: Int, nonDirectIds: NonDirectIds): Term = term match
    case app: App =>
      val newFn = rewriteAppFn(app.fn, depth + 1, nonDirectIds)
      val newArg = app.fn match
        case scopedLambda: Lambda if scopedLambda.params.size == 1 =>
          rewriteScopedBindingArg(app.arg, scopedLambda.params.head, nonDirectIds)
        case _ =>
          rewriteExpr(app.arg, 0, nonDirectIds)
      if (newFn ne app.fn) || (newArg ne app.arg) then app.copy(fn = newFn, arg = newArg)
      else app
    case lambda: Lambda =>
      val direct  = depth >= saturationFloor(lambda.params.size)
      val newBody = rewriteExpr(lambda.body, 0, nonDirectIds)
      withIsDirect(lambda.copy(body = newBody), direct)
    case cond: Cond =>
      val newC = rewriteExpr(cond.cond, 0, nonDirectIds)
      val newT = rewriteExpr(cond.ifTrue, 0, nonDirectIds)
      val newF = rewriteExpr(cond.ifFalse, 0, nonDirectIds)
      if (newC ne cond.cond) || (newT ne cond.ifTrue) || (newF ne cond.ifFalse) then
        cond.copy(cond = newC, ifTrue = newT, ifFalse = newF)
      else cond
    case group: TermGroup =>
      val inner = rewriteExpr(group.inner, depth, nonDirectIds)
      if inner ne group.inner then group.copy(inner = inner) else group
    case tuple: Tuple =>
      val newElems = tuple.elements.map(e => rewriteExpr(e, 0, nonDirectIds))
      if elementsChanged(tuple.elements.toList, newElems.toList) then
        tuple.copy(elements = newElems)
      else tuple
    case expr: Expr => rewriteExpr(expr, depth, nonDirectIds)
    case ref:  Ref =>
      ref.qualifier match
        case Some(q) =>
          val newQ = rewriteTerm(q, 0, nonDirectIds)
          if newQ ne q then ref.copy(qualifier = Some(newQ)) else ref
        case None => ref
    case _ => term

  private def rewriteAppFn(
    fn:           Ref | App | Lambda,
    depth:        Int,
    nonDirectIds: NonDirectIds
  ): Ref | App | Lambda = fn match
    case ref: Ref =>
      ref.qualifier match
        case Some(q) =>
          val newQ = rewriteTerm(q, 0, nonDirectIds)
          if newQ ne q then ref.copy(qualifier = Some(newQ)) else ref
        case None => ref
    case app: App =>
      val innerFn = rewriteAppFn(app.fn, depth + 1, nonDirectIds)
      val newArg = app.fn match
        case scopedLambda: Lambda if scopedLambda.params.size == 1 =>
          rewriteScopedBindingArg(app.arg, scopedLambda.params.head, nonDirectIds)
        case _ =>
          rewriteExpr(app.arg, 0, nonDirectIds)
      if (innerFn ne app.fn) || (newArg ne app.arg) then app.copy(fn = innerFn, arg = newArg)
      else app
    case lambda: Lambda =>
      val direct  = depth >= saturationFloor(lambda.params.size)
      val newBody = rewriteExpr(lambda.body, 0, nonDirectIds)
      withIsDirect(lambda.copy(body = newBody), direct)

  /** Rewrite the arg of a scoped-binding App. The bound lambda (if any) gets its `isDirect` from
    * how the binder is used, not from the local AST position.
    */
  private def rewriteScopedBindingArg(
    arg:          Expr,
    binder:       FnParam,
    nonDirectIds: NonDirectIds
  ): Expr =
    arg.terms match
      case (boundLambda: Lambda) :: rest =>
        val direct  = binder.id.forall(id => !nonDirectIds.contains(id))
        val newBody = rewriteExpr(boundLambda.body, 0, nonDirectIds)
        val tagged  = withIsDirect(boundLambda.copy(body = newBody), direct)
        val newRest = rest.map(t => rewriteTerm(t, 0, nonDirectIds))
        arg.copy(terms = tagged :: newRest)
      case _ =>
        rewriteExpr(arg, 0, nonDirectIds)

  private def withIsDirect(lambda: Lambda, direct: Boolean): Lambda =
    val meta = lambda.meta.getOrElse(LambdaMeta())
    lambda.copy(meta = Some(meta.copy(isDirect = direct)))

  private def termsChanged(old: List[Term], updated: List[Term]): Boolean =
    old.zip(updated).exists { case (o, n) => o ne n }

  private def elementsChanged(old: List[Expr], updated: List[Expr]): Boolean =
    old.zip(updated).exists { case (o, n) => o ne n }
