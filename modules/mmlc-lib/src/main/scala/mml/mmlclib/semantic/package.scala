package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

enum UnresolvableTypeContext derives CanEqual:
  case NamedValue(name: String)
  case Argument
  case Function

enum TypeError extends CompilationError:
  // Function and Operator Definition Errors
  case MissingParameterType(param: FnParam, decl: Decl, phase: String)
  case MissingReturnType(decl: Decl, phase: String)
  case RecursiveFunctionMissingReturnType(decl: Decl, phase: String)
  case MissingOperatorParameterType(param: FnParam, decl: Decl, phase: String)
  case MissingOperatorReturnType(decl: Decl, phase: String)

  // Expression and Application Errors
  case TypeMismatch(
    node:       Typeable,
    expected:   TypeSpec,
    actual:     TypeSpec,
    phase:      String,
    expectedBy: Option[String]
  )
  case UndersaturatedApplication(app: App, expectedArgs: Int, actualArgs: Int, phase: String)
  case OversaturatedApplication(app: App, expectedArgs: Int, actualArgs: Int, phase: String)
  case InvalidApplication(app: App, fnType: TypeSpec, argType: TypeSpec, phase: String)

  // Conditional Errors
  case ConditionalBranchTypeMismatch(
    cond:      Cond,
    trueType:  TypeSpec,
    falseType: TypeSpec,
    phase:     String
  )
  case ConditionalBranchTypeUnknown(cond: Cond, phase: String)

  // General Type Errors
  case UnresolvableType(node: Typeable, context: Option[UnresolvableTypeContext], phase: String)
  case IncompatibleTypes(
    node:    AstNode,
    type1:   TypeSpec,
    type2:   TypeSpec,
    context: String,
    phase:   String
  )
  case UntypedHoleInBinding(bnd: Bnd, phase: String)

enum SemanticError extends CompilationError:
  case UndefinedRef(ref: Ref, member: Member, phase: String)
  case UndefinedTypeRef(typeRef: TypeRef, member: Member, phase: String)
  case DuplicateName(name: String, duplicates: List[Resolvable], phase: String)
  case InvalidExpression(expr: Expr, message: String, phase: String)
  case DanglingTerms(terms: List[Term], message: String, phase: String)
  case MemberErrorFound(error: ParsingMemberError, phase: String)
  case ParsingIdErrorFound(error: ParsingIdError, phase: String)
  case InvalidExpressionFound(invalidExpr: mml.mmlclib.ast.InvalidExpression, phase: String)
  case TypeCheckingError(error: TypeError)
  case InvalidEntryPoint(message: String, span: SrcSpan)

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

