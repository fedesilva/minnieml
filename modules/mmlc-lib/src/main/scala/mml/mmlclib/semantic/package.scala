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
    expected:   Type,
    actual:     Type,
    phase:      String,
    expectedBy: Option[String]
  )
  case UndersaturatedApplication(app: App, expectedArgs: Int, actualArgs: Int, phase: String)
  case OversaturatedApplication(app: App, expectedArgs: Int, actualArgs: Int, phase: String)
  case InvalidApplication(app: App, fnType: Type, argType: Type, phase: String)
  case InvalidSelection(ref: Ref, baseType: Type, phase: String)
  case UnknownField(ref: Ref, struct: TypeStruct, phase: String)

  // Conditional Errors
  case ConditionalBranchTypeMismatch(
    cond:      Cond,
    trueType:  Type,
    falseType: Type,
    phase:     String
  )
  case ConditionalBranchTypeUnknown(cond: Cond, phase: String)

  // General Type Errors
  case UnresolvableType(node: Typeable, context: Option[UnresolvableTypeContext], phase: String)
  case IncompatibleTypes(
    node:    AstNode,
    type1:   Type,
    type2:   Type,
    context: String,
    phase:   String
  )
  case UntypedHoleInBinding(bindingName: String, span: SrcSpan, phase: String)

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

/** Generate a stable ID for stdlib members */
private def stdlibId(name: String): Option[String] = Some(s"stdlib::$name")

/** Inject basic types with native mappings into the module.
  */
def injectBasicTypes(module: Module): Module =
  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(dummySpan, name, stdlibId(name), Nil)

  val basicTypes: List[TypeDef | TypeAlias] = List(
    // Native type definitions with LLVM mappings
    TypeDef(
      span     = dummySpan,
      name     = "Int64",
      typeSpec = Some(NativePrimitive(dummySpan, "i64")),
      id       = stdlibId("Int64")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Int32",
      typeSpec = Some(NativePrimitive(dummySpan, "i32")),
      id       = stdlibId("Int32")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Int16",
      typeSpec = Some(NativePrimitive(dummySpan, "i16")),
      id       = stdlibId("Int16")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Int8",
      typeSpec = Some(NativePrimitive(dummySpan, "i8")),
      id       = stdlibId("Int8")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Float",
      typeSpec = Some(NativePrimitive(dummySpan, "float")),
      id       = stdlibId("Float")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Double",
      typeSpec = Some(NativePrimitive(dummySpan, "double")),
      id       = stdlibId("Double")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Bool",
      typeSpec = Some(NativePrimitive(dummySpan, "i1")),
      id       = stdlibId("Bool")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "CharPtr",
      typeSpec = Some(NativePointer(dummySpan, "i8")),
      id       = stdlibId("CharPtr")
    ),
    TypeDef(
      span = dummySpan,
      name = "String",
      typeSpec = Some(
        NativeStruct(
          dummySpan,
          List("length" -> stdlibTypeRef("Int64"), "data" -> stdlibTypeRef("CharPtr"))
        )
      ),
      id = stdlibId("String")
    ),
    TypeDef(
      // This should be an alias to an MML type (which in turn will have it's own native repr)
      span     = dummySpan,
      name     = "SizeT",
      typeSpec = Some(NativePrimitive(dummySpan, "i64")),
      id       = stdlibId("SizeT")
    ),
    // same as above, should this be it's own type or an alias?
    TypeDef(
      span     = dummySpan,
      name     = "Char",
      typeSpec = Some(NativePrimitive(dummySpan, "i8")),
      id       = stdlibId("Char")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "Unit",
      typeSpec = Some(NativePrimitive(dummySpan, "void")),
      id       = stdlibId("Unit")
    ),

    // Type aliases pointing to native types
    TypeAlias(
      span    = dummySpan,
      name    = "Int",
      typeRef = stdlibTypeRef("Int64"),
      id      = stdlibId("Int")
    ),
    TypeAlias(
      span    = dummySpan,
      name    = "Byte",
      typeRef = stdlibTypeRef("Int8"),
      id      = stdlibId("Byte")
    ),
    TypeAlias(
      span    = dummySpan,
      name    = "Word",
      typeRef = stdlibTypeRef("Int8"),
      id      = stdlibId("Word")
    ),
    // Output buffer - opaque pointer to heap-allocated struct
    TypeDef(
      span     = dummySpan,
      name     = "Buffer",
      typeSpec = Some(NativePointer(dummySpan, "i8")),
      id       = stdlibId("Buffer")
    ),

    // Pointer types for arrays
    TypeDef(
      span     = dummySpan,
      name     = "Int64Ptr",
      typeSpec = Some(NativePointer(dummySpan, "i64")),
      id       = stdlibId("Int64Ptr")
    ),
    TypeDef(
      span     = dummySpan,
      name     = "StringPtr",
      typeSpec = Some(NativePointer(dummySpan, "%struct.String")),
      id       = stdlibId("StringPtr")
    ),

    // Monomorphic array types
    TypeDef(
      span = dummySpan,
      name = "IntArray",
      typeSpec = Some(
        NativeStruct(
          dummySpan,
          List(
            "length" -> stdlibTypeRef("Int64"),
            "data" -> stdlibTypeRef("Int64Ptr")
          )
        )
      ),
      id = stdlibId("IntArray")
    ),
    TypeDef(
      span = dummySpan,
      name = "StringArray",
      typeSpec = Some(
        NativeStruct(
          dummySpan,
          List(
            "length" -> stdlibTypeRef("Int64"),
            "data" -> stdlibTypeRef("StringPtr")
          )
        )
      ),
      id = stdlibId("StringArray")
    )
  )

  // Build resolvables index from stdlib types
  val typeIndex = basicTypes.foldLeft(module.resolvables) { (idx, t) =>
    idx.updatedType(t)
  }

  module.copy(
    members     = basicTypes ++ module.members,
    resolvables = typeIndex
  )

