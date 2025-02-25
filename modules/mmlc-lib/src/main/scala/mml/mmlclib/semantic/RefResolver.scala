package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

object RefResolver:

  /** Resolve all references in a module. Returns either a list of errors or a new module with
    * resolved references.
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
        resolveExpr(bnd.value, module, bnd).map(updatedExpr => bnd.copy(value = updatedExpr))
      case fnDef: FnDef =>
        resolveExpr(fnDef.body, module, fnDef).map(updatedExpr => fnDef.copy(body = updatedExpr))
      case opDef: OpDef =>
        resolveExpr(opDef.body, module, opDef).map { updatedExpr =>
          opDef match
            case bin:   BinOpDef => bin.copy(body = updatedExpr)
            case unary: UnaryOpDef => unary.copy(body = updatedExpr)
        }
      case _ =>
        Right(member)

  /** Returns all members (bindings, functions, operators) whose name matches the reference. */
  private def lookupRefs(ref: Ref, module: Module): List[Member] =
    module.members.collect {
      case bnd:   Bnd if bnd.name == ref.name => bnd
      case fnDef: FnDef if fnDef.name == ref.name => fnDef
      case opDef: OpDef if opDef.name == ref.name => opDef
    }

  /** Resolve references in an expression.
    *
    * We traverse the list of terms while tracking whether we expect an operand.
    *
    *   - In **operand position** we first try to resolve to a non‑operator value (e.g. a binding or
    *     function). If none is found, we try to resolve to a *prefix* unary operator (one with
    *     right associativity) and **remain expecting an operand**.
    *
    *   - In **operator position** we first try for a binary operator. Otherwise, we try a *postfix*
    *     unary operator (one with left associativity) and then stop expecting an operand.
    */
  private def resolveExpr(
    expr:   Expr,
    module: Module,
    owner:  Member
  ): Either[List[SemanticError], Expr] =
    // State: (accumulated terms, flag indicating if an operand is expected, accumulated errors)
    val initial: (List[Term], Boolean, List[SemanticError]) = (Nil, true, Nil)

    val (resolvedTerms, expectOperand, errors) =
      expr.terms.foldLeft(initial) { case ((acc, expectOperand, errs), term) =>
        term match
          case ref: Ref =>
            val candidates = lookupRefs(ref, module)
            if expectOperand then
              // In operand position, try non‑operator value first.
              candidates.find {
                case _: Bnd => true
                case _: FnDef => true
                case _ => false
              } match
                case Some(member) =>
                  (acc :+ ref.copy(resolvedAs = Some(member)), false, errs)
                case None =>
                  // Otherwise, try resolving as a prefix unary operator (only accept those with right associativity).
                  candidates.collect {
                    case op: UnaryOpDef if op.assoc == Associativity.Right => op
                  } match
                    case unaryOp :: _ =>
                      // Keep expecting an operand so that additional prefix operators can chain.
                      (acc :+ ref.copy(resolvedAs = Some(unaryOp)), true, errs)
                    case Nil =>
                      // Fallback: if a binary operator exists (unlikely here) or nothing found.
                      candidates.collect { case op: BinOpDef => op } match
                        case binOp :: _ =>
                          (
                            acc :+ ref.copy(resolvedAs = Some(binOp)),
                            false,
                            errs :+ SemanticError.UndefinedRef(ref, owner)
                          )
                        case Nil =>
                          (acc :+ ref, false, errs :+ SemanticError.UndefinedRef(ref, owner))
            else
              // In operator position: first try a binary operator,
              // otherwise allow a postfix unary operator (with left associativity).
              candidates.collect { case op: BinOpDef => op } match
                case binOp :: _ =>
                  (acc :+ ref.copy(resolvedAs = Some(binOp)), true, errs)
                case Nil =>
                  candidates.collect {
                    case op: UnaryOpDef if op.assoc == Associativity.Left => op
                  } match
                    case unaryOp :: _ =>
                      (acc :+ ref.copy(resolvedAs = Some(unaryOp)), false, errs)
                    case Nil =>
                      (acc :+ ref, false, errs :+ SemanticError.UndefinedRef(ref, owner))
          case group: GroupTerm =>
            // A group is an expression by itself; its inner expression starts expecting an operand.
            resolveExpr(group.inner, module, owner) match
              case Right(updatedInner) =>
                (acc :+ group.copy(inner = updatedInner), false, errs)
              case Left(innerErrs) =>
                (acc :+ group, false, errs ++ innerErrs)
          case nested: Expr =>
            // Recursively resolve nested expressions.
            resolveExpr(nested, module, owner) match
              case Right(updated) =>
                (acc :+ updated, false, errs)
              case Left(innerErrs) =>
                (acc :+ nested, false, errs ++ innerErrs)
          case other =>
            // Literals and other terms are treated as operands.
            (acc :+ other, false, errs)
      }

    // If we end still expecting an operand, then there was a trailing operator.
    val finalErrors =
      if expectOperand then
        errors :+ SemanticError.InvalidExpression(expr, "Trailing operator without operand")
      else errors

    if finalErrors.nonEmpty then Left(finalErrors)
    else Right(expr.copy(terms = resolvedTerms))