/** Inject basic types with native mappings into the module.
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
      name     = "Int32",
      typeSpec = Some(NativePrimitive(dummySpan, "i32"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Int16",
      typeSpec = Some(NativePrimitive(dummySpan, "i16"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Int8",
      typeSpec = Some(NativePrimitive(dummySpan, "i8"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Float",
      typeSpec = Some(NativePrimitive(dummySpan, "float"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Double",
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
      // This should be an alias to an MML type (which in turn will have it's own native repr)
      span     = dummySpan,
      name     = "SizeT",
      typeSpec = Some(NativePrimitive(dummySpan, "i64"))
    ),
    // same as above, should this be it's own type or an alias?
    TypeDef(
      span     = dummySpan,
      name     = "Char",
      typeSpec = Some(NativePrimitive(dummySpan, "i8"))
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Unit",
      typeSpec = Some(NativePrimitive(dummySpan, "void"))
    ),

    // Type aliases pointing to native types
    TypeAlias(
      span    = dummySpan,
      name    = "Int",
      typeRef = TypeRef(dummySpan, "Int64")
    ),
    TypeAlias(
      span    = dummySpan,
      name    = "Byte",
      typeRef = TypeRef(dummySpan, "Int8")
    ),
    TypeAlias(
      span    = dummySpan,
      name    = "Word",
      typeRef = TypeRef(dummySpan, "Int8")
    ),
    // Output buffer - opaque pointer to heap-allocated struct
    TypeDef(
      span     = dummySpan,
      name     = "Buffer",
      typeSpec = Some(NativePointer(dummySpan, "i8"))
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

  // Helper to create a binary operator as Bnd(Lambda)
  def mkBinOp(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    llvmOp:     String,
    paramType:  TypeSpec,
    returnType: TypeSpec
  ): Bnd =
    val param1      = FnParam(dummySpan, "a", typeAsc = Some(paramType))
    val param2      = FnParam(dummySpan, "b", typeAsc = Some(paramType))
    val body        = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp))))
    val mangledName = OpMangling.mangleOp(name, 2)
    val meta = BindingMeta(
      origin        = BindingOrigin.Operator,
      arity         = CallableArity.Binary,
      precedence    = prec,
      associativity = Some(assoc),
      originalName  = name,
      mangledName   = mangledName
    )
    val lambda = Lambda(
      span     = dummySpan,
      params   = List(param1, param2),
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      span       = dummySpan,
      name       = mangledName,
      value      = Expr(dummySpan, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta)
    )

  // Helper to create a unary operator as Bnd(Lambda)
  def mkUnaryOp(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    llvmOp:     String,
    paramType:  TypeSpec,
    returnType: TypeSpec
  ): Bnd =
    val param       = FnParam(dummySpan, "a", typeAsc = Some(paramType))
    val body        = Expr(dummySpan, List(NativeImpl(dummySpan, nativeOp = Some(llvmOp))))
    val mangledName = OpMangling.mangleOp(name, 1)
    val meta = BindingMeta(
      origin        = BindingOrigin.Operator,
      arity         = CallableArity.Unary,
      precedence    = prec,
      associativity = Some(assoc),
      originalName  = name,
      mangledName   = mangledName
    )
    val lambda = Lambda(
      span     = dummySpan,
      params   = List(param),
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      span       = dummySpan,
      name       = mangledName,
      value      = Expr(dummySpan, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta)
    )

  // Arithmetic operators: Int -> Int -> Int
  val arithmeticOps = List(
    ("*", 80, Associativity.Left, "mul"),
    ("/", 80, Associativity.Left, "sdiv"),
    ("+", 60, Associativity.Left, "add"),
    ("-", 60, Associativity.Left, "sub"),
    ("<<", 55, Associativity.Left, "shl"),
    (">>", 55, Associativity.Left, "ashr")
  ).map { case (name, prec, assoc, llvmOp) =>
    mkBinOp(name, prec, assoc, llvmOp, intType, intType)
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
    mkBinOp(name, prec, assoc, llvmOp, intType, boolType)
  }

  // Logical operators: Bool -> Bool -> Bool
  val logicalOps = List(
    ("and", 40, Associativity.Left, "and"),
    ("or", 30, Associativity.Left, "or")
  ).map { case (name, prec, assoc, llvmOp) =>
    mkBinOp(name, prec, assoc, llvmOp, boolType, boolType)
  }

  // Unary arithmetic operators: Int -> Int
  val unaryArithmeticOps = List(
    ("-", 95, Associativity.Right, "neg"),
    ("+", 95, Associativity.Right, "nop")
  ).map { case (name, prec, assoc, llvmOp) =>
    mkUnaryOp(name, prec, assoc, llvmOp, intType, intType)
  }

  // Unary logical operators: Bool -> Bool
  val unaryLogicalOps = List(
    ("not", 95, Associativity.Right, "not")
  ).map { case (name, prec, assoc, llvmOp) =>
    mkUnaryOp(name, prec, assoc, llvmOp, boolType, boolType)
  }

  val standardOps =
    arithmeticOps ++ comparisonOps ++ logicalOps ++ unaryArithmeticOps ++ unaryLogicalOps

  module.copy(members = standardOps ++ module.members)

/** Inject common native functions that are repeatedly defined across samples. Includes print,
  * println, readline, concat, to_string, and str_to_int functions.
  */
