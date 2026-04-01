package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** Parses an optional type ascription that follows `:`.
  *
  * Examples:
  * {{{
  * let id: Int = 1;
  * fn inc(x: Int): Int = x + 1; ;
  * }}}
  */
private[parser] def typeAscP(info: SourceInfo)(using P[Any]): P[Option[Type]] =
  P(":" ~ typeSpecP(info)).?

/** Parses a full type expression.
  *
  * Function arrows are right-associated by construction, so:
  * {{{
  * Int -> String -> Boolean
  * }}}
  * reads as a callable taking `Int` and `String` and returning `Boolean`.
  */
private[parser] def typeSpecP(info: SourceInfo)(using P[Any]): P[Type] =
  P(spP(info) ~ typeAtomP(info) ~ (arrowKw ~ typeAtomP(info)).rep ~ spNoWsP(info) ~ spP(info))
    .map { case (start, head, tail, end, _) =>
      val types = head :: tail.toList
      if types.size == 1 then head
      else
        types.init match
          case paramHead :: paramTail =>
            TypeFn(span(start, end), NonEmptyList(paramHead, paramTail), types.last)
          case Nil =>
            head
    }

/** Parses one atomic type fragment: either a grouped type or a nominal type reference. */
private[parser] def typeAtomP(info: SourceInfo)(using P[Any]): P[Type] =
  P(typeGroupP(info) | typeRefP(info))

/** Parses a parenthesized type used for grouping inside larger type expressions.
  *
  * Example:
  * {{{
  * (Int -> String) -> Boolean
  * }}}
  */
private[parser] def typeGroupP(info: SourceInfo)(using P[Any]): P[Type] =
  P(spP(info) ~ "(" ~ typeSpecP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, innerType, end, _) =>
      TypeGroup(span(start, end), List(innerType))
    }

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
  P(spP(info) ~ typeIdP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, end, _) =>
      TypeRef(span(start, end), id)
    }

/** Parses a native type definition.
  *
  * Example:
  * {{{
  * type RawPtr = @native[t=ptr];
  * }}}
  */
private[parser] def nativeTypeDefP(info: SourceInfo)(using P[Any]): P[TypeDef] =
  P(
    spP(info)
      ~ visibilityP.? ~ typeKw ~ spP(info) ~ typeIdP ~ spNoWsP(info) ~ defAsKw ~
      nativeTypeP(info) ~ semiKw
      ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, vis, nameStart, id, nameEnd, typeSpec, end, _) =>
    TypeDef(
      visibility = vis.getOrElse(Visibility.Protected),
      span(start, end),
      Name(span(nameStart, nameEnd), id),
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
    spP(info)
      ~ docCommentP(info)
      ~ visibilityP.? ~ structKw ~ spP(info) ~ typeIdP ~ spNoWsP(info) ~
      "{" ~ recordFieldsP(info) ~ "}" ~ semiKw
      ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, doc, viz, nameStart, id, nameEnd, fields, end, _) =>
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
        Name(span(nameStart, nameEnd), id),
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
    spP(info) ~
      bindingIdP ~ spNoWsP(info) ~ ":" ~ typeSpecP(info) ~
      spNoWsP(info) ~ spP(info)
  ).map { case (start, id, nameEnd, tpe, end, _) =>
    Field(span(start, end), Name(span(start, nameEnd), id), tpe)
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
    spP(info)
      ~ visibilityP.? ~ typeKw ~ spP(info) ~ typeIdP ~ spNoWsP(info) ~ defAsKw ~ typeSpecP(
        info
      ) ~ semiKw
      ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, vis, nameStart, id, nameEnd, typeSpec, end, _) =>
    TypeAlias(
      visibility = vis.getOrElse(Visibility.Protected),
      span(start, end),
      Name(span(nameStart, nameEnd), id),
      typeSpec
    )
  }

/** Parses the body of a native type expression.
  *
  * Examples:
  * {{{
  * @native[t=i64]
  * @native[t=*i8,mem=heap]
  * @native[mem=heap] { ptr: RawPtr, len: Int }
  * }}}
  */
