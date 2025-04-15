package mml.mmlclib.codegen.emitter

import cats.syntax.all.*

/** Helper for generating syntactically correct LLVM IR type definitions */
def emitTypeDefinition(typeName: String, fields: List[String]): String =
  s"%$typeName = type { ${fields.mkString(", ")} }"

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
case class CodeGenError(message: String)

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
    val stringId = s"str.${nextStringId}"
    (
      copy(
        stringConstants = stringConstants + (stringId -> content),
        nextStringId    = nextStringId + 1
      ),
      stringId
    )

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
    * @return
    *   updated CodeGenState with the native type added
    */
  def withNativeType(typeName: String): CodeGenState =
    if nativeTypes.contains(typeName) then this
    else
      typeName match
        case "String" =>
          val typeDef = emitTypeDefinition("String", List("i64", "i8*"))
          copy(nativeTypes = nativeTypes + (typeName -> typeDef))
        // Add other types as needed
        case _ => this

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

  /** Maintains backward compatibility with existing code that uses declareNativeType */
  def declareNativeType(typeName: String): CodeGenState =
    withNativeType(typeName)

  /** Returns the LLVM type representation for a native type. */
  def llvmTypeForNative(typeName: String): String = typeName match
    case "Int" => "i32"
    case "Boolean" => "i1"
    case "String" => "%String" // Custom struct type
    case _ => "i32" // Default fallback

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
  */
case class CompileResult(
  register:  Int,
  state:     CodeGenState,
  isLiteral: Boolean = false,
  typeName:  String  = "Int" // Default to Int for backward compatibility
)
