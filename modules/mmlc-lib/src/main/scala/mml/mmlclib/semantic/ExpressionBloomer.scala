package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import cats.syntax.all.*

object ExpressionBloomer:

  enum RewriteError:
    case UndefinedRef(ref: Ref, member: Member)
    case UndefinedOperator(ref: Ref)

  def rewriteModule(module: Module): Either[List[RewriteError], Module] =
    val newModule = injectStandardOperators(module)

    val badRefs = validateAllRefs(newModule)
    if badRefs.nonEmpty then badRefs.asLeft
    else
      val newMembers = newModule.members.map {
        case bnd: Bnd => rewriteBnd(bnd, newModule)
        case other => other
      }
      newModule.copy(members = newMembers).asRight

  def rewriteBnd(bnd: Bnd, module: Module): Bnd =
    bnd.copy(value = rewriteExpr(bnd.value, module))

  def rewriteExpr(expr: Expr, module: Module): Expr =
    // Get all operators with their positions
    val ops = expr.terms.zipWithIndex.collect {
      case (r: Ref, idx) if isRefOp(r, module).isDefined =>
        (r, idx, isRefOp(r, module).get)
    }

    if ops.isEmpty then return expr

    // Precedence from 10 down to 1
    val currPrec = (10 to 1 by -1).find(p => ops.exists(_._3.precedence == p)).getOrElse(0)

    if currPrec == 0 then return expr

    // Find all operators at this precedence level
    val opsAtPrec = ops.filter(_._3.precedence == currPrec)

    // For right associative, take rightmost. For left associative, take leftmost
    val op = opsAtPrec.head._3.assoc match {
      case Associativity.Left => opsAtPrec.head
      case Associativity.Right => opsAtPrec.last
    }

    // Create the group with this operator
    val (ref, pos, opDef) = op
    val left              = expr.terms.slice(0, pos)
    val right             = expr.terms.slice(pos + 1, expr.terms.length)

    // Create new expression with the group
    val newGroup = GroupTerm(
      span = expr.span,
      inner = Expr(
        span = expr.span,
        terms = List(
          Expr(expr.span, left),
          ref,
          Expr(expr.span, right)
        )
      )
    )

    // Continue rewriting
    Expr(expr.span, List(newGroup))

  def validateAllRefs(module: Module): List[RewriteError] =
    module.members.flatMap {
      case bnd: Bnd =>
        collectBadRefs(bnd.value, module).map(RewriteError.UndefinedRef(_, bnd))
      case fnDef: FnDef =>
        collectBadRefs(fnDef.body, module).map(RewriteError.UndefinedRef(_, fnDef))
      case opDef: OpDef =>
        collectBadRefs(opDef.body, module).map(RewriteError.UndefinedRef(_, opDef))
      case _ => List.empty
    }

  def collectBadRefs(expr: Expr, module: Module): List[Ref] =
    expr.terms.foldLeft(List.empty[Ref]) {
      case (acc, ref: Ref) =>
        if lookupRef(ref, module).isDefined then acc
        else ref :: acc
      case (acc, group: GroupTerm) =>
        acc ++ collectBadRefs(group.inner, module)
      case (acc, expr: Expr) =>
        acc ++ collectBadRefs(expr, module)
      case (acc, _) => acc
    }

  def isRefOp(ref: Ref, module: Module): Option[OpDef] =
    lookupRef(ref, module) match {
      case Some(o: OpDef) => Some(o)
      case _ => None
    }

  def isRefFn(ref: Ref, module: Module): Option[FnDef] =
    lookupRef(ref, module) match {
      case Some(f: FnDef) => Some(f)
      case _ => None
    }

  def lookupRef(term: Ref, module: Module): Option[Member] =
    module.members.find {
      case bnd:   Bnd => bnd.name == term.name
      case fnDef: FnDef => fnDef.name == term.name
      case opDef: OpDef => opDef.name == term.name
      case _ => false
    }

  def injectStandardOperators(module: Module): Module =
    val standardOps = List(
      ("^", 10, Associativity.Right),
      ("*", 8, Associativity.Left),
      ("/", 8, Associativity.Left),
      ("+", 6, Associativity.Left),
      ("-", 6, Associativity.Left)
    ).map { case (name, prec, assoc) =>
      val dummySpan = SourceSpan(SourcePoint(0, 0, 0), SourcePoint(0, 0, 0))
      BinOpDef(
        span       = dummySpan,
        name       = name,
        param1     = FnParam(dummySpan, "a"),
        param2     = FnParam(dummySpan, "b"),
        precedence = prec,
        assoc      = assoc,
        body       = Expr(dummySpan, List(Hole(dummySpan))),
        typeSpec   = None,
        typeAsc    = None,
        docComment = None
      )
    }
    module.copy(members = standardOps ++ module.members)
