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
      def getPrecedence(term: Term): Option[Int] = term match
        case Ref(_, name, _, _) => operators.get(name)
        case _ => None

      def groupTerms(terms: List[Term], precedence: Int): List[Term] =
        if terms.length < 3 then terms
        else
          val indices = terms.zipWithIndex.collect {
            case (op @ Ref(_, name, _, _), idx) if operators.get(name).contains(precedence) => idx
          }

          indices.foldLeft(terms) { (currentTerms, idx) =>
            if idx > 0 && idx < currentTerms.length - 1 then
              val (before, rest) = currentTerms.splitAt(idx - 1)
              val leftOperand    = rest.head
              val op             = rest(1)
              val rightOperand   = rest(2)
              val after          = rest.drop(3)

              val grouped = GroupTerm(
                SourceSpan(leftOperand.span.start, rightOperand.span.end),
                Expr(expr.span, List(leftOperand, op, rightOperand))
              )
              before ++ List(grouped) ++ after
            else currentTerms
          }

      val allPrecedences = operators.values.toSet.toList.sorted
      val rewrittenTerms = allPrecedences.foldLeft(expr.terms) { (terms, prec) =>
        groupTerms(terms, prec)
      }

      Expr(expr.span, rewrittenTerms, expr.typeSpec)

    def rewriteMember(member: Member): Member = member match
      case bnd @ Bnd(_, _, expr, _, _, _) =>
        bnd.copy(value = rewriteExpr(expr))
      case fn @ FnDef(_, _, _, body, _, _, _) =>
        fn.copy(body = rewriteExpr(body))
      case other => other

    module.copy(members = module.members.map(rewriteMember))
