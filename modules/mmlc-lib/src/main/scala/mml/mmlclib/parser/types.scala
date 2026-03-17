package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def typeAscP(info: SourceInfo)(using P[Any]): P[Option[Type]] =
  P(":" ~ typeSpecP(info)).?

private[parser] def typeSpecP(info: SourceInfo)(using P[Any]): P[Type] =
  P(spP(info) ~ typeAtomP(info) ~ ("->" ~ typeAtomP(info)).rep ~ spNoWsP(info) ~ spP(info))
    .map { case (start, head, tail, end, _) =>
      val types = head :: tail.toList
      if types.size == 1 then head
      else TypeFn(span(start, end), types.init, types.last)
    }

private[parser] def typeAtomP(info: SourceInfo)(using P[Any]): P[Type] =
  P(typeGroupP(info) | typeRefP(info))

private[parser] def typeGroupP(info: SourceInfo)(using P[Any]): P[Type] =
  P(spP(info) ~ "(" ~ typeSpecP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, innerType, end, _) =>
      TypeGroup(span(start, end), List(innerType))
    }

private[parser] def typeRefP(info: SourceInfo)(using P[Any]): P[Type] =
  P(spP(info) ~ typeIdP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, end, _) =>
      TypeRef(span(start, end), id)
    }

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

private[parser] def recordFieldsP(info: SourceInfo)(using P[Any]): P[Vector[Field]] =
  P(fieldP(info).rep(sep = ","))
    .map(_.toVector)

private[parser] def fieldP(info: SourceInfo)(using P[Any]): P[Field] =
  P(
    spP(info) ~
      bindingIdP ~ spNoWsP(info) ~ ":" ~ typeSpecP(info) ~
      spNoWsP(info) ~ spP(info)
  ).map { case (start, id, nameEnd, tpe, end, _) =>
    Field(span(start, end), Name(span(start, nameEnd), id), tpe)
  }

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

private[parser] def nativePrimitiveTypeP(info: SourceInfo)(using P[Any]): P[NativePrimitive] =
  P(spP(info) ~ llvmPrimitiveTypeP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, llvmType, end, _) =>
      NativePrimitive(span(start, end), llvmType)
    }

private[parser] def nativePointerTypeP(info: SourceInfo)(using P[Any]): P[NativePointer] =
  P(spP(info) ~ "*" ~ llvmPrimitiveTypeP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, llvmType, end, _) =>
      NativePointer(span(start, end), llvmType)
    }

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

private[parser] def nativeFieldListP(info: SourceInfo)(using P[Any]): P[List[(String, Type)]] =
  P(nativeFieldP(info).rep(sep = ","))
    .map(_.toList)

private[parser] def nativeFieldP(info: SourceInfo)(using P[Any]): P[(String, Type)] =
  P(bindingIdP ~ ":" ~ typeSpecP(info))

private[parser] def nativeIdentP(using P[Any]): P[String] =
  P(CharsWhileIn("a-zA-Z_", 1) ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

private[parser] def llvmPrimitiveTypeP(using P[Any]): P[String] =
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

private[parser] def visibilityP[$: P]: P[Visibility] =
  P("pub").map(_ => Visibility.Public) |
    P("prot").map(_ => Visibility.Protected) |
    P("priv").map(_ => Visibility.Private)
