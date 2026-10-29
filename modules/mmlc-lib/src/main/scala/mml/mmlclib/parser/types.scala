package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.parser.Recovery.Context

import MmlWhitespace.*

/** Identifies syntax owned by the caller of a type expression. */
private[parser] enum TypeContext:
  case Expression, Declaration, BindingHeader, Parameter, LambdaParameter, Field, Alias, Operator
  case Group(outer: TypeContext)
  case Protocol(outer: TypeContext)

  def allowsTypeVariables: Boolean = this match
    case Protocol(_) => true
    case Group(outer) => outer.allowsTypeVariables
    case _ => false

  def recovery: Recovery.Context =
    this match
      case Group(outer) => outer.recovery
      case Protocol(outer) => outer.recovery
      case Expression => TypeContext.expressionRecovery
      case Declaration | BindingHeader | LambdaParameter | Alias => TypeContext.commonRecovery
      case Parameter => TypeContext.parameterRecovery
      case Field => TypeContext.fieldRecovery
      case Operator => TypeContext.operatorRecovery

  def balancedRecovery: Recovery.Context =
    val ordinaryStops = recovery.hardStops.filterNot(TypeContext.declarations.contains)
    val closers       = List(")", "]", "}").map(Recovery.Anchor.Token(_))
    Recovery.Context(recovery.anchors ++ ordinaryStops ++ closers, TypeContext.declarations)

private[parser] object TypeContext:

  import Recovery.Anchor.*

  private val declarations =
    List(
      "let",
      "fn",
      "op",
      "type",
      "struct",
      "protocol",
      "instance",
      "pub",
      "prot",
      "priv",
      "inline"
    ).map(Keyword(_))
  val expressionGroupRecovery: Context = Recovery.Context(Nil, declarations)
  private val hardStops      = List(Token("="), Token(","), Token(";")) ++ declarations
  private val anchors        = List(Token("->"), Token("/*"))
  private val commonRecovery = Recovery.Context(anchors, hardStops)
  private val expressionRecovery =
    Recovery.Context(anchors ++ List("then", "elif", "else").map(Keyword(_)), hardStops)
  private val parameterRecovery = Recovery.Context(Token(")") :: anchors, hardStops)
  private val fieldRecovery     = Recovery.Context(Token("}") :: anchors, hardStops)
  private val operatorRecovery =
    Recovery.Context(
      anchors,
      hardStops ++ List(NumberStart, Keyword("left"), Keyword("right"), Keyword("where"))
    )

/** Parses an optional type ascription that follows `:`.
  *
  * Examples:
  * {{{
  * let id: Int = 1;
  * fn inc(x: Int): Int = x + 1; ;
  * }}}
  */
private[parser] def typeAscP(info: SourceInfo, context: TypeContext = TypeContext.Expression)(using
  P[Any]
): P[Option[Type]] =
  P(NoCut(":" ~/ typeSpecP(info, context))).?

/** Parses a full type expression.
  *
  * Function arrows are right-associated by construction, so:
  * {{{
  * Int -> String -> Boolean
  * }}}
  * reads as a callable taking `Int` and `String` and returning `Boolean`.
  */
private[parser] def typeSpecP(info: SourceInfo, context: TypeContext)(using P[Any]): P[Type] =

  // Protocol headers retain applications so unsupported parameterization has a focused diagnostic.
  def appliedTypeP: P[Type] =
    if !context.allowsTypeVariables then typeAtomP(info, context)
    else
      P(
        pointP(info) ~ typeAtomP(info, context) ~
          (&(protocolTypeStartP) ~ typeAtomP(info, context)).rep
      ).map { case (start, base, args) =>
        if args.isEmpty then base
        else TypeApplication(span(start, sourceEnd(args.last, start)), base, args.toList)
      }

  def nextTypeP: P[Type] =
    context match
      case TypeContext.LambdaParameter =>
        P(arrowKw ~ &(validTypeAtomP) ~ typeAtomP(info, context))
      case _ => P(arrowKw ~ appliedTypeP)

  P(pointP(info) ~ appliedTypeP ~ nextTypeP.rep ~ pointP(info))
    .map { case (start, head, tail, _) =>
      val types = head :: tail.toList
      if types.size == 1 then head
      else
        types.init match
          case paramHead :: paramTail =>
            val result = types.last
            val end    = sourceEnd(result, start)
            TypeFn(span(start, end), NonEmptyList(paramHead, paramTail), result)
          case Nil =>
            head
    }

