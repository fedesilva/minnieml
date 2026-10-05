package mml.mmlclib.codegen.emitter.abis

import cats.syntax.all.*
import mml.mmlclib.codegen.TargetAbi
import mml.mmlclib.codegen.emitter.*

/** One register component of an aggregate, at its byte offset in storage. */
case class AbiPart(llvmType: String, offset: Int, size: Int)

case class NativeCallKey(name: String, plan: NativeAbiPlan)

enum AbiValue:
  case Direct(llvmType: String, attribute: String = "")
  case Coerce(layout: TypeLayout, parts: List[AbiPart])
  case Indirect(layout: TypeLayout, byval: Boolean)

  def sourceType: String = this match
    case Direct(typ, _) => typ
    case Coerce(layout, _) => layout.llvmType
    case Indirect(layout, _) => layout.llvmType

  def parameterTypes: List[String] = this match
    case Direct(typ, attr) => List(s"$typ $attr".trim)
    case Coerce(_, parts) => parts.map(_.llvmType)
    case Indirect(layout, byval) =>
      val attr = if byval then s" byval(${layout.llvmType})" else ""
      List(s"ptr$attr align ${if byval then layout.alignment.max(8) else layout.alignment}")

/** Complete native signature, shared by declarations and every call emission path. */
case class NativeAbiPlan(result: AbiValue, arguments: List[AbiValue]):

  def returnType: String = result match
    case AbiValue.Direct(typ, attr) => s"$attr $typ".trim
    case AbiValue.Coerce(_, Nil) => "void"
    case AbiValue.Coerce(_, List(part)) => part.llvmType
    case AbiValue.Coerce(_, parts) => parts.map(_.llvmType).mkString("{ ", ", ", " }")
    case AbiValue.Indirect(_, _) => "void"

  private def sretType: Option[String] = result match
    case AbiValue.Indirect(layout, _) =>
      s"ptr sret(${layout.llvmType}) align ${layout.alignment}".some
    case _ => none

  def parameterTypes: List[String] = sretType.toList ++ arguments.flatMap(_.parameterTypes)

  def declare(name: String, state: CodeGenState): CodeGenState =
    state.withFunctionDeclaration(name, returnType, parameterTypes)

  /** Reuse a native adapter whose stack frame owns argument and result packing storage. */
  def emitCall(
    name:       String,
    values:     List[String],
    state:      CodeGenState,
    aliasScope: Option[String] = None,
    noalias:    Option[String] = None
  ): (Int, CodeGenState) =
    val needsStorage = (result :: arguments).exists {
      case AbiValue.Direct(_, _) | AbiValue.Coerce(_, Nil) => false
      case _ => true
    }
    if !needsStorage then emitCallWithStorage(name, values, state, aliasScope, noalias)
    else
      val key = NativeCallKey(name, this)
      val (prepared, adapter) = state.nativeCallAdapters.get(key) match
        case Some(existing) => (state, existing)
        case None =>
          val (allocated, entry) = state.allocAnonFnName
          val registered =
            allocated.copy(nativeCallAdapters = allocated.nativeCallAdapters + (key -> entry))
          val body = registered.copy(
            output                  = Nil,
            entryPrologueOutput     = Nil,
            nextRegister            = arguments.size,
            insideLoopifiedFunction = false
          )
          val operands      = arguments.indices.map(i => s"%$i").toList
          val (reg, called) = emitCallWithStorage(name, operands, body, none, none)
          val ret =
            if result.sourceType == "void" then "  ret void"
            else s"  ret ${result.sourceType} %$reg"
          val params = arguments.zipWithIndex.map((arg, i) => s"${arg.sourceType} %$i")
          val header =
            s"define internal ${result.sourceType} @$entry(${params.mkString(", ")}) inlinehint #0 {"
          val completed  = called.emit(ret).emit("}")
          val definition = renderFunctionLines(header, completed).mkString("\n")
          (mergeFunctionBodyState(registered, completed).addDeferredDefinition(definition), entry)
      val reg          = prepared.nextRegister
      val returnsValue = result.sourceType != "void"
      val called = prepared
        .withRegister(if returnsValue then reg + 1 else reg)
        .emit(
          mml.mmlclib.codegen.emitter.emitCall(
            Option.when(returnsValue)(reg),
            result.sourceType.some,
            adapter,
            arguments.zip(values).map((arg, value) => (arg.sourceType, value)),
            aliasScope,
            noalias
          )
        )
      (if returnsValue then reg else 0, called)

  private def emitCallWithStorage(
    name:       String,
    values:     List[String],
    state:      CodeGenState,
    aliasScope: Option[String],
    noalias:    Option[String]
  ): (Int, CodeGenState) =
    val (args, packed) = arguments.zip(values).foldLeft((List.empty[(String, String)], state)) {
      case ((args, st), (arg, value)) =>
        arg match
          case AbiValue.Direct(_, _) => (args :+ (arg.parameterTypes.head, value), st)
          case AbiValue.Indirect(layout, _) =>
            val (ptr, allocated) = allocate(layout.size, layout.alignment.max(8), st)
            val stored           = allocated.emit(s"  store ${layout.llvmType} $value, ptr $ptr")
            (args :+ (arg.parameterTypes.head, ptr), stored)
          case AbiValue.Coerce(layout, parts) =>
            val (ptr, stored) = pack(layout, parts, value, st)
            val (components, loaded) = parts.foldLeft((List.empty[(String, String)], stored)) {
              case ((components, current), part) =>
                val (address, addressed) = addressAt(ptr, part.offset, current)
                val reg                  = addressed.nextRegister
                val next = addressed
                  .withRegister(reg + 2)
                  .emit(s"  %$reg = load ${part.llvmType}, ptr $address, align 1")
                  .emit(s"  %${reg + 1} = freeze ${part.llvmType} %$reg")
                (components :+ (part.llvmType, s"%${reg + 1}"), next)
            }
            (args ++ components, loaded)
    }
    result match
      case AbiValue.Coerce(layout, Nil) =>
        val reg = packed.nextRegister
        val called = packed.emit(
          mml.mmlclib.codegen.emitter.emitCall(none, none, name, args, aliasScope, noalias)
        )
        (
          reg,
          called.withRegister(reg + 1).emit(s"  %$reg = freeze ${layout.llvmType} zeroinitializer")
        )
      case AbiValue.Indirect(layout, _) =>
        val (ptr, allocated) = allocate(layout.size, layout.alignment, packed)
        val called = allocated.emit(
          mml.mmlclib.codegen.emitter.emitCall(
            none,
            "void".some,
            name,
            (sretType.getOrElse("ptr"), ptr) :: args,
            aliasScope,
            noalias
          )
        )
        loadResult(layout, ptr, called)
      case AbiValue.Direct("void", _) =>
        (
          0,
          packed.emit(
            mml.mmlclib.codegen.emitter.emitCall(
              none,
              none,
              name,
              args,
              aliasScope,
              noalias
            )
          )
        )
      case _ =>
        val reg = packed.nextRegister
        val called = packed
          .withRegister(reg + 1)
          .emit(
            mml.mmlclib.codegen.emitter.emitCall(
              reg.some,
              returnType.some,
              name,
              args,
              aliasScope,
              noalias
            )
          )
        result match
          case AbiValue.Coerce(layout, parts) =>
            val (ptr, allocated) = allocate(storageSize(layout, parts), layout.alignment, called)
            val stored = parts.zipWithIndex.foldLeft(allocated) { case (st, (part, index)) =>
              val (value, extracted) =
                if parts.size == 1 then (s"%$reg", st)
                else
                  val field = st.nextRegister
                  (
                    s"%$field",
                    st.withRegister(field + 1)
                      .emit(emitExtractValue(field, returnType, s"%$reg", index))
                  )
              val (address, addressed) = addressAt(ptr, part.offset, extracted)
              addressed.emit(s"  store ${part.llvmType} $value, ptr $address, align 1")
            }
            loadResult(layout, ptr, stored)
          case _ => (reg, called)

  private def storageSize(layout: TypeLayout, parts: List[AbiPart]): Int =
    parts.map(p => p.offset + p.size).foldLeft(layout.size)(_.max(_))

  private def allocate(size: Int, alignment: Int, state: CodeGenState): (String, CodeGenState) =
    val reg = state.nextRegister
    val typ = s"[$size x i8]"
    (
      s"%$reg",
      state
        .withRegister(reg + 1)
        .emit(s"  %$reg = alloca $typ, align $alignment")
        .emit(s"  store $typ zeroinitializer, ptr %$reg, align $alignment")
    )

  private def pack(
    layout: TypeLayout,
    parts:  List[AbiPart],
    value:  String,
    state:  CodeGenState
  ): (String, CodeGenState) =
    val (ptr, allocated) = allocate(storageSize(layout, parts), layout.alignment, state)
    (ptr, allocated.emit(s"  store ${layout.llvmType} $value, ptr $ptr"))

  private def addressAt(ptr: String, offset: Int, state: CodeGenState): (String, CodeGenState) =
    if offset == 0 then (ptr, state)
    else
      val reg = state.nextRegister
      (
        s"%$reg",
        state
          .withRegister(reg + 1)
          .emit(s"  %$reg = getelementptr i8, ptr $ptr, i64 $offset")
      )

  private def loadResult(
    layout: TypeLayout,
    ptr:    String,
    state:  CodeGenState
  ): (Int, CodeGenState) =
    val reg = state.nextRegister
    (reg, state.withRegister(reg + 1).emit(s"  %$reg = load ${layout.llvmType}, ptr $ptr"))

