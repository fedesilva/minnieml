package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Helper for generating syntactically correct LLVM IR type definitions */
def emitTypeDefinition(typeName: String, fields: List[String]): String =
  s"%$typeName = type { ${fields.mkString(", ")} }"

def emitPointerTypeDefinition(typeName: String, pointeeType: String): String =
  s"%$typeName = type $pointeeType*"

/** Helper for generating syntactically correct LLVM IR constant assignment */
def emitConstant(result: Int, typ: String, value: String): String =
  s"  %$result = $typ $value"

/** Helper for generating syntactically correct LLVM IR add instruction */
def emitAdd(result: Int, typ: String, left: String, right: String): String =
  s"  %$result = add $typ $left, $right"

/** Helper for generating syntactically correct LLVM IR subtract instruction */
def emitSub(result: Int, typ: String, left: String, right: String): String =
  s"  %$result = sub $typ $left, $right"

/** Helper for generating syntactically correct LLVM IR multiply instruction */
def emitMul(result: Int, typ: String, left: String, right: String): String =
  s"  %$result = mul $typ $left, $right"

/** Helper for generating syntactically correct LLVM IR signed division instruction */
def emitSDiv(result: Int, typ: String, left: String, right: String): String =
  s"  %$result = sdiv $typ $left, $right"

/** Helper for generating syntactically correct LLVM IR xor instruction */
def emitXor(result: Int, typ: String, left: String, right: String): String =
  s"  %$result = xor $typ $left, $right"

/** Helper for generating syntactically correct LLVM IR load instruction */
def emitLoad(result: Int, typ: String, ptr: String): String =
  s"  %$result = load $typ, $typ* $ptr"

/** Helper for generating syntactically correct LLVM IR store instruction */
def emitStore(value: String, typ: String, ptr: String): String =
  s"  store $typ $value, $typ* $ptr"

/** Helper for generating syntactically correct LLVM IR getelementptr instruction */
def emitGetElementPtr(
  result:   Int,
  baseType: String,
  ptrType:  String,
  ptr:      String,
  indices:  List[(String, String)]
): String =
  val indexStrings = indices.map { case (typ, value) => s"$typ $value" }.mkString(", ")
  s"  %$result = getelementptr $baseType, $ptrType $ptr, $indexStrings"

/** Helper for generating syntactically correct LLVM IR call instruction */
def emitCall(
  result:     Option[Int],
  returnType: Option[String],
  fnName:     String,
  args:       List[(String, String)]
): String =
  val resultPart = result.map(r => s"%$r = ").getOrElse("")
  val returnPart = returnType.map(t => s"$t ").getOrElse("void ")
  val argString  = args.map { case (typ, value) => s"$typ $value" }.mkString(", ")
  s"  ${resultPart}call $returnPart@$fnName($argString)"

/** Helper for generating syntactically correct LLVM IR global variable declaration */
def emitGlobalVariable(name: String, typ: String, value: String): String =
  s"@$name = global $typ $value"

/** Helper for generating syntactically correct LLVM IR function declaration */
def emitFunctionDeclaration(name: String, returnType: String, params: List[String]): String =
  val paramString = params.mkString(", ")
  s"declare $returnType @$name($paramString)"

/** Represents an error that occurred during code generation. */
case class CodeGenError(message: String, node: Option[AstNode] = None)

/** Represents the state during code generation.
  *
  * @param nextRegister
  *   the next available register number
  * @param output
  *   the list of emitted LLVM IR lines (in reverse order)
  * @param initializers
  *   list of initializer function names for global ctors
  * @param stringConstants
  *   map of string constant name to its content
  * @param nextStringId
  *   the next available string constant ID
  * @param moduleHeader
  *   optional header for the module (to avoid duplication)
  * @param nativeTypes
  *   map of native type names to their LLVM IR definitions
  * @param functionDeclarations
  *   map of function names to their declarations
  */
case class CodeGenState(
  nextRegister:         Int                 = 0,
  output:               List[String]        = List.empty,
  initializers:         List[String]        = List.empty,
  stringConstants:      Map[String, String] = Map.empty,
  nextStringId:         Int                 = 0,
  moduleHeader:         Option[String]      = None,
  nativeTypes:          Map[String, String] = Map.empty,
  functionDeclarations: Map[String, String] = Map.empty
):
  /** Returns a new state with an updated register counter. */
  def withRegister(reg: Int): CodeGenState =
    copy(nextRegister = reg)

  /** Emits a single line of LLVM IR code, returning the updated state. */
  def emit(line: String): CodeGenState =
    copy(output = line :: output)

  /** Emits multiple lines of LLVM IR code at once. */
  def emitAll(lines: List[String]): CodeGenState =
    copy(output = lines.reverse ::: output)

  /** Adds an initializer function to the list. */
  def addInitializer(fnName: String): CodeGenState =
    copy(initializers = fnName :: initializers)

  /** Returns the complete LLVM IR as a single string. */
  def result: String =
    output.reverse.mkString("\n")

  /** Adds a string constant to the state and returns the name of the constant. */
  def addStringConstant(content: String): (CodeGenState, String) =
    // Check if this content already exists
    stringConstants.find(_._2 == content) match {
      case Some((existingId, _)) => (this, existingId)
      case None =>
        val stringId = s"str.${nextStringId}"
        (
          copy(
            stringConstants = stringConstants + (stringId -> content),
            nextStringId    = nextStringId + 1
          ),
          stringId
        )
    }

  /** Sets the module header if not already set.
    *
    * @param moduleName
    *   the name of the module
    * @return
    *   updated CodeGenState with the module header
    */
  def withModuleHeader(moduleName: String): CodeGenState =
    moduleHeader match
      case Some(_) => this // Already has a header
      case None =>
        val header = s"; ModuleID = '$moduleName'\ntarget triple = \"x86_64-unknown-unknown\"\n"
        copy(moduleHeader = Some(header))

  /** Adds a native type definition if not already defined.
    *
    * @param typeName
    *   the name of the native type
    * @param llvmTypeDef
    *   the LLVM type definition string
    * @return
    *   updated CodeGenState with the native type added
    */
  def withNativeType(typeName: String, llvmTypeDef: String): CodeGenState =
    if nativeTypes.contains(typeName) then this
    else copy(nativeTypes = nativeTypes + (typeName -> llvmTypeDef))

  /** Adds a function declaration if not already declared.
    *
    * @param name
    *   the function name
    * @param returnType
    *   the return type in LLVM IR format
    * @param paramTypes
    *   the parameter types in LLVM IR format
    * @return
    *   updated CodeGenState with the function declaration added
    */
  def withFunctionDeclaration(
    name:       String,
    returnType: String,
    paramTypes: List[String]
  ): CodeGenState =
    if functionDeclarations.contains(name) then this
    else
      val declaration = emitFunctionDeclaration(name, returnType, paramTypes)
      copy(functionDeclarations = functionDeclarations + (name -> declaration))

