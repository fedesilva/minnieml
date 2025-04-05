package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object RefResolver:

  /** Resolve all references in a module. Returns either a list of errors or a new module with
    * resolved references.
    *
    * This phase assumes that all references are checked by the duplicate name checker.
    *
    * TODO: if we find a duplicate or malformed reference just bail with a semantic error.
    */
  def rewriteModule(module: Module): Either[List[SemanticError], Module] =
    val updatedMembers = module.members.map(member => resolveMember(member, module))
    val errors         = updatedMembers.collect { case Left(errs) => errs }.flatten
    if errors.nonEmpty then errors.asLeft
    else module.copy(members = updatedMembers.collect { case Right(member) => member }).asRight

  /** Resolve references in a module member. */
  private def resolveMember(member: Member, module: Module): Either[List[SemanticError], Member] =
    member match
      case bnd: Bnd =>
        resolveExpr(bnd.value, bnd, module).map(updatedExpr => bnd.copy(value = updatedExpr))
      case fnDef: FnDef =>
        resolveExpr(fnDef.body, fnDef, module).map(updatedExpr => fnDef.copy(body = updatedExpr))
      case opDef: OpDef =>
        resolveExpr(opDef.body, opDef, module).map { updatedExpr =>
          opDef match
            case bin:   BinOpDef => bin.copy(body = updatedExpr)
            case unary: UnaryOpDef => unary.copy(body = updatedExpr)
        }
      case _ =>
        member.asRight[List[SemanticError]]

  /** Returns all members (bindings, functions, operators) whose name matches the reference.
    */
  private def lookupRefs(ref: Ref, member: Member, module: Module): List[Resolvable] =

    def collectMembers =
      module.members
        .filter(_ != member)
        .collect {
          case bnd:   Bnd if bnd.name == ref.name => bnd
          case fnDef: FnDef if fnDef.name == ref.name => fnDef
          case opDef: OpDef if opDef.name == ref.name => opDef
        }

    val params = member match
      case fnDef: FnDef =>
        fnDef.params.filter(_.name == ref.name)
      case opDef: OpDef =>
        opDef match
          case bin:   BinOpDef => List(bin.param1, bin.param2).filter(_.name == ref.name)
          case unary: UnaryOpDef => List(unary.param).filter(_.name == ref.name)
      case _ => Nil

    if params.nonEmpty then params else collectMembers

  /** Resolve references in an expression.
    *
    * Returns either a list of errors or a new expression with resolved references.
    *
    * Because there can be multiple symbols with the same name in the module, we need to collect all
    * candidates and store them in the reference. Later, during type checking, we will resolve the
    * reference to the correct symbol based on the context.
    */
  private def resolveExpr(
    expr:   Expr,
    member: Member,
    module: Module
  ): Either[List[SemanticError], Expr] =
    expr.terms.traverse {
      case ref: Ref =>
        val candidates = lookupRefs(ref, member, module)
        if candidates.isEmpty then List(SemanticError.UndefinedRef(ref, member)).asLeft
        else ref.copy(candidates = candidates).asRight

      case group: TermGroup =>
        resolveExpr(group.inner, member, module)
          .map(updatedExpr => group.copy(inner = updatedExpr))

      case e: Expr =>
        resolveExpr(e, member, module)
          .map(updatedExpr => e.copy(terms = updatedExpr.terms))

      case t: Tuple =>
        t.elements
          .traverse(e => resolveExpr(e, member, module))
          .map(newElems => t.copy(elements = newElems))

      case cond: Cond =>
        for
          newCond <- resolveExpr(cond.cond, member, module)
          newIfTrue <- resolveExpr(cond.ifTrue, member, module)
          newIfFalse <- resolveExpr(cond.ifFalse, member, module)
        yield cond.copy(cond = newCond, ifTrue = newIfTrue, ifFalse = newIfFalse)

      case term =>
        term.asRight[List[SemanticError]]
    } map { updatedTerms =>
      expr.copy(terms = updatedTerms)
    }
