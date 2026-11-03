package mml.mmlclib.ast.queries

import mml.mmlclib.ast.*

/** Memory operations selected from the owning nominal declaration. */
object MemoryFunctions:

  /** A destructor's lookup name and binding identity, which may be reserved during generation. */
  final case class Target(name: String, id: String)

  /** Selects the owning declaration's destructor, or an explicitly named native stdlib destructor.
    * Struct generation may use a pending helper only in generatingModule; other callers require an
    * indexed binding. Missing type identity or an unavailable destructor yields no target.
    */
  def free(
    tpe:              Type,
    index:            ResolvablesIndex,
    generatingModule: Option[String] = None
  ): Option[Target] =
    TypeMemoryQueries
      .freeFnFor(tpe, index)
      .flatMap(resolve(tpe, _, index, generatingModule))

  /** Prefers an indexed owner binding, then that owner's pending struct helper, then an explicit
    * native stdlib binding. A same-named binding in an unrelated module is not a candidate.
    */
  private def resolve(
    tpe:              Type,
    name:             String,
    index:            ResolvablesIndex,
    generatingModule: Option[String]
  ): Option[Target] =
    TypeQueries.declaration(tpe, index).flatMap { declaration =>

      val kind = declaration match
        case _: TypeDef => "typedef"
        case _: TypeStruct => "typestruct"
        case _: TypeAlias => "typealias"

      val suffix = s"::$kind::${declaration.name}"

      declaration.id.filter(_.endsWith(suffix)).flatMap { typeId =>
        val owner = typeId.stripSuffix(suffix)
        val id    = s"$owner::bnd::$name"
        val pending = declaration match
          case _: TypeStruct => generatingModule.contains(owner)
          case _ => false

        def registered(targetId: String): Option[String] =
          index.lookup(targetId).collect { case _: Bnd => targetId }

        val importedDestructor = NativeTypeQueries
          .resolveNativeType(tpe, index)
          .filter(native => native.freeFn.contains(name))
          .flatMap(_ => registered(s"stdlib::bnd::$name"))

        registered(id)
          .orElse(Option.when(pending)(id))
          .orElse(importedDestructor)
          .map(Target(name, _))
      }
    }
