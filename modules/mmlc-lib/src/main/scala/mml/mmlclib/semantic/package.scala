package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

enum UnresolvableTypeContext derives CanEqual:
  case NamedValue(name: String)
  case Argument
  case Function

private def showType(t: Type): String = t match
  case TypeRef(_, name, _, _) => name
  case ts: TypeStruct => ts.name
  case TypeFn(_, params, ret) => s"(${params.map(showType).mkString(", ")}) -> ${showType(ret)}"
  case TypeUnit(_) => "()"
  case TypeTuple(_, elems) => s"(${elems.map(showType).mkString(", ")})"
  case NativePrimitive(_, llvm, _, _) => llvm
  case NativePointer(_, llvm, _, _) => s"*$llvm"
  case TypeVariable(_, name) => name
  case _ => t.getClass.getSimpleName

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
  case UntypedHoleInBinding(bindingName: String, source: SourceOrigin, phase: String)

  // TODO:   Why not define this within the members?
  def message: String = this match
    case MissingParameterType(param, _, _) =>
      s"Missing type annotation for parameter '${param.name}'"
    case MissingReturnType(decl, _) =>
      s"Missing return type for '${decl.name}'"
    case RecursiveFunctionMissingReturnType(decl, _) =>
      s"Recursive function '${decl.name}' requires explicit return type"
    case MissingOperatorParameterType(param, _, _) =>
      s"Missing type annotation for operator parameter '${param.name}'"
    case MissingOperatorReturnType(decl, _) =>
      s"Missing return type for operator '${decl.name}'"
    case TypeMismatch(_, expected, actual, _, expectedBy) =>
      val ctx = expectedBy.map(by => s" (expected by $by)").getOrElse("")
      s"Type mismatch: expected ${showType(expected)}, got ${showType(actual)}$ctx"
    case UndersaturatedApplication(_, expected, actual, _) =>
      s"Too few arguments: expected $expected, got $actual"
    case OversaturatedApplication(_, expected, actual, _) =>
      s"Too many arguments: expected $expected, got $actual"
    case InvalidApplication(_, fnType, argType, _) =>
      s"Cannot apply ${showType(fnType)} to ${showType(argType)}"
    case InvalidSelection(ref, baseType, _) =>
      s"Cannot select '${ref.name}' from ${showType(baseType)}"
    case UnknownField(ref, _, _) =>
      s"Unknown field '${ref.name}' in struct"
    case ConditionalBranchTypeMismatch(_, trueType, falseType, _) =>
      s"Conditional branches have different types: ${showType(trueType)} vs ${showType(falseType)}"
    case ConditionalBranchTypeUnknown(_, _) =>
      "Cannot determine type of conditional branches"
    case UnresolvableType(_, context, _) =>
      context match
        case Some(UnresolvableTypeContext.NamedValue(name)) =>
          s"Cannot resolve type for '$name'"
        case Some(UnresolvableTypeContext.Argument) =>
          "Cannot resolve type for argument"
        case Some(UnresolvableTypeContext.Function) =>
          "Cannot resolve function type"
        case None =>
          "Cannot resolve type"
    case IncompatibleTypes(_, type1, type2, ctx, _) =>
      s"Incompatible types in $ctx: ${showType(type1)} and ${showType(type2)}"
    case UntypedHoleInBinding(name, _, _) =>
      s"Typed hole in '$name' requires type annotation"

