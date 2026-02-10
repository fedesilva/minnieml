package mml.mmlclib.codegen.emitter

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.abis.AbiStrategy
import mml.mmlclib.errors.{CompilationError, CompilerWarning}

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
def emitLoad(
  result:     Int,
  typ:        String,
  ptr:        String,
  tbaaTag:    Option[String] = None,
  aliasScope: Option[String] = None,
  noalias:    Option[String] = None
): String =
  val metadataParts = List(
    tbaaTag.map(tag => s"!tbaa $tag"),
    aliasScope.map(tag => s"!alias.scope $tag"),
    noalias.map(tag => s"!noalias $tag")
  ).flatten
  val metadataSuffix = if metadataParts.isEmpty then "" else metadataParts.mkString(", ", ", ", "")
  s"  %$result = load $typ, $typ* $ptr$metadataSuffix"

/** Helper for generating syntactically correct LLVM IR store instruction */
def emitStore(
  value:      String,
  typ:        String,
  ptr:        String,
  tbaaTag:    Option[String] = None,
  aliasScope: Option[String] = None,
  noalias:    Option[String] = None
): String =
  val metadataParts = List(
    tbaaTag.map(tag => s"!tbaa $tag"),
    aliasScope.map(tag => s"!alias.scope $tag"),
    noalias.map(tag => s"!noalias $tag")
  ).flatten
  val metadataSuffix = if metadataParts.isEmpty then "" else metadataParts.mkString(", ", ", ", "")
  s"  store $typ $value, $typ* $ptr$metadataSuffix"

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
  args:       List[(String, String)],
  aliasScope: Option[String] = None,
  noalias:    Option[String] = None
): String =
  val resultPart = result.map(r => s"%$r = ").getOrElse("")
  val returnPart = returnType.map(t => s"$t ").getOrElse("void ")
  val argString  = args.map { case (typ, value) => s"$typ $value" }.mkString(", ")
  val metadataParts = List(
    aliasScope.map(tag => s"!alias.scope $tag"),
    noalias.map(tag => s"!noalias $tag")
  ).flatten
  val metadataSuffix = if metadataParts.isEmpty then "" else metadataParts.mkString(", ", ", ", "")
  s"  ${resultPart}call $returnPart@$fnName($argString)$metadataSuffix"

/** Helper for generating LLVM IR extractvalue instruction */
def emitExtractValue(result: Int, structType: String, value: String, index: Int): String =
  s"  %$result = extractvalue $structType $value, $index"

/** Helper for generating LLVM IR insertvalue instruction */
def emitInsertValue(
  result:        Int,
  aggregateType: String,
  aggregate:     String,
  elementType:   String,
  element:       String,
  index:         Int
): String =
  s"  %$result = insertvalue $aggregateType $aggregate, $elementType $element, $index"

/** Helper for generating LLVM IR ptrtoint instruction */
def emitPtrToInt(result: Int, fromType: String, value: String, toType: String): String =
  s"  %$result = ptrtoint $fromType $value to $toType"

/** Get the field types of a struct from a TypeSpec if it's a NativeStruct. Returns None if not a
  * native struct type.
  */
def getStructFieldTypesFromTypeSpec(
  typeSpec: Type,
  state:    CodeGenState
): Option[List[String]] =
  typeSpec match
    case TypeRef(_, _, resolvedId, _) =>
      resolvedId.flatMap(state.resolvables.lookupType) match
        case Some(typeDef: TypeDef) =>
          typeDef.typeSpec match
            case Some(NativeStruct(_, fields, _, _)) =>
              val fieldTypes = fields.map { case (_, fieldTypeSpec) =>
                getLlvmType(fieldTypeSpec, state)
              }
              if fieldTypes.forall(_.isRight) then Some(fieldTypes.collect { case Right(t) => t })
              else None
            case _ => None
        case Some(typeStruct: TypeStruct) =>
          val fieldTypes = typeStruct.fields.toList.map { field =>
            getLlvmType(field.typeSpec, state)
          }
          if fieldTypes.forall(_.isRight) then Some(fieldTypes.collect { case Right(t) => t })
          else None
        case Some(typeAlias: TypeAlias) =>
          typeAlias.typeSpec
            .flatMap(getStructFieldTypesFromTypeSpec(_, state))
            .orElse(getStructFieldTypesFromTypeSpec(typeAlias.typeRef, state))
        case _ => None
    case typeStruct: TypeStruct =>
      val fieldTypes = typeStruct.fields.toList.map { field =>
        getLlvmType(field.typeSpec, state)
      }
      if fieldTypes.forall(_.isRight) then Some(fieldTypes.collect { case Right(t) => t })
      else None
    case _ => None

