package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Completes closure environment destructor bodies after ownership analysis. */
object ClosureDestructorBodyGenerator:
  /** Complete helper bodies using ownership witnesses recorded on move captures. */
  def rewriteModule(state: CompilerState): CompilerState =
    val module = state.module
    val index  = module.resolvables
    val environments = module.members
      .collect { case b: Bnd => b }
      .flatMap { b =>
        TermTraversal.collect(b.value) { case l: Lambda if l.isMove => l }
      }
      .flatMap(l => l.meta.flatMap(_.envStructName).map(_ -> l))
      .toMap
    val members = module.members.map {
      case b: Bnd =>
        b.value.terms match
          case List(l: Lambda) =>
            l.body.terms match
              case List(d: DestroyClosureEnvironment) =>
                val environment = index
                  .lookupType(d.layoutId)
                  .collect { case ts: TypeStruct => ts }
                  .flatMap(ts => environments.get(ts.name).map(ts -> _))
                environment.fold(b) { (layout, lambda) =>
                  val fields =
                    layout.fields.toList.drop(1).zip(lambda.captures).flatMap { (field, cap) =>
                      field.id.flatMap { fieldId =>
                        cap match
                          case Capture.OwnedClosure(_, targetId) =>
                            FieldCleanup.Closure(fieldId, targetId).some
                          case _ =>
                            DestructionTargets
                              .forType(field.typeSpec, index)
                              .map(id => FieldCleanup.Value(fieldId, id))
                      }
                    }
                  ClosureDestructorAst.withBody(b, l, d.copy(fields = fields))
                }
              case _ => b
          case _ => b
      case member => member
    }
    state.withModule(module.copy(members = members))
