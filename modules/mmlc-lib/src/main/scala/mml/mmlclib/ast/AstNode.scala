package mml.mmlclib.ast

import cats.data.NonEmptyList

/* Represents a point in the source code, with a line and column number.
 */
final case class SrcPoint(
  line:  Int,
  col:   Int,
  index: Int
)

/* Represents a span of source code, with a start and end point.
 */
final case class SrcSpan(
  start: SrcPoint,
  end:   SrcPoint
)

sealed trait AstNode derives CanEqual

sealed trait Typeable extends AstNode {

  /** This is the computed type */
  def typeSpec: Option[TypeSpec]

  /** This is the type the user declares. */
  def typeAsc: Option[TypeSpec]
}

sealed trait FromSource extends AstNode {
  def span: SrcSpan
}

enum ModVisibility:
  // Everyone
  case Public
  // Everyone in the same and below modules
  case Lexical
  // Only within the same module
  case Protected

enum MemberVisibility derives CanEqual:
  // Everyone
  case Public
  // Only siblings of the conatianer
  case Protected
  // Inside the module that contains the member
  case Private

case class Module(
  span:       SrcSpan,
  name:       String,
  visibility: ModVisibility,
  members:    List[Member],
  isImplicit: Boolean            = false,
  docComment: Option[DocComment] = None
) extends AstNode,
      FromSource

/** Represents a top level member of a module. */
sealed trait Member extends AstNode

sealed trait Resolvable extends AstNode:
  def name: String

case class DocComment(
  span: SrcSpan,
  text: String
) extends AstNode,
      FromSource

sealed trait Decl extends Member, Typeable, Resolvable:
  def docComment: Option[DocComment]
  def visibility: MemberVisibility

