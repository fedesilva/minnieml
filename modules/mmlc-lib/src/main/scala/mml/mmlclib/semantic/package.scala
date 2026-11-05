package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

/** Type after one argument is consumed. A residual callable uses the supplied source location; a
  * fully applied callable preserves its declared return type and location.
  */
private[semantic] def remainingCallableType(function: TypeFn, source: SourceOrigin): Type =
  NonEmptyList
    .fromList(function.paramTypes.tail)
    .fold(function.returnType)(params => TypeFn(source, params, function.returnType))

enum UnresolvableTypeContext derives CanEqual:
  case NamedValue(name: String)
  case Argument
  case Function

enum TypeError extends CompilationError:
  // Function and Operator Definition Errors
  case MissingParameterType(param: FnParam, decl: Decl & Resolvable, phase: String)
  case MissingReturnType(decl: Decl & Resolvable, phase: String)
  case RecursiveFunctionMissingReturnType(decl: Decl & Resolvable, phase: String)
  case MissingOperatorParameterType(param: FnParam, decl: Decl & Resolvable, phase: String)
  case MissingOperatorReturnType(decl: Decl & Resolvable, phase: String)

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
    node:  AstNode,
    type1: Type,
    type2: Type,
    phase: String
  )
  case ConflictingLambdaParamInference(
    param:     FnParam,
    firstType: Type,
    nextType:  Type,
    phase:     String
  )
  case UninferrableLambdaParam(param: FnParam, phase: String)
  case UntypedHoleInBinding(bindingName: String, source: SourceOrigin, phase: String)

enum SemanticError extends CompilationError:
  case UndefinedRef(ref: Ref, member: Member, phase: String)
  case UndefinedTypeRef(typeRef: TypeRef, member: Member, phase: String)
  case AmbiguousTypeRef(
    typeRef:    TypeRef,
    candidates: List[ResolvableType],
    member:     Member,
    phase:      String
  )
  case DuplicateName(
    name:           String,
    duplicates:     List[Resolvable],
    phase:          String,
    parameterOwner: Option[Bnd] = None
  )
  case InvalidExpression(expr: Expr, reason: ExpressionError, phase: String)
  case DanglingTerms(terms: List[Term], reason: ExpressionError, phase: String)
  case MemberErrorFound(error: ParsingMemberError, phase: String)
  case ParsingIdErrorFound(error: ParsingIdError, phase: String)
  case ParseTermErrorFound(error: ParseTermError, phase: String)
  case ParseTypeErrorFound(error: ParseTypeError, phase: String)
  case InvalidExpressionFound(invalidExpr: mml.mmlclib.ast.InvalidExpression, phase: String)
  case TypeCheckingError(error: TypeError)
  case ProtocolErrorFound(error: ProtocolError, phase: String)
  case InvalidEntryPoint(error: EntryPointError)
  case VisibilityViolation(ref: Ref, decl: Resolvable, phase: String)
  // Ownership errors
  /** The use site and moved storage identity are independent of retained dependency evidence. */
  case UseAfterMove(
    ref:        Ref,
    movedAt:    SourceOrigin,
    phase:      String,
    ownerId:    Option[String],
    source:     SourceOrigin,
    dependency: Option[Ref] = None
  )
  case ConsumingParamNotLastUse(param: FnParam, ref: Ref, phase: String)
  case BorrowedValuePassedToConsumingParam(param: FnParam, ref: Ref, phase: String)
  case PartialApplicationWithConsuming(fn: Term, param: FnParam, phase: String)
  case ConditionalOwnershipMismatch(cond: Cond, phase: String)
  case BorrowEscapeViaReturn(ref: Ref, phase: String)
  case CapturedBorrowedHeapBinding(ref: Ref, phase: String)
  case CapturedMovedHeapBinding(ref: Ref, movedAt: SourceOrigin, phase: String)
  case BorrowClosureEscapeViaReturn(lambda: Lambda, phase: String)

/** Generate a stable ID for stdlib members */
private def stdlibId(declSegment: String, name: String): Option[String] =
  Some(s"stdlib::$declSegment::$name")

private val stdlibAliasNames: Set[String] = Set("Int", "Byte", "Word")