/** This is required because we don't have multiple file, cross module capabilities
  */
def injectStandardOperators(module: Module): Module =

  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(dummySpan, name, stdlibId(name), Nil)

  // Helper function to create TypeRef for basic types
  def intType  = stdlibTypeRef("Int")
  def boolType = stdlibTypeRef("Bool")

  // Helper to create a binary operator as Bnd(Lambda)
  def mkBinOp(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    tpl:        String,
    paramType:  Type,
    returnType: Type
  ): Bnd =
    val param1      = FnParam(dummySpan, "a", typeAsc = Some(paramType))
    val param2      = FnParam(dummySpan, "b", typeAsc = Some(paramType))
    val body        = Expr(dummySpan, List(NativeImpl(dummySpan, nativeTpl = Some(tpl))))
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
      meta       = Some(meta),
      id         = stdlibId(mangledName)
    )

  // Helper to create a unary operator as Bnd(Lambda)
  def mkUnaryOp(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    tpl:        String,
    paramType:  Type,
    returnType: Type
  ): Bnd =
    val param       = FnParam(dummySpan, "a", typeAsc = Some(paramType))
    val body        = Expr(dummySpan, List(NativeImpl(dummySpan, nativeTpl = Some(tpl))))
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
      meta       = Some(meta),
      id         = stdlibId(mangledName)
    )

  // Arithmetic operators: Int -> Int -> Int
  val arithmeticOps = List(
    ("*", 80, Associativity.Left, "mul %type %operand1, %operand2"),
    ("/", 80, Associativity.Left, "sdiv %type %operand1, %operand2"),
    ("%", 80, Associativity.Left, "srem %type %operand1, %operand2"),
    ("+", 60, Associativity.Left, "add %type %operand1, %operand2"),
    ("-", 60, Associativity.Left, "sub %type %operand1, %operand2"),
    ("<<", 55, Associativity.Left, "shl %type %operand1, %operand2"),
    (">>", 55, Associativity.Left, "ashr %type %operand1, %operand2")
  ).map { case (name, prec, assoc, tpl) =>
    mkBinOp(name, prec, assoc, tpl, intType, intType)
  }

  // Comparison operators: Int -> Int -> Bool
  val comparisonOps = List(
    ("==", 50, Associativity.Left, "icmp eq %type %operand1, %operand2"),
    ("!=", 50, Associativity.Left, "icmp ne %type %operand1, %operand2"),
    ("<", 50, Associativity.Left, "icmp slt %type %operand1, %operand2"),
    (">", 50, Associativity.Left, "icmp sgt %type %operand1, %operand2"),
    ("<=", 50, Associativity.Left, "icmp sle %type %operand1, %operand2"),
    (">=", 50, Associativity.Left, "icmp sge %type %operand1, %operand2")
  ).map { case (name, prec, assoc, tpl) =>
    mkBinOp(name, prec, assoc, tpl, intType, boolType)
  }

  // Logical operators: Bool -> Bool -> Bool
  val logicalOps = List(
    ("and", 40, Associativity.Left, "and %type %operand1, %operand2"),
    ("or", 30, Associativity.Left, "or %type %operand1, %operand2")
  ).map { case (name, prec, assoc, tpl) =>
    mkBinOp(name, prec, assoc, tpl, boolType, boolType)
  }

  // Unary arithmetic operators: Int -> Int
  val unaryArithmeticOps = List(
    ("-", 95, Associativity.Right, "sub %type 0, %operand"),
    ("+", 95, Associativity.Right, "add %type 0, %operand")
  ).map { case (name, prec, assoc, tpl) =>
    mkUnaryOp(name, prec, assoc, tpl, intType, intType)
  }

  // Unary logical operators: Bool -> Bool
  val unaryLogicalOps = List(
    ("not", 95, Associativity.Right, "xor %type 1, %operand")
  ).map { case (name, prec, assoc, tpl) =>
    mkUnaryOp(name, prec, assoc, tpl, boolType, boolType)
  }

  val standardOps =
    arithmeticOps ++ comparisonOps ++ logicalOps ++ unaryArithmeticOps ++ unaryLogicalOps

  // Build resolvables index from stdlib operators
  val opIndex = standardOps.foldLeft(module.resolvables) { (idx, bnd) =>
    idx.updated(bnd)
  }

  module.copy(members = standardOps ++ module.members, resolvables = opIndex)

