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
            val meta       = lambda.meta.getOrElse(LambdaMeta())
            val updated    = lambda.copy(meta = Some(meta.copy(isTailRecursive = true)))
            val updatedBnd = bnd.copy(value = bnd.value.copy(terms = updated :: rest))
            updatedBnd
          case _ => bnd
      case other => other

  /** Check if expr contains a tail-recursive call to bnd in terminal position. */
  private def hasTailRecursiveCall(expr: Expr, bnd: Bnd): Boolean =
    expr.terms match
      case List(term) => hasTailRecursiveCallInTerm(term, bnd)
      case _ => false

  /** Check if a term contains a tail-recursive call in terminal position. */
  private def hasTailRecursiveCallInTerm(term: Term, bnd: Bnd): Boolean =
    term match
      case cond: Cond =>
        hasTailRecursiveCall(cond.ifTrue, bnd) || hasTailRecursiveCall(cond.ifFalse, bnd)

      case app: App =>
        app.fn match
          case lambda: Lambda =>
            // Both sequence lambdas (__stmt) and let-binding lambdas traverse into body
            hasTailRecursiveCall(lambda.body, bnd)
          case _ =>
            isSelfCall(app, bnd)

      case _ => false

  /** Check if app is a direct call to self (bnd) */
  private def isSelfCall(app: App, bnd: Bnd): Boolean =
    collectCallee(app) match
      case Some(ref) => isSelfRef(ref, bnd)
      case None => false

  /** Extract the callee Ref from a curried application chain */
  private def collectCallee(app: App): Option[Ref] =
    app.fn match
      case ref:  Ref => Some(ref)
      case next: App => collectCallee(next)
      case _ => None

  private def isSelfRef(ref: Ref, bnd: Bnd): Boolean =
    if ref.qualifier.isDefined then return false
    // Compare by ID if available, otherwise fall back to name
    ref.resolvedId match
      case Some(id) => bnd.id.contains(id)
      case None => ref.name == bnd.name