/** Recognizes a complete type atom when distinguishing a lambda separator from a type arrow. */
private def validTypeAtomP(using P[Any]): P[Unit] =
  P(("(" ~ validTypeAtomP ~ (arrowKw ~ validTypeAtomP).rep ~ ")") | typeIdP.map(_ => ()))

private def protocolTypeStartP(using P[Any]): P[Unit] = P("(" | "'" | typeIdP.map(_ => ()))

/** Parses a type atom, preserving a failed fragment up to the next type or caller boundary. */
private[parser] def typeAtomP(info: SourceInfo, context: TypeContext)(using P[Any]): P[Type] =

  def boundaryP: P[Unit] =
    context match
      case TypeContext.Expression | TypeContext.BindingHeader |
          TypeContext.Protocol(TypeContext.BindingHeader) =>
        Pass
      case _ =>
        P(Index)
          .filter(index => Recovery.scan(info.text, index, context.recovery).end == index)
          .map(_ => ())

  def variableP: P[Type] =
    if context.allowsTypeVariables then typeVariableP(info) else Fail

  def applicationBoundaryP: P[Unit] =
    if context.allowsTypeVariables then P(&(protocolTypeStartP) | boundaryP) else boundaryP

  P(
    NoCut(
      (typeGroupP(info, context) | variableP | typeRefP(info)) ~ applicationBoundaryP
    ) | failedTypeP(
      info,
      context
    )
  )

/** Prefers a balanced failed fragment ending at its caller, then recovers past unmatched openers.
  */
private def failedTypeP(info: SourceInfo, context: TypeContext)(using P[Any]): P[Type] =

  def balancedP: P[Recovery.Result] =
    P(
      recoveryP(info, context.balancedRecovery).filter { result =>
        result.boundary match
          case Recovery.Boundary.AtAnchor(_) => true
          case _ => false
      } ~ &(typeRecoveryEndP(context))
    )

  P(pointP(info) ~~ (balancedP | recoveryP(info, context.recovery))).map {
    case (start, recovered) =>
      typeError(info, start, info.pointAt(recovered.end))
  }

/** Retains exact failed source text; a missing type has a zero-width span and no failed text. */
private def typeError(info: SourceInfo, start: SrcPoint, end: SrcPoint): ParseTypeError =

  val failedCode = info.slice(start.index, end.index)
  ParseTypeError(
    span(start, end),
    if failedCode.isEmpty then "Expected type" else "Invalid type syntax",
    Option.when(failedCode.nonEmpty)(failedCode)
  )

/** Recognizes only boundaries that can terminate a balanced malformed type in this context. */
private def typeRecoveryEndP(context: TypeContext)(using P[Any]): P[Unit] =

  def callerEndP: P[Unit] =
    context match
      case TypeContext.Expression => P("=" | "," | ";" | ")" | "]" | "}" | thenKw | elifKw | elseKw)
      case TypeContext.Declaration | TypeContext.BindingHeader => P("=")
      case TypeContext.Parameter => P("," | ")")
      case TypeContext.LambdaParameter => P(",")
      case TypeContext.Field => P("," | "}")
      case TypeContext.Alias => P(";")
      case TypeContext.Operator => P("=" | CharIn("0-9") | "left" | "right" | whereKw)
      case TypeContext.Group(_) => P(")")
      case TypeContext.Protocol(outer) => typeRecoveryEndP(outer)

  P(arrowKw | callerEndP)