/** Inject common native functions that are repeatedly defined across samples. Includes print,
  * println, readline, concat, to_string, and str_to_int functions.
  */
def injectCommonFunctions(module: Module): Module =
  val dummySpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(dummySpan, name, stdlibId(name), Nil)

  // Helper function to create TypeRef for basic types
  def stringType = stdlibTypeRef("String")
  def intType    = stdlibTypeRef("Int")
  def unitType   = stdlibTypeRef("Unit")
  def bufferType = stdlibTypeRef("Buffer")

  // Helper to create a function as Bnd(Lambda)
  def mkFn(name: String, params: List[FnParam], returnType: Type): Bnd =
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
      meta       = Some(meta),
      id         = stdlibId(name)
    )

  // Helper to create a binary operator that calls a function
  // Note: fnName must be a stdlib function that has already been created
  def mkBinOpExpr(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    fnName:     String,
    param1Type: Type,
    param2Type: Type,
    returnType: Type
  ): Bnd =
    val param1 = FnParam(dummySpan, "a", typeAsc = Some(param1Type))
    val param2 = FnParam(dummySpan, "b", typeAsc = Some(param2Type))
    // Create refs with resolvedId pointing to the stdlib function
    val fnRef = Ref(dummySpan, fnName, resolvedId = stdlibId(fnName))
    val body =
      Expr(dummySpan, List(fnRef, Ref(dummySpan, "a"), Ref(dummySpan, "b")))
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
      meta       = Some(meta),
      id         = stdlibId(mangledName)
    )

  val commonFunctions = List(
    mkFn("print", List(FnParam(dummySpan, "a", typeAsc = Some(stringType))), unitType),
    mkFn("println", List(FnParam(dummySpan, "a", typeAsc = Some(stringType))), unitType),
    mkFn("mml_sys_flush", List(), unitType),
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
    mkFn(
      "buffer_write_int",
      List(
        FnParam(dummySpan, "b", typeAsc = Some(bufferType)),
        FnParam(dummySpan, "n", typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "buffer_writeln_int",
      List(
        FnParam(dummySpan, "b", typeAsc = Some(bufferType)),
        FnParam(dummySpan, "n", typeAsc = Some(intType))
      ),
      unitType
    ),
    // File operations
    mkFn("open_file_read", List(FnParam(dummySpan, "path", typeAsc = Some(stringType))), intType),
    mkFn("open_file_write", List(FnParam(dummySpan, "path", typeAsc = Some(stringType))), intType),
    mkFn("open_file_append", List(FnParam(dummySpan, "path", typeAsc = Some(stringType))), intType),
    mkFn("close_file", List(FnParam(dummySpan, "fd", typeAsc = Some(intType))), unitType),
    mkFn("read_line_fd", List(FnParam(dummySpan, "fd", typeAsc = Some(intType))), stringType)
  )

  // Array type refs
  def intArrayType    = stdlibTypeRef("IntArray")
  def stringArrayType = stdlibTypeRef("StringArray")

  // Array functions
  val arrayFunctions = List(
    // IntArray functions
    mkFn("ar_int_new", List(FnParam(dummySpan, "size", typeAsc = Some(intType))), intArrayType),
    mkFn(
      "ar_int_set",
      List(
        FnParam(dummySpan, "arr", typeAsc   = Some(intArrayType)),
        FnParam(dummySpan, "idx", typeAsc   = Some(intType)),
        FnParam(dummySpan, "value", typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "ar_int_get",
      List(
        FnParam(dummySpan, "arr", typeAsc = Some(intArrayType)),
        FnParam(dummySpan, "idx", typeAsc = Some(intType))
      ),
      intType
    ),
    mkFn(
      "unsafe_ar_int_set",
      List(
        FnParam(dummySpan, "arr", typeAsc   = Some(intArrayType)),
        FnParam(dummySpan, "idx", typeAsc   = Some(intType)),
        FnParam(dummySpan, "value", typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "unsafe_ar_int_get",
      List(
        FnParam(dummySpan, "arr", typeAsc = Some(intArrayType)),
        FnParam(dummySpan, "idx", typeAsc = Some(intType))
      ),
      intType
    ),
    mkFn("ar_int_len", List(FnParam(dummySpan, "arr", typeAsc = Some(intArrayType))), intType),
    // StringArray functions
    mkFn("ar_str_new", List(FnParam(dummySpan, "size", typeAsc = Some(intType))), stringArrayType),
    mkFn(
      "ar_str_set",
      List(
        FnParam(dummySpan, "arr", typeAsc   = Some(stringArrayType)),
        FnParam(dummySpan, "idx", typeAsc   = Some(intType)),
        FnParam(dummySpan, "value", typeAsc = Some(stringType))
      ),
      unitType
    ),
    mkFn(
      "ar_str_get",
      List(
        FnParam(dummySpan, "arr", typeAsc = Some(stringArrayType)),
        FnParam(dummySpan, "idx", typeAsc = Some(intType))
      ),
      stringType
    ),
    mkFn("ar_str_len", List(FnParam(dummySpan, "arr", typeAsc = Some(stringArrayType))), intType)
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
    ),
    mkBinOpExpr(
      "write_int",
      20,
      Associativity.Left,
      "buffer_write_int",
      bufferType,
      intType,
      unitType
    ),
    mkBinOpExpr(
      "writeln_int",
      20,
      Associativity.Left,
      "buffer_writeln_int",
      bufferType,
      intType,
      unitType
    )
  )

  val allFunctions = commonFunctions ++ arrayFunctions ++ bufferOps

  // Build resolvables index from stdlib functions
  val fnIndex = allFunctions.foldLeft(module.resolvables) { (idx, bnd) =>
    idx.updated(bnd)
  }

  module.copy(members = allFunctions ++ module.members, resolvables = fnIndex)

def collectBadRefs(expr: Expr, module: Module): List[Ref] =
  expr.terms.foldLeft(List.empty[Ref]) {
    case (acc, ref: Ref) =>
      ref.qualifier match
        case Some(qualifier) =>
          val qualifierExpr = Expr(qualifier.span, List(qualifier))
          acc ++ collectBadRefs(qualifierExpr, module)
        case None =>
          if lookupRef(ref, module).isDefined then acc
          else ref :: acc
    case (acc, group: TermGroup) =>
      acc ++ collectBadRefs(group.inner, module)
    case (acc, expr: Expr) =>
      acc ++ collectBadRefs(expr, module)
    case (acc, _) => acc
  }

def lookupRef(term: Ref, module: Module): Option[Member] =
  if term.qualifier.isDefined then return None
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

/** Check if a term is an operator ref. Returns (Ref, Bnd, precedence, associativity) */
def matchOpRef(
  term:        Term,
  resolvables: ResolvablesIndex
): Option[(Ref, Bnd, Int, Associativity)] = term match
  case ref: Ref =>
    ref.candidateIds
      .flatMap(resolvables.lookup)
      .collectFirst {
        case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Operator) =>
          bnd
      }
      .flatMap { bnd =>
        bnd.meta.map(m => (ref, bnd, m.precedence, m.associativity.getOrElse(Associativity.Left)))
      }
  case _ => None

/** Check if a term is a binary operator ref */
def matchBinOpRef(
  term:        Term,
  resolvables: ResolvablesIndex
): Option[(Ref, Bnd, Int, Associativity)] = term match
  case ref: Ref =>
    ref.candidateIds
      .flatMap(resolvables.lookup)
      .collectFirst {
        case bnd: Bnd
            if bnd.meta
              .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Binary) =>
          bnd
      }
      .flatMap { bnd =>
        bnd.meta.map(m => (ref, bnd, m.precedence, m.associativity.getOrElse(Associativity.Left)))
      }
  case _ => None

/** Check if a term is a prefix operator ref */
def matchPrefixOpRef(
  term:        Term,
  resolvables: ResolvablesIndex
): Option[(Ref, Bnd, Int, Associativity)] = term match
  case ref: Ref =>
    ref.candidateIds
      .flatMap(resolvables.lookup)
      .collectFirst {
        case bnd: Bnd
            if bnd.meta.exists(m =>
              m.origin == BindingOrigin.Operator &&
                m.arity == CallableArity.Unary &&
                m.associativity.contains(Associativity.Right)
            ) =>
          bnd
      }
      .flatMap { bnd =>
        bnd.meta.map(m => (ref, bnd, m.precedence, Associativity.Right))
      }
  case _ => None

/** Check if a term is a postfix operator ref */
def matchPostfixOpRef(
  term:        Term,
  resolvables: ResolvablesIndex
): Option[(Ref, Bnd, Int, Associativity)] = term match
  case ref: Ref =>
    ref.candidateIds
      .flatMap(resolvables.lookup)
      .collectFirst {
        case bnd: Bnd
            if bnd.meta.exists(m =>
              m.origin == BindingOrigin.Operator &&
                m.arity == CallableArity.Unary &&
                m.associativity.contains(Associativity.Left)
            ) =>
          bnd
      }
      .flatMap { bnd =>
        bnd.meta.map(m => (ref, bnd, m.precedence, Associativity.Left))
      }
  case _ => None

/** Check if a term is a function ref */
def matchFnRef(term: Term, resolvables: ResolvablesIndex): Option[(Ref, Bnd)] = term match
  case ref: Ref =>
    ref.candidateIds.flatMap(resolvables.lookup).collectFirst {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
        (ref, bnd)
    }
  case _ => None

object IsAtom:
  def unapply(term: Term): Option[Term] = term match
    case _: Ref => None
    case other => other.some
