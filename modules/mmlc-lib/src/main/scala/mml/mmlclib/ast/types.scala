package mml.mmlclib.ast

// **Type Specifications**
sealed trait TypeSpec extends AstNode, FromSource

type ResolvableType = TypeAlias | TypeDef

/** References a type by name */
case class TypeRef(
  span:       SrcSpan,
  name:       String,
  resolvedAs: Option[ResolvableType] = None
) extends TypeSpec

sealed trait NativeType extends TypeSpec, Native

case class NativePrimitive(
  span:     SrcSpan,
  llvmType: String
) extends NativeType

case class NativePointer(
  span:     SrcSpan,
  llvmType: String
) extends NativeType

case class NativeStruct(
  span:   SrcSpan,
  fields: Map[String, TypeSpec]
) extends NativeType

/** A type application, ie:  `List Int, Map String Int` */
case class TypeApplication(span: SrcSpan, base: TypeSpec, args: List[TypeSpec]) extends TypeSpec

/** The type of a Fn `String -> Int` */
case class TypeFn(span: SrcSpan, paramTypes: List[TypeSpec], returnType: TypeSpec) extends TypeSpec

/** A tuple type: `(1, "uno") : (Int, String)` */
case class TypeTuple(span: SrcSpan, elements: List[TypeSpec]) extends TypeSpec

/** Structural type `{ name: String, age: Int }` */
case class TypeStruct(span: SrcSpan, fields: List[(String, TypeSpec)]) extends TypeSpec

/** Refine types with a predicate `Int {i => i < 100 && i > 0 }` */
case class TypeRefinement(span: SrcSpan, id: Option[String], expr: Expr) extends TypeSpec

/** Union types  `Int | None | Something | Other` */
case class Union(span: SrcSpan, types: List[TypeSpec]) extends TypeSpec

/** Intersection Types `Readable & Writable` */
case class Intersection(span: SrcSpan, types: List[TypeSpec]) extends TypeSpec

/** The unit type `()` */
case class TypeUnit(span: SrcSpan) extends TypeSpec

/** A grouping of types, mostly for disambiguation: `Map String (List Int)` */
case class TypeGroup(span: SrcSpan, types: List[TypeSpec]) extends TypeSpec

/** A type variable (like 'T, 'R in the type system) */
case class TypeVariable(
  span: SrcSpan,
  name: String // "'T", "'R", "'A", etc.
) extends TypeSpec

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
  bodyType: TypeSpec // The actual type with variables
) extends TypeSpec

/** A type definition, which is a new named type, as opposed to a type alias. */
case class TypeDef(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  typeSpec:   Option[TypeSpec],
  docComment: Option[DocComment] = None,
  typeAsc:    Option[TypeSpec]   = None
) extends Decl,
      FromSource

/** A type alias, which is a new name for an existing type, NOT a new type */
case class TypeAlias(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  typeRef:    TypeSpec,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends Decl,
      FromSource

/** Represents a type specification that could not be resolved. Preserves the original type for
  * debugging and error reporting.
  */
case class InvalidType(
  span:         SrcSpan,
  originalType: TypeSpec
) extends TypeSpec,
      InvalidNode,
      FromSource
