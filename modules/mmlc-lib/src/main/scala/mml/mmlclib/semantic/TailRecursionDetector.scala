package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

object TailRecursionDetector:

  def rewriteModule(state: CompilerState): CompilerState =
    if state.config.noTco then state
    else
      val (updatedMembers, updatedResolvables) = state.module.members.foldLeft(
        (List.empty[Member], state.module.resolvables)
      ) { case ((accMembers, resolvables), member) =>
        rewriteMember(member) match
          case updatedBnd: Bnd if updatedBnd ne member.asInstanceOf[AnyRef] =>
            (accMembers :+ updatedBnd, resolvables.updated(updatedBnd))
          case other =>
            (accMembers :+ other, resolvables)
      }
      state.withModule(
        state.module.copy(members = updatedMembers, resolvables = updatedResolvables)
      )

  private def rewriteMember(member: Member): Member =
    member match
      case bnd: Bnd =>
        bnd.value.terms match
          case (lambda: Lambda) :: rest =>
            val isTailRec     = hasTailRecursiveCallById(lambda.body, bnd)
            val rewrittenBody = rewriteLetBoundLambdas(lambda.body)
            val updatedMeta =
              if isTailRec then
                Some(lambda.meta.getOrElse(LambdaMeta()).copy(isTailRecursive = true))
              else lambda.meta
            if isTailRec || (rewrittenBody ne lambda.body) then
              val updated = lambda.copy(meta = updatedMeta, body = rewrittenBody)
              bnd.copy(value = bnd.value.copy(terms = updated :: rest))
            else bnd
          case _ => bnd
      case other => other

  /** Visit every expression scope, preserving the binding used to recognize local self-calls. */
  private def rewriteLetBoundLambdas(expr: Expr): Expr =
    expr.copy(terms = expr.terms.map(rewriteTerm))

  private def rewriteTerm(term: Term): Term = term match

    case expr: Expr => rewriteLetBoundLambdas(expr)

    case app: App => rewriteApp(app)

    case lambda: Lambda => lambda.copy(body = rewriteLetBoundLambdas(lambda.body))

    case cond: Cond =>
      cond.copy(
        cond    = rewriteLetBoundLambdas(cond.cond),
        ifTrue  = rewriteLetBoundLambdas(cond.ifTrue),
        ifFalse = rewriteLetBoundLambdas(cond.ifFalse)
      )

    case group: TermGroup => group.copy(inner = rewriteLetBoundLambdas(group.inner))

    case tuple: Tuple => tuple.copy(elements = tuple.elements.map(rewriteLetBoundLambdas))

    case ref: Ref => ref.copy(qualifier = ref.qualifier.map(rewriteTerm))

    case destroy: DestroyClosure =>
      destroy.copy(operand = rewriteLetBoundLambdas(destroy.operand))

    case destroy: DestroyClosureEnvironment =>
      destroy.copy(operand = rewriteLetBoundLambdas(destroy.operand))

    case disarm: DisarmClosureEnvironment =>
      disarm.copy(operand = rewriteLetBoundLambdas(disarm.operand))

    case dispatch: DispatchClosureDestructor =>
      dispatch.copy(operand = rewriteLetBoundLambdas(dispatch.operand))

    case invalid: InvalidExpression =>
      invalid.copy(originalExpr = rewriteLetBoundLambdas(invalid.originalExpr))

    case other => other

  private def rewriteApp(app: App): App =
    val updatedFn: Ref | App | Lambda = app.fn match
      case lambda: Lambda => lambda.copy(body = rewriteLetBoundLambdas(lambda.body))
      case nested: App => rewriteApp(nested)
      case ref:    Ref => ref.copy(qualifier = ref.qualifier.map(rewriteTerm))
    val updatedArg = rewriteLetBoundLambdas(app.arg)
    val boundArg = app.fn match
      case lambda: Lambda if lambda.params.size == 1 =>
        markBoundLambda(updatedArg, lambda.params.head)
      case _ => updatedArg
    app.copy(fn = updatedFn, arg = boundArg)

  private def markBoundLambda(arg: Expr, param: FnParam): Expr =
    arg.terms match
      case List(lambda: Lambda) if hasTailRecursiveCallById(lambda.body, param) =>
        val meta = lambda.meta.getOrElse(LambdaMeta()).copy(isTailRecursive = true)
        arg.copy(terms = List(lambda.copy(meta = meta.some)))
      case _ => arg

  private def hasTailRecursiveCallById(
    expr:    Expr,
    binding: Resolvable
  ): Boolean =
    expr.terms match
      case List(term) => hasTailRecursiveCallInTerm(term, binding)
      case _ => false

  private def hasTailRecursiveCallInTerm(
    term:    Term,
    binding: Resolvable
  ): Boolean =
    term match
      case cond: Cond =>
        hasTailRecursiveCallById(cond.ifTrue, binding) ||
        hasTailRecursiveCallById(cond.ifFalse, binding)

      case app: App =>
        app.fn match
          case lambda: Lambda =>
            hasTailRecursiveCallById(lambda.body, binding)
          case _ =>
            isSelfCall(app, binding)

      case _ => false

  private def isSelfCall(
    app:     App,
    binding: Resolvable
  ): Boolean =
    collectCallee(app) match
      case Some(ref) => isSelfRef(ref, binding)
      case None => false

  /** Extract the callee Ref from a curried application chain */
  private def collectCallee(app: App): Option[Ref] =
    app.fn match
      case ref:  Ref => Some(ref)
      case next: App => collectCallee(next)
      case _ => None

  private def isSelfRef(ref: Ref, binding: Resolvable): Boolean =
    if ref.qualifier.isDefined then false
    else
      ref.resolvedId match
        case Some(id) => binding.id.contains(id)
        case None => ref.name == binding.name