enum SemanticError extends CompilationError:
  case UndefinedRef(ref: Ref, member: Member, phase: String)
  case UndefinedTypeRef(typeRef: TypeRef, member: Member, phase: String)
  case DuplicateName(name: String, duplicates: List[Resolvable], phase: String)
  case InvalidExpression(expr: Expr, msg: String, phase: String)
  case DanglingTerms(terms: List[Term], msg: String, phase: String)
  case MemberErrorFound(error: ParsingMemberError, phase: String)
  case ParsingIdErrorFound(error: ParsingIdError, phase: String)
  case InvalidExpressionFound(invalidExpr: mml.mmlclib.ast.InvalidExpression, phase: String)
  case TypeCheckingError(error: TypeError)
  case InvalidEntryPoint(msg: String, source: SourceOrigin)
  // Ownership errors
  case UseAfterMove(ref: Ref, movedAt: SourceOrigin, phase: String)
  case ConsumingParamNotLastUse(param: FnParam, ref: Ref, phase: String)
  case PartialApplicationWithConsuming(fn: Term, param: FnParam, phase: String)
  case ConditionalOwnershipMismatch(cond: Cond, phase: String)
  case BorrowEscapeViaReturn(ref: Ref, phase: String)

  def message: String = this match
    case UndefinedRef(ref, _, _) =>
      s"Undefined reference: '${ref.name}'"
    case UndefinedTypeRef(typeRef, _, _) =>
      s"Undefined type: '${typeRef.name}'"
    case DuplicateName(name, _, _) =>
      s"Duplicate definition: '$name'"
    case InvalidExpression(_, msg, _) =>
      msg
    case DanglingTerms(_, msg, _) =>
      msg
    case MemberErrorFound(error, _) =>
      s"Parse error in member: ${error.message}"
    case ParsingIdErrorFound(error, _) =>
      s"Parse error in identifier: ${error.message}"
    case InvalidExpressionFound(_, _) =>
      "Invalid expression"
    case TypeCheckingError(error) =>
      error.message
    case InvalidEntryPoint(msg, _) =>
      msg
    case UseAfterMove(ref, movedAt, _) =>
      movedAt.spanOpt match
        case Some(span) =>
          s"Use of '${ref.name}' after move at ${span.start.line}:${span.start.col}"
        case None =>
          s"Use of '${ref.name}' after move"
    case ConsumingParamNotLastUse(param, ref, _) =>
      s"Consuming parameter '${param.name}' must be the last use of '${ref.name}'"
    case PartialApplicationWithConsuming(_, param, _) =>
      s"Cannot partially apply function with consuming parameter '${param.name}'"
    case ConditionalOwnershipMismatch(_, _) =>
      "Conditional branches have different ownership states"
    case BorrowEscapeViaReturn(ref, _) =>
      s"Cannot return borrowed value '${ref.name}' from a function that returns a heap type"

/** Generate a stable ID for stdlib members */
private def stdlibId(declSegment: String, name: String): Option[String] =
  Some(s"stdlib::$declSegment::$name")

private val stdlibAliasNames: Set[String] = Set("Int", "Byte", "Word")

private def stdlibTypeId(name: String): Option[String] =
  val segment = if stdlibAliasNames.contains(name) then "typealias" else "typedef"
  stdlibId(segment, name)

/** Inject basic types with native mappings into the module.
  */
