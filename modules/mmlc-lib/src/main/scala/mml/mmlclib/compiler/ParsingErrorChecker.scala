package mml.mmlclib.compiler

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.SemanticError

/** Checks for ParsingError instances in a module. */
object ParsingErrorChecker:

  private val phaseName = "mml.mmlclib.compiler.ParsingErrorChecker"

  private def termErrorsInMember(member: Member): List[SemanticError] =
    member match
      case bnd: Bnd =>
        termErrorsInExpr(bnd.value)
      case invalid: InvalidMember =>
        termErrorsInMember(invalid.originalMember)
      case duplicate: DuplicateMember =>
        termErrorsInMember(duplicate.originalMember)
      case _ =>
        Nil

  private def termErrorsInExpr(expr: Expr): List[SemanticError] =
    expr.terms.flatMap(termErrorsInTerm)

  private def termErrorsInTerm(term: Term): List[SemanticError] =
    term match
      case error: TermError =>
        List(SemanticError.TermErrorFound(error, phaseName))
      case expr: Expr =>
        termErrorsInExpr(expr)
      case cond: Cond =>
        termErrorsInExpr(cond.cond) ++
          termErrorsInExpr(cond.ifTrue) ++
          termErrorsInExpr(cond.ifFalse)
      case app: App =>
        termErrorsInTerm(app.fn) ++ termErrorsInExpr(app.arg)
      case lambda: Lambda =>
        termErrorsInExpr(lambda.body)
      case group: TermGroup =>
        termErrorsInExpr(group.inner)
      case tuple: Tuple =>
        tuple.elements.toList.flatMap(termErrorsInExpr)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(termErrorsInTerm)
      case invalid: InvalidExpression =>
        termErrorsInExpr(invalid.originalExpr)
      case _ =>
        Nil

  /** Check for MemberError instances in a module, accumulating errors in the state. */
  def checkModule(state: CompilerState): CompilerState =
    val memberErrors = state.module.members.collect {
      case error: ParsingMemberError =>
        SemanticError.MemberErrorFound(error, phaseName)
      case error: ParsingIdError =>
        SemanticError.ParsingIdErrorFound(error, phaseName)
    }
    val termErrors = state.module.members.flatMap(termErrorsInMember)
    val errors     = memberErrors ++ termErrors
    state.addErrors(errors)
