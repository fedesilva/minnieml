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
      def findNextOperator(terms: List[Term], precedence: Int): Option[(Int, Term)] =
        terms.zipWithIndex.collect {
          case (op @ Ref(_, name, _, _), idx) if operators.get(name).contains(precedence) =>
            (idx, op)
        }.headOption

      def groupAtPrecedence(terms: List[Term], precedence: Int): List[Term] =
        findNextOperator(terms, precedence) match
          case None => terms
          case Some((idx, op)) if idx > 0 && idx < terms.length - 1 =>
            val (before, rest) = terms.splitAt(idx)
            val leftOperand    = before.last
            val rightOperand   = rest.tail.head
            val afterGroup     = rest.drop(2)

            val grouped = GroupTerm(
              SourceSpan(leftOperand.span.start, rightOperand.span.end),
              Expr(expr.span, List(leftOperand, op, rightOperand))
            )

            groupAtPrecedence(before.dropRight(1) ++ List(grouped) ++ afterGroup, precedence)
          case _ => terms

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
