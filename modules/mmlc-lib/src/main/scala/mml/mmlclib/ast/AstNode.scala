package mml.mmlclib.ast

import cats.data.NonEmptyList

/* Represents a point in the source code, with a line and column number.
 */
final case class SourcePoint(
  line:  Int,
  col:   Int,
  index: Int
)

/* Represents a span of source code, with a start and end point.
 */
final case class SourceSpan(
  start: SourcePoint,
  end:   SourcePoint
)

sealed trait AstNode derives CanEqual

sealed trait Typeable extends AstNode {
  def typeSpec: Option[TypeSpec]
  def typeAsc:  Option[TypeSpec]
}

sealed trait FromSource extends AstNode {
  def span: SourceSpan
}

enum ModVisibility:
  case Public
  case Protected
  case Lexical

case class Module(
  span:       SourceSpan,
  name:       String,
  visibility: ModVisibility,
  members:    List[Member],
  isImplicit: Boolean            = false,
  docComment: Option[DocComment] = None
) extends AstNode,
      FromSource

/** Represents a top level member of a module. */
sealed trait Member extends AstNode

sealed trait Error extends AstNode {
  def span:       SourceSpan
  def message:    String
  def failedCode: Option[String]
}

case class MemberError(
  span:       SourceSpan,
  message:    String,
  failedCode: Option[String]
) extends Member,
      Error

case class DocComment(
  span: SourceSpan,
  text: String
) extends AstNode,
      FromSource

sealed trait Decl extends Member, Typeable {
  def docComment: Option[DocComment]
}

case class FnParam(
  span:       SourceSpan,
  name:       String,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends AstNode,
      FromSource,
      Typeable

case class FnDef(
  span:       SourceSpan,
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

/*
 *    precedence table
 *    0 - special/reserved
 *    1 - application
 *    2 - div/mul
 *    3 - sum/substr
 *
 */
sealed trait OpDef extends Decl, FromSource {
  def precedence: Int
}

case class BinOpDef(
  span:       SourceSpan,
  name:       String,
  param1:     FnParam,
  param2:     FnParam,
  precedence: Int,
  body:       Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends OpDef

case class UnaryOpDef(
  span:       SourceSpan,
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
  span:       SourceSpan,
  name:       String,
  value:      Expr,
  typeSpec:   Option[TypeSpec]   = None,
  typeAsc:    Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends Decl,
      FromSource

sealed trait Term extends AstNode, Typeable, FromSource

case class TermError(
  span:       SourceSpan,
  message:    String,
  failedCode: Option[String]
) extends Term,
      Error:
  final val typeSpec: Option[TypeSpec] = None
  final val typeAsc:  Option[TypeSpec] = None

case class Expr(
  span:     SourceSpan,
  terms:    List[Term],
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

case class Cond(
  span:     SourceSpan,
  cond:     Expr,
  ifTrue:   Expr,
  ifFalse:  Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

case class GroupTerm(
  span:    SourceSpan,
  inner:   Expr,
  typeAsc: Option[TypeSpec] = None
) extends Term,
      FromSource:
  def typeSpec: Option[TypeSpec] = inner.typeSpec

case class Tuple(
  span:     SourceSpan,
  elements: NonEmptyList[Expr],
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term

/** Points to something declared elsewhere */
case class Ref(
  span:     SourceSpan,
  name:     String,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends Term,
      FromSource

case class MehRef(
  span:     SourceSpan,
  typeSpec: Option[TypeSpec],
  typeAsc:  Option[TypeSpec] = None
) extends Term,
      FromSource

case class Hole(
  span:     SourceSpan,
  typeSpec: Option[TypeSpec] = None,
  typeAsc:  Option[TypeSpec] = None
) extends Term,
      FromSource

// **Literals**

sealed trait LiteralValue extends Term, FromSource

case class LiteralInt(
  span:  SourceSpan,
  value: Int
) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralIntType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralString(span: SourceSpan, value: String) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralStringType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralBool(span: SourceSpan, value: Boolean) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralBoolType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralUnit(span: SourceSpan) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralUnitType(span))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralFloat(span: SourceSpan, value: Float) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralFloatType(span))
  final val typeAsc:  Option[TypeSpec] = None

// **Type Specifications**
sealed trait TypeSpec extends AstNode, FromSource

/** A type by name */
case class TypeName(span: SourceSpan, name: String) extends TypeSpec

/** A type application, ie:  `List Int, Map String Int` */
case class TypeApplication(span: SourceSpan, base: TypeSpec, args: List[TypeSpec]) extends TypeSpec

/** The type of a Fn `String => Int` */
case class TypeFn(span: SourceSpan, paramTypes: List[TypeSpec], returnType: TypeSpec)
    extends TypeSpec

/** A tuple type: `(1, "uno") : (Int, String)` */
case class TypeTuple(span: SourceSpan, elements: List[TypeSpec]) extends TypeSpec

/** Structural type `{ name: String, age: Int }` */
case class TypeStruct(span: SourceSpan, fields: List[(String, TypeSpec)]) extends TypeSpec

/** Refine types with a predicate `Int {i => i < 100 && i > 0 }` */
case class TypeRefinement(span: SourceSpan, id: Option[String], expr: Expr) extends TypeSpec

/** Union types  `Int | None` */
case class Union(span: SourceSpan, types: List[TypeSpec]) extends TypeSpec

/** Intersection Types `Readable & Writable` */
case class Intersection(span: SourceSpan, types: List[TypeSpec]) extends TypeSpec

/** The unit type `()` */
case class TypeUnit(span: SourceSpan) extends TypeSpec

/** Type Sequences `[Int], [String], [T]` */
case class TypeSeq(span: SourceSpan, inner: TypeSpec) extends TypeSpec

/** A grouping of types, mostly for disambiguation: `Map String (List Int)` */
case class TypeGroup(span: SourceSpan, types: List[TypeSpec]) extends TypeSpec

/** Helpers to represent known types */
sealed trait LiteralType extends TypeSpec {
  def typeName: String
}
case class LiteralIntType(span: SourceSpan) extends LiteralType {
  final val typeName = "Int"
}
case class LiteralStringType(span: SourceSpan) extends LiteralType {
  final val typeName = "String"
}
case class LiteralBoolType(span: SourceSpan) extends LiteralType {
  final val typeName = "Bool"
}

case class LiteralUnitType(span: SourceSpan) extends LiteralType {
  final val typeName = "Unit"
}

case class LiteralFloatType(span: SourceSpan) extends LiteralType {
  final val typeName = "Float"
}