def injectBasicTypes(module: Module): Module =
  val syntheticSource = SourceOrigin.Synth

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(syntheticSource, name, stdlibTypeId(name), Nil)

  val basicTypes: List[TypeDef | TypeAlias] = List(
    // Native type definitions with LLVM mappings
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Int64"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i64")),
      id       = stdlibId("typedef", "Int64")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Int32"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i32")),
      id       = stdlibId("typedef", "Int32")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Int16"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i16")),
      id       = stdlibId("typedef", "Int16")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Int8"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i8")),
      id       = stdlibId("typedef", "Int8")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Float"),
      typeSpec = Some(NativePrimitive(syntheticSource, "float")),
      id       = stdlibId("typedef", "Float")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Double"),
      typeSpec = Some(NativePrimitive(syntheticSource, "double")),
      id       = stdlibId("typedef", "Double")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Bool"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i1")),
      id       = stdlibId("typedef", "Bool")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("CharPtr"),
      typeSpec = Some(NativePointer(syntheticSource, "i8")),
      id       = stdlibId("typedef", "CharPtr")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("String"),
      typeSpec = Some(
        NativeStruct(
          syntheticSource,
          List(
            "length" -> stdlibTypeRef("Int64"),
            "data" -> stdlibTypeRef("CharPtr")
          ),
          memEffect = Some(MemEffect.Alloc)
        )
      ),
      id = stdlibId("typedef", "String")
    ),
    TypeDef(
      // This should be an alias to an MML type (which in turn will have it's own native repr)
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("SizeT"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i64")),
      id       = stdlibId("typedef", "SizeT")
    ),
    // same as above, should this be it's own type or an alias?
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Char"),
      typeSpec = Some(NativePrimitive(syntheticSource, "i8")),
      id       = stdlibId("typedef", "Char")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Unit"),
      typeSpec = Some(NativePrimitive(syntheticSource, "void")),
      id       = stdlibId("typedef", "Unit")
    ),

    // Type aliases pointing to native types
    TypeAlias(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Int"),
      typeRef  = stdlibTypeRef("Int64"),
      id       = stdlibId("typealias", "Int")
    ),
    TypeAlias(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Byte"),
      typeRef  = stdlibTypeRef("Int8"),
      id       = stdlibId("typealias", "Byte")
    ),
    TypeAlias(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Word"),
      typeRef  = stdlibTypeRef("Int8"),
      id       = stdlibId("typealias", "Word")
    ),
    // Output buffer - opaque pointer to heap-allocated struct
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Buffer"),
      typeSpec = Some(NativePointer(syntheticSource, "i8", memEffect = Some(MemEffect.Alloc))),
      id       = stdlibId("typedef", "Buffer")
    ),

    // Pointer types for arrays
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Int64Ptr"),
      typeSpec = Some(NativePointer(syntheticSource, "i64")),
      id       = stdlibId("typedef", "Int64Ptr")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("StringPtr"),
      typeSpec = Some(NativePointer(syntheticSource, "%struct.String")),
      id       = stdlibId("typedef", "StringPtr")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("FloatPtr"),
      typeSpec = Some(NativePointer(syntheticSource, "float")),
      id       = stdlibId("typedef", "FloatPtr")
    ),

    // Monomorphic array types
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("IntArray"),
      typeSpec = Some(
        NativeStruct(
          syntheticSource,
          List(
            "length" -> stdlibTypeRef("Int64"),
            "data" -> stdlibTypeRef("Int64Ptr")
          ),
          memEffect = Some(MemEffect.Alloc)
        )
      ),
      id = stdlibId("typedef", "IntArray")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("StringArray"),
      typeSpec = Some(
        NativeStruct(
          syntheticSource,
          List(
            "length" -> stdlibTypeRef("Int64"),
            "data" -> stdlibTypeRef("StringPtr")
          ),
          memEffect = Some(MemEffect.Alloc)
        )
      ),
      id = stdlibId("typedef", "StringArray")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("FloatArray"),
      typeSpec = Some(
        NativeStruct(
          syntheticSource,
          List(
            "length" -> stdlibTypeRef("Int64"),
            "data" -> stdlibTypeRef("FloatPtr")
          ),
          memEffect = Some(MemEffect.Alloc)
        )
      ),
      id = stdlibId("typedef", "FloatArray")
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

  val syntheticSource = SourceOrigin.Synth

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(syntheticSource, name, stdlibTypeId(name), Nil)

  // Helper function to create TypeRef for basic types
  def intType   = stdlibTypeRef("Int")
  def boolType  = stdlibTypeRef("Bool")
  def floatType = stdlibTypeRef("Float")

  // Helper to create a binary operator as Bnd(Lambda)
  def mkBinOp(
    name:       String,
    prec:       Int,
    assoc:      Associativity,
    tpl:        String,
    paramType:  Type,
    returnType: Type
  ): Bnd =
    val param1 = FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(paramType))
    val param2 = FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(paramType))
    val body   = Expr(syntheticSource, List(NativeImpl(syntheticSource, nativeTpl = Some(tpl))))
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
      source   = SourceOrigin.Synth,
      params   = List(param1, param2),
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(mangledName),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = stdlibId("bnd", mangledName)
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
    val param = FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(paramType))
    val body  = Expr(syntheticSource, List(NativeImpl(syntheticSource, nativeTpl = Some(tpl))))
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
      source   = SourceOrigin.Synth,
      params   = List(param),
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(mangledName),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = stdlibId("bnd", mangledName)
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

  // Float arithmetic: Float -> Float -> Float
  val floatArithmeticOps = List(
    ("+.", 60, Associativity.Left, "fadd %type %operand1, %operand2"),
    ("-.", 60, Associativity.Left, "fsub %type %operand1, %operand2"),
    ("*.", 80, Associativity.Left, "fmul %type %operand1, %operand2"),
    ("/.", 80, Associativity.Left, "fdiv %type %operand1, %operand2")
  ).map { case (name, prec, assoc, tpl) =>
    mkBinOp(name, prec, assoc, tpl, floatType, floatType)
  }

  // Float comparison: Float -> Float -> Bool
  val floatComparisonOps = List(
    ("<.", 50, Associativity.Left, "fcmp olt %type %operand1, %operand2"),
    (">.", 50, Associativity.Left, "fcmp ogt %type %operand1, %operand2"),
    ("<=.", 50, Associativity.Left, "fcmp ole %type %operand1, %operand2"),
    (">=.", 50, Associativity.Left, "fcmp oge %type %operand1, %operand2"),
    ("==.", 50, Associativity.Left, "fcmp oeq %type %operand1, %operand2"),
    ("!=.", 50, Associativity.Left, "fcmp une %type %operand1, %operand2")
  ).map { case (name, prec, assoc, tpl) =>
    mkBinOp(name, prec, assoc, tpl, floatType, boolType)
  }

  // Unary float: Float -> Float
  val unaryFloatOps = List(
    ("-.", 95, Associativity.Right, "fsub %type 0.0, %operand")
  ).map { case (name, prec, assoc, tpl) =>
    mkUnaryOp(name, prec, assoc, tpl, floatType, floatType)
  }

  val standardOps =
    arithmeticOps ++ comparisonOps ++ logicalOps ++ unaryArithmeticOps ++ unaryLogicalOps ++
      floatArithmeticOps ++ floatComparisonOps ++ unaryFloatOps

  // Build resolvables index from stdlib operators
  val opIndex = standardOps.foldLeft(module.resolvables) { (idx, bnd) =>
    idx.updated(bnd)
  }

  module.copy(members = standardOps ++ module.members, resolvables = opIndex)

