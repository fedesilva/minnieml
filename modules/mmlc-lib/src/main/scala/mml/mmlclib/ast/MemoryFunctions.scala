package mml.mmlclib.ast

/** Memory operations selected from the owning nominal declaration. */
object MemoryFunctions:

  final case class Target(name: String, id: String)

  def free(
    tpe:              Type,
    index:            ResolvablesIndex,
    generatingModule: Option[String] = None
  ): Option[Target] =
    TypeUtils
      .freeFnFor(tpe, index)
      .flatMap(resolve(tpe, _, index, generatingModule, allowImportedDestructor = true))

  def clone(
    tpe:              Type,
    index:            ResolvablesIndex,
    generatingModule: Option[String] = None
  ): Option[Target] =
    TypeUtils.cloneFnFor(tpe, index).flatMap(resolve(tpe, _, index, generatingModule))

  private def resolve(
    tpe:                     Type,
    name:                    String,
    index:                   ResolvablesIndex,
    generatingModule:        Option[String],
    allowImportedDestructor: Boolean = false
  ): Option[Target] =
    TypeUtils.declaration(tpe, index).flatMap { declaration =>

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

        val importedDestructor = TypeUtils
          .resolveNativeType(tpe, index)
          .filter(native => allowImportedDestructor && native.freeFn.contains(name))
          .flatMap(_ => registered(s"stdlib::bnd::$name"))

        registered(id)
          .orElse(Option.when(pending)(id))
          .orElse(importedDestructor)
          .map(Target(name, _))
      }
    }
