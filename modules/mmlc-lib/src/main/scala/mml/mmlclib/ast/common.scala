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

// --- Binding Metadata for unified callable representation ---

enum CallableArity derives CanEqual:
  case Nullary
  case Unary
  case Binary
  case Nary(paramCount: Int)

enum BindingOrigin derives CanEqual:
  case Function
  case Operator

object Precedence:
  val MaxUser:  Int = 100
  val Function: Int = 101

object OpMangling:
  private val opCharNames: Map[Char, String] = Map(
    '=' -> "eq",
    '!' -> "bang",
    '#' -> "hash",
    '$' -> "dollar",
    '%' -> "percent",
    '^' -> "caret",
    '&' -> "amp",
    '*' -> "star",
    '+' -> "plus",
    '<' -> "lt",
    '>' -> "gt",
    '?' -> "quest",
    '/' -> "slash",
    '\\' -> "bslash",
    '|' -> "pipe",
    '~' -> "tilde",
    '-' -> "minus"
  )

  def mangleOp(op: String, arity: Int): String =
    val translated =
      if op.forall(_.isLetterOrDigit) then op
      else op.map(c => opCharNames.getOrElse(c, c.toString)).mkString("_")
    s"op.$translated.$arity"

final case class BindingMeta(
  origin:        BindingOrigin,
  arity:         CallableArity,
  precedence:    Int,
  associativity: Option[Associativity],
  originalName:  String,
  mangledName:   String
)

/** Marker trait for nodes that represent invalid/error constructs. These nodes allow the compiler
  * to continue processing even when errors are encountered, enabling better LSP support and partial
  * compilation.
  */
trait InvalidNode extends AstNode

trait Error extends InvalidNode:
  def span:       SrcSpan
  def message:    String
  def failedCode: Option[String]
