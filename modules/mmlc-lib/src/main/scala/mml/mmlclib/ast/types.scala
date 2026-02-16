package mml.mmlclib.ast

// **Type Specifications**
sealed trait Type extends AstNode, FromSource

sealed trait ResolvableType extends Resolvable

// type ResolvableType = TypeAlias | TypeDef

/** References a type by name */
case class TypeRef(
  span:         SrcSpan,
  name:         String,
  resolvedId:   Option[String] = None,
  candidateIds: List[String]   = Nil
) extends Type

sealed trait NativeType extends Type, Native:
  def memEffect: Option[MemEffect]
  def freeFn:    Option[String]

case class NativePrimitive(
  span:      SrcSpan,
  llvmType:  String,
  memEffect: Option[MemEffect] = None,
  freeFn:    Option[String]    = None
) extends NativeType

case class NativePointer(
  span:      SrcSpan,
  llvmType:  String,
  memEffect: Option[MemEffect] = None,
  freeFn:    Option[String]    = None
) extends NativeType

// TODO: make this use Field
case class NativeStruct(
  span:      SrcSpan,
  fields:    List[(String, Type)],
  memEffect: Option[MemEffect] = None,
  freeFn:    Option[String]    = None
) extends NativeType

/** A type application, ie:  `List Int, Map String Int` */
case class TypeApplication(span: SrcSpan, base: Type, args: List[Type]) extends Type

/** The type of a Fn `String -> Int` */
case class TypeFn(span: SrcSpan, paramTypes: List[Type], returnType: Type) extends Type

/** A tuple type: `(1, "uno") : (Int, String)` */
case class TypeTuple(span: SrcSpan, elements: List[Type]) extends Type

/** Structural type `{ name: String, age: Int }` Can't be instanced. It's just an interface like.
  * Open row, tbd row field
  */
case class TypeOpenRecord(span: SrcSpan, fields: List[(String, Type)]) extends Type

// TODO: not all decls are typeable: a type is a type not a typeable
/** Closed, nominal record */
case class TypeStruct(
  span:       SrcSpan,
  docComment: Option[DocComment],
  visibility: Visibility,
  nameNode:   Name,
  fields:     Vector[Field],
  id:         Option[String] = None
) extends Type,
      ResolvableType,
      Decl:
  val typeSpec: Option[Type] = None
  val typeAsc:  Option[Type] = None

case class Field(
  span:     SrcSpan,
  nameNode: Name,
  typeSpec: Type,
  id:       Option[String] = None
) extends FromSource,
      Resolvable

/** Refine types with a predicate `Int {i => i < 100 && i > 0 }` */
case class TypeRefinement(span: SrcSpan, id: Option[String], expr: Expr) extends Type

/** Union types  `Int | None | Something | Other` */
case class Union(span: SrcSpan, types: List[Type]) extends Type

/** Intersection Types `Readable & Writable` */
case class Intersection(span: SrcSpan, types: List[Type]) extends Type

/** The unit type `()` */
case class TypeUnit(span: SrcSpan) extends Type

/** A grouping of types, mostly for disambiguation: `Map String (List Int)` */
case class TypeGroup(span: SrcSpan, types: List[Type]) extends Type

/** A type variable (like 'T, 'R in the type system) */
case class TypeVariable(
  span: SrcSpan,
  name: String // "'T", "'R", "'A", etc.
) extends Type

/** A type scheme: ∀'T 'R 'A. Type
  *
  * This represents both polymorphic and monomorphic types uniformly.
  *
  * Examples:
  *   - ∀'T. 'T → 'T (identity function)
  *   - ∀'A 'B. 'A → 'B → 'A (const function)
  *   - Int → Int (monomorphic, vars = Nil)
  */
case class TypeScheme(
  span:     SrcSpan,
  vars:     List[String], // ["'T", "'R"] - the quantified variables
  bodyType: Type // The actual type with variables
) extends Type

/** A type definition, which is a new named type, as opposed to a type alias. */
case class TypeDef(
  visibility: Visibility         = Visibility.Protected,
  span:       SrcSpan,
  nameNode:   Name,
  typeSpec:   Option[Type],
  docComment: Option[DocComment] = None,
  typeAsc:    Option[Type]       = None,
  id:         Option[String]     = None
) extends Decl,
      ResolvableType,
      FromSource

/** A type alias, which is a new name for an existing type, NOT a new type */
case class TypeAlias(
  visibility: Visibility         = Visibility.Protected,
  span:       SrcSpan,
  nameNode:   Name,
  typeRef:    Type,
  typeSpec:   Option[Type]       = None,
  typeAsc:    Option[Type]       = None,
  docComment: Option[DocComment] = None,
  id:         Option[String]     = None
) extends Decl,
      ResolvableType,
      FromSource

/** Represents a type specification that could not be resolved. Preserves the original type for
  * debugging and error reporting.
  */
case class InvalidType(
  span:         SrcSpan,
  originalType: Type
) extends Type,
      InvalidNode,
      FromSource