/** A closed type group in an expression leaves subsequent application terms outside its error. */
private def failedExpressionTypeGroupP(info: SourceInfo)(using P[Any]): P[Type] =
  P(
    pointP(info) ~ "(" ~ recoveryP(info, TypeContext.expressionGroupRecovery) ~
      tokenP(info)(P(")")) ~ pointP(info)
  ).map { case (start, _, close, _) => typeError(info, start, close.location.end) }

/** Parses a parenthesized type used for grouping inside larger type expressions.
  *
  * Example:
  * {{{
  * (Int -> String) -> Boolean
  * }}}
  */
private[parser] def typeGroupP(info: SourceInfo, context: TypeContext)(using P[Any]): P[Type] =

  def fallbackP: P[Type] =
    context match
      case TypeContext.Expression => failedExpressionTypeGroupP(info)
      case _ => Fail

  P(
    pointP(info) ~ "(" ~ typeSpecP(info, TypeContext.Group(context)) ~
      tokenP(info)(P(")")) ~ pointP(info)
  )
    .map { case (start, innerType, close, _) =>
      TypeGroup(span(start, close.location.end), List(innerType))
    } | fallbackP

/** Parses a nominal type reference.
  *
  * Examples:
  * {{{
  * Int
  * Person
  * RawPtr
  * }}}
  */
private[parser] def typeRefP(info: SourceInfo)(using P[Any]): P[Type] =
  P(tokenP(info)(typeIdP) ~ pointP(info))
    .map { case (id, _) =>
      TypeRef(id.location, id.value)
    }

/** Parses a native type definition.
  *
  * Example:
  * {{{
  * type RawPtr = @metal:repr[t=ptr];
  * }}}
  */
private[parser] def nativeTypeDefP(info: SourceInfo)(using P[Any]): P[TypeDef] =
  P(
    pointP(info)
      ~ visibilityP.? ~ typeKw ~ tokenP(info)(typeIdP) ~ defAsKw ~
      nativeTypeP(info) ~ tokenP(info)(semiKw) ~ pointP(info)
  ).map { case (start, vis, id, typeSpec, terminator, _) =>
    TypeDef(
      visibility = vis.getOrElse(Visibility.Protected),
      span(start, terminator.location.end),
      Name(id.location, id.value),
      typeSpec.some
    )
  }

/** Parses a struct declaration.
  *
  * Example:
  * {{{
  * struct Person {
  *   name: String,
  *   age: Int
  * };
  * }}}
  */
private[parser] def structDefP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    pointP(info)
      ~ docCommentP(info)
      ~ visibilityP.? ~ structKw ~ tokenP(info)(typeIdP) ~
      "{" ~ recordFieldsP(info) ~ "}" ~ tokenP(info)(semiKw) ~ pointP(info)
  ).map { case (start, doc, viz, id, fields, terminator, _) =>
    val end        = terminator.location.end
    val structSpan = span(start, end)
    if fields.isEmpty then
      val snippet = info.slice(start.index, end.index).trim
      ParsingMemberError(
        span       = structSpan,
        message    = "Struct declarations must define at least one field",
        failedCode = if snippet.isEmpty then None else Some(snippet)
      )
    else
      TypeStruct(
        structSpan,
        doc,
        viz.getOrElse(Visibility.Protected),
        Name(id.location, id.value),
        fields
      )
  }

/** Parses the comma-separated field list inside a `struct { ... }` body. */
private[parser] def recordFieldsP(info: SourceInfo)(using P[Any]): P[Vector[Field]] =
  P(fieldP(info).rep(sep = ","))
    .map(_.toVector)

/** Parses one named struct field.
  *
  * Example:
  * {{{
  * name: String
  * }}}
  */
private[parser] def fieldP(info: SourceInfo)(using P[Any]): P[Field] =
  P(
    tokenP(info)(bindingIdP) ~ ":" ~ typeSpecP(info, TypeContext.Field) ~ pointP(info)
  ).map { case (id, tpe, _) =>
    val end = sourceEnd(tpe, id.location.end)
    Field(span(id.location.start, end), Name(id.location, id.value), tpe)
  }

/** Parses a nominal type alias.
  *
  * Example:
  * {{{
  * type UserId = Int;
  * }}}
  */
