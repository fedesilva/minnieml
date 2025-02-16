package mml.mmlclib.semantic

import mml.mmlclib.ast.*

enum SemanticError:
  case UndefinedRef(ref: Ref, member: Member)
  case InvalidExpression(expr: Expr, message: String)

def injectStandardOperators(module: Module): Module =
  val binOps =
    List(
      ("^", 90, Associativity.Right),
      ("*", 80, Associativity.Left),
      ("/", 80, Associativity.Left),
      ("+", 60, Associativity.Left),
      ("-", 60, Associativity.Left)
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

  val unaryOps =
    List(
      ("-", 95, Associativity.Right),
      ("+", 95, Associativity.Right),
      ("!", 95, Associativity.Left)
    ).map { case (name, prec, assoc) =>
      val dummySpan = SourceSpan(SourcePoint(0, 0, 0), SourcePoint(0, 0, 0))
      UnaryOpDef(
        span       = dummySpan,
        name       = name,
        param      = FnParam(dummySpan, "a"),
        precedence = prec,
        assoc      = assoc,
        body       = Expr(dummySpan, List(Hole(dummySpan))),
        typeSpec   = None,
        typeAsc    = None,
        docComment = None
      )
    }

  val standardOps = binOps ++ unaryOps

  module.copy(members = standardOps ++ module.members)

def validateAllRefs(module: Module): List[SemanticError] =
  module.members.flatMap {
    case bnd: Bnd =>
      collectBadRefs(bnd.value, module).map(SemanticError.UndefinedRef(_, bnd))
    case fnDef: FnDef =>
      collectBadRefs(fnDef.body, module).map(SemanticError.UndefinedRef(_, fnDef))
    case opDef: OpDef =>
      collectBadRefs(opDef.body, module).map(SemanticError.UndefinedRef(_, opDef))
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