/** Represents the result of compiling a term or expression.
  *
  * @param register
  *   the register holding the result (or literal value)
  * @param state
  *   the updated code generation state
  * @param isLiteral
  *   indicates whether the result is a literal value
  * @param typeName
  *   the MML type name of the result
  * @param exitBlock
  *   the block label where control exits (for phi node predecessors in nested conditionals)
  */
case class CompileResult(
  register:  Int,
  state:     CodeGenState,
  isLiteral: Boolean        = false,
  typeName:  String         = "Int", // Default to Int for backward compatibility
  exitBlock: Option[String] = None
)

/** Convert a NativeType AST node to LLVM type definition string.
  *
  * @param typeName
  *   the name of the type being defined
  * @param nativeType
  *   the native type specification from the AST
  * @param state
  *   the current code generation state
  * @return
  *   Either an error or the LLVM type definition string
  */
def nativeTypeToLlvmDef(
  typeName:   String,
  nativeType: mml.mmlclib.ast.NativeType,
  state:      CodeGenState
): Either[CodeGenError, String] =
  nativeType match
    case NativePrimitive(_, llvmType) =>
      Right(s"%$typeName = type $llvmType")
    case NativePointer(_, llvmType) =>
      Right(emitPointerTypeDefinition(typeName, llvmType))
    case NativeStruct(_, fields) =>
      // Convert each field's TypeSpec to LLVM type
      val fieldResults = fields.toList.map { case (fieldName, typeSpec) =>
        getLlvmType(typeSpec, state).map((fieldName, _))
      }
      // Check for errors
      val errors = fieldResults.collect { case Left(err) => err }
      if errors.nonEmpty then
        Left(
          CodeGenError(s"Failed to resolve struct fields: ${errors.map(_.message).mkString(", ")}")
        )
      else
        val llvmFields = fieldResults.collect { case Right((_, t)) => t }
        Right(emitTypeDefinition(typeName, llvmFields))

/** Convert any TypeSpec to LLVM type string.
  *
  * This is a basic implementation for Block 3. It will be expanded in Block 4 to handle all type
  * specifications properly.
  *
  * @param typeSpec
  *   the type specification to convert
  * @param state
  *   the current code generation state
  * @return
  *   Either an error or the LLVM type string
  */
def getLlvmType(
  typeSpec: mml.mmlclib.ast.TypeSpec,
  state:    CodeGenState
): Either[CodeGenError, String] =

  typeSpec match
    case typeRef @ TypeRef(_, name, resolvedOpt) =>
      resolvedOpt match
        case Some(resolved) =>
          resolved match
            case typeDef: TypeDef =>
              typeDef.typeSpec match
                case Some(nativeType: NativeType) =>
                  // Follow through to get the actual LLVM type
                  nativeType match
                    case NativePrimitive(_, llvmType) => Right(llvmType)
                    case NativePointer(_, llvmType) => Right(s"$llvmType*")
                    case _: NativeStruct => Right(s"%$name") // Structs use % prefix
                case _ =>
                  // Non-native types cannot be translated to LLVM yet
                  Left(CodeGenError(s"Cannot determine LLVM type for non-native type: $name"))
            case typeAlias: TypeAlias =>
              // Use the computed typeSpec if available, otherwise follow the typeRef
              typeAlias.typeSpec match
                case Some(spec) => getLlvmType(spec, state)
                case None => getLlvmType(typeAlias.typeRef, state)
        case None =>
          // Unresolved type - this should have been caught by TypeResolver
          // Add debug info to understand what's happening
          Left(CodeGenError(s"Unresolved type reference: $name (TypeRef with no resolvedAs)"))
    case TypeUnit(_) =>
      Right("void")
    case np: NativePrimitive =>
      // Direct primitive type (shouldn't normally happen at this level)
      Right(np.llvmType)
    case ptr: NativePointer =>
      // Direct pointer type (shouldn't normally happen at this level)
      Right(ptr.llvmType)
    case _: NativeStruct =>
      Left(CodeGenError("Unexpected inline native struct"))
    case other =>
      // No LLVM type mapping for this TypeSpec
      Left(
        CodeGenError(
          s"No LLVM type mapping for TypeSpec: ${other.getClass.getSimpleName}",
          Some(other)
        )
      )
