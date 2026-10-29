package mml.mmlclib.ast

import cats.data.NonEmptyList
import mml.mmlclib.errors.DiagnosticId

// **Type Specifications**
sealed trait Type extends AstNode, FromSource

sealed trait ResolvableType extends Resolvable

// type ResolvableType = TypeAlias | TypeDef

/** References a type by name */
case class TypeRef(
  source:       SourceOrigin,
  name:         String,
  resolvedId:   Option[String] = None,
  candidateIds: List[String]   = Nil
) extends Type

sealed trait NativeType extends Type, Native:
  def memEffect: Option[MemEffect]
  def freeFn:    Option[String]

case class NativePrimitive(
  source:    SourceOrigin,
  llvmType:  String,
  memEffect: Option[MemEffect] = None,
  freeFn:    Option[String]    = None
) extends NativeType

case class NativePointer(
  source:    SourceOrigin,
  llvmType:  String,
  memEffect: Option[MemEffect] = None,
  freeFn:    Option[String]    = None
) extends NativeType

// TODO: make this use Field
case class NativeStruct(
  source:    SourceOrigin,
  fields:    List[(String, Type)],
  memEffect: Option[MemEffect] = None,
  freeFn:    Option[String]    = None
) extends NativeType

/** A type application, ie:  `List Int, Map String Int` */
case class TypeApplication(
  source: SourceOrigin,
  base:   Type,
  args:   List[Type]
) extends Type

/** The type of a Fn `String -> Int` */
case class TypeFn(
  source:     SourceOrigin,
  paramTypes: NonEmptyList[Type],
  returnType: Type
) extends Type

/** A tuple type: `(1, "uno") : (Int, String)` */
case class TypeTuple(
  source:   SourceOrigin,
  elements: List[Type]
) extends Type

/** Structural type `{ name: String, age: Int }` Can't be instanced. It's just an interface like.
  * Open row, tbd row field
  */
case class TypeOpenRecord(
  source: SourceOrigin,
  fields: List[(String, Type)]
) extends Type

/** Closed, nominal record */
case class TypeStruct(
  source:     SourceOrigin,
  docComment: Option[DocComment],
  visibility: Visibility,
  nameNode:   Name,
  fields:     Vector[Field],
  id:         Option[String] = None
) extends Type,
      ResolvableType,
      Decl

case class Field(
  source:   SourceOrigin,
  nameNode: Name,
  typeSpec: Type,
  id:       Option[String] = None
) extends FromSource,
      Resolvable

/** Refine types with a predicate `Int {i => i < 100 && i > 0 }` */
case class TypeRefinement(
  source: SourceOrigin,
  id:     Option[String],
  expr:   Expr
) extends Type

/** Union types  `Int | None | Something | Other` */
case class Union(
  source: SourceOrigin,
  types:  List[Type]
) extends Type

/** Intersection Types `Readable & Writable` */
case class Intersection(
  source: SourceOrigin,
  types:  List[Type]
) extends Type

/** The Unit type. */
case class TypeUnit(
  source: SourceOrigin
) extends Type

/** A grouping of types, mostly for disambiguation: `Map String (List Int)` */
case class TypeGroup(
  source: SourceOrigin,
  types:  List[Type]
) extends Type

/** A type variable (like 'T, 'R in the type system) */
case class TypeVariable(
  source: SourceOrigin,
  name:   String // "'T", "'R", "'A", etc.
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
  source:   SourceOrigin,
  vars:     List[String], // ["'T", "'R"] - the quantified variables
  bodyType: Type // The actual type with variables
) extends Type

/** A type definition, which is a new named type, as opposed to a type alias. */
case class TypeDef(
  visibility: Visibility         = Visibility.Protected,
  source:     SourceOrigin,
  nameNode:   Name,
  typeSpec:   Option[Type],
  docComment: Option[DocComment] = None,
  typeAsc:    Option[Type]       = None,
  id:         Option[String]     = None
) extends Decl,
      ResolvableType,
      Typeable

/** A type alias, which is a new name for an existing type, NOT a new type */
case class TypeAlias(
  visibility: Visibility         = Visibility.Protected,
  source:     SourceOrigin,
  nameNode:   Name,
  typeRef:    Type,
  typeSpec:   Option[Type]       = None,
  typeAsc:    Option[Type]       = None,
  docComment: Option[DocComment] = None,
  id:         Option[String]     = None
) extends Decl,
      ResolvableType,
      Typeable

/** Recovered malformed type syntax. Ingest attaches the primary diagnostic for this occurrence. */
case class ParseTypeError(
  source:     SourceOrigin,
  message:    String,
  failedCode: Option[String],
  causes:     Vector[DiagnosticId] = Vector.empty
) extends Type,
      Error

object ParseTypeError:
  def apply(span: SrcSpan, message: String, failedCode: Option[String]): ParseTypeError =
    new ParseTypeError(SourceOrigin.Loc(span), message, failedCode)

/** A failed type lookup retains its written reference and the diagnostics that explain it. */
sealed trait TypeResolutionError extends Type, InvalidNode:
  def originalType: TypeRef
  def causes:       Vector[DiagnosticId]

/** No type declaration matches the written reference. */
case class UndefinedTypeError(
  source:       SourceOrigin,
  originalType: TypeRef,
  causes:       Vector[DiagnosticId] = Vector.empty
) extends TypeResolutionError

/** More than one declaration matches the written reference. */
case class AmbiguousTypeError(
  source:       SourceOrigin,
  originalType: TypeRef,
  candidates:   List[ResolvableType],
  causes:       Vector[DiagnosticId] = Vector.empty
) extends TypeResolutionError
