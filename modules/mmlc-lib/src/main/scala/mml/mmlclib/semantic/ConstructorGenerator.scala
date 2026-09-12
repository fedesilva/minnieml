package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import BindingIds.Allocation

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

  private def arityOf(count: Int): CallableArity =
    count match
      case 0 => CallableArity.Nullary
      case 1 => CallableArity.Unary
      case 2 => CallableArity.Binary
      case n => CallableArity.Nary(n)

  private def mkStructConstructor(struct: TypeStruct, moduleName: String): Allocation[Bnd] =
    val constructorName = s"__mk_${struct.name}"
    val returnType =
      TypeRef(struct.source, struct.name, resolvedId = struct.id)
    val allocatedParams = struct.fields.toList.traverse { field =>
      LocalBindings.param(
        BindingOwner.binding(moduleName, constructorName),
        field.name,
        typeAsc = Some(field.typeSpec),
        purpose = "constructor"
      )
    }
    allocatedParams
      .map { params =>
        val meta = BindingMeta(
          origin        = BindingOrigin.Constructor,
          arity         = arityOf(params.size),
          precedence    = Precedence.Function,
          associativity = None,
          originalName  = struct.name,
          mangledName   = constructorName
        )
        val bodyExpr = Expr(
          struct.source,
          List(DataConstructor(struct.source, typeSpec = Some(returnType))),
          typeAsc  = None,
          typeSpec = Some(returnType)
        )
        val lambda = Lambda(
          source   = struct.source,
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
          value      = Expr(struct.source, List(lambda)),
          typeSpec   = bodyExpr.typeSpec,
          typeAsc    = Some(returnType),
          docComment = None,
          meta       = Some(meta),
          id         = BindingIds.declaration(moduleName, constructorName).some
        )
      }
      .flatTap(BindingIds.reserveDeclaration)

  private def mkNativeStructConstructor(
    td:         TypeDef,
    ns:         NativeStruct,
    moduleName: String
  ): Allocation[Bnd] =
    val constructorName = s"__mk_${td.name}"
    val returnType =
      TypeRef(td.source, td.name, resolvedId = td.id)
    val allocatedParams = ns.fields.traverse { case (fieldName, fieldType) =>
      LocalBindings.param(
        BindingOwner.binding(moduleName, constructorName),
        fieldName,
        typeAsc = Some(fieldType),
        purpose = "constructor"
      )
    }
    allocatedParams
      .map { params =>
        val meta = BindingMeta(
          origin        = BindingOrigin.Constructor,
          arity         = arityOf(params.size),
          precedence    = Precedence.Function,
          associativity = None,
          originalName  = td.name,
          mangledName   = constructorName
        )
        val bodyExpr = Expr(
          td.source,
          List(DataConstructor(td.source, typeSpec = Some(returnType))),
          typeAsc  = None,
          typeSpec = Some(returnType)
        )
        val lambda = Lambda(
          source   = td.source,
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
          value      = Expr(td.source, List(lambda)),
          typeSpec   = bodyExpr.typeSpec,
          typeAsc    = Some(returnType),
          docComment = None,
          meta       = Some(meta),
          id         = BindingIds.declaration(moduleName, constructorName).some
        )
      }
      .flatTap(BindingIds.reserveDeclaration)

  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val moduleName = module.name

    val allocation = module.members.flatTraverse:
      case struct: TypeStruct =>
        mkStructConstructor(struct, moduleName).map(ctor => List(struct, ctor): List[Member])
      case td: TypeDef =>
        td.typeSpec match
          case Some(ns: NativeStruct) if ns.fields.nonEmpty =>
            mkNativeStructConstructor(td, ns, moduleName).map(ctor => List(td, ctor): List[Member])
          case _ => List[Member](td).pure[Allocation]
      case other => List(other).pure[Allocation]

    val (supply, newMembers) = allocation.run(state.bindingIds.include(module)).value
    state
      .copy(bindingIds = supply)
      .withModule(ResolvablesIndexer.refresh(module.copy(members = newMembers)))
