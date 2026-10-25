package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Proof used by ownership cleanup placement, independent of unfinished destructor bodies. */
private[semantic] object StorageOnlyDestruction:

  private val runtimeTargets = Set(
    "stdlib::bnd::__free_String",
    "stdlib::bnd::__free_Buffer",
    "stdlib::bnd::__free_Rng",
    "stdlib::bnd::__free_IntArray",
    "stdlib::bnd::__free_StringArray",
    "stdlib::bnd::__free_FloatArray",
    "stdlib::bnd::free_cstr",
    "stdlib::bnd::mml_free_raw"
  )

  /** Borrowed captures remain owned by their enclosing scope when an environment is released. */
  def ownedCaptures(lambda: Lambda): List[Ref] =
    if !lambda.isMove then Nil
    else
      lambda.captures.collect {
        case capture
            if !capture.isInstanceOf[Capture.BorrowedRef] &&
              !capture.ref.resolvedId
                .exists(id => lambda.meta.exists(_.borrowedCaptures.contains(id))) =>
          capture.ref
      }

  def closure(value: Ref, values: CallableValues): Boolean =

    def storageOnly(
      operand:       Term,
      tpe:           Type,
      activeLambdas: Set[CallableIdentity],
      activeTypes:   Set[String]
    ): Boolean =
      val index = values.index
      TypeUtils.canonical(tpe, index).exists {

        case _: TypeFn =>
          values
            .provenLambdas(operand)
            .exists(_.forall { lambda =>
              val identity = CallableIdentity(lambda)
              !activeLambdas.contains(identity) &&
              (lambda.captures.isEmpty || (lambda.isMove && ownedCaptures(lambda).forall { ref =>
                ref.typeSpec.exists(
                  storageOnly(ref, _, activeLambdas + identity, activeTypes)
                )
              }))
            })

        case ref: TypeRef =>
          ref.resolvedId.filterNot(activeTypes.contains).exists { id =>
            index.lookupType(id).exists {

              case struct: TypeStruct =>
                val generated = DestructionTargets
                  .forType(ref, index)
                  .flatMap(index.lookup)
                  .exists {
                    case b: Bnd => b.meta.exists(_.origin == BindingOrigin.Destructor)
                    case _ => false
                  }
                (!TypeUtils.requiresDestruction(ref, index) || generated) &&
                struct.fields.forall { field =>
                  val projection = Ref(
                    SourceOrigin.Synth,
                    field.name,
                    qualifier  = operand.some,
                    resolvedId = field.id,
                    typeSpec   = field.typeSpec.some
                  )
                  field.id.nonEmpty &&
                  storageOnly(projection, field.typeSpec, activeLambdas, activeTypes + id)
                }

              case _: TypeDef =>
                !TypeUtils.requiresDestruction(ref, index) ||
                DestructionTargets.forType(ref, index).exists(runtimeTargets.contains)

              case _ => false
            }
          }

        case _ => false
      }

    value.typeSpec.flatMap(TypeUtils.canonical(_, values.index)).exists {
      case tpe: TypeFn => storageOnly(value, tpe, Set.empty, Set.empty)
      case _ => false
    }