private[parser] def typeAliasP(info: SourceInfo)(using P[Any]): P[TypeAlias] =
  P(
    pointP(info)
      ~ visibilityP.? ~ typeKw ~ tokenP(info)(typeIdP) ~ defAsKw ~ !reprKw ~
      typeSpecP(info, TypeContext.Alias) ~ tokenP(info)(semiKw) ~ pointP(info)
  ).map { case (start, vis, id, typeSpec, terminator, _) =>
    TypeAlias(
      visibility = vis.getOrElse(Visibility.Protected),
      span(start, terminator.location.end),
      Name(id.location, id.value),
      typeSpec
    )
  }

/** Parses the body of a native type expression.
  *
  * Examples:
  * {{{
  * @metal:repr[t=i64]
  * @metal:repr[t=*i8,mem=heap]
  * @metal:repr[mem=heap] { ptr: RawPtr, len: Int }
  * }}}
  */
private[parser] def nativeTypeP(info: SourceInfo)(using P[Any]): P[NativeType] =
  P(
    pointP(info) ~ reprKw ~ (nativeBracketTypeP(info) | nativeStructP(info)) ~ pointP(info)
  ).map { case (start, body, _) =>
    val end    = sourceEnd(body, start)
    val source = SourceOrigin.Loc(span(start, end))
    body match
      case p: NativePrimitive => p.copy(source = source)
      case p: NativePointer => p.copy(source = source)
      case s: NativeStruct => s.copy(source = source)
  }

/** Parses bracket-style native types such as `@metal:repr[t=i64]` or `@metal:repr[t=*i8]`. */
private[parser] def nativeBracketTypeP(info: SourceInfo)(using P[Any]): P[NativeType] =
  enum Attr:
    case TAttr(t: NativeType)
    case MemAttr(eff: MemEffect)
    case FreeAttr(name: String)

  def tAttrP: P[Attr] =
    P("t=" ~ (nativePointerTypeP(info) | nativePrimitiveTypeP(info))).map(Attr.TAttr(_))
  def memAttrP: P[Attr] =
    P("mem=" ~ "heap").map(_ => Attr.MemAttr(MemEffect.Alloc))
  def freeAttrP: P[Attr] =
    P("free=" ~ nativeIdentP).map(Attr.FreeAttr(_))

  P(
    pointP(info) ~ "[" ~ (tAttrP | memAttrP | freeAttrP).rep(sep = ",", min = 1) ~
      tokenP(info)(P("]"))
  )
    .flatMap { case (start, attrs, close) =>
      attrs.collectFirst { case Attr.TAttr(t) => t } match
        case None => Fail
        case Some(base) =>
          val memOpt  = attrs.collectFirst { case Attr.MemAttr(eff) => eff }
          val freeOpt = attrs.collectFirst { case Attr.FreeAttr(n) => n }
          val source  = SourceOrigin.Loc(span(start, close.location.end))
          val result = base match
            case p: NativePrimitive => p.copy(source = source, memEffect = memOpt, freeFn = freeOpt)
            case p: NativePointer => p.copy(source = source, memEffect = memOpt, freeFn = freeOpt)
            case s: NativeStruct => s.copy(source = source, memEffect = memOpt, freeFn = freeOpt)
          Pass(result)
    }

/** Parses a native primitive LLVM type.
  *
  * Examples:
  * {{{
  * i64
  * ptr
  * double
  * }}}
  */
private[parser] def nativePrimitiveTypeP(info: SourceInfo)(using P[Any]): P[NativePrimitive] =
  P(llvmNativePrimitiveTypeP(info) ~ pointP(info))
    .map { case (llvmType, _) =>
      NativePrimitive(llvmType.location, llvmType.value)
    }

/** Parses a pointer-style native type.
  *
  * Example:
  * {{{
  * *i8
  * }}}
  */
private[parser] def nativePointerTypeP(info: SourceInfo)(using P[Any]): P[NativePointer] =
  P(pointP(info) ~ "*" ~ llvmPointeeTypeP(info) ~ pointP(info))
    .map { case (start, llvmType, _) =>
      NativePointer(span(start, llvmType.location.end), llvmType.value)
    }

