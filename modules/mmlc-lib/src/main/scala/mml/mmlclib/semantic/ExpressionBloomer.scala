package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import cats.syntax.all.*

object ExpressionBloomer:

  enum RewriteError:
    case UndefinedRef(ref: Ref, member: Member)
    case UndefinedOperator(ref: Ref)

  // Top-level: rewrite members (Bnd, FnDef and OpDef) in a module.
  // We will start with simple operations on expressions.
  // TODO
  //  - Implement rewriteBnd
  //  - Implement binary operators
  //  - Implement prefix operators
  //  - Implement postfix operators
  //  - Implement function calls (same as prefix operators)
  //  - Implement rewriteFnDef
  //  - Implement rewriteOpDef
  def rewriteModule(module: Module): Either[List[RewriteError], Module] =

    val newModule = injectStandardOperators(module)

    // Validate all references in the module.
    val badRefs = validateAllRefs(newModule)
    if badRefs.nonEmpty then badRefs.asLeft
    else
      val newMembers = newModule.members.map {
        case bnd: Bnd => rewriteBnd(bnd)
        // case fnDef: FnDef => rewriteFnDef(fnDef)
        // case opDef: OpDef => rewriteOpDef(opDef)
        case other => other
      }
      ???

  // Rewrite a binding.
  def rewriteBnd(bnd: Bnd): Bnd =
    // A binding is a simple expression, so we can rewrite it directly.
    // We will start by rewriting the expression.
    ???

  // Rewrite an expression.
  def rewriteExpr(expr: Expr): Expr =
    // An expression is a list of terms, so we can rewrite each term.
    // We will start by rewriting the terms.
    ???

  def validateAllRefs(module: Module): List[RewriteError] =
    module.members.flatMap {
      case bnd: Bnd =>
        collectBadRefs(bnd.value, module).map(RewriteError.UndefinedRef(_, bnd))
      case fnDef: FnDef =>
        collectBadRefs(fnDef.body, module).map(RewriteError.UndefinedRef(_, fnDef))
      case opDef: OpDef =>
        collectBadRefs(opDef.body, module).map(RewriteError.UndefinedRef(_, opDef))
    }

  def collectBadRefs(expr: Expr, module: Module): List[Ref] =
    expr.terms.foldLeft(List.empty[Ref]) {
      case (acc, ref: Ref) =>
        if lookupRef(ref, module).isDefined then ref :: acc
        else acc
      case (acc, group: GroupTerm) =>
        acc ++ collectBadRefs(group.inner, module)
      case (acc, expr: Expr) =>
        acc ++ collectBadRefs(expr, module)
    }

  def isRefOp(ref: Ref, module: Module): Option[OpDef] =
    lookupRef(ref, module) match {
      case o: OpDef => o.some
      case _ => None
    }

  def isRefFn(ref: Ref, module: Module): Option[FnDef] =
    lookupRef(ref, module) match {
      case f: FnDef => f.some
      case _ => None
    }

  // a ref is undefined if the name is not part of the modules members
  // TODO when we have imports, look there, too.
  def lookupRef(term: Ref, module: Module): Option[Member] =
    module.members.find {
      case bnd:   Bnd => bnd.name == term.name
      case fnDef: FnDef => fnDef.name == term.name
      case opDef: OpDef => opDef.name == term.name
      case _ => false
    }

  // Prepend standard operator definitions to the module.
  def injectStandardOperators(module: Module): Module =
    val standardOps = List(
      ("+", 3),
      ("-", 3),
      ("*", 2),
      ("/", 2)
    ).map { case (name, prec) =>
      val dummySpan = SourceSpan(SourcePoint(0, 0, 0), SourcePoint(0, 0, 0))
      BinOpDef(
        span       = dummySpan,
        name       = name,
        param1     = FnParam(dummySpan, "a"),
        param2     = FnParam(dummySpan, "b"),
        precedence = prec,
        assoc      = Associativity.Left,
        body       = Expr(dummySpan, List(Hole(dummySpan))),
        typeSpec   = None,
        typeAsc    = None,
        docComment = None
      )
    }
    module.copy(members = standardOps ++ module.members)
