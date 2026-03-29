package mml.mmlclib.ast

import cats.data.NonEmptyList

enum MemEffect derives CanEqual:
  case Alloc // returns newly allocated memory, caller owns
  case Static // returns pointer to static/existing memory

sealed trait Term extends AstNode, Typeable, FromSource:
  def withTypeAsc(t: Type): Term = this match
    case x: Expr => x.copy(typeAsc = Some(t))
    case x: Cond => x.copy(typeAsc = Some(t))
    case x: App => x.copy(typeAsc = Some(t))
    case x: Lambda => x.copy(typeAsc = Some(t))
    case x: TermGroup => x.copy(typeAsc = Some(t))
    case x: Tuple => x.copy(typeAsc = Some(t))
    case x: Ref => x.copy(typeAsc = Some(t))
    case x: Placeholder => x.copy(typeAsc = Some(t))
    case x: Hole => x.copy(typeAsc = Some(t))
    case x: LiteralInt => x.copy(typeAsc = Some(t))
    case x: LiteralString => x.copy(typeAsc = Some(t))
    case x: LiteralBool => x.copy(typeAsc = Some(t))
    case x: LiteralUnit => x.copy(typeAsc = Some(t))
    case x: LiteralFloat => x.copy(typeAsc = Some(t))
    case x: NativeImpl => x.copy(typeAsc = Some(t))
    case x: InvalidExpression => x.copy(typeAsc = Some(t))
    case _: TermError => this
    case _: DataConstructor => this
    case _: DataDestructor => this

case class TermError(
  source:     SourceOrigin,
  message:    String,
  failedCode: Option[String]
) extends Term,
      Error:
  final val typeSpec: Option[Type] = None
  final val typeAsc:  Option[Type] = None

object TermError:
  def apply(span: SrcSpan, message: String, failedCode: Option[String]): TermError =
    new TermError(SourceOrigin.Loc(span), message, failedCode)

case class Expr(
  source:   SourceOrigin,
  terms:    List[Term],
  typeAsc:  Option[Type] = None,
  typeSpec: Option[Type] = None
) extends Term

case class Cond(
  source:   SourceOrigin,
  cond:     Expr,
  ifTrue:   Expr,
  ifFalse:  Expr,
  typeSpec: Option[Type] = None,
  typeAsc:  Option[Type] = None
) extends Term

case class App(
  source:   SourceOrigin,
  fn:       Ref | App | Lambda,
  arg:      Expr,
  typeAsc:  Option[Type] = None,
  typeSpec: Option[Type] = None
) extends Term

object App:
  def apply(
    span:     SrcSpan,
    fn:       Ref | App | Lambda,
    arg:      Expr,
    typeAsc:  Option[Type],
    typeSpec: Option[Type]
  ): App =
    new App(SourceOrigin.Loc(span), fn, arg, typeAsc, typeSpec)

  def apply(
    span: SrcSpan,
    fn:   Ref | App | Lambda,
    arg:  Expr
  ): App =
    new App(SourceOrigin.Loc(span), fn, arg, None, None)

enum Capture:
  case CapturedRef(override val ref: Ref)
  case CapturedLiteral(override val ref: Ref, cloneFnId: String)

  def ref: Ref

case class LambdaMeta(
  isTailRecursive: Boolean        = false,
  envStructName:   Option[String] = None
)

case class Lambda(
  source:   SourceOrigin,
  params:   List[FnParam],
  body:     Expr,
  captures: List[Capture],
  typeSpec: Option[Type]       = None,
  typeAsc:  Option[Type]       = None,
  meta:     Option[LambdaMeta] = None
) extends Term

object Lambda:
  def apply(
    span:     SrcSpan,
    params:   List[FnParam],
    body:     Expr,
    captures: List[Capture],
    typeSpec: Option[Type],
    typeAsc:  Option[Type],
    meta:     Option[LambdaMeta]
  ): Lambda =
    new Lambda(SourceOrigin.Loc(span), params, body, captures, typeSpec, typeAsc, meta)

  def apply(
    span:     SrcSpan,
    params:   List[FnParam],
    body:     Expr,
    captures: List[Capture],
    typeSpec: Option[Type],
    typeAsc:  Option[Type]
  ): Lambda =
    new Lambda(SourceOrigin.Loc(span), params, body, captures, typeSpec, typeAsc, None)

  def apply(
    span:     SrcSpan,
    params:   List[FnParam],
    body:     Expr,
    captures: List[Capture]
  ): Lambda =
    new Lambda(SourceOrigin.Loc(span), params, body, captures, None, None, None)

case class TermGroup(
  source:  SourceOrigin,
  inner:   Expr,
  typeAsc: Option[Type] = None
) extends Term:
  def typeSpec: Option[Type] = inner.typeSpec

case class Tuple(
  source:   SourceOrigin,
  elements: NonEmptyList[Expr],
  typeAsc:  Option[Type] = None,
  typeSpec: Option[Type] = None
) extends Term

