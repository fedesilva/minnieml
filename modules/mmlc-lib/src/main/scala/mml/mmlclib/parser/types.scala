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
      ~ visibilityP.? ~ typeKw ~ typeIdP ~ defAsKw ~ nativeTypeP(info) ~ semiKw
      ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, vis, id, typeSpec, end, _) =>
    TypeDef(
      visibility = vis.getOrElse(Visibility.Protected),
      span(start, end),
      id,
      typeSpec.some
    )
  }

private[parser] def structDefP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    spP(info)
      ~ docCommentP(info)
      ~ visibilityP.? ~ structKw ~ typeIdP ~ "{" ~ recordFieldsP(info) ~ "}" ~ semiKw
      ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, doc, viz, id, fields, end, _) =>
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
        id,
        fields
      )
  }

private[parser] def recordFieldsP(info: SourceInfo)(using P[Any]): P[Vector[Field]] =
  P(fieldP(info).rep(sep = ","))
    .map(_.toVector)

private[parser] def fieldP(info: SourceInfo)(using P[Any]): P[Field] =
  P(
    spP(info) ~
      bindingIdP ~ ":" ~ typeSpecP(info) ~
      spNoWsP(info) ~ spP(info)
  ).map { case (start, id, tpe, end, _) =>
    Field(span(start, end), id, tpe)
  }

private[parser] def typeAliasP(info: SourceInfo)(using P[Any]): P[TypeAlias] =
  P(
    spP(info)
      ~ visibilityP.? ~ typeKw ~ typeIdP ~ defAsKw ~ typeSpecP(info) ~ semiKw
      ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, vis, id, typeSpec, end, _) =>
    TypeAlias(
      visibility = vis.getOrElse(Visibility.Protected),
      span(start, end),
      id,
      typeSpec
    )
  }

private[parser] def nativeTypeP(info: SourceInfo)(using P[Any]): P[NativeType] =
  P(
    spP(info) ~ nativeKw ~ (nativeBracketTypeP(info) | nativeStructP(info)) ~ spNoWsP(info) ~
      spP(info)
  ).map { case (start, body, end, _) =>
    body match
      case p: NativePrimitive => p.copy(span = span(start, end))
      case p: NativePointer => p.copy(span = span(start, end))
      case s: NativeStruct => s.copy(span = span(start, end))
  }

private[parser] def nativeBracketTypeP(info: SourceInfo)(using P[Any]): P[NativeType] =
  def memEffectP: P[MemEffect]  = P("heap").map(_ => MemEffect.Alloc)
  def tAttrP:     P[NativeType] = P("t=" ~ (nativePointerTypeP(info) | nativePrimitiveTypeP(info)))
  def memAttrP:   P[MemEffect]  = P("mem=" ~ memEffectP)

  P(
    "[" ~ (
      (tAttrP ~ ("," ~ memAttrP).?).map { case (t, m) =>
        m.fold(t) { case eff =>
          t match
            case p: NativePrimitive => p.copy(memEffect = Some(eff))
            case p: NativePointer => p.copy(memEffect = Some(eff))
            case s: NativeStruct => s.copy(memEffect = Some(eff))
        }
      } |
        (memAttrP ~ "," ~ tAttrP).map { case (m, t) =>
          t match
            case p: NativePrimitive => p.copy(memEffect = Some(m))
            case p: NativePointer => p.copy(memEffect = Some(m))
            case s: NativeStruct => s.copy(memEffect = Some(m))
        }
    ) ~ "]"
  )

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
  def memAttrP: P[MemEffect] = P("[" ~ "mem=" ~ "heap".! ~ "]").map(_ => MemEffect.Alloc)

  P(spP(info) ~ memAttrP.? ~ "{" ~ nativeFieldListP(info) ~ "}" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, memOpt, fields, end, _) =>
      NativeStruct(span(start, end), fields, memOpt)
    }

private[parser] def nativeFieldListP(info: SourceInfo)(using P[Any]): P[List[(String, Type)]] =
  P(nativeFieldP(info).rep(sep = ","))
    .map(_.toList)

private[parser] def nativeFieldP(info: SourceInfo)(using P[Any]): P[(String, Type)] =
  P(bindingIdP ~ ":" ~ typeSpecP(info))

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