object NativeAbiPlan:

  def classify(
    returnType: String,
    paramTypes: List[String],
    state:      CodeGenState
  ): Either[CodeGenError, NativeAbiPlan] =
    for
      result <- classifyValue(returnType, state, isReturn = true)
      args <- paramTypes.traverse(classifyValue(_, state, isReturn = false))
    yield
      val classified =
        if state.targetAbi == TargetAbi.X86_64 then
          val initialGp = result match
            case AbiValue.Indirect(_, _) => 1
            case _ => 0
          args
            .foldLeft((List.empty[AbiValue], initialGp, 0)) { case ((acc, gp, fp), arg) =>
              val (neededGp, neededFp) = registers(arg)
              arg match
                case AbiValue.Coerce(layout, _) if gp + neededGp > 6 || fp + neededFp > 8 =>
                  (acc :+ AbiValue.Indirect(layout, byval = true), gp, fp)
                case _ => (acc :+ arg, (gp + neededGp).min(6), (fp + neededFp).min(8))
            }
            ._1
        else args
      NativeAbiPlan(result, classified)

  private def registers(value: AbiValue): (Int, Int) =
    val types = value match
      case AbiValue.Direct(typ, _) => List(typ)
      case AbiValue.Coerce(_, parts) => parts.map(_.llvmType)
      case AbiValue.Indirect(_, _) => Nil
    val fp = types.count(t => t == "half" || t == "float" || t == "double" || t.startsWith("<"))
    (types.size - fp, fp)

  private def classifyValue(
    typ:      String,
    state:    CodeGenState,
    isReturn: Boolean
  ): Either[CodeGenError, AbiValue] =
    state.layout.of(typ, state).map { layout =>
      if getStructFieldTypes(typ, state).isEmpty then
        val extend = state.targetAbi != TargetAbi.AArch64
        val attr = typ match
          case "i1" if extend => "zeroext"
          case "i8" | "i16" if extend => "signext"
          case _ => ""
        AbiValue.Direct(typ, attr)
      else if layout.size == 0 then AbiValue.Coerce(layout, Nil)
      else if state.targetAbi == TargetAbi.X86_64 then
        if layout.size > 16 then AbiValue.Indirect(layout, byval = true)
        else AbiValue.Coerce(layout, x86Parts(layout))
      else
        val leaves = layout.leaves()
        val hfa = leaves.nonEmpty && leaves.size <= 4 &&
          leaves.map(_.layout.llvmType).distinct.size == 1 &&
          Set("half", "float", "double")(leaves.head.layout.llvmType)
        if hfa then
          val base = leaves.head.layout.llvmType
          AbiValue.Coerce(layout, List(AbiPart(s"[${leaves.size} x $base]", 0, layout.size)))
        else if layout.size > 16 then AbiValue.Indirect(layout, byval = false)
        else
          val size = if isReturn && layout.size <= 8 then layout.size else alignTo(layout.size, 8)
          val coerced = if size <= 8 then s"i${size * 8}" else "[2 x i64]"
          AbiValue.Coerce(layout, List(AbiPart(coerced, 0, size)))
    }

  private def x86Parts(layout: TypeLayout): List[AbiPart] =
    (0 until layout.size by 8).toList.flatMap { offset =>
      val leaves =
        layout.leaves().filter(f => f.offset < offset + 8 && f.offset + f.layout.size > offset)
      if leaves.isEmpty then Nil
      else
        val size    = leaves.map(f => f.offset + f.layout.size - offset).max.min(8)
        val integer = leaves.exists(f => !Set("half", "float", "double")(f.layout.llvmType))
        val typ =
          if integer then s"i${size * 8}"
          else if leaves.exists(_.layout.llvmType == "double") then "double"
          else if leaves.forall(_.layout.llvmType == "half") then
            if leaves.size == 1 then "half"
            else if leaves.size == 2 then "<2 x half>"
            else "<4 x half>"
          else if size > 4 then "<2 x float>"
          else "float"
        val storage = if typ == "<4 x half>" || typ == "<2 x float>" then 8 else size
        List(AbiPart(typ, offset, storage))
    }
