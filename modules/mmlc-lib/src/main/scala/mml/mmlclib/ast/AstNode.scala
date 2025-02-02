package mml.mmlclib.ast

final case class SourcePoint(
  line: Int,
  col:  Int
)

sealed trait AstNode

sealed trait Typeable extends AstNode {
  def typeSpec: Option[TypeSpec]
}

enum ModVisibility:
  case Public
  case Protected
  case Lexical

case class Module(
  name:       String,
  visibility: ModVisibility,
  members:    List[Member],
  isImplicit: Boolean = false
) extends AstNode

// **Members**
sealed trait Member extends AstNode

case class MemberError(
  start:      SourcePoint,
  end:        SourcePoint,
  message:    String,
  failedCode: Option[String]
) extends Member

// **Comments**
case class Comment(text: String) extends Member

sealed trait Decl extends Member, Typeable

case class FnDef(
  name:     String,
  params:   List[String],
  body:     Expr,
  typeSpec: Option[TypeSpec] = None
) extends Decl

case class Bnd(
  name:     String,
  value:    Expr,
  typeSpec: Option[TypeSpec] = None
) extends Decl

// **Expressions and Terms**

sealed trait Term extends AstNode, Typeable

case class Expr(terms: List[Term], typeSpec: Option[TypeSpec] = None) extends Term

// **Terms of an expression**

/** Points to a something declared elsewhere */
case class Ref(name: String, typeSpec: Option[TypeSpec]) extends Term

// **Literals**
sealed trait LiteralValue extends Term
case class LiteralInt(value: Int) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralIntType)
}
case class LiteralString(value: String) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralStringType)
}
case class LiteralBool(value: Boolean) extends LiteralValue {
  final val typeSpec: Option[TypeSpec] = Some(LiteralBoolType)
}

// **Type Specifications**
sealed trait TypeSpec extends AstNode

/** A type by name */
case class TypeName(name: String) extends TypeSpec

/** A type application, ie:  `List Int, Map String Int` */
case class TypeApplication(base: TypeSpec, args: List[TypeSpec]) extends TypeSpec

/** The type of a Fn `String => Int` */
case class TypeFn(paramTypes: List[TypeSpec], returnType: TypeSpec) extends TypeSpec

/** A tuple type: `(1, "uno") : (Int, String)` */
case class TypeTuple(elements: List[TypeSpec]) extends TypeSpec

/** Structural type `{ name: String, age: Int }` */
case class TypeStruct(fields: List[(String, TypeSpec)]) extends TypeSpec

/** Refine types with a predicate `Int {i => i < 100 && i > 0 }` */
case class TypeRefinement(id: Option[String], expr: Expr) extends TypeSpec

/** Union types  `Int | None` */
case class Union(types: List[TypeSpec]) extends TypeSpec

/** Intersection Types `Readable & Writable` */
case class Intersection(types: List[TypeSpec]) extends TypeSpec

/** The unit type `()` */
case object TypeUnit extends TypeSpec

/** Type Sequences `[Int], [String], [T]` */
case class TypeSeq(inner: TypeSpec) extends TypeSpec

/** A grouping of types, mostly for deambiguation: `Map String (List Int)` */
case class TypeGroup(types: List[TypeSpec]) extends TypeSpec

/** Helpers to represent known types */
sealed trait LiteralType extends TypeSpec {
  def typeName: String
}
case object LiteralIntType extends LiteralType {
  final val typeName = "Int"
}
case object LiteralStringType extends LiteralType {
  final val typeName = "String"
}
case object LiteralBoolType extends LiteralType {
  final val typeName = "Bool"
}
