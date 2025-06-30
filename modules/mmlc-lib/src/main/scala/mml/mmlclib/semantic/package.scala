package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

enum SemanticError extends CompilationError:
  case UndefinedRef(ref: Ref, member: Member, phase: String)
  case UndefinedTypeRef(typeRef: TypeRef, member: Member, phase: String)
  case DuplicateName(name: String, duplicates: List[Resolvable], phase: String)
  case InvalidExpression(expr: Expr, message: String, phase: String)
  case DanglingTerms(terms: List[Term], message: String, phase: String)
  case MemberErrorFound(error: MemberError, phase: String)
  case InvalidExpressionFound(invalidExpr: mml.mmlclib.ast.InvalidExpression, phase: String)

/** State that threads through semantic phases, accumulating errors while transforming the module */
case class SemanticPhaseState(
  module: Module,
  errors: Vector[SemanticError]
):
  /** Add errors to the state */
  def addErrors(newErrors: List[SemanticError]): SemanticPhaseState =
    copy(errors = errors ++ newErrors)

  /** Add a single error to the state */
  def addError(error: SemanticError): SemanticPhaseState =
    copy(errors = errors :+ error)

  /** Update the module in the state */
  def withModule(newModule: Module): SemanticPhaseState =
    copy(module = newModule)

/** This is required because we don't have multiple file, cross module capabilities
  */
def injectStandardOperators(module: Module): Module =

  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  val binOps =
    List(
      ("^", 90, Associativity.Right),
      ("*", 80, Associativity.Left),
      ("/", 80, Associativity.Left),
      ("+", 60, Associativity.Left),
      ("-", 60, Associativity.Left),
      ("==", 50, Associativity.Left),
      ("!=", 50, Associativity.Left),
      ("<", 50, Associativity.Left),
      (">", 50, Associativity.Left),
      ("<=", 50, Associativity.Left),
      (">=", 50, Associativity.Left),
      ("and", 40, Associativity.Left),
      ("or", 30, Associativity.Left)
    ).map { case (name, prec, assoc) =>
      BinOpDef(
        span       = dummySpan,
        name       = name,
        param1     = FnParam(dummySpan, "a"),
        param2     = FnParam(dummySpan, "b"),
        precedence = prec,
        assoc      = assoc,
        body       = Expr(dummySpan, List(NativeImpl(dummySpan))),
        typeSpec   = None,
        typeAsc    = None,
        docComment = None
      )
    }

  val unaryOps =
    List(
      ("-", 95, Associativity.Right),
      ("+", 95, Associativity.Right),
      ("not", 95, Associativity.Right)
    ).map { case (name, prec, assoc) =>
      UnaryOpDef(
        span       = dummySpan,
        name       = name,
        param      = FnParam(dummySpan, "a"),
        precedence = prec,
        assoc      = assoc,
        body       = Expr(dummySpan, List(NativeImpl(dummySpan))),
        typeSpec   = None,
        typeAsc    = None,
        docComment = None
      )
    }

  val standardOps = binOps ++ unaryOps

  module.copy(members = standardOps ++ module.members)

def collectBadRefs(expr: Expr, module: Module): List[Ref] =
  expr.terms.foldLeft(List.empty[Ref]) {
    case (acc, ref: Ref) =>
      if lookupRef(ref, module).isDefined then acc
      else ref :: acc
    case (acc, group: TermGroup) =>
      acc ++ collectBadRefs(group.inner, module)
    case (acc, expr: Expr) =>
      acc ++ collectBadRefs(expr, module)
    case (acc, _) => acc
  }

def lookupRef(term: Ref, module: Module): Option[Member] =
  module.members.find {
    case bnd:   Bnd => bnd.name == term.name
    case fnDef: FnDef => fnDef.name == term.name
    case opDef: OpDef => opDef.name == term.name
    case _ => false
  }

def lookupNames(name: String, module: Module): Seq[Member] =
  module.members.collect {
    case bnd:   Bnd if bnd.name == name => bnd
    case fnDef: FnDef if fnDef.name == name => fnDef
    case opDef: OpDef if opDef.name == name => opDef
  }

// --- Extractors to cleanup pattern matching ---
//
object IsOpRef:
  def unapply(term: Term): Option[(Ref, OpDef, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case op: BinOpDef => (ref, op, op.precedence, op.assoc)
        case op: UnaryOpDef => (ref, op, op.precedence, op.assoc)
      }
    case _ => None

object IsBinOpRef:
  def unapply(term: Term): Option[(Ref, BinOpDef, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst { case op: BinOpDef =>
        (ref, op, op.precedence, op.assoc)
      }
    case _ => None

object IsPrefixOpRef:
  def unapply(term: Term): Option[(Ref, UnaryOpDef, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case op: UnaryOpDef if op.assoc == Associativity.Right =>
          (ref, op, op.precedence, op.assoc)
      }
    case _ => None

object IsPostfixOpRef:
  def unapply(term: Term): Option[(Ref, UnaryOpDef, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case op: UnaryOpDef if op.assoc == Associativity.Left =>
          (ref, op, op.precedence, op.assoc)
      }
    case _ => None

object IsFnRef:
  def unapply(term: Term): Option[(Ref, FnDef)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst { case fn: FnDef =>
        (ref, fn)
      }
    case _ => None

object IsAtom:
  def unapply(term: Term): Option[Term] = term match
    case v:   LiteralValue => v.some
    case g:   TermGroup => g.some
    case h:   Hole => h.some
    case ref: Ref if ref.candidates.exists(c => c.isInstanceOf[Bnd] || c.isInstanceOf[FnParam]) =>
      ref.some
    case x => x.some
