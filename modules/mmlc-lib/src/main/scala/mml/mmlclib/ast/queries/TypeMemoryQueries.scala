package mml.mmlclib.ast.queries

import cats.syntax.option.*
import mml.mmlclib.ast.*

/** Destruction requirements, function containment, and names of type destructors. */
object TypeMemoryQueries:

  /** True for function types, allocating native definitions, and structs containing either. */
  def requiresDestruction(tpe: Type, index: ResolvablesIndex): Boolean =
    requiresDestruction(tpe, index, Set.empty)

  private def requiresDestruction(tpe: Type, index: ResolvablesIndex, seen: Set[String]): Boolean =
    TypeQueries.canonical(tpe, index).exists {
      case _: TypeFn => true
      case other => isHeapType(other, index, seen)
    }

  private def heapNativeType(definition: TypeDef): Option[NativeType] =
    definition.typeSpec.collect {
      case native: NativeType if native.memEffect.contains(MemEffect.Alloc) => native
    }

  /** True for allocating native definitions and structs with fields requiring destruction. */
  def isHeapType(tpe: Type, index: ResolvablesIndex): Boolean =
    isHeapType(tpe, index, Set.empty)

  private def isHeapType(tpe: Type, index: ResolvablesIndex, seen: Set[String]): Boolean =
    TypeQueries.declaration(tpe, index).exists {
      case definition: TypeDef => heapNativeType(definition).isDefined

      case struct: TypeStruct if !struct.id.exists(seen.contains) =>
        struct.fields.exists(field => requiresDestruction(field.typeSpec, index, seen ++ struct.id))

      case _ => false
    }

  /** True when a struct has fields requiring destruction, including through nested structs. */
  def hasHeapFields(struct: TypeStruct, index: ResolvablesIndex): Boolean =
    isHeapType(struct, index)

  /** True when the resolved declaration is a struct with fields requiring destruction. */
  def isStructWithHeapFields(tpe: Type, index: ResolvablesIndex): Boolean =
    TypeQueries.declaration(tpe, index).exists {
      case struct: TypeStruct => hasHeapFields(struct, index)
      case _ => false
    }

  /** Destructor name for an allocating native definition or heap struct. Native definitions use
    * their explicit destructor when present; default names use the resolved declaration's name.
    */
  def freeFnFor(tpe: Type, index: ResolvablesIndex): Option[String] =
    TypeQueries.declaration(tpe, index).flatMap {
      case definition: TypeDef =>
        heapNativeType(definition).map(_.freeFn.getOrElse(s"__free_${definition.name}"))

      case struct: TypeStruct if hasHeapFields(struct, index) => s"__free_${struct.name}".some

      case _ => none
    }

  /** True for a function type or a struct containing one, following aliases and nested fields. */
  def containsFunction(tpe: Type, index: ResolvablesIndex): Boolean =

    def loop(current: Type, seen: Set[String]): Boolean =
      TypeQueries.canonical(current, index).exists {
        case _: TypeFn => true

        case other =>
          TypeQueries.declaration(other, index).exists {
            case struct: TypeStruct if !struct.id.exists(seen.contains) =>
              struct.fields.exists(field => loop(field.typeSpec, seen ++ struct.id))

            case _ => false
          }
      }

    loop(tpe, Set.empty)