/** Get the field types of a struct from its LLVM type name using state's nativeTypes. Parses the
  * stored type definition to extract field types. Returns None if not a struct or parsing fails.
  */
def getStructFieldTypes(llvmType: String, state: CodeGenState): Option[List[String]] =
  if !llvmType.startsWith("%") then None
  else
    val typeName = llvmType.drop(1)
    state.nativeTypes.get(typeName).flatMap { typeDef =>
      // Parse "%TypeName = type { field1, field2 }" to extract fields
      val pattern = """type \{ (.+) \}""".r
      pattern.findFirstMatchIn(typeDef).map { m =>
        m.group(1).split(",").map(_.trim).toList
      }
    }

def resolveTypeStruct(typeSpec: Type, resolvables: ResolvablesIndex): Option[TypeStruct] =
  typeSpec match
    case ts: TypeStruct => Some(ts)
    case TypeGroup(_, types) if types.size == 1 =>
      resolveTypeStruct(types.head, resolvables)
    case TypeRef(_, _, resolvedId, _) =>
      resolvedId.flatMap(resolvables.lookupType) match
        case Some(ts: TypeStruct) => Some(ts)
        case Some(td: TypeDef) =>
          td.typeSpec match
            case Some(ns: NativeStruct) =>
              val fields = ns.fields.map { case (name, t) =>
                Field(td.span, name, t)
              }.toVector
              Some(TypeStruct(td.span, None, td.visibility, td.name, fields, td.id))
            case _ => None
        case Some(ta: TypeAlias) =>
          ta.typeSpec
            .flatMap(resolveTypeStruct(_, resolvables))
            .orElse(resolveTypeStruct(ta.typeRef, resolvables))
        case _ => None
    case _ => None

/** Helper for generating syntactically correct LLVM IR global variable declaration */
def emitGlobalVariable(name: String, typ: String, value: String): String =
  s"@$name = global $typ $value"

/** Helper for generating syntactically correct LLVM IR function declaration */
def emitFunctionDeclaration(name: String, returnType: String, params: List[String]): String =
  val paramString = params.mkString(", ")
  s"declare $returnType @$name($paramString) #0"

/** Represents an error that occurred during code generation. */
case class CodeGenError(message: String, node: Option[AstNode] = None) extends CompilationError

/** Get size of LLVM type in bytes */
def sizeOfLlvmType(llvmType: String): Int = llvmType match
  case "i1" | "i8" => 1
  case "i16" => 2
  case "i32" | "float" => 4
  case "i64" | "double" | "ptr" => 8
  case t if t.endsWith("*") => 8
  case _ => 8

/** Get alignment of LLVM type in bytes (for struct field offset calculation) */
def alignOfLlvmType(llvmType: String): Int = llvmType match
  case "i1" | "i8" => 1
  case "i16" => 2
  case "i32" | "float" => 4
  case "i64" | "double" | "ptr" => 8
  case t if t.endsWith("*") => 8
  case _ => 8

/** Align offset to the given alignment boundary */
def alignTo(offset: Int, alignment: Int): Int =
  val mask = alignment - 1
  (offset + mask) & ~mask

enum TbaaNode derives CanEqual:
  case Root(name: String)
  case Scalar(name: String, parentId: Int)
  case Struct(name: String, fields: List[(Int, Int)]) // (typeId, offset) pairs

