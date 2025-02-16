package mml.mmlclib.semantic

import mml.mmlclib.ast.*

object ExpressionBloomer:
  private def injectStandardOperators(module: Module): Module =
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
        body       = Expr(dummySpan, List(Hole(dummySpan))),
        typeSpec   = None,
        typeAsc    = None,
        docComment = None
      )
    }
    module.copy(members = standardOps ++ module.members)

  def rewriteModule(module: Module): Module =
    val moduleWithOps = injectStandardOperators(module)
    val operators = moduleWithOps.members.foldLeft(Map.empty[String, Int]) {
      case (ops, BinOpDef(_, name, _, _, prec, _, _, _, _)) => ops + (name -> prec)
      case (ops, UnaryOpDef(_, name, _, prec, _, _, _, _, _)) => ops + (name -> prec)
      case (ops, _) => ops
    }

    def rewriteExpr(expr: Expr): Expr =
      def precedence(term: Term): Int = term match
        case Ref(_, name, _, _) => operators.getOrElse(name, 0)
        case _ => 0

      def groupAtPrecedence(terms: List[Term], targetPrec: Int): List[Term] =
        terms match
          case left :: op :: right :: rest if precedence(op) == targetPrec =>
            val grouped = GroupTerm(
              SourceSpan(left.span.start, right.span.end),
              Expr(expr.span, List(left, op, right))
            )
            groupAtPrecedence(grouped :: rest, targetPrec)
          case t :: rest => t :: groupAtPrecedence(rest, targetPrec)
          case Nil => Nil

      val allPrecedences = operators.values.toSet.toList.sorted
      val rewrittenTerms = allPrecedences.foldLeft(expr.terms) { (terms, prec) =>
        groupAtPrecedence(terms, prec)
      }

      Expr(expr.span, rewrittenTerms, expr.typeSpec)

    def rewriteMember(member: Member): Member = member match
      case bnd @ Bnd(_, _, expr, _, _, _) =>
        bnd.copy(value = rewriteExpr(expr))
      case fn @ FnDef(_, _, _, body, _, _, _) =>
        fn.copy(body = rewriteExpr(body))
      case other => other

    module.copy(members = module.members.map(rewriteMember))
