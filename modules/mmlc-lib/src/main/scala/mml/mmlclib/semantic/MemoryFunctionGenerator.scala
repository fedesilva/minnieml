package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import BindingIds.Allocation

/** Registers destructors and consuming constructors for structs with owned fields.
  *
  * Native and nested struct fields get ordinary free calls. Function-bearing destructor bodies are
  * completed after closure dispatch registration by StructDestructorBodyGenerator. Native types
  * (String, Buffer, etc.) provide their memory functions in the C runtime.
  */
object MemoryFunctionGenerator:
  private val syntheticSource = SourceOrigin.Synth

  private def unitTypeRef(source: SourceOrigin): TypeRef =
    TypeRef(source, "Unit", Some("stdlib::typedef::Unit"), Nil)

  private def isHeapField(field: Field, resolvables: ResolvablesIndex): Boolean =
    TypeUtils.requiresDestruction(field.typeSpec, resolvables)

  private def heapFieldsOf(struct: TypeStruct, resolvables: ResolvablesIndex): Vector[Field] =
    struct.fields.filter(isHeapField(_, resolvables))

  /** Build a `__free_StructName` function for a user struct.
    *
    * Generated pattern: {{{ fn __free_User(~u: User): Unit = let _ = __free_String u.name;
    * __free_String u.role }}}
    */
  private def mkFreeFunction(
    struct:      TypeStruct,
    moduleName:  String,
    resolvables: ResolvablesIndex
  ): Allocation[(Bnd, List[SemanticError])] =
    val structName = struct.name
    val fnName     = s"__free_$structName"
    val paramName  = "s"

    // Type refs
    val structTypeRef =
      TypeRef(syntheticSource, structName, struct.id, Nil)
    val unitTR = unitTypeRef(syntheticSource)

    // Parameter - consuming since it takes ownership
    val allocation = LocalBindings.local(
      BindingOwner.binding(moduleName, fnName),
      paramName,
      typeAsc   = Some(structTypeRef),
      consuming = true,
      purpose   = "destructor"
    )

    allocation
      .flatMap { local =>
        val param = local.param
        // Get heap fields to free
        val heapFields = heapFieldsOf(struct, resolvables)

        // Build free calls for each heap field
        val fieldResults = heapFields.toList
          .filter(field => TypeUtils.freeFnFor(field.typeSpec, resolvables).nonEmpty)
          .map { field =>
            MemoryFunctions
              .free(field.typeSpec, resolvables, moduleName.some)
              .toRight(
                SemanticError.InvalidExpression(
                  Expr(field.source, Nil, typeSpec = field.typeSpec.some),
                  s"Missing destructor for field '${field.name}'",
                  "memory-function-generator"
                )
              )
              .map { target =>
                // Build: __free_T s.fieldName
                val freeFnRef = Ref(
                  SourceOrigin.Synth,
                  target.name,
                  resolvedId = target.id.some,
                  typeSpec = Some(
                    TypeFn(syntheticSource, cats.data.NonEmptyList.one(field.typeSpec), unitTR)
                  )
                )
                val paramRef = local.ref
                val fieldRef = Ref(
                  SourceOrigin.Synth,
                  field.name,
                  qualifier = Some(paramRef),
                  typeSpec  = Some(field.typeSpec)
                )
                val argExpr = Expr(syntheticSource, List(fieldRef), typeSpec = Some(field.typeSpec))
                App(syntheticSource, freeFnRef, argExpr, typeSpec = Some(unitTR))
              }
          }

        val errors = fieldResults.flatMap(_.left.toOption)
        val cleanup = LocalBindings.cleanup(
          fieldResults.flatMap(_.toOption),
          BindingOwner.binding(moduleName, fnName),
          unitTR
        )

        cleanup.map { body =>
          // Build the function type: StructName -> Unit
          val fnType = TypeFn(syntheticSource, cats.data.NonEmptyList.one(structTypeRef), unitTR)

          // Build the lambda
          val lambda = Lambda(
            syntheticSource,
            List(param),
            body,
            Nil,
            typeSpec = Some(fnType),
            typeAsc  = Some(unitTR)
          )

          // Build the binding
          val meta = BindingMeta(
            origin        = BindingOrigin.Destructor,
            arity         = CallableArity.Unary,
            precedence    = Precedence.Function,
            associativity = None,
            originalName  = fnName,
            mangledName   = fnName
          )

          val binding = Bnd(
            source     = SourceOrigin.Synth,
            nameNode   = Name.synth(fnName),
            value      = Expr(syntheticSource, List(lambda)),
            typeSpec   = Some(fnType),
            typeAsc    = Some(unitTR),
            docComment = None,
            meta       = Some(meta),
            id         = BindingIds.declaration(moduleName, fnName).some
          )
          (binding, errors)
        }
      }
      .flatTap { (binding, _) => BindingIds.reserveDeclaration(binding) }

  /** Rewrite a __mk_StructName constructor to mark heap-typed params as consuming */
  private def rewriteConstructor(
    struct:      TypeStruct,
    resolvables: ResolvablesIndex,
    members:     List[Member]
  ): List[Member] =
    val constructorName = s"__mk_${struct.name}"
    members.map:
      case bnd: Bnd if bnd.name == constructorName =>
        val lambdaOpt = bnd.value.terms.collectFirst { case l: Lambda => l }
        lambdaOpt match
          case Some(lambda) =>
            val newParams = lambda.params.zip(struct.fields.toList).map { (param, field) =>
              if isHeapField(field, resolvables) then param.copy(consuming = true)
              else param
            }
            val newLambda = lambda.copy(params = newParams)
            bnd.copy(value = Expr(bnd.value.source, List(newLambda)))
          case None => bnd
      case other => other

  /** Rewrite a native struct constructor to mark heap-typed params as consuming */
  private def rewriteNativeConstructor(
    td:          TypeDef,
    ns:          NativeStruct,
    resolvables: ResolvablesIndex,
    members:     List[Member]
  ): List[Member] =
    val constructorName = s"__mk_${td.name}"
    members.map:
      case bnd: Bnd if bnd.name == constructorName =>
        val lambdaOpt = bnd.value.terms.collectFirst { case l: Lambda => l }
        lambdaOpt match
          case Some(lambda) =>
            val newParams = lambda.params.zip(ns.fields).map { (param, field) =>
              if TypeUtils.isHeapType(field._2, resolvables) then param.copy(consuming = true)
              else param
            }
            val newLambda = lambda.copy(params = newParams)
            bnd.copy(value = Expr(bnd.value.source, List(newLambda)))
          case None => bnd
      case other => other

  /** Main entry point - generate memory functions for structs with heap fields */
  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val moduleName = module.name

    // Find all user structs
    val structs = module.members.collect { case s: TypeStruct => s }

    // Find structs that have heap fields
    val structsNeedingMemFns = structs.filter(TypeUtils.hasHeapFields(_, module.resolvables))

    // Find native structs with heap fields (constructor rewrite only, no free/clone generation)
    val nativeStructsWithHeapFields = module.members.collect { case td: TypeDef =>
      td.typeSpec.collect {
        case ns: NativeStruct
            if ns.fields.exists(f => TypeUtils.isHeapType(f._2, module.resolvables)) =>
          (td, ns)
      }
    }.flatten

    if structsNeedingMemFns.isEmpty && nativeStructsWithHeapFields.isEmpty then state
    else
      // Rewrite constructor params: mark heap-typed params as consuming
      val membersWithRewrittenCtors = structsNeedingMemFns.foldLeft(module.members) {
        (members, struct) =>
          rewriteConstructor(struct, module.resolvables, members)
      }

      // Also rewrite native struct constructor params
      val membersWithAllCtors = nativeStructsWithHeapFields.foldLeft(membersWithRewrittenCtors) {
        case (members, (td, ns)) =>
          rewriteNativeConstructor(td, ns, module.resolvables, members)
      }

      // Update resolvables with rewritten constructor Bnds
      val allCtorNames =
        structsNeedingMemFns.map(s => s"__mk_${s.name}") ++
          nativeStructsWithHeapFields.map { case (td, _) => s"__mk_${td.name}" }
      val rewrittenCtors = allCtorNames.flatMap { ctorName =>
        membersWithAllCtors.collectFirst { case b: Bnd if b.name == ctorName => b }
      }
      val resolvablesWithCtors = rewrittenCtors.foldLeft(module.resolvables) { (idx, bnd) =>
        idx.updated(bnd)
      }

      val allocation = structsNeedingMemFns.traverse { struct =>
        mkFreeFunction(struct, moduleName, resolvablesWithCtors)
      }
      val (supply, generated) = allocation.run(state.bindingIds.include(module)).value

      val generatedFns = generated.map(_._1)
      val errors       = generated.flatMap(_._2)
      val finalMembers = membersWithAllCtors ++ generatedFns
      state
        .copy(bindingIds = supply)
        .withModule(ResolvablesIndexer.refresh(module.copy(members = finalMembers)))
        .addErrors(errors)