/** Represents the state during code generation.
  *
  * @param targetAbi
  *   the target ABI for native lowering
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
  moduleName:           String              = "",
  targetAbi:            TargetAbi           = TargetAbi.Default,
  abi:                  AbiStrategy         = AbiStrategy.forTarget(TargetAbi.Default),
  nextRegister:         Int                 = 0,
  output:               List[String]        = List.empty,
  initializers:         List[String]        = List.empty,
  stringConstants:      Map[String, String] = Map.empty,
  nextStringId:         Int                 = 0,
  moduleHeader:         Option[String]      = None,
  nativeTypes:          Map[String, String] = Map.empty,
  functionDeclarations: Map[String, String] = Map.empty,
  emitLoopMetadata:     Boolean             = false,
  // Warnings accumulated during codegen (lifted to CompilerState when done)
  warnings: List[CompilerWarning] = List.empty,
  // TBAA State
  tbaaNodes:     Map[TbaaNode, Int] = Map.empty,
  tbaaOutput:    List[String]       = List.empty,
  nextTbaaId:    Int                = 0,
  tbaaRootId:    Option[Int]        = None,
  tbaaScalarIds: Map[String, Int]   = Map.empty,
  tbaaStructIds: Map[String, Int]   = Map.empty,
  // Alias scope metadata
  aliasScopeOutput:   List[String]     = List.empty,
  aliasScopeDomainId: Option[Int]      = None,
  aliasScopeIds:      Map[String, Int] = Map.empty,
  emitAliasScopes:    Boolean          = false,
  // Resolvables index for soft reference lookups
  resolvables: ResolvablesIndex = ResolvablesIndex()
):
  /** Returns a new state with an updated register counter. */
  def withRegister(reg: Int): CodeGenState =
    copy(nextRegister = reg)

  /** Mangles a member name with the module prefix: modulename_membername */
  def mangleName(name: String): String =
    s"${moduleName.toLowerCase}_$name"

  /** Emits a single line of LLVM IR code, returning the updated state. */
  def emit(line: String): CodeGenState =
    copy(output = line :: output)

  /** Emits multiple lines of LLVM IR code at once. */
  def emitAll(lines: List[String]): CodeGenState =
    copy(output = lines.reverse ::: output)

  /** Adds an initializer function to the list. */
  def addInitializer(fnName: String): CodeGenState =
    copy(initializers = fnName :: initializers)

  def withLoopMetadata: CodeGenState =
    if emitLoopMetadata then this else copy(emitLoopMetadata = true)

  /** Adds a warning to the codegen state (will be lifted to CompilerState when done). */
  def addWarning(warning: CompilerWarning): CodeGenState =
    copy(warnings = warning :: warnings)

  /** Returns the complete LLVM IR as a single string. */
  def result: String =
    val mainOutput = output.reverse.mkString("\n")
    val aliasSection =
      if aliasScopeOutput.nonEmpty then
        "\n\n; Alias Scope Metadata\n" + aliasScopeOutput.reverse.mkString("\n")
      else ""
    val tbaaSection =
      if tbaaOutput.nonEmpty then "\n\n; TBAA Metadata\n" + tbaaOutput.reverse.mkString("\n")
      else ""
    mainOutput + aliasSection + tbaaSection

  /** Initialize TBAA Root if not present */
  def ensureTbaaRoot: CodeGenState =
    ensureTbaaRootWithId._1

  /** Initialize TBAA Root if not present, returning state and root ID */
  def ensureTbaaRootWithId: (CodeGenState, Int) =
    tbaaRootId match
      case Some(id) => (this, id)
      case None =>
        val rootNode = TbaaNode.Root("MML TBAA Root")
        val rootId   = nextTbaaId
        val metadata = s"""!$rootId = !{!"MML TBAA Root"}"""
        val newState = copy(
          tbaaNodes  = tbaaNodes + (rootNode -> rootId),
          tbaaOutput = metadata :: tbaaOutput,
          nextTbaaId = nextTbaaId + 1,
          tbaaRootId = Some(rootId)
        )
        (newState, rootId)

  /** Get or create a TBAA scalar type node */
  def getTbaaScalar(name: String): (CodeGenState, Int) =
    tbaaScalarIds.get(name) match
      case Some(id) => (this, id)
      case None =>
        val (s1, rootId) = ensureTbaaRootWithId
        val node         = TbaaNode.Scalar(name, rootId)
        s1.tbaaNodes.get(node) match
          case Some(id) => (s1.copy(tbaaScalarIds = s1.tbaaScalarIds + (name -> id)), id)
          case None =>
            val id = s1.nextTbaaId
            // Scalar type metadata: !n = !{!"name", !root, i64 0}
            val metadata = s"""!$id = !{!"$name", !$rootId, i64 0}"""
            (
              s1.copy(
                tbaaNodes     = s1.tbaaNodes + (node -> id),
                tbaaOutput    = metadata :: s1.tbaaOutput,
                nextTbaaId    = s1.nextTbaaId + 1,
                tbaaScalarIds = s1.tbaaScalarIds + (name -> id)
              ),
              id
            )

  /** Get or create a TBAA access tag for a scalar access */
  def getTbaaAccessTag(typeName: String): (CodeGenState, String) =
    val (s1, typeId) = getTbaaScalar(typeName)

    // We need to emit the tag metadata node itself
    val tagKey = s"tag_$typeName"
    s1.tbaaScalarIds.get(tagKey) match
      case Some(id) => (s1, s"!$id")
      case None =>
        val tagId    = s1.nextTbaaId
        val metadata = s"!$tagId = !{!$typeId, !$typeId, i64 0}"
        (
          s1.copy(
            tbaaOutput    = metadata :: s1.tbaaOutput,
            nextTbaaId    = s1.nextTbaaId + 1,
            tbaaScalarIds = s1.tbaaScalarIds + (tagKey -> tagId)
          ),
          s"!$tagId"
        )

  /** Get or create a TBAA struct type node with field layout.
    * @param name
    *   struct type name (e.g., "String")
    * @param fields
    *   list of (scalarTypeName, byteOffset) pairs
    * @return
    *   (updated state, struct node ID)
    */
  def getTbaaStruct(name: String, fields: List[(String, Int)]): (CodeGenState, Int) =
    tbaaStructIds.get(name) match
      case Some(id) => (this, id)
      case None =>
        // First ensure all scalar types exist
        val (stateWithScalars, scalarIds) = fields.foldLeft((this, List.empty[(Int, Int)])) {
          case ((s, acc), (scalarName, offset)) =>
            val (s2, scalarId) = s.getTbaaScalar(scalarName)
            (s2, acc :+ (scalarId, offset))
        }
        val structId = stateWithScalars.nextTbaaId
        // Struct node: !{!"name", !scalar1, i64 offset1, !scalar2, i64 offset2, ...}
        val fieldParts = scalarIds.map { case (sid, off) => s"!$sid, i64 $off" }.mkString(", ")
        val metadata   = s"""!$structId = !{!"$name", $fieldParts}"""
        (
          stateWithScalars.copy(
            tbaaOutput    = metadata :: stateWithScalars.tbaaOutput,
            nextTbaaId    = stateWithScalars.nextTbaaId + 1,
            tbaaStructIds = stateWithScalars.tbaaStructIds + (name -> structId)
          ),
          structId
        )

  /** Get or create a TBAA field access tag for a struct field.
    * @param structName
    *   the struct type name
    * @param structFields
    *   list of (scalarTypeName, byteOffset) for all fields
    * @param fieldIndex
    *   which field is being accessed
    * @return
    *   (updated state, tag string like "!5")
    */
  def getTbaaFieldAccessTag(
    structName:   String,
    structFields: List[(String, Int)],
    fieldIndex:   Int
  ): (CodeGenState, String) =
    val (scalarName, offset) = structFields(fieldIndex)
    val tagKey               = s"tag_${structName}_field_$fieldIndex"
    tbaaScalarIds.get(tagKey) match
      case Some(id) => (this, s"!$id")
      case None =>
        // Ensure struct node exists
        val (s1, structId) = getTbaaStruct(structName, structFields)
        // Get scalar type ID for this field
        val (s2, scalarId) = s1.getTbaaScalar(scalarName)
        val tagId          = s2.nextTbaaId
        // Access tag: !{!structId, !scalarId, i64 offset}
        val metadata = s"!$tagId = !{!$structId, !$scalarId, i64 $offset}"
        (
          s2.copy(
            tbaaOutput    = metadata :: s2.tbaaOutput,
            nextTbaaId    = s2.nextTbaaId + 1,
            tbaaScalarIds = s2.tbaaScalarIds + (tagKey -> tagId)
          ),
          s"!$tagId"
        )

  /** Ensure an alias scope domain metadata node exists. */
  def ensureAliasScopeDomain: (CodeGenState, Int) =
    aliasScopeDomainId match
      case Some(id) => (this, id)
      case None =>
        val id       = nextTbaaId
        val metadata = s"""!$id = distinct !{!$id, !"MML Alias Scope Domain"}"""
        (
          copy(
            aliasScopeOutput   = metadata :: aliasScopeOutput,
            nextTbaaId         = nextTbaaId + 1,
            aliasScopeDomainId = Some(id)
          ),
          id
        )

  /** Ensure an alias scope metadata node exists for a given MML type name. */
  def ensureAliasScopeNode(typeName: String): (CodeGenState, Int) =
    aliasScopeIds.get(typeName) match
      case Some(id) => (this, id)
      case None =>
        val (stateWithDomain, domainId) = ensureAliasScopeDomain
        val id                          = stateWithDomain.nextTbaaId
        val metadata = s"""!$id = distinct !{!$id, !$domainId, !"alias.scope:$typeName"}"""
        (
          stateWithDomain.copy(
            aliasScopeOutput = metadata :: stateWithDomain.aliasScopeOutput,
            nextTbaaId       = stateWithDomain.nextTbaaId + 1,
            aliasScopeIds    = stateWithDomain.aliasScopeIds + (typeName -> id)
          ),
          id
        )

  /** Get the metadata reference string for an alias scope. */
  def aliasScopeTag(typeName: String): (CodeGenState, String) =
    val (stateWithScope, id) = ensureAliasScopeNode(typeName)
    (stateWithScope, s"!{!$id}")

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
    * @param targetTriple
    *   the target triple for code generation
    * @return
    *   updated CodeGenState with the module header
    */
  def withModuleHeader(moduleName: String, targetTriple: String): CodeGenState =
    moduleHeader match
      case Some(_) => this // Already has a header
      case None =>
        val header = s"; ModuleID = '$moduleName'\ntarget triple = \"$targetTriple\"\n"
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

