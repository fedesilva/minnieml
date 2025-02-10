package mml.mmlclib.ast

/* Represents a point in the source code, with a line and column number.
 */
final case class SourcePoint(
  line: Int,
  col:  Int
)

/* Represents a span of source code, with a start and end point.
 */
final case class SourceSpan(
  start: SourcePoint,
  end:   SourcePoint
)

/** Represents a node in the abstract syntax tree (AST).
  */
sealed trait AstNode

/** Represents a node that can be typed.
  */
sealed trait Typeable extends AstNode {
  def typeSpec: Option[TypeSpec]
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

case class MemberError(
  span:       SourceSpan,
  message:    String,
  failedCode: Option[String]
) extends Member

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
  docComment: Option[DocComment] = None
) extends AstNode,
      FromSource

case class FnDef(
  span:       SourceSpan,
  name:       String,
  params:     List[FnParam],
  body:       Expr,
  typeSpec:   Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends Decl,
      FromSource

case class Bnd(
  span:       SourceSpan,
  name:       String,
  value:      Expr,
  typeSpec:   Option[TypeSpec]   = None,
  docComment: Option[DocComment] = None
) extends Decl,
      FromSource

sealed trait Term extends AstNode, Typeable, FromSource

case class Expr(
  span:     SourceSpan,
  terms:    List[Term],
  typeSpec: Option[TypeSpec] = None
) extends Term

case class Cond(
  span:     SourceSpan,
  cond:     Expr,
  ifTrue:   Expr,
  ifFalse:  Expr,
  typeSpec: Option[TypeSpec] = None
) extends Term

case class GroupTerm(
  span:  SourceSpan,
  inner: Expr
) extends Term,
      FromSource:
  def typeSpec: Option[TypeSpec] = inner.typeSpec

/** Points to something declared elsewhere */
case class Ref(
  span:     SourceSpan,
  name:     String,
  typeSpec: Option[TypeSpec]
) extends Term,
      FromSource

case class MehRef(span: SourceSpan, typeSpec: Option[TypeSpec]) extends Term, FromSource

case class Hole(span: SourceSpan) extends Term, FromSource:
  final val typeSpec: Option[TypeSpec] = None

// **Literals**

sealed trait LiteralValue extends Term, FromSource

case class LiteralInt(
  span:  SourceSpan,
  value: Int
) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralIntType(span))
}
case class LiteralString(span: SourceSpan, value: String) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralStringType(span))
}
case class LiteralBool(span: SourceSpan, value: Boolean) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralBoolType(span))
}

case class LiteralUnit(span: SourceSpan) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralUnitType(span))
}

case class LiteralFloat(span: SourceSpan, value: Float) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralFloatType(span))
}

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