/** Points to something declared elsewhere */
case class Ref(
  source:       SourceOrigin,
  name:         String,
  typeAsc:      Option[Type]   = None,
  typeSpec:     Option[Type]   = None,
  resolvedId:   Option[String] = None,
  candidateIds: List[String]   = Nil,
  qualifier:    Option[Term]   = None
) extends Term,
      FromSource

/** The `_` symbol */
case class Placeholder(
  source:   SourceOrigin,
  typeSpec: Option[Type],
  typeAsc:  Option[Type] = None
) extends Term,
      FromSource

case class Hole(
  source:   SourceOrigin,
  typeAsc:  Option[Type] = None,
  typeSpec: Option[Type] = None
) extends Term,
      FromSource

// **Literals**

sealed trait LiteralValue extends Term, FromSource

case class LiteralInt(
  source:   SourceOrigin,
  value:    Int,
  typeSpec: Option[Type],
  typeAsc:  Option[Type] = None
) extends LiteralValue

object LiteralInt:
  def apply(span: SrcSpan, value: Int): LiteralInt =
    new LiteralInt(
      SourceOrigin.Loc(span),
      value,
      Some(TypeRef(SourceOrigin.Loc(span), "Int")),
      None
    )

  def unapply(lit: LiteralInt): Option[(SourceOrigin, Int)] =
    Some((lit.source, lit.value))

case class LiteralString(
  source:   SourceOrigin,
  value:    String,
  typeSpec: Option[Type],
  typeAsc:  Option[Type] = None
) extends LiteralValue

object LiteralString:
  def apply(span: SrcSpan, value: String): LiteralString =
    new LiteralString(
      SourceOrigin.Loc(span),
      value,
      Some(TypeRef(SourceOrigin.Loc(span), "String")),
      None
    )

  def unapply(lit: LiteralString): Option[(SourceOrigin, String)] =
    Some((lit.source, lit.value))

case class LiteralBool(
  source:   SourceOrigin,
  value:    Boolean,
  typeSpec: Option[Type],
  typeAsc:  Option[Type] = None
) extends LiteralValue

object LiteralBool:
  def apply(span: SrcSpan, value: Boolean): LiteralBool =
    new LiteralBool(
      SourceOrigin.Loc(span),
      value,
      Some(TypeRef(SourceOrigin.Loc(span), "Bool")),
      None
    )

  def unapply(lit: LiteralBool): Option[(SourceOrigin, Boolean)] =
    Some((lit.source, lit.value))

case class LiteralUnit(
  source:   SourceOrigin,
  typeSpec: Option[Type],
  typeAsc:  Option[Type] = None
) extends LiteralValue

object LiteralUnit:
  def apply(span: SrcSpan): LiteralUnit =
    new LiteralUnit(SourceOrigin.Loc(span), Some(TypeRef(SourceOrigin.Loc(span), "Unit")), None)

  def unapply(lit: LiteralUnit): Option[SourceOrigin] =
    Some(lit.source)

case class LiteralFloat(
  source:   SourceOrigin,
  value:    Float,
  typeSpec: Option[Type],
  typeAsc:  Option[Type] = None
) extends LiteralValue

object LiteralFloat:
  def apply(span: SrcSpan, value: Float): LiteralFloat =
    new LiteralFloat(
      SourceOrigin.Loc(span),
      value,
      Some(TypeRef(SourceOrigin.Loc(span), "Float")),
      None
    )

  def unapply(lit: LiteralFloat): Option[(SourceOrigin, Float)] =
    Some((lit.source, lit.value))

/** Marks the body of a function as a data type constructor The codegen will use the return type to
  * find the datatype fields. The typechecker will have to match the arguments types with the fields
  * of the struct. The parser creates both the data type and the constructor.
  */
case class DataConstructor(
  source:   SourceOrigin,
  typeSpec: Option[Type] = None
) extends Term:
  val typeAsc: Option[Type] = None

/** Marks the body of a destructor function for a data type. The codegen will use the type to
  * determine how to free the memory. Generated alongside DataConstructor for structs.
  */
case class DataDestructor(
  source:   SourceOrigin,
  typeSpec: Option[Type] = None
) extends Term:
  val typeAsc: Option[Type] = None

case class NativeImpl(
  source:       SourceOrigin,
  typeSpec:     Option[Type]      = None,
  typeAsc:      Option[Type]      = None,
  nativeTpl:    Option[String]    = None,
  memEffect:    Option[MemEffect] = None,
  nativeSymbol: Option[String]    = None
) extends Native,
      Term

/** Represents an expression that could not be resolved or is otherwise invalid. Preserves the
  * original expression for debugging and error reporting.
  */
case class InvalidExpression(
  source:       SourceOrigin,
  originalExpr: Expr,
  typeSpec:     Option[Type] = None,
  typeAsc:      Option[Type] = None
) extends Term,
      InvalidNode,
      FromSource