private[semantic] def stdlibTypeId(name: String): Option[String] =
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
      nameNode = Name.synth("CString"),
      typeSpec = NativePointer(
        syntheticSource,
        "i8",
        memEffect = MemEffect.Alloc.some,
        freeFn    = "free_cstr".some
      ).some,
      id = stdlibId("typedef", "CString")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("RawPtr"),
      typeSpec = Some(NativePrimitive(syntheticSource, "ptr")),
      id       = stdlibId("typedef", "RawPtr")
    ),
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("String"),
      typeSpec = Some(
        NativeStruct(
          syntheticSource,
          List(
            "length" -> stdlibTypeRef("Int32"),
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
      typeRef  = stdlibTypeRef("Int32"),
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
    TypeDef(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth("Rng"),
      typeSpec = Some(NativePointer(syntheticSource, "i8", memEffect = Some(MemEffect.Alloc))),
      id       = stdlibId("typedef", "Rng")
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
      nameNode = Name.synth("Int32Ptr"),
      typeSpec = Some(NativePointer(syntheticSource, "i32")),
      id       = stdlibId("typedef", "Int32Ptr")
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
            "length" -> stdlibTypeRef("Int32"),
            "data" -> stdlibTypeRef("Int32Ptr")
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
            "length" -> stdlibTypeRef("Int32"),
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
            "length" -> stdlibTypeRef("Int32"),
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

/** Inject common native functions and the ToString and Clone protocols with their native instances.
  * Includes print, println, readline, concat, named numeric string conversions, and str_to_int.
  */
def injectCommonFunctions(module: Module): Module =
  val syntheticSource = SourceOrigin.Synth

  // Helper to create a resolved TypeRef to a stdlib type
  def stdlibTypeRef(name: String): TypeRef =
    TypeRef(syntheticSource, name, stdlibTypeId(name), Nil)

  // Helper function to create TypeRef for basic types
  def stringType  = stdlibTypeRef("String")
  def cstringType = stdlibTypeRef("CString")
  def intType     = stdlibTypeRef("Int")
  def boolType    = stdlibTypeRef("Bool")
  def floatType   = stdlibTypeRef("Float")
  def unitType    = stdlibTypeRef("Unit")
  def bufferType  = stdlibTypeRef("Buffer")
  def rngType     = stdlibTypeRef("Rng")

  // Helper to create a function as Bnd(Lambda)
  def mkFn(
    name:         String,
    params:       List[FnParam],
    returnType:   Type,
    memEffect:    Option[MemEffect] = None,
    nativeSymbol: Option[String]    = None
  ): Bnd =
    val arity = CallableArity.fromParameterCount(params.size)
    val meta = BindingMeta(
      kind         = BindingKind.Function,
      arity        = arity,
      originalName = name,
      mangledName  = name
    )
    val body = Expr(
      syntheticSource,
      List(NativeImpl(syntheticSource, memEffect = memEffect, nativeSymbol = nativeSymbol))
    )
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
      kind         = BindingKind.Operator(prec, assoc),
      arity        = CallableArity.Binary,
      originalName = name,
      mangledName  = mangledName
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
    val arity = CallableArity.fromParameterCount(params.size)
    val meta = BindingMeta(
      kind         = BindingKind.Function,
      arity        = arity,
      originalName = name,
      mangledName  = name
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
      "str_eq",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringType)),
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(stringType))
      ),
      boolType
    ),
    mkFn(
      "str_strip_margin",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = Some(stringType))),
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
    mkFn(
      "to_cstr",
      List(FnParam(SourceOrigin.Synth, Name.synth("s"), typeAsc = stringType.some)),
      cstringType,
      MemEffect.Alloc.some
    ),
    // Random number generator
    mkFn(
      "rng_new",
      List(FnParam(SourceOrigin.Synth, Name.synth("seed"), typeAsc = Some(intType))),
      rngType,
      Some(MemEffect.Alloc)
    ),
    mkFn("rng_new_random", List(), rngType, Some(MemEffect.Alloc)),
    mkFn(
      "rng_next",
      List(FnParam(SourceOrigin.Synth, Name.synth("rng"), typeAsc = Some(rngType))),
      intType
    ),
    mkFn(
      "rng_between",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("rng"), typeAsc = Some(rngType)),
        FnParam(SourceOrigin.Synth, Name.synth("min"), typeAsc = Some(intType)),
        FnParam(SourceOrigin.Synth, Name.synth("max"), typeAsc = Some(intType))
      ),
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
      "mkBufferWithFdAndSize",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("fd"), typeAsc   = Some(intType)),
        FnParam(SourceOrigin.Synth, Name.synth("size"), typeAsc = Some(intType))
      ),
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
      "buffer_write_byte",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("b"), typeAsc = Some(bufferType)),
        FnParam(SourceOrigin.Synth, Name.synth("n"), typeAsc = Some(intType))
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
    mkFn(
      "int64_to_str",
      List(FnParam(SourceOrigin.Synth, Name.synth("a"), typeAsc = stdlibTypeRef("Int64").some)),
      stringType,
      MemEffect.Alloc.some
    ),
    mkFnWithTpl(
      "int_to_int64",
      List(FnParam(SourceOrigin.Synth, Name.synth("i"), typeAsc = intType.some)),
      stdlibTypeRef("Int64"),
      "sext i32 %operand to i64"
    ),
    // Conversion functions (template-based)
    mkFnWithTpl(
      "int_to_float",
      List(FnParam(SourceOrigin.Synth, Name.synth("i"), typeAsc = Some(intType))),
      floatType,
      "sitofp i32 %operand to float"
    ),
    mkFnWithTpl(
      "float_to_int",
      List(FnParam(SourceOrigin.Synth, Name.synth("f"), typeAsc = Some(floatType))),
      intType,
      "fptosi float %operand to i32"
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
      "free_cstr",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("s"), typeAsc = cstringType.some, consuming = true)
      ),
      unitType,
      nativeSymbol = "free".some
    ),
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
    ),
    mkFn(
      "__free_Rng",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("rng"), typeAsc = Some(rngType), consuming = true)
      ),
      unitType
    ),
    mkFn(
      "mml_free_raw",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("p"), typeAsc = Some(stdlibTypeRef("RawPtr")))
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
      "ar_int_fill",
      List(
        FnParam(SourceOrigin.Synth, Name.synth("arr"), typeAsc   = Some(intArrayType)),
        FnParam(SourceOrigin.Synth, Name.synth("value"), typeAsc = Some(intType))
      ),
      unitType
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
      "write_byte",
      20,
      Associativity.Left,
      "buffer_write_byte",
      bufferType,
      intType,
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

  injectStandardClones(
    injectStandardConversions(
      module.copy(members = allFunctions ++ module.members, resolvables = fnIndex)
    )
  )

object IsAtom:
  def unapply(term: Term): Option[Term] = term match
    case _: Ref => None
    case other => other.some
