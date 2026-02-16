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

/** A name with its source position. Used on named declarations so the LSP can get the precise
  * position of the identifier without guessing from keyword offsets.
  */
case class Name(span: SrcSpan, value: String) extends AstNode, FromSource

object Name:
  private val emptySpan:    SrcSpan = SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))
  def synth(value: String): Name    = Name(emptySpan, value)

trait Typeable extends AstNode {

  /** This is the computed type */
  def typeSpec: Option[Type]

  /** This is the type the user declares. */
  def typeAsc: Option[Type]
}

trait FromSource extends AstNode {
  def span:   SrcSpan
  def source: SourceOrigin = SourceOrigin.Loc(span)
}

/** Distinguishes AST nodes parsed from source vs synthesized by the compiler. */
enum SourceOrigin derives CanEqual:
  case Loc(span: SrcSpan)
  case Synth

  def spanOpt: Option[SrcSpan] = this match
    case Loc(s) => Some(s)
    case Synth => None

  def isFromSource: Boolean = this match
    case Loc(_) => true
    case Synth => false

/** Visibility control shared by modules and members.
  *
  *   - `pub`: Importable and referenceable from any module.
  *   - `lex`: Visible only within the defining module and its descendants; never exported.
  *   - `prot`: Visible to the current module, sibling modules, and nested modules; external access
  *     requires import or fully qualified names.
  *   - `priv`: Confined to the defining module (sibling modules can refer without import but it is
  *     not exportable).
  */
enum Visibility derives CanEqual:
  // Everyone
  case Public
  // Visible to the module, its siblings, and nested modules
  case Protected
  // Inside the module that contains the member
  case Private

/** Represents a top level member of a module. */
trait Member extends AstNode

trait Resolvable extends AstNode:
  def nameNode: Name
  def name:     String = nameNode.value
  def id:       Option[String]

case class DocComment(
  span: SrcSpan,
  text: String
) extends AstNode,
      FromSource

trait Decl extends Member, Typeable, Resolvable:
  def docComment: Option[DocComment]
  def visibility: Visibility

case class FnParam(
  override val source: SourceOrigin,
  nameNode:            Name,
  typeSpec:            Option[Type]       = None,
  typeAsc:             Option[Type]       = None,
  docComment:          Option[DocComment] = None,
  id:                  Option[String]     = None,
  consuming:           Boolean            = false // for ~param syntax: takes ownership
) extends AstNode,
      FromSource,
      Typeable,
      Resolvable:
  private val syntheticSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))
  def span: SrcSpan = source match
    case SourceOrigin.Loc(s) => s
    case SourceOrigin.Synth => syntheticSpan

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
  case Destructor
  case Constructor

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
    '-' -> "minus",
    '.' -> "dot",
    '~' -> "tilde"
  )

  def mangleOp(op: String, arity: Int): String =
    val translated =
      if op.forall(c => c.isLetterOrDigit || c == '_') then op
      else op.map(c => opCharNames.getOrElse(c, c.toString)).mkString("_")
    s"op.$translated.$arity"

final case class BindingMeta(
  origin:        BindingOrigin,
  arity:         CallableArity,
  precedence:    Int,
  associativity: Option[Associativity],
  originalName:  String,
  mangledName:   String,
  inlineHint:    Boolean = false
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