/** Parses a native struct literal.
  *
  * Example:
  * {{{
  * @metal:repr[mem=heap] { ptr: RawPtr, len: Int }
  * }}}
  */
private[parser] def nativeStructP(info: SourceInfo)(using P[Any]): P[NativeStruct] =
  enum StructAttr:
    case MemAttr(eff: MemEffect)
    case FreeAttr(name: String)

  def memAttrP: P[StructAttr] =
    P("mem=" ~ "heap").map(_ => StructAttr.MemAttr(MemEffect.Alloc))
  def freeAttrP: P[StructAttr] =
    P("free=" ~ nativeIdentP).map(StructAttr.FreeAttr(_))
  def attrsP: P[Seq[StructAttr]] =
    P("[" ~ (memAttrP | freeAttrP).rep(sep = ",", min = 1) ~ "]")

  P(
    pointP(info) ~ attrsP.? ~ "{" ~ nativeFieldListP(info) ~ tokenP(info)(P("}")) ~ pointP(info)
  )
    .map { case (start, attrsOpt, fields, close, _) =>
      val attrs   = attrsOpt.getOrElse(Seq.empty)
      val memOpt  = attrs.collectFirst { case StructAttr.MemAttr(eff) => eff }
      val freeOpt = attrs.collectFirst { case StructAttr.FreeAttr(n) => n }
      NativeStruct(span(start, close.location.end), fields, memOpt, freeOpt)
    }

/** Parses the comma-separated field list inside a native struct literal. */
private[parser] def nativeFieldListP(info: SourceInfo)(using P[Any]): P[List[(String, Type)]] =
  P(nativeFieldP(info).rep(sep = ","))
    .map(_.toList)

/** Parses one native struct field such as `len: Int`. */
private[parser] def nativeFieldP(info: SourceInfo)(using P[Any]): P[(String, Type)] =
  P(bindingIdP ~ ":" ~ typeSpecP(info, TypeContext.Field))

/** Parses an identifier used only inside native attributes such as `free=release_buf`. */
private[parser] def nativeIdentP(using P[Any]): P[String] =
  P(CharsWhileIn("a-zA-Z_", 1) ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

/** Parses any supported LLVM primitive token accepted by `@metal:repr[t=...]`. */
private[parser] def llvmNativePrimitiveTypeP(info: SourceInfo)(using
  P[Any]
): P[ParsedToken[String]] =
  P(llvmPointeeTypeP(info) | tokenP(info)(P("ptr".!)))

/** Parses the pointee side of native primitive and pointer types.
  *
  * Examples:
  * {{{
  * i8
  * i64
  * float
  * }}}
  */
private[parser] def llvmPointeeTypeP(info: SourceInfo)(using P[Any]): P[ParsedToken[String]] =
  P(
    tokenP(info)(P(("i" ~ CharIn("0-9").rep(1)).!)).flatMap { token =>
      val bits = token.value.drop(1).toIntOption.getOrElse(0)
      if bits == 1 || bits == 8 || bits == 16 || bits == 32 || bits == 64 || bits == 128 then
        Pass(token)
      else Fail
    } |
      tokenP(info)(P("half".! | "bfloat".! | "float".! | "double".! | "fp128".!))
  )

/** Parses the optional declaration visibility modifier.
  *
  * Examples:
  * {{{
  * pub
  * prot
  * priv
  * }}}
  */
private[parser] def visibilityP[$: P]: P[Visibility] =
  P(pubKw).map(_ => Visibility.Public) |
    P(protKw).map(_ => Visibility.Protected) |
    P(privKw).map(_ => Visibility.Private)

/** Parses a written protocol type parameter, retaining the apostrophe in its name. */
private[parser] def typeVariableP(info: SourceInfo)(using P[Any]): P[TypeVariable] =
  P(tokenP(info)(P("'" ~~ typeIdP).!)).map { token =>
    TypeVariable(token.location, token.value)
  }
