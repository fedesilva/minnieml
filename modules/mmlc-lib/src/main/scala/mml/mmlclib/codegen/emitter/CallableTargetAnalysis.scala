package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.expression.collectArgsAndFunction

/** An emitted entry has a stable identity even when its closure environment varies at runtime. */
case class CallableDefinition(
  id:        String,
  lambda:    Lambda,
  signature: TypeFn,
  name:      String,
  ordinal:   Int,
  binding:   Option[Bnd] = None
)

/** Complete value-flow evidence. Unknown participates in every merge, including missing branches.
  * Parameter values come only from the application being inspected, never from observed callers.
  */
case class CallableTargetAnalysis(
  definitions: Map[String, CallableDefinition] = Map.empty,
  lambdas:     Map[Lambda, String]             = Map.empty,
  sources:     Map[String, Expr]               = Map.empty,
  index:       ResolvablesIndex                = ResolvablesIndex()
):

  private enum Value:
    case Unknown
    case Callable(id: String, captures: Map[String, Value])
    case Aggregate(fields: Map[String, Value])

  private def merge(left: Value, right: Value): Value = (left, right) match

    case (Value.Callable(a, ac), Value.Callable(b, bc)) if a == b =>
      Value.Callable(
        a,
        (ac.keySet ++ bc.keySet).map { id =>
          id -> merge(ac.getOrElse(id, Value.Unknown), bc.getOrElse(id, Value.Unknown))
        }.toMap
      )
    case (Value.Aggregate(a), Value.Aggregate(b)) if a.keySet == b.keySet =>
      Value.Aggregate(a.map((id, value) => id -> merge(value, b(id))))
    case _ => Value.Unknown

  private def evaluate(term: Term, scope: Map[String, Value], seen: Set[String]): Value = term match

    case lambda: Lambda =>
      lambdas.get(lambda).fold[Value](Value.Unknown) { id =>
        val captures = lambda.captures.flatMap { capture =>
          capture.ref.resolvedId.map(_ -> evaluate(capture.ref, scope, seen + id))
        }.toMap
        Value.Callable(id, captures)
      }
    case ref: Ref if ref.qualifier.isDefined =>
      (ref.resolvedId, ref.qualifier.map(evaluate(_, scope, seen))) match
        case (Some(id), Some(Value.Aggregate(fields))) => fields.getOrElse(id, Value.Unknown)
        case _ => Value.Unknown
    case ref: Ref =>
      ref.resolvedId.fold[Value](Value.Unknown) { id =>
        scope.get(id).getOrElse {
          if seen.contains(id) then Value.Unknown
          else sources.get(id).fold[Value](Value.Unknown)(evaluate(_, scope, seen + id))
        }
      }
    case expr:  Expr => expr.terms.lastOption.fold[Value](Value.Unknown)(evaluate(_, scope, seen))
    case group: TermGroup => evaluate(group.inner, scope, seen)
    case cond:  Cond =>
      merge(evaluate(cond.ifTrue, scope, seen), evaluate(cond.ifFalse, scope, seen))
    case app: App =>
      val (callee, args) = collectArgsAndFunction(app)
      val arguments      = args.map(evaluate(_, scope, seen))
      callee match
        case lambda: Lambda =>
          val parameters =
            lambda.params.zip(arguments).flatMap((param, value) => param.id.map(_ -> value)).toMap
          evaluate(lambda.body, scope ++ parameters, seen)
        case ref: Ref =>
          evaluate(ref, scope, seen) match
            case Value.Callable(id, captures) if !seen.contains(s"call:$id") =>
              definitions.get(id).fold[Value](Value.Unknown) { definition =>
                val params  = definition.lambda.params
                val nullary = params.isEmpty && args.size == 1
                if arguments.size != params.size && !nullary then Value.Unknown
                else
                  val parameters =
                    params.zip(arguments).flatMap((param, value) => param.id.map(_ -> value)).toMap
                  val constructor = definition.binding
                    .filter(_.meta.exists(_.origin == BindingOrigin.Constructor))
                    .flatMap(_.typeSpec.flatMap(resolveToTypeFn(_, index)))
                    .flatMap(signature => TypeUtils.canonical(signature.returnType, index))
                    .flatMap {
                      case struct: TypeStruct => struct.some
                      case ref:    TypeRef =>
                        ref.resolvedId.flatMap(index.lookupType).collect {
                          case struct: TypeStruct => struct
                        }
                      case _ => none
                    }
                  constructor match
                    case Some(struct) =>
                      Value.Aggregate(
                        struct.fields.toList
                          .zip(arguments)
                          .flatMap((field, value) => field.id.map(_ -> value))
                          .toMap
                      )
                    case None =>
                      evaluate(definition.lambda.body, captures ++ parameters, seen + s"call:$id")
              }
            case _ => Value.Unknown
    case _ => Value.Unknown

  /** Returns an entry only when all contributing value paths identify the same function body. */
  def target(term: Term): Option[String] = evaluate(term, Map.empty, Set.empty) match
    case Value.Callable(id, _) => id.some
    case _ => none

object CallableTargetAnalysis:

  /** Indexes immutable bindings and actual callable bodies; immediate scope lambdas stay inline. */
  def analyze(module: Module): CallableTargetAnalysis =

    def register(
      lambda:   Lambda,
      name:     String,
      id:       Option[String],
      binding:  Option[Bnd],
      analysis: CallableTargetAnalysis
    ): CallableTargetAnalysis =
      val ordinal  = analysis.definitions.size
      val identity = id.getOrElse(s"${module.name}::codegen::entry::$ordinal")
      binding
        .flatMap(_.typeSpec)
        .orElse(lambda.typeSpec)
        .flatMap(resolveToTypeFn(_, module.resolvables))
        .fold(visit(lambda.body, analysis)) { signature =>
          val definition = CallableDefinition(identity, lambda, signature, name, ordinal, binding)
          val registered = analysis.copy(
            definitions = analysis.definitions.updated(identity, definition),
            lambdas     = analysis.lambdas.updated(lambda, identity)
          )
          visit(lambda.body, registered)
        }

    def visit(term: Term, analysis: CallableTargetAnalysis): CallableTargetAnalysis = term match

      case app: App if app.fn.isInstanceOf[Lambda] =>
        app.fn match
          case lambda: Lambda =>
            val withSource = lambda.params.headOption.flatMap(_.id).fold(analysis) { id =>
              analysis.copy(sources = analysis.sources.updated(id, app.arg))
            }
            val withValue = app.arg.terms match
              case List(value: Lambda) =>
                val param = lambda.params.headOption
                register(value, param.fold("_anon")(_.name), param.flatMap(_.id), none, withSource)
              case _ => visit(app.arg, withSource)
            visit(lambda.body, withValue)
          case _ => analysis
      case lambda: Lambda => register(lambda, "_anon", none, none, analysis)
      case other =>
        TermTraversal.children(other).foldLeft(analysis)((current, child) => visit(child, current))

    module.members.foldLeft(CallableTargetAnalysis(index = module.resolvables)) {
      case (analysis, binding: Bnd) =>
        val withSource =
          binding.id.fold(analysis)(id =>
            analysis.copy(sources = analysis.sources.updated(id, binding.value))
          )
        binding.value.terms match
          case List(lambda: Lambda) =>
            register(lambda, binding.name, binding.id, binding.some, withSource)
          case _ => visit(binding.value, withSource)
      case (analysis, _) => analysis
    }
