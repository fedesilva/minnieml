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

/** Inject basic types with native mappings into the module. Uses TypeDef + TypeAlias pattern for
  * extensibility.
  */
def injectBasicTypes(module: Module): Module =
  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  val basicTypes = List(
    // Native type definitions with LLVM mappings
    TypeDef(
      span     = dummySpan,
      name     = "Int64",
      typeSpec = Some(NativePrimitive(dummySpan, "i64"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Float64",
      typeSpec = Some(NativePrimitive(dummySpan, "double"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Bool",
      typeSpec = Some(NativePrimitive(dummySpan, "i1"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "CharPtr",
      typeSpec = Some(NativePointer(dummySpan, "i8"))
    ),
    TypeDef(
      span = dummySpan,
      name = "String",
      typeSpec = Some(
        NativeStruct(
          dummySpan,
          Map("length" -> TypeRef(dummySpan, "Int64"), "data" -> TypeRef(dummySpan, "CharPtr"))
        )
      )
    ),
    TypeDef(
      span     = dummySpan,
      name     = "SizeT",
      typeSpec = Some(NativePrimitive(dummySpan, "i64"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Char",
      typeSpec = Some(NativePrimitive(dummySpan, "i8"))
    ),

    // Type aliases pointing to native types
    TypeAlias(
      span    = dummySpan,
      name    = "Int",
      typeRef = TypeRef(dummySpan, "Int64")
    ),
    TypeAlias(
      span    = dummySpan,
      name    = "Float",
      typeRef = TypeRef(dummySpan, "Float64")
    )
  )

  module.copy(
    members = basicTypes ++ module.members
  )

/** This is required because we don't have multiple file, cross module capabilities
  */
def injectStandardOperators(module: Module): Module =

  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  // Helper function to create TypeRef for basic types
  def intType  = TypeRef(dummySpan, "Int")
  def boolType = TypeRef(dummySpan, "Bool")

  // Arithmetic operators: Int -> Int -> Int
  val arithmeticOps = List(
    ("^", 90, Associativity.Right, "pow"),
    ("*", 80, Associativity.Left, "mul"),
    ("/", 80, Associativity.Left, "sdiv"),
    ("+", 60, Associativity.Left, "add"),
    ("-", 60, Associativity.Left, "sub")
  ).map { case (name, prec, assoc, llvmOp) =>
    BinOpDef(
      span       = dummySpan,
      name       = name,
      param1     = FnParam(dummySpan, "a", typeSpec = Some(intType)),
      param2     = FnParam(dummySpan, "b", typeSpec = Some(intType)),
      precedence = prec,
      assoc      = assoc,
      body       = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp)))),
      typeSpec   = Some(TypeFn(dummySpan, List(intType, intType), intType)),
      typeAsc    = None,
      docComment = None
    )
  }

  // Comparison operators: Int -> Int -> Bool
  val comparisonOps = List(
    ("==", 50, Associativity.Left, "icmp_eq"),
    ("!=", 50, Associativity.Left, "icmp_ne"),
    ("<", 50, Associativity.Left, "icmp_slt"),
    (">", 50, Associativity.Left, "icmp_sgt"),
    ("<=", 50, Associativity.Left, "icmp_sle"),
    (">=", 50, Associativity.Left, "icmp_sge")
  ).map { case (name, prec, assoc, llvmOp) =>
    BinOpDef(
      span       = dummySpan,
      name       = name,
      param1     = FnParam(dummySpan, "a", typeSpec = Some(intType)),
      param2     = FnParam(dummySpan, "b", typeSpec = Some(intType)),
      precedence = prec,
      assoc      = assoc,
      body       = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp)))),
      typeSpec   = Some(TypeFn(dummySpan, List(intType, intType), boolType)),
      typeAsc    = None,
      docComment = None
    )
  }

  // Logical operators: Bool -> Bool -> Bool
  val logicalOps = List(
    ("and", 40, Associativity.Left, "and"),
    ("or", 30, Associativity.Left, "or")
  ).map { case (name, prec, assoc, llvmOp) =>
    BinOpDef(
      span       = dummySpan,
      name       = name,
      param1     = FnParam(dummySpan, "a", typeSpec = Some(boolType)),
      param2     = FnParam(dummySpan, "b", typeSpec = Some(boolType)),
      precedence = prec,
      assoc      = assoc,
      body       = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp)))),
      typeSpec   = Some(TypeFn(dummySpan, List(boolType, boolType), boolType)),
      typeAsc    = None,
      docComment = None
    )
  }

  // Unary arithmetic operators: Int -> Int
  val unaryArithmeticOps = List(
    ("-", 95, Associativity.Right, "neg"),
    ("+", 95, Associativity.Right, "nop")
  ).map { case (name, prec, assoc, llvmOp) =>
    UnaryOpDef(
      span       = dummySpan,
      name       = name,
      param      = FnParam(dummySpan, "a", typeSpec = Some(intType)),
      precedence = prec,
      assoc      = assoc,
      body       = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp)))),
      typeSpec   = Some(TypeFn(dummySpan, List(intType), intType)),
      typeAsc    = None,
      docComment = None
    )
  }

  // Unary logical operators: Bool -> Bool
  val unaryLogicalOps = List(
    ("not", 95, Associativity.Right, "not")
  ).map { case (name, prec, assoc, llvmOp) =>
    UnaryOpDef(
      span       = dummySpan,
      name       = name,
      param      = FnParam(dummySpan, "a", typeSpec = Some(boolType)),
      precedence = prec,
      assoc      = assoc,
      body       = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp)))),
      typeSpec   = Some(TypeFn(dummySpan, List(boolType), boolType)),
      typeAsc    = None,
      docComment = None
    )
  }

  val standardOps =
    arithmeticOps ++ comparisonOps ++ logicalOps ++ unaryArithmeticOps ++ unaryLogicalOps

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
