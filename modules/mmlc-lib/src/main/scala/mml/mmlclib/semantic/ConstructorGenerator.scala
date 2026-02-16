package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.UUID

/** Generates struct constructor bindings (`__mk_StructName`) for user-defined structs and native
  * structs with fields.
  *
  * This phase runs after TypeResolver (so TypeRefs have resolvedId) and before RefResolver (so
  * references to constructors like `Person` resolve to `__mk_Person`).
  *
  * Previously this logic lived in the parser. Moving it here gives clean separation: the parser
  * produces a faithful AST, and semantic phases transform it.
  */
object ConstructorGenerator:

  private def genId(moduleName: String, name: String): Option[String] =
    Some(s"$moduleName::bnd::$name")

  private def paramId(moduleName: String, ctorName: String, paramName: String): Option[String] =
    Some(
      s"$moduleName::bnd::$ctorName::$paramName::${UUID.randomUUID().toString.take(8)}"
    )

  private def arityOf(count: Int): CallableArity =
    count match
      case 0 => CallableArity.Nullary
      case 1 => CallableArity.Unary
      case 2 => CallableArity.Binary
      case n => CallableArity.Nary(n)

  private def mkStructConstructor(struct: TypeStruct, moduleName: String): Bnd =
    val constructorName = s"__mk_${struct.name}"
    val returnType =
      TypeRef(struct.span, struct.name, resolvedId = struct.id)
    val params = struct.fields.toList.map { field =>
      FnParam(
        SourceOrigin.Synth,
        Name.synth(field.name),
        typeAsc = Some(field.typeSpec),
        id      = paramId(moduleName, constructorName, field.name)
      )
    }
    val meta = BindingMeta(
      origin        = BindingOrigin.Constructor,
      arity         = arityOf(params.size),
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = struct.name,
      mangledName   = constructorName
    )
    val bodyExpr = Expr(
      struct.span,
      List(DataConstructor(struct.span, typeSpec = Some(returnType))),
      typeAsc  = None,
      typeSpec = Some(returnType)
    )
    val lambda = Lambda(
      span     = struct.span,
      params   = params,
      body     = bodyExpr,
      captures = Nil,
      typeSpec = bodyExpr.typeSpec,
      typeAsc  = Some(returnType)
    )
    Bnd(
      visibility = struct.visibility,
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(constructorName),
      value      = Expr(struct.span, List(lambda)),
      typeSpec   = bodyExpr.typeSpec,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = genId(moduleName, constructorName)
    )

  private def mkNativeStructConstructor(td: TypeDef, ns: NativeStruct, moduleName: String): Bnd =
    val constructorName = s"__mk_${td.name}"
    val returnType =
      TypeRef(td.span, td.name, resolvedId = td.id)
    val params = ns.fields.map { case (fieldName, fieldType) =>
      FnParam(
        SourceOrigin.Synth,
        Name.synth(fieldName),
        typeAsc = Some(fieldType),
        id      = paramId(moduleName, constructorName, fieldName)
      )
    }
    val meta = BindingMeta(
      origin        = BindingOrigin.Constructor,
      arity         = arityOf(params.size),
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = td.name,
      mangledName   = constructorName
    )
    val bodyExpr = Expr(
      td.span,
      List(DataConstructor(td.span, typeSpec = Some(returnType))),
      typeAsc  = None,
      typeSpec = Some(returnType)
    )
    val lambda = Lambda(
      span     = td.span,
      params   = params,
      body     = bodyExpr,
      captures = Nil,
      typeSpec = bodyExpr.typeSpec,
      typeAsc  = Some(returnType)
    )
    Bnd(
      visibility = td.visibility,
      source     = SourceOrigin.Synth,
      nameNode   = Name.synth(constructorName),
      value      = Expr(td.span, List(lambda)),
      typeSpec   = bodyExpr.typeSpec,
      typeAsc    = Some(returnType),
      docComment = None,
      meta       = Some(meta),
      id         = genId(moduleName, constructorName)
    )

  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val moduleName = module.name

    val newMembers = module.members.flatMap:
      case struct: TypeStruct =>
        List(struct, mkStructConstructor(struct, moduleName))
      case td: TypeDef =>
        td.typeSpec match
          case Some(ns: NativeStruct) if ns.fields.nonEmpty =>
            List(td, mkNativeStructConstructor(td, ns, moduleName))
          case _ => List(td)
      case other => List(other)

    val newResolvables = newMembers.foldLeft(module.resolvables):
      case (idx, bnd: Bnd)
          if bnd.id.isDefined && !module.resolvables.lookup(bnd.id.get).isDefined =>
        idx.updated(bnd)
      case (idx, _) => idx

    state.withModule(module.copy(members = newMembers, resolvables = newResolvables))
