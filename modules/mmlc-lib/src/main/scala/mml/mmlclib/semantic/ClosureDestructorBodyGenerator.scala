package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Completes closure environment destructor bodies after ownership analysis. */
object ClosureDestructorBodyGenerator:

  private val phase = "closure-destructor-bodies"

  private def error(term: Term, message: String): SemanticError =
    SemanticError.InvalidExpression(Expr(term.source, List(term)), message, phase)

  private def cleanup(
    destruction: DestroyClosureEnvironment,
    environment: ClosureEnvironment,
    index:       ResolvablesIndex
  ): DestroyClosureEnvironment =
    val fields = environment.captureFields.flatMap { (field, capture) =>
      field.id.flatMap { fieldId =>
        capture match
          case _: Capture.BorrowedRef => none
          case Capture.OwnedClosure(_, targetId) =>
            FieldCleanup.Closure(fieldId, targetId).some
          case _: Capture.CapturedRef | _: Capture.CapturedLiteral |
              _: Capture.CapturedConditional =>
            DestructionTargets.forType(field.typeSpec, index).map(FieldCleanup.Value(fieldId, _))
      }
    }
    val borrowedFields = environment.captureFields
      .collect { case (field, _: Capture.BorrowedRef) =>
        field.id
      }
      .flatten
      .toSet
    destruction.copy(fields = fields, borrowedFields = borrowedFields)

  /** Complete helper bodies using ownership witnesses recorded on move captures. */
  def rewriteModule(state: CompilerState): CompilerState =
    val module   = state.module
    val index    = module.resolvables
    val recovery = ValueAvailability.fromModule(module)
    val lambdas = module.members
      .collect { case b: Bnd => b }
      .flatMap(binding => TermTraversal.collect(binding.value) { case lambda: Lambda => lambda })
      .filter { lambda =>
        lambda.meta.exists(_.envLayoutId.isDefined) ||
        recovery.needsClosureEnvironment(lambda)
      }
    val environments = ClosureEnvironments.analyze(lambdas, index)
    val invalidIds   = environments.errors.flatMap(_.lambda.meta.flatMap(_.envLayoutId)).toSet

    def complete(
      binding:     Bnd,
      helper:      Lambda,
      destruction: DestroyClosureEnvironment
    ): (Member, List[SemanticError]) =
      environments.byId.get(destruction.layoutId) match

        case Some(environment) if environment.lambda.isMove =>
          (
            ClosureDestructorAst.withBody(
              binding,
              helper,
              cleanup(destruction, environment, index)
            ),
            Nil
          )

        case Some(_) =>
          (
            binding,
            List(
              error(
                destruction,
                s"Destructor refers to a borrowed closure environment: ${destruction.layoutId}"
              )
            )
          )

        case None if invalidIds.contains(destruction.layoutId) => (binding, Nil)

        case None =>
          (
            binding,
            List(
              error(
                destruction,
                s"Missing move-lambda metadata for layout: ${destruction.layoutId}"
              )
            )
          )

    val results = module.members.map {
      case b: Bnd =>
        b.value.terms match
          case List(l: Lambda) =>
            l.body.terms match
              case List(d: DestroyClosureEnvironment) => complete(b, l, d)
              case _ => (b, Nil)
          case _ => (b, Nil)
      case member => (member, Nil)
    }
    state
      .withModule(module.copy(members = results.map(_._1)))
      .addErrors(environments.errors.map(e => error(e.lambda, e.message)) ++ results.flatMap(_._2))