/** An entry in the function scope, tracking a binding's register and type info.
  *
  * When `isLiteral` is true, the value has not been materialized into a register — it will be
  * emitted inline by consumers (e.g. as an immediate operand).
  */
case class ScopeEntry(
  register:     Int,
  typeName:     String,
  isLiteral:    Boolean        = false,
  literalValue: Option[String] = None
):
  def operandStr: String =
    literalValue.getOrElse(
      if isLiteral then register.toString else s"%$register"
    )

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
  register:     Int,
  state:        CodeGenState,
  isLiteral:    Boolean,
  typeName:     String,
  exitBlock:    Option[String] = None,
  literalValue: Option[String] = None
) {
  def operandStr: String =
    literalValue.getOrElse(
      if isLiteral then register.toString else s"%$register"
    )
}

def getMmlTypeName(typeSpec: Type): Option[String] = typeSpec match {
  case TypeRef(_, name, _, _) => Some(name)
  case NativePrimitive(_, "i1", _, _) => Some("Bool")
  case NativePrimitive(_, "i64", _, _) => Some("Int")
  case NativePrimitive(_, "void", _, _) => Some("Unit")
  case NativePointer(_, llvm, _, _) => Some(s"Pointer($llvm)")
  case NativeStruct(_, _, _, _) => Some("NativeStruct")
  case TypeUnit(_) => Some("Unit")
  case TypeFn(_, _, _) => Some("Function")
  case TypeTuple(_, _) => Some("Tuple")
  case TypeStruct(_, _, _, name, _, _) => Some(name)
  case _ => None
}

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
    case NativePrimitive(_, llvmType, _, _) =>
      Right(s"%$typeName = type $llvmType")
    case NativePointer(_, llvmType, _, _) =>
      Right(emitPointerTypeDefinition(typeName, llvmType))
    case NativeStruct(_, fields, _, _) =>
      // Convert each field's TypeSpec to LLVM type
      val fieldResults = fields.map { case (fieldName, typeSpec) =>
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
        Right(emitTypeDefinition(s"struct.$typeName", llvmFields))

/** Convert any TypeSpec to LLVM type string.
  *
  * @param typeSpec
  *   the type specification to convert
  * @param state
  *   the current code generation state
  * @return
  *   Either an error or the LLVM type string
  */
def getLlvmType(
  typeSpec: mml.mmlclib.ast.Type,
  state:    CodeGenState
): Either[CodeGenError, String] =

  typeSpec match
    case TypeRef(_, name, resolvedId, _) =>
      resolvedId.flatMap(state.resolvables.lookupType) match
        case Some(typeDef: TypeDef) =>
          typeDef.typeSpec match
            case Some(nativeType: NativeType) =>
              // Follow through to get the actual LLVM type
              nativeType match
                case NativePrimitive(_, llvmType, _, _) => Right(llvmType)
                case NativePointer(_, llvmType, _, _) => Right(s"$llvmType*")
                case _: NativeStruct => Right(s"%struct.$name") // Use %struct. prefix
            case _ =>
              // Non-native types cannot be translated to LLVM yet
              Left(CodeGenError(s"Cannot determine LLVM type for non-native type: $name"))
        case Some(typeAlias: TypeAlias) =>
          // Use the computed typeSpec if available, otherwise follow the typeRef
          typeAlias.typeSpec match
            case Some(spec) => getLlvmType(spec, state)
            case None => getLlvmType(typeAlias.typeRef, state)
        case Some(_: TypeStruct) =>
          Right(s"%struct.$name")
        case _ =>
          Left(
            CodeGenError(
              s"Unresolved type reference: $name (TypeRef with no resolvedId - probably typechecker bug)"
            )
          )
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
    case ts: TypeStruct =>
      Right(s"%struct.${ts.name}")
    case TypeFn(_, _, ret) =>
      // When a function type leaks into places expecting a concrete type (e.g., constructor
      // return type lookup), fall back to the return type's LLVM mapping.
      getLlvmType(ret, state)
    case other =>
      // No LLVM type mapping for this TypeSpec
      Left(
        CodeGenError(
          s"No LLVM type mapping for TypeSpec: ${other.getClass.getSimpleName}",
          Some(other)
        )
      )
