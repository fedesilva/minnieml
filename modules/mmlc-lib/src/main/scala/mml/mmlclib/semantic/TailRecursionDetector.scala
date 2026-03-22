package mml.mmlclib.semantic

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
          case (lambda: Lambda) :: rest if hasTailRecursiveCall(lambda.body, bnd) =>
            val meta = lambda.meta.getOrElse(LambdaMeta())
            val updated = lambda.copy(
              meta = Some(meta.copy(isTailRecursive = true)),
              body = rewriteLetBoundLambdas(lambda.body)
            )
            val updatedBnd = bnd.copy(value = bnd.value.copy(terms = updated :: rest))
            updatedBnd
          case (lambda: Lambda) :: rest =>
            val rewrittenBody = rewriteLetBoundLambdas(lambda.body)
            if rewrittenBody ne lambda.body then
              val updated    = lambda.copy(body = rewrittenBody)
              val updatedBnd = bnd.copy(value = bnd.value.copy(terms = updated :: rest))
              updatedBnd
            else bnd
          case _ => bnd
      case other => other

  /** Traverse let-binding chains to find and mark tail-recursive let-bound lambdas. */
  private def rewriteLetBoundLambdas(expr: Expr): Expr =
    expr.terms match
      case List(app: App) =>
        app.fn match
          case lambda: Lambda if lambda.params.size == 1 =>
            val param         = lambda.params.head
            val updatedArg    = rewriteLetBoundArg(app.arg, param)
            val updatedBody   = rewriteLetBoundLambdas(lambda.body)
            val updatedLambda = lambda.copy(body = updatedBody)
            if (updatedArg ne app.arg) || (updatedBody ne lambda.body) then
              Expr(expr.source, List(app.copy(fn = updatedLambda, arg = updatedArg)), expr.typeSpec)
            else expr
          case _ => expr
      case _ => expr

  /** Check if the arg of a let-binding is a lambda that self-recurses via the param. */
  private def rewriteLetBoundArg(arg: Expr, param: FnParam): Expr =
    arg.terms match
      case List(innerLambda: Lambda) if hasTailRecursiveCallByParam(innerLambda.body, param) =>
        val meta    = innerLambda.meta.getOrElse(LambdaMeta())
        val updated = innerLambda.copy(meta = Some(meta.copy(isTailRecursive = true)))
        Expr(arg.source, List(updated), arg.typeSpec)
      case _ => arg

  /** Check if expr contains a tail-recursive call to bnd in terminal position. */
  private def hasTailRecursiveCall(expr: Expr, bnd: Bnd): Boolean =
    hasTailRecursiveCallById(expr, bnd.name, bnd.id)

  /** Check if expr contains a tail-recursive call to a let-bound param. */
  private def hasTailRecursiveCallByParam(expr: Expr, param: FnParam): Boolean =
    hasTailRecursiveCallById(expr, param.name, param.id)

  private def hasTailRecursiveCallById(
    expr:     Expr,
    selfName: String,
    selfId:   Option[String]
  ): Boolean =
    expr.terms match
      case List(term) => hasTailRecursiveCallInTerm(term, selfName, selfId)
      case _ => false

  private def hasTailRecursiveCallInTerm(
    term:     Term,
    selfName: String,
    selfId:   Option[String]
  ): Boolean =
    term match
      case cond: Cond =>
        hasTailRecursiveCallById(cond.ifTrue, selfName, selfId) ||
        hasTailRecursiveCallById(cond.ifFalse, selfName, selfId)

      case app: App =>
        app.fn match
          case lambda: Lambda =>
            hasTailRecursiveCallById(lambda.body, selfName, selfId)
          case _ =>
            isSelfCall(app, selfName, selfId)

      case _ => false

  private def isSelfCall(
    app:      App,
    selfName: String,
    selfId:   Option[String]
  ): Boolean =
    collectCallee(app) match
      case Some(ref) => isSelfRef(ref, selfName, selfId)
      case None => false

  /** Extract the callee Ref from a curried application chain */
  private def collectCallee(app: App): Option[Ref] =
    app.fn match
      case ref:  Ref => Some(ref)
      case next: App => collectCallee(next)
      case _ => None

  private def isSelfRef(ref: Ref, selfName: String, selfId: Option[String]): Boolean =
    if ref.qualifier.isDefined then false
    else
      ref.resolvedId match
        case Some(id) => selfId.contains(id)
        case None => ref.name == selfName
