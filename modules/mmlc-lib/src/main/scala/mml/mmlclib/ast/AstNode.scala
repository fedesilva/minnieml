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
  def typeSpec: Option[TypeSpec]
  def typeAsc:  Option[TypeSpec]
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

sealed trait Error extends AstNode:
  def span:       SrcSpan
  def message:    String
  def failedCode: Option[String]

case class MemberError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String]
) extends Member,
      Error

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
  docComment: Option[DocComment] = None
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

case class TermGroup(
  span:    SrcSpan,
  inner:   Expr,
  typeAsc: Option[TypeSpec] = None
) extends Term,
      FromSource:
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
  span:  SrcSpan,
  value: Int
) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralIntType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralString(span: SrcSpan, value: String) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralStringType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralBool(span: SrcSpan, value: Boolean) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralBoolType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralUnit(span: SrcSpan) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralUnitType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralFloat(span: SrcSpan, value: Float) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralFloatType(span))
  final val typeAsc:  Option[TypeSpec] = None

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

case class NativeType(
  span:       SrcSpan,
  attributes: Map[String, String] = Map()
) extends TypeSpec

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

/** Helpers to represent known types */
sealed trait LiteralType extends TypeSpec {
  def typeName: String
}
case class LiteralIntType(span: SrcSpan) extends LiteralType {
  final val typeName: "Int" = "Int"
}
case class LiteralStringType(span: SrcSpan) extends LiteralType {
  final val typeName: "String" = "String"
}
case class LiteralBoolType(span: SrcSpan) extends LiteralType {
  final val typeName: "Bool" = "Bool"
}

case class LiteralUnitType(span: SrcSpan) extends LiteralType {
  final val typeName: "Unit" = "Unit"
}

case class LiteralFloatType(span: SrcSpan) extends LiteralType {
  final val typeName: "Float" = "Float"
}

sealed trait Native extends AstNode

case class NativeTypeImpl(
  span: SrcSpan
) extends TypeSpec,
      Native,
      FromSource

case class NativeImpl(
  span:     SrcSpan,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Native,
      Term