def injectCommonFunctions(module: Module): Module =
  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  // Helper function to create TypeRef for basic types
  def stringType = TypeRef(dummySpan, "String")
  def intType    = TypeRef(dummySpan, "Int")
  def unitType   = TypeRef(dummySpan, "Unit")
  def bufferType = TypeRef(dummySpan, "Buffer")

  // Helper to create a function as Bnd(Lambda)
  def mkFn(name: String, params: List[FnParam], returnType: TypeSpec): Bnd =
    val arity = params.size match
      case 0 => CallableArity.Nullary
      case 1 => CallableArity.Unary
      case 2 => CallableArity.Binary
      case n => CallableArity.Nary(n)
    val meta = BindingMeta(
      origin        = BindingOrigin.Function,
      arity         = arity,
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = name,
      mangledName   = name
    )
    val body = Expr(dummySpan, List(NativeImpl(dummySpan)))
    val lambda = Lambda(
      span     = dummySpan,
      params   = params,
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      span       = dummySpan,
      name       = name,
      value      = Expr(dummySpan, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta)
    )

  // Helper to create a binary operator that calls a function
  def mkBinOpExpr(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    fnName:     String,
    param1Type: TypeSpec,
    param2Type: TypeSpec,
    returnType: TypeSpec
  ): Bnd =
    val param1 = FnParam(dummySpan, "a", typeAsc = Some(param1Type))
    val param2 = FnParam(dummySpan, "b", typeAsc = Some(param2Type))
    val body =
      Expr(dummySpan, List(Ref(dummySpan, fnName), Ref(dummySpan, "a"), Ref(dummySpan, "b")))
    val mangledName = OpMangling.mangleOp(name, 2)
    val meta = BindingMeta(
      origin        = BindingOrigin.Operator,
      arity         = CallableArity.Binary,
      precedence    = prec,
      associativity = Some(assoc),
      originalName  = name,
      mangledName   = mangledName
    )
    val lambda = Lambda(
      span     = dummySpan,
      params   = List(param1, param2),
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      span       = dummySpan,
      name       = mangledName,
      value      = Expr(dummySpan, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta)
    )

  val commonFunctions = List(
    mkFn("print", List(FnParam(dummySpan, "a", typeAsc = Some(stringType))), unitType),
    mkFn("println", List(FnParam(dummySpan, "a", typeAsc = Some(stringType))), unitType),
    mkFn("readline", List(), stringType),
    mkFn(
      "concat",
      List(
        FnParam(dummySpan, "a", typeAsc = Some(stringType)),
        FnParam(dummySpan, "b", typeAsc = Some(stringType))
      ),
      stringType
    ),
    mkFn("to_string", List(FnParam(dummySpan, "a", typeAsc = Some(intType))), stringType),
    mkFn("str_to_int", List(FnParam(dummySpan, "a", typeAsc = Some(stringType))), intType),
    // Buffer functions
    mkFn("mkBuffer", List(), bufferType),
    mkFn("mkBufferWithFd", List(FnParam(dummySpan, "fd", typeAsc = Some(intType))), bufferType),
    mkFn("mkBufferWithSize", List(FnParam(dummySpan, "size", typeAsc = Some(intType))), bufferType),
    mkFn("flush", List(FnParam(dummySpan, "b", typeAsc = Some(bufferType))), unitType),
    mkFn(
      "buffer_write",
      List(
        FnParam(dummySpan, "b", typeAsc = Some(bufferType)),
        FnParam(dummySpan, "s", typeAsc = Some(stringType))
      ),
      unitType
    ),
    mkFn(
      "buffer_writeln",
      List(
        FnParam(dummySpan, "b", typeAsc = Some(bufferType)),
        FnParam(dummySpan, "s", typeAsc = Some(stringType))
      ),
      unitType
    ),
    // File operations
    mkFn("open_file_read", List(FnParam(dummySpan, "path", typeAsc = Some(stringType))), intType),
    mkFn("open_file_write", List(FnParam(dummySpan, "path", typeAsc = Some(stringType))), intType),
    mkFn("open_file_append", List(FnParam(dummySpan, "path", typeAsc = Some(stringType))), intType),
    mkFn("close_file", List(FnParam(dummySpan, "fd", typeAsc = Some(intType))), unitType)
  )

  // Buffer operators that wrap native functions
  val bufferOps = List(
    mkBinOpExpr("write", 20, Associativity.Left, "buffer_write", bufferType, stringType, unitType),
    mkBinOpExpr(
      "writeln",
      20,
      Associativity.Left,
      "buffer_writeln",
      bufferType,
      stringType,
      unitType
    )
  )

  module.copy(members = commonFunctions ++ bufferOps ++ module.members)

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
  // For operators, we need to match by originalName from meta
  module.members.find {
    case bnd: Bnd =>
      bnd.name == term.name ||
      bnd.meta.exists(_.originalName == term.name)
    case _ => false
  }

def lookupNames(name: String, module: Module): Seq[Member] =
  // For operators, we need to match by originalName from meta
  module.members.collect {
    case bnd: Bnd if bnd.name == name || bnd.meta.exists(_.originalName == name) => bnd
  }

// --- Extractors to cleanup pattern matching ---
//
// These extractors read from BindingMeta to determine operator/function properties.
// Return type is (Ref, Bnd, precedence, associativity) for operators.

object IsOpRef:
  def unapply(term: Term): Option[(Ref, Bnd, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Operator) =>
          val m = bnd.meta.get
          (ref, bnd, m.precedence, m.associativity.getOrElse(Associativity.Left))
      }
    case _ => None

object IsBinOpRef:
  def unapply(term: Term): Option[(Ref, Bnd, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case bnd: Bnd
            if bnd.meta
              .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Binary) =>
          val m = bnd.meta.get
          (ref, bnd, m.precedence, m.associativity.getOrElse(Associativity.Left))
      }
    case _ => None

object IsPrefixOpRef:
  def unapply(term: Term): Option[(Ref, Bnd, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case bnd: Bnd
            if bnd.meta.exists(m =>
              m.origin == BindingOrigin.Operator &&
                m.arity == CallableArity.Unary &&
                m.associativity.contains(Associativity.Right)
            ) =>
          val m = bnd.meta.get
          (ref, bnd, m.precedence, Associativity.Right)
      }
    case _ => None

object IsPostfixOpRef:
  def unapply(term: Term): Option[(Ref, Bnd, Int, Associativity)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case bnd: Bnd
            if bnd.meta.exists(m =>
              m.origin == BindingOrigin.Operator &&
                m.arity == CallableArity.Unary &&
                m.associativity.contains(Associativity.Left)
            ) =>
          val m = bnd.meta.get
          (ref, bnd, m.precedence, Associativity.Left)
      }
    case _ => None

object IsFnRef:
  def unapply(term: Term): Option[(Ref, Bnd)] = term match
    case ref: Ref =>
      ref.candidates.collectFirst {
        case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
          (ref, bnd)
      }
    case _ => None

object IsAtom:
  def unapply(term: Term): Option[Term] = term match
    case _: Ref => None
    case other => other.some