/** Inject common native functions that are repeatedly defined across samples. Includes print,
  * println, readline, concat, to_string, and str_to_int functions.
  */
def injectCommonFunctions(module: Module): Module =
  val syntheticSource = SourceOrigin.Synth

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(syntheticSource, name, stdlibTypeId(name), Nil)

  // Helper function to create TypeRef for basic types
  def stringType = stdlibTypeRef("String")
  def intType    = stdlibTypeRef("Int")
  def floatType  = stdlibTypeRef("Float")
  def unitType   = stdlibTypeRef("Unit")
  def bufferType = stdlibTypeRef("Buffer")

  // Helper to create a function as Bnd(Lambda)
  def mkFn(
    name:       String,
    params:     List[FnParam],
    returnType: Type,
    memEffect:  Option[MemEffect] = None
  ): Bnd =
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
    val body = Expr(syntheticSource, List(NativeImpl(syntheticSource, memEffect = memEffect)))
    val lambda = Lambda(
      source   = SourceOrigin.Synth,
      params   = params,
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(name),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = stdlibId("bnd", name)
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
    val param1 = FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(param1Type))
    val param2 = FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(param2Type))
    // Create refs with resolvedId pointing to the stdlib function
    val fnRef = Ref(SourceOrigin.Synth, fnName, resolvedId = stdlibId("bnd", fnName))
    val body =
      Expr(syntheticSource, List(fnRef, Ref(SourceOrigin.Synth, "a"), Ref(SourceOrigin.Synth, "b")))
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
      source   = SourceOrigin.Synth,
      params   = List(param1, param2),
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(mangledName),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = stdlibId("bnd", mangledName)
    )

  // Helper to create a function with a native template (like mkFn but with nativeTpl)
  def mkFnWithTpl(
    name:       String,
    params:     List[FnParam],
    returnType: Type,
    tpl:        String
  ): Bnd =
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
    val body = Expr(syntheticSource, List(NativeImpl(syntheticSource, nativeTpl = Some(tpl))))
    val lambda = Lambda(
      source   = SourceOrigin.Synth,
      params   = params,
      body     = body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = Some(returnType)
    )
    Bnd(
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(name),
      value      = Expr(syntheticSource, List(lambda)),
      typeSpec   = None,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = stdlibId("bnd", name)
    )

  val commonFunctions = List(
    mkFn(
      "print",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringType))),
      unitType
    ),
    mkFn(
      "println",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringType))),
      unitType
    ),
    mkFn("mml_sys_flush", List(), unitType),
    mkFn("readline", List(), stringType, Some(MemEffect.Alloc)),
    mkFn(
      "concat",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringType)),
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(stringType))
      ),
      stringType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "int_to_str",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(intType))),
      stringType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "float_to_str",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(floatType))),
      stringType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "str_to_int",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringType))),
      intType
    ),
    // Buffer functions
    mkFn("mkBuffer", List(), bufferType, Some(MemEffect.Alloc)),
    mkFn(
      "mkBufferWithFd",
      List(FnParam(SourceOrigin.Synth, Name.synth("fd"), typeAsc = Some(intType))),
      bufferType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "mkBufferWithSize",
      List(FnParam(SourceOrigin.Synth, Name.synth("size"), typeAsc = Some(intType))),
      bufferType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "flush",
      List(FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType))),
      unitType
    ),
    mkFn(
      "buffer_write",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("s"), typeAsc = Some(stringType))
      ),
      unitType
    ),
    mkFn(
      "buffer_writeln",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("s"), typeAsc = Some(stringType))
      ),
      unitType
    ),
    mkFn(
      "buffer_write_int",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("n"), typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "buffer_writeln_int",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("n"), typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "buffer_write_float",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("n"), typeAsc = Some(floatType))
      ),
      unitType
    ),
    mkFn(
      "buffer_writeln_float",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("n"), typeAsc = Some(floatType))
      ),
      unitType
    ),
    // Conversion functions (template-based)
    mkFnWithTpl(
      "int_to_float",
      List(FnParam(SourceOrigin.Synth, Name.synth("i"), typeAsc = Some(intType))),
      floatType,
      "sitofp i64 %operand to float"
    ),
    mkFnWithTpl(
      "float_to_int",
      List(FnParam(SourceOrigin.Synth, Name.synth("f"), typeAsc = Some(floatType))),
      intType,
      "fptosi float %operand to i64"
    ),
    mkFnWithTpl(
      "sqrt",
      List(FnParam(SourceOrigin.Synth, Name.synth("x"), typeAsc = Some(floatType))),
      floatType,
      "call float @llvm.sqrt.f32(float %operand)"
    ),
    mkFnWithTpl(
      "fabs",
      List(FnParam(SourceOrigin.Synth, Name.synth("x"), typeAsc = Some(floatType))),
      floatType,
      "call float @llvm.fabs.f32(float %operand)"
    ),
    // File operations
    mkFn(
      "open_file_read",
      List(FnParam(SourceOrigin.Synth, Name.synth("path"), typeAsc = Some(stringType))),
      intType
    ),
    mkFn(
      "open_file_write",
      List(FnParam(SourceOrigin.Synth, Name.synth("path"), typeAsc = Some(stringType))),
      intType
    ),
    mkFn(
      "open_file_append",
      List(FnParam(SourceOrigin.Synth, Name.synth("path"), typeAsc = Some(stringType))),
      intType
    ),
    mkFn(
      "close_file",
      List(FnParam(SourceOrigin.Synth, Name.synth("fd"), typeAsc = Some(intType))),
      unitType
    ),
    mkFn(
      "read_line_fd",
      List(FnParam(SourceOrigin.Synth, Name.synth("fd"), typeAsc = Some(intType))),
      stringType,
      Some(MemEffect.Alloc)
    ),
    // Memory management free functions - params are consuming (take ownership)
    mkFn(
      "__free_String",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("s"), typeAsc = Some(stringType), consuming = true)
      ),
      unitType
    ),
    mkFn(
      "__free_Buffer",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType), consuming = true)
      ),
      unitType
    )
  )

  // Array type refs
  def intArrayType    = stdlibTypeRef("IntArray")
  def stringArrayType = stdlibTypeRef("StringArray")
  def floatArrayType  = stdlibTypeRef("FloatArray")

  // Array functions
  val arrayFunctions = List(
    // IntArray functions
    mkFn(
      "ar_int_new",
      List(FnParam(SourceOrigin.Synth, Name.synth("size"), typeAsc = Some(intType))),
      intArrayType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "ar_int_set",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc   = Some(intArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc   = Some(intType)),
        FnParam(SourceOrigin.Synth, Name.synth("value"), typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "ar_int_get",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(intArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc = Some(intType))
      ),
      intType
    ),
    mkFn(
      "unsafe_ar_int_set",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc   = Some(intArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc   = Some(intType)),
        FnParam(SourceOrigin.Synth, Name.synth("value"), typeAsc = Some(intType))
      ),
      unitType
    ),
    mkFn(
      "unsafe_ar_int_get",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(intArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc = Some(intType))
      ),
      intType
    ),
    mkFn(
      "ar_int_len",
      List(FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(intArrayType))),
      intType
    ),
    // StringArray functions
    mkFn(
      "ar_str_new",
      List(FnParam(SourceOrigin.Synth, Name.synth("size"), typeAsc = Some(intType))),
      stringArrayType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "ar_str_set",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(stringArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc = Some(intType)),
        FnParam(
          SourceOrigin.Synth,
          Name.synth("value"),
          typeAsc   = Some(stringType),
          consuming = true
        )
      ),
      unitType
    ),
    mkFn(
      "ar_str_get",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(stringArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc = Some(intType))
      ),
      stringType
    ),
    mkFn(
      "ar_str_len",
      List(FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(stringArrayType))),
      intType
    ),
    // FloatArray functions
    mkFn(
      "ar_float_new",
      List(FnParam(SourceOrigin.Synth, Name.synth("size"), typeAsc = Some(intType))),
      floatArrayType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "ar_float_set",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc   = Some(floatArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc   = Some(intType)),
        FnParam(SourceOrigin.Synth, Name.synth("value"), typeAsc = Some(floatType))
      ),
      unitType
    ),
    mkFn(
      "ar_float_get",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(floatArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc = Some(intType))
      ),
      floatType
    ),
    mkFn(
      "unsafe_ar_float_set",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc   = Some(floatArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc   = Some(intType)),
        FnParam(SourceOrigin.Synth, Name.synth("value"), typeAsc = Some(floatType))
      ),
      unitType
    ),
    mkFn(
      "unsafe_ar_float_get",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(floatArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("idx"), typeAsc = Some(intType))
      ),
      floatType
    ),
    mkFn(
      "ar_float_len",
      List(FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc = Some(floatArrayType))),
      intType
    ),
    // Memory management free functions for arrays - params are consuming
    mkFn(
      "__free_IntArray",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(intArrayType), consuming = true)
      ),
      unitType
    ),
    mkFn(
      "__free_StringArray",
      List(
        FnParam(
          SourceOrigin.Synth,
          Name.synth("a"),
          typeAsc   = Some(stringArrayType),
          consuming = true
        )
      ),
      unitType
    ),
    mkFn(
      "__free_FloatArray",
      List(
        FnParam(
          SourceOrigin.Synth,
          Name.synth("a"),
          typeAsc   = Some(floatArrayType),
          consuming = true
        )
      ),
      unitType
    ),
    // Memory management clone functions - return heap copies
    mkFn(
      "__clone_String",
      List(FnParam(SourceOrigin.Synth, Name.synth("s"), typeAsc = Some(stringType))),
      stringType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "__clone_Buffer",
      List(FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType))),
      bufferType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "__clone_IntArray",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(intArrayType))),
      intArrayType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "__clone_StringArray",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringArrayType))),
      stringArrayType,
      Some(MemEffect.Alloc)
    ),
    mkFn(
      "__clone_FloatArray",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(floatArrayType))),
      floatArrayType,
      Some(MemEffect.Alloc)
    )
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
    ),
    mkBinOpExpr(
      "write_float",
      20,
      Associativity.Left,
      "buffer_write_float",
      bufferType,
      floatType,
      unitType
    ),
    mkBinOpExpr(
      "writeln_float",
      20,
      Associativity.Left,
      "buffer_writeln_float",
      bufferType,
      floatType,
      unitType
    )
  )

  // String operators
  val stringOps = List(
    mkBinOpExpr("++", 61, Associativity.Right, "concat", stringType, stringType, stringType)
  )

  val allFunctions = commonFunctions ++ arrayFunctions ++ bufferOps ++ stringOps

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
          val qualifierExpr = Expr(qualifier.source, List(qualifier))
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
