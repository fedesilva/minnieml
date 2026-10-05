package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Storage layout, including padding and recursively positioned fields. */
case class TypeLayout(
  llvmType:  String,
  size:      Int,
  alignment: Int,
  fields:    List[LayoutField] = Nil
):

  def leaves(offset: Int = 0): List[LayoutField] =
    if size == 0 then Nil
    else if fields.isEmpty then List(LayoutField(offset, this))
    else fields.flatMap(f => f.layout.leaves(offset + f.offset))

case class LayoutField(offset: Int, layout: TypeLayout)

/** LLVM data-layout rules for the scalar and aggregate types emitted by MML. */
case class TargetLayout(specification: String):

  private val entries = specification.split("-").toList

  private def alignment(key: String, default: Int): Int =
    entries
      .find(_.startsWith(s"$key:"))
      .flatMap(_.split(":").lift(1))
      .flatMap(_.toIntOption)
      .filter(_ > 0)
      .map(_ / 8)
      .getOrElse(default)

  def aggregate(llvmType: String, members: List[TypeLayout]): TypeLayout =
    val (fields, end) = members.foldLeft((List.empty[LayoutField], 0)) {
      case ((fields, offset), member) =>
        val start = alignTo(offset, member.alignment)
        (fields :+ LayoutField(start, member), start + member.size)
    }
    val align = members.map(_.alignment).foldLeft(alignment("a", 1))(_.max(_))
    TypeLayout(llvmType, alignTo(end, align), align, fields)

  def of(llvmType: String, state: CodeGenState): Either[CodeGenError, TypeLayout] =
    def resolve(typ: String, visited: Set[String]): Either[CodeGenError, TypeLayout] =
      if visited(typ) then CodeGenError(s"Recursive value layout: $typ").asLeft
      else
        getStructFieldTypes(typ, state) match
          case Some(fields) =>
            fields.traverse(resolve(_, visited + typ)).map(aggregate(typ, _))
          case None => scalar(typ)

    resolve(llvmType, Set.empty)

  def fromType(typ: Type, state: CodeGenState): Either[CodeGenError, TypeLayout] =
    typ match
      case TypeGroup(_, List(inner)) => fromType(inner, state)
      case ref: TypeRef =>
        ref.resolvedId.flatMap(state.resolvables.lookupType) match
          case Some(td: TypeDef) =>
            td.typeSpec
              .toRight(CodeGenError(s"Missing layout for ${td.name}"))
              .flatMap(fromType(_, state))
          case Some(ts: TypeStruct) => fromType(ts, state)
          case Some(ta: TypeAlias) => fromType(ta.typeSpec.getOrElse(ta.typeRef), state)
          case None => CodeGenError(s"Unresolved layout: ${ref.name}").asLeft
      case ts: TypeStruct =>
        ts.fields.toList
          .traverse(f => fromType(f.typeSpec, state))
          .map(aggregate(s"%struct.${ts.name}", _))
      case ns: NativeStruct =>
        ns.fields.traverse(f => fromType(f._2, state)).map(aggregate("struct", _))
      case _: NativePointer => scalar("ptr")
      case _: TypeFn => of("{ ptr, ptr }", state)
      case TypeUnit(_) => scalar("void")
      case np: NativePrimitive => scalar(np.llvmType)
      case _ => CodeGenError(s"Unsupported storage layout: $typ").asLeft

  private def scalar(typ: String): Either[CodeGenError, TypeLayout] =
    val shape = typ match
      case "void" => (0, 1).some
      case "i1" | "i8" => (1, alignment(typ, 1)).some
      case "i16" => (2, alignment(typ, 2)).some
      case "i32" => (4, alignment(typ, 4)).some
      case "i64" => (8, alignment(typ, 4)).some
      case "half" => (2, alignment("f16", 2)).some
      case "float" => (4, alignment("f32", 4)).some
      case "double" => (8, alignment("f64", 8)).some
      case pointerType if pointerType == "ptr" || pointerType.endsWith("*") =>
        val pointer = entries
          .find(e => e.startsWith("p:") || e.startsWith("p0:"))
          .map(_.split(":").toList.drop(1).flatMap(_.toIntOption))
        pointer match
          case Some(size :: align :: _) => (size / 8, align / 8).some
          case _ => (8, 8).some
      case _ => none
    shape
      .map { case (size, align) => TypeLayout(typ, alignTo(size, align), align) }
      .toRight(CodeGenError(s"Unsupported LLVM storage type: $typ"))

object TargetLayout:

  val default: TargetLayout = TargetLayout("e-i64:64")

  /** Split LLVM aggregate members without splitting nested aggregates. */
  def fields(typ: String): Option[List[String]] =
    val trimmed = typ.trim
    Option.when(trimmed.startsWith("{") && trimmed.endsWith("}")) {
      val body = trimmed.drop(1).dropRight(1)
      val (parts, start, _) = body.zipWithIndex.foldLeft((List.empty[String], 0, 0)) {
        case ((parts, start, depth), (char, index)) =>
          char match
            case '{' | '[' | '<' => (parts, start, depth + 1)
            case '}' | ']' | '>' => (parts, start, depth - 1)
            case ',' if depth == 0 => (parts :+ body.slice(start, index).trim, index + 1, depth)
            case _ => (parts, start, depth)
      }
      (parts :+ body.drop(start).trim).filter(_.nonEmpty)
    }
