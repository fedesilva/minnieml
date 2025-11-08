package mml.mmlclib.ast

/* Represents a point in the source code, with a line, column number and the char index.
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

trait AstNode derives CanEqual

trait Typeable extends AstNode {

  /** This is the computed type */
  def typeSpec: Option[TypeSpec]

  /** This is the type the user declares. */
  def typeAsc: Option[TypeSpec]
}

trait FromSource extends AstNode {
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

/** Represents a top level member of a module. */
trait Member extends AstNode

trait Resolvable extends AstNode:
  def name: String

case class DocComment(
  span: SrcSpan,
  text: String
) extends AstNode,
      FromSource

trait Decl extends Member, Typeable, Resolvable:
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

enum Associativity derives CanEqual:
  case Left
  case Right

/** Marker trait for nodes that represent invalid/error constructs. These nodes allow the compiler
  * to continue processing even when errors are encountered, enabling better LSP support and partial
  * compilation.
  */
trait InvalidNode extends AstNode

trait Error extends AstNode, InvalidNode:
  def span:       SrcSpan
  def message:    String
  def failedCode: Option[String]
