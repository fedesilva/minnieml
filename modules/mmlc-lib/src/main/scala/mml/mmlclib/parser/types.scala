package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def typeAscP(source: String)(using P[Any]): P[Option[TypeSpec]] =
  P(":" ~ typeSpecP(source)).?

private[parser] def typeSpecP(source: String)(using P[Any]): P[TypeSpec] =
  P(
    P(spP(source) ~ typeIdP ~ spP(source))
      .map { case (start, id, end) =>
        TypeRef(span(start, end), id)
      }
  )

private[parser] def nativeTypeDefP(source: String)(using P[Any]): P[TypeDef] =
  P(
    spP(source)
      ~ memberVisibilityP.? ~ typeKw ~ typeIdP ~ defAsKw ~ nativeTypeP(source) ~ endKw
      ~ spP(source)
  ).map { case (start, vis, id, typeSpec, end) =>
    TypeDef(
      visibility = vis.getOrElse(MemberVisibility.Protected),
      span(start, end),
      id,
      typeSpec.some
    )
  }

private[parser] def typeAliasP(source: String)(using P[Any]): P[TypeAlias] =
  P(
    spP(source)
      ~ memberVisibilityP.? ~ typeKw ~ typeIdP ~ defAsKw ~ typeSpecP(source) ~ endKw
      ~ spP(source)
  ).map { case (start, vis, id, typeSpec, end) =>
    TypeAlias(
      visibility = vis.getOrElse(MemberVisibility.Protected),
      span(start, end),
      id,
      typeSpec
    )
  }

private[parser] def nativeTypeP(source: String)(using P[Any]): P[NativeType] =
  P(spP(source) ~ nativeKw ~ ":" ~/ nativeTypeBodyP(source) ~ spP(source))
    .map { case (start, body, end) =>
      body match {
        case p: NativePrimitive => p.copy(span = span(start, end))
        case p: NativePointer => p.copy(span = span(start, end))
        case s: NativeStruct => s.copy(span = span(start, end))
      }
    }

private[parser] def nativeTypeBodyP(source: String)(using P[Any]): P[NativeType] =
  P(nativeStructP(source) | nativePointerP(source) | nativePrimitiveP(source))

private[parser] def nativePrimitiveP(source: String)(using P[Any]): P[NativePrimitive] =
  P(spP(source) ~ llvmPrimitiveTypeP ~ spP(source))
    .map { case (start, llvmType, end) =>
      NativePrimitive(span(start, end), llvmType)
    }

private[parser] def nativePointerP(source: String)(using P[Any]): P[NativePointer] =
  P(spP(source) ~ "*" ~ llvmPrimitiveTypeP ~ spP(source))
    .map { case (start, llvmType, end) =>
      NativePointer(span(start, end), llvmType)
    }

private[parser] def nativeStructP(source: String)(using P[Any]): P[NativeStruct] =
  P(spP(source) ~ "{" ~/ nativeFieldListP(source) ~ "}" ~ spP(source))
    .map { case (start, fields, end) =>
      NativeStruct(span(start, end), fields)
    }

private[parser] def nativeFieldListP(source: String)(using P[Any]): P[Map[String, TypeSpec]] =
  P(nativeFieldP(source).rep(sep = ","))
    .map { fields =>
      val fieldList = fields.toList
      val fieldMap  = fieldList.toMap
      fieldMap
    }

private[parser] def nativeFieldP(source: String)(using P[Any]): P[(String, TypeSpec)] =
  P(bindingIdP ~ ":" ~/ typeSpecP(source))

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

private[parser] def memberVisibilityP[$: P]: P[MemberVisibility] =
  P("pub").map(_ => MemberVisibility.Public) |
    P("prot").map(_ => MemberVisibility.Protected) |
    P("priv").map(_ => MemberVisibility.Private)