private[parser] def nativeTypeP(info: SourceInfo)(using P[Any]): P[NativeType] =
  P(
    spP(info) ~ nativeKw ~ (nativeBracketTypeP(info) | nativeStructP(info)) ~ spNoWsP(info) ~
      spP(info)
  ).map { case (start, body, end, _) =>
    val source = SourceOrigin.Loc(span(start, end))
    body match
      case p: NativePrimitive => p.copy(source = source)
      case p: NativePointer => p.copy(source = source)
      case s: NativeStruct => s.copy(source = source)
  }

/** Parses bracket-style native types such as `@native[t=i64]` or `@native[t=*i8]`. */
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

  P("[" ~ (tAttrP | memAttrP | freeAttrP).rep(sep = ",", min = 1) ~ "]")
    .flatMap { attrs =>
      attrs.collectFirst { case Attr.TAttr(t) => t } match
        case None => Fail
        case Some(base) =>
          val memOpt  = attrs.collectFirst { case Attr.MemAttr(eff) => eff }
          val freeOpt = attrs.collectFirst { case Attr.FreeAttr(n) => n }
          val result = base match
            case p: NativePrimitive => p.copy(memEffect = memOpt, freeFn = freeOpt)
            case p: NativePointer => p.copy(memEffect = memOpt, freeFn = freeOpt)
            case s: NativeStruct => s.copy(memEffect = memOpt, freeFn = freeOpt)
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
  P(spP(info) ~ llvmNativePrimitiveTypeP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, llvmType, end, _) =>
      NativePrimitive(span(start, end), llvmType)
    }

/** Parses a pointer-style native type.
  *
  * Example:
  * {{{
  * *i8
  * }}}
  */
private[parser] def nativePointerTypeP(info: SourceInfo)(using P[Any]): P[NativePointer] =
  P(spP(info) ~ "*" ~ llvmPointeeTypeP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, llvmType, end, _) =>
      NativePointer(span(start, end), llvmType)
    }

/** Parses a native struct literal.
  *
  * Example:
  * {{{
  * @native[mem=heap] { ptr: RawPtr, len: Int }
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

  P(spP(info) ~ attrsP.? ~ "{" ~ nativeFieldListP(info) ~ "}" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, attrsOpt, fields, end, _) =>
      val attrs   = attrsOpt.getOrElse(Seq.empty)
      val memOpt  = attrs.collectFirst { case StructAttr.MemAttr(eff) => eff }
      val freeOpt = attrs.collectFirst { case StructAttr.FreeAttr(n) => n }
      NativeStruct(span(start, end), fields, memOpt, freeOpt)
    }

/** Parses the comma-separated field list inside a native struct literal. */
private[parser] def nativeFieldListP(info: SourceInfo)(using P[Any]): P[List[(String, Type)]] =
  P(nativeFieldP(info).rep(sep = ","))
    .map(_.toList)

/** Parses one native struct field such as `len: Int`. */
private[parser] def nativeFieldP(info: SourceInfo)(using P[Any]): P[(String, Type)] =
  P(bindingIdP ~ ":" ~ typeSpecP(info))

/** Parses an identifier used only inside native attributes such as `free=release_buf`. */
private[parser] def nativeIdentP(using P[Any]): P[String] =
  P(CharsWhileIn("a-zA-Z_", 1) ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

/** Parses any supported LLVM primitive token accepted by `@native[t=...]`. */
private[parser] def llvmNativePrimitiveTypeP(using P[Any]): P[String] =
  P(llvmPointeeTypeP | "ptr".!)

/** Parses the pointee side of native primitive and pointer types.
  *
  * Examples:
  * {{{
  * i8
  * i64
  * float
  * }}}
  */
private[parser] def llvmPointeeTypeP(using P[Any]): P[String] =
  P(
    ("i" ~ CharIn("0-9").rep(1)).!.flatMap { t =>
      val bits = t.drop(1).toIntOption.getOrElse(0)
      if bits == 1 || bits == 8 || bits == 16 || bits == 32 || bits == 64 || bits == 128 then
        Pass(t)
      else Fail
    } |
      "half".! |
      "bfloat".! |
      "float".! |
      "double".! |
      "fp128".!
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
  P("pub").map(_ => Visibility.Public) |
    P("prot").map(_ => Visibility.Protected) |
    P("priv").map(_ => Visibility.Private)