case class FnParam(
  span:       SrcSpan,
  name:       String,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends AstNode,
      FromSource,
      Typeable,
      Resolvable

case class FnDef(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  params:     List[FnParam],
  body:       Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends Decl,
      FromSource

enum Associativity derives CanEqual:
  case Left
  case Right

sealed trait OpDef extends Decl, FromSource {
  def name:       String
  def precedence: Int
  def assoc:      Associativity
  def body:       Expr
}

case class BinOpDef(
  visibility: MemberVisibility = MemberVisibility.Public,
  span:       SrcSpan,
  name:       String,
  param1:     FnParam,
  param2:     FnParam,
  precedence: Int,
  assoc:      Associativity,
  /** Expression that is evaluated when the operator is used.
    */
  body: Expr,
  /** Type specification for the operator.
    *
    * If `None` type is unknown
    */
  typeSpec: Option[TypeSpec] = None,
  /** */
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends OpDef

case class UnaryOpDef(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  param:      FnParam,
  precedence: Int,
  assoc:      Associativity,
  body:       Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends OpDef

case class Bnd(
  visibility: MemberVisibility   = MemberVisibility.Protected,
  span:       SrcSpan,
  name:       String,
  value:      Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None,
  syntax:     Option[Decl]       = None
) extends Decl,
      FromSource

sealed trait Term extends AstNode, Typeable, FromSource

case class TermError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String]
) extends Term,
      Error:
  final val typeSpec: Option[TypeSpec] = None
  final val typeAsc:  Option[TypeSpec] = None

case class Expr(
  span:     SrcSpan,
  terms:    List[Term],
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term

case class Cond(
  span:     SrcSpan,
  cond:     Expr,
  ifTrue:   Expr,
  ifFalse:  Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

case class App(
  span:     SrcSpan,
  fn:       Ref | App,
  arg:      Expr,
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term

case class Lambda(
  span:     SrcSpan,
  params:   List[FnParam],
  body:     Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

case class TermGroup(
  span:    SrcSpan,
  inner:   Expr,
  typeAsc: Option[TypeSpec] = None
) extends Term:
  def typeSpec: Option[TypeSpec] = inner.typeSpec

case class Tuple(
  span:     SrcSpan,
  elements: NonEmptyList[Expr],
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term

/** Points to something declared elsewhere */
case class Ref(
  span:       SrcSpan,
  name:       String,
  typeAsc:    Option[TypeSpec]   = None,
  typeSpec:   Option[TypeSpec]   = None,
  resolvedAs: Option[Resolvable] = None,
  candidates: List[Resolvable]   = Nil
) extends Term,
      FromSource

case class Placeholder(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends Term,
      FromSource

case class Hole(
  span:     SrcSpan,
  typeAsc:  Option[TypeSpec] = None,
  typeSpec: Option[TypeSpec] = None
) extends Term,
      FromSource

// **Literals**

sealed trait LiteralValue extends Term, FromSource

case class LiteralInt(
  span:     SrcSpan,
  value:    Int,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralInt {
  def apply(span: SrcSpan, value: Int): LiteralInt =
    new LiteralInt(span, value, Some(TypeRef(span, "Int")), None)

  def unapply(lit: LiteralInt): Option[(SrcSpan, Int)] =
    Some((lit.span, lit.value))
}

case class LiteralString(
  span:     SrcSpan,
  value:    String,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralString {
  def apply(span: SrcSpan, value: String): LiteralString =
    new LiteralString(span, value, Some(TypeRef(span, "String")), None)

  def unapply(lit: LiteralString): Option[(SrcSpan, String)] =
    Some((lit.span, lit.value))
}

case class LiteralBool(
  span:     SrcSpan,
  value:    Boolean,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralBool {
  def apply(span: SrcSpan, value: Boolean): LiteralBool =
    new LiteralBool(span, value, Some(TypeRef(span, "Bool")), None)

  def unapply(lit: LiteralBool): Option[(SrcSpan, Boolean)] =
    Some((lit.span, lit.value))
}

case class LiteralUnit(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralUnit {
  def apply(span: SrcSpan): LiteralUnit =
    new LiteralUnit(span, Some(TypeRef(span, "Unit")), None)

  def unapply(lit: LiteralUnit): Option[SrcSpan] =
    Some(lit.span)
}

case class LiteralFloat(
  span:     SrcSpan,
  value:    Float,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends LiteralValue

object LiteralFloat {
  def apply(span: SrcSpan, value: Float): LiteralFloat =
    new LiteralFloat(span, value, Some(TypeRef(span, "Float")), None)

  def unapply(lit: LiteralFloat): Option[(SrcSpan, Float)] =
    Some((lit.span, lit.value))
}

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

/** Union types  `Int | None` */
// TODO: this is a Tuple2, not a list. remove 'types' and use tp1 and tp2
case class Union(span: SrcSpan, types: List[TypeSpec]) extends TypeSpec

/** Intersection Types `Readable & Writable` */
case class Intersection(span: SrcSpan, types: List[TypeSpec]) extends TypeSpec

/** The unit type `()` */
case class TypeUnit(span: SrcSpan) extends TypeSpec

/** Type Sequences `[Int], [String], [T]` */
case class TypeSeq(span: SrcSpan, inner: TypeSpec) extends TypeSpec

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

sealed trait Native extends AstNode

case class NativeImpl(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None,
  nativeOp: Option[String]   = None
) extends Native,
      Term

// **Invalid Nodes for Error Recovery**

/** Marker trait for nodes that represent invalid/error constructs. These nodes allow the compiler
  * to continue processing even when errors are encountered, enabling better LSP support and partial
  * compilation.
  */
sealed trait InvalidNode extends AstNode

sealed trait Error extends AstNode, InvalidNode:
  def span:       SrcSpan
  def message:    String
  def failedCode: Option[String]

/** Represents an expression that could not be resolved or is otherwise invalid. Preserves the
  * original expression for debugging and error reporting.
  */
case class InvalidExpression(
  span:         SrcSpan,
  originalExpr: Expr,
  typeSpec:     Option[TypeSpec] = None,
  typeAsc:      Option[TypeSpec] = None
) extends Term,
      InvalidNode,
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

/** Represents a duplicate member declaration. The first occurrence remains valid and referenceable,
  * subsequent duplicates are wrapped in this node.
  */
case class DuplicateMember(
  span:            SrcSpan,
  originalMember:  Member,
  firstOccurrence: Member
) extends Member,
      InvalidNode,
      FromSource

/** Represents a member that is invalid for reasons other than being a duplicate. For example,
  * functions with duplicate parameter names.
  */
case class InvalidMember(
  span:           SrcSpan,
  originalMember: Member,
  reason:         String
) extends Member,
      InvalidNode,
      FromSource

case class ParsingMemberError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String]
) extends Member,
      Error

case class ParsingIdError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String],
  invalidId:  String
) extends Member,
      Error
