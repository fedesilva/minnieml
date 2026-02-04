package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Generates memory management functions (`__free_T` and `__clone_T`) for user-defined structs that
  * contain heap-allocated fields.
  *
  * This phase runs after TypeChecker and before ResolvablesIndexer. For each user struct with heap
  * fields, it generates:
  *   - `__free_StructName(s: StructName): Unit` - frees all heap fields
  *   - `__clone_StructName(s: StructName): StructName` - deep copies all heap fields
  *
  * Native types (String, Buffer, etc.) have their memory functions defined in the C runtime.
  */
object MemoryFunctionGenerator:
  private val syntheticSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))

  /** Generate a stable ID for generated memory functions */
  private def genId(moduleName: String, fnName: String): Option[String] =
    Some(s"$moduleName::bnd::$fnName")

  /** Check if a struct has any fields that are heap types */
  private def structHasHeapFields(struct: TypeStruct, resolvables: ResolvablesIndex): Boolean =
    struct.fields.exists { field =>
      OwnershipAnalyzer.getTypeName(field.typeSpec).exists { typeName =>
        OwnershipAnalyzer.isHeapType(typeName, resolvables)
      }
    }

  /** Get heap fields from a struct */
  private def heapFieldsOf(struct: TypeStruct, resolvables: ResolvablesIndex): Vector[Field] =
    struct.fields.filter { field =>
      OwnershipAnalyzer.getTypeName(field.typeSpec).exists { typeName =>
        OwnershipAnalyzer.isHeapType(typeName, resolvables)
      }
    }

  /** Build a `__free_StructName` function for a user struct.
    *
    * Generated pattern: {{{
    * fn __free_User(~u: User): Unit =
    *   let _ = __free_String u.name;
    *   __free_String u.role
    * }}}
    */
  private def mkFreeFunction(
    struct:      TypeStruct,
    moduleName:  String,
    resolvables: ResolvablesIndex
  ): Bnd =
    val structName = struct.name
    val fnName     = s"__free_$structName"
    val paramName  = "s"

    // Type refs
    val structTypeRef =
      TypeRef(syntheticSpan, structName, struct.id, Nil)
    val unitTypeRef =
      TypeRef(syntheticSpan, "Unit", Some("stdlib::typedef::Unit"), Nil)

    // Parameter - consuming since it takes ownership
    val param = FnParam(
      syntheticSpan,
      paramName,
      typeAsc   = Some(structTypeRef),
      consuming = true
    )

    // Get heap fields to free
    val heapFields = heapFieldsOf(struct, resolvables)

    // Build free calls for each heap field
    val freeCalls: List[Term] = heapFields.toList.flatMap { field =>
      OwnershipAnalyzer.getTypeName(field.typeSpec).flatMap { typeName =>
        OwnershipAnalyzer.freeFnFor(typeName, resolvables).map { freeFnName =>
          // Build: __free_T s.fieldName
          val freeFnRef = Ref(
            syntheticSpan,
            freeFnName,
            resolvedId = Some(s"stdlib::bnd::$freeFnName"),
            typeSpec   = Some(TypeFn(syntheticSpan, List(field.typeSpec), unitTypeRef))
          )
          val paramRef = Ref(syntheticSpan, paramName, typeSpec = Some(structTypeRef))
          val fieldRef = Ref(
            syntheticSpan,
            field.name,
            qualifier = Some(paramRef),
            typeSpec  = Some(field.typeSpec)
          )
          val argExpr = Expr(syntheticSpan, List(fieldRef), typeSpec = Some(field.typeSpec))
          App(syntheticSpan, freeFnRef, argExpr, typeSpec = Some(unitTypeRef))
        }
      }
    }

    // Build the body - sequence of let _ = free; statements ending with unit
    val body =
      if freeCalls.isEmpty then Expr(syntheticSpan, List(LiteralUnit(syntheticSpan)))
      else
        // Chain free calls with let _ = ...; pattern
        val lastCall = freeCalls.last
        val initCalls = freeCalls.init

        val innerBody = Expr(syntheticSpan, List(lastCall), typeSpec = Some(unitTypeRef))
        initCalls.foldRight(innerBody) { (call, acc) =>
          val discardParam =
            FnParam(syntheticSpan, "_", typeSpec = Some(unitTypeRef), typeAsc = Some(unitTypeRef))
          val wrapper =
            Lambda(syntheticSpan, List(discardParam), acc, Nil, typeSpec = Some(unitTypeRef))
          val callExpr = Expr(syntheticSpan, List(call), typeSpec = Some(unitTypeRef))
          Expr(
            syntheticSpan,
            List(App(syntheticSpan, wrapper, callExpr, typeSpec = Some(unitTypeRef))),
            typeSpec = Some(unitTypeRef)
          )
        }

    // Build the function type: StructName -> Unit
    val fnType = TypeFn(syntheticSpan, List(structTypeRef), unitTypeRef)

    // Build the lambda
    val lambda = Lambda(
      syntheticSpan,
      List(param),
      body,
      Nil,
      typeSpec = Some(fnType),
      typeAsc  = Some(unitTypeRef)
    )

    // Build the binding
    val meta = BindingMeta(
      origin        = BindingOrigin.Function,
      arity         = CallableArity.Unary,
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = fnName,
      mangledName   = fnName
    )

    Bnd(
      span       = syntheticSpan,
      name       = fnName,
      value      = Expr(syntheticSpan, List(lambda)),
      typeSpec   = Some(fnType),
      typeAsc    = Some(unitTypeRef),
      docComment = None,
      meta       = Some(meta),
      id         = genId(moduleName, fnName)
    )

  /** Build a `__clone_StructName` function for a user struct.
    *
    * Generated pattern: {{{
    * fn __clone_User(u: User): User =
    *   __mk_User (__clone_String u.name) (__clone_String u.role)
    * }}}
    */
  private def mkCloneFunction(
    struct:      TypeStruct,
    moduleName:  String,
    resolvables: ResolvablesIndex
  ): Bnd =
    val structName = struct.name
    val fnName     = s"__clone_$structName"
    val paramName  = "s"

    // Type refs
    val structTypeRef =
      TypeRef(syntheticSpan, structName, struct.id, Nil)

    // Parameter - not consuming since we're cloning (borrowing)
    val param = FnParam(
      syntheticSpan,
      paramName,
      typeAsc   = Some(structTypeRef),
      consuming = false
    )

    // Build the constructor type as a curried function: T1 -> T2 -> ... -> StructType
    val fieldTypes = struct.fields.map(_.typeSpec).toList
    val constructorType = fieldTypes.foldRight(structTypeRef: Type) { (fieldType, accType) =>
      TypeFn(syntheticSpan, List(fieldType), accType)
    }

    // Constructor reference: __mk_StructName
    val constructorName = s"__mk_$structName"
    val constructorRef = Ref(
      syntheticSpan,
      constructorName,
      resolvedId = Some(s"$moduleName::bnd::$constructorName"),
      typeSpec   = Some(constructorType)
    )

    // Build arguments: for each field, either clone (if heap) or just access (if not)
    val argExprs: List[Expr] = struct.fields.toList.map { field =>
      val paramRef  = Ref(syntheticSpan, paramName, typeSpec = Some(structTypeRef))
      val fieldRef  = Ref(syntheticSpan, field.name, qualifier = Some(paramRef))
      val fieldExpr = Expr(syntheticSpan, List(fieldRef), typeSpec = Some(field.typeSpec))

      OwnershipAnalyzer.getTypeName(field.typeSpec) match
        case Some(typeName) if OwnershipAnalyzer.isHeapType(typeName, resolvables) =>
          // Heap field - wrap with clone
          OwnershipAnalyzer.cloneFnFor(typeName, resolvables) match
            case Some(cloneFnName) =>
              val cloneFnRef = Ref(
                syntheticSpan,
                cloneFnName,
                resolvedId = Some(s"stdlib::bnd::$cloneFnName"),
                typeSpec   = Some(TypeFn(syntheticSpan, List(field.typeSpec), field.typeSpec))
              )
              val cloneApp =
                App(syntheticSpan, cloneFnRef, fieldExpr, typeSpec = Some(field.typeSpec))
              Expr(syntheticSpan, List(cloneApp), typeSpec = Some(field.typeSpec))
            case None =>
              // No clone function - just use field directly (shouldn't happen)
              fieldExpr
        case _ =>
          // Non-heap field - use directly
          fieldExpr
    }

    // Build constructor call: __mk_StructName arg1 arg2 ...
    // Track the result type through each curried application
    val (constructorCall, _) =
      argExprs.foldLeft((constructorRef: Ref | App | Lambda, constructorType)) { case ((fn, currType), arg) =>
        val resultType = currType match
          case TypeFn(_, _, ret) => ret
          case other             => other
        val app = App(syntheticSpan, fn, arg, typeSpec = Some(resultType))
        (app, resultType)
      }

    val body = Expr(syntheticSpan, List(constructorCall), typeSpec = Some(structTypeRef))

    // Build the function type: StructName -> StructName
    val fnType = TypeFn(syntheticSpan, List(structTypeRef), structTypeRef)

    // Build the lambda
    val lambda = Lambda(
      syntheticSpan,
      List(param),
      body,
      Nil,
      typeSpec = Some(fnType),
      typeAsc  = Some(structTypeRef)
    )

    // Build the binding
    val meta = BindingMeta(
      origin        = BindingOrigin.Function,
      arity         = CallableArity.Unary,
      precedence    = Precedence.Function,
      associativity = None,
      originalName  = fnName,
      mangledName   = fnName
    )

    Bnd(
      span       = syntheticSpan,
      name       = fnName,
      value      = Expr(syntheticSpan, List(lambda)),
      typeSpec   = Some(fnType),
      typeAsc    = Some(structTypeRef),
      docComment = None,
      meta       = Some(meta),
      id         = genId(moduleName, fnName)
    )

  /** Main entry point - generate memory functions for structs with heap fields */
  def rewriteModule(state: CompilerState): CompilerState =
    val module     = state.module
    val moduleName = module.name

    // Find all user structs
    val structs = module.members.collect { case s: TypeStruct => s }

    // Find structs that have heap fields
    val structsNeedingMemFns = structs.filter(structHasHeapFields(_, module.resolvables))

    if structsNeedingMemFns.isEmpty then state
    else
      // Generate __free_T and __clone_T for each struct
      val generatedFns = structsNeedingMemFns.flatMap { struct =>
        List(
          mkFreeFunction(struct, moduleName, module.resolvables),
          mkCloneFunction(struct, moduleName, module.resolvables)
        )
      }

      // Add generated functions to module members
      val newMembers = module.members ++ generatedFns

      // Update resolvables index with new functions
      val newResolvables = generatedFns.foldLeft(module.resolvables) { (idx, fn) =>
        idx.updated(fn)
      }

      state.withModule(module.copy(members = newMembers, resolvables = newResolvables))
