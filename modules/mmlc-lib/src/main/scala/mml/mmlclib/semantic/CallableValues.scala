package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Resolved value flow preserves callable ownership through bindings, returns, and arguments. */
final case class CallableValues private (
  bindings:                    Map[String, List[Expr]],
  private val lexicalBindings: Map[String, List[Expr]],
  index:                       ResolvablesIndex,
  recovery:                    ValueAvailability
):

  /** Supplied arguments retain the substitutions and cycle guard from their call site. */
  private case class LexicalValue(
    value:       Expr,
    environment: Map[String, LexicalValue],
    seen:        Set[String]
  )

  private case class Origin(
    lambda:      Lambda,
    supplied:    List[LexicalValue]        = Nil,
    environment: Map[String, LexicalValue] = Map.empty
  ):
    def remainingParams: List[FnParam] = lambda.params.drop(supplied.size)

    /** A nullary callable's synthetic Unit input has no consuming source parameter. */
    def parameterOwnership: List[Boolean] =
      if lambda.params.isEmpty then List(false).drop(supplied.size)
      else remainingParams.map(_.consuming)

  /** Known targets remain usable for flow even when another alternative is unresolved. */
  private case class OriginResult(
    known:      List[Origin],
    complete:   Boolean,
    references: List[Ref] = Nil
  ):

    def ++(other: OriginResult): OriginResult =
      OriginResult(
        known ++ other.known,
        complete && other.complete,
        references ++ other.references
      )

    def flatMap(f: Origin => OriginResult): OriginResult =
      known.map(f).foldLeft(OriginResult(Nil, complete))(_ ++ _)

    /** An unresolved alternative cannot inherit a contract from the known subset. */
    def hasConsistentParameterOwnership: Boolean =
      known.isEmpty || (complete && known.map(_.parameterOwnership).distinct.size <= 1)

  private val unknownOrigins = OriginResult(Nil, complete = false)

  private def resolveOrigins(
    term:          Term,
    bindingValues: Map[String, List[Expr]],
    exactCalls:    Boolean
  ): OriginResult =

    def resolve(
      value:       Term,
      fields:      List[String],
      seen:        Set[String],
      environment: Map[String, LexicalValue]
    ): OriginResult =
      value match

        case _ if !recovery.isAvailable(value) => unknownOrigins

        case lambda: Lambda if fields.isEmpty =>
          OriginResult(List(Origin(lambda, environment = environment)), complete = true)

        case expr: Expr =>
          expr.terms.lastOption.fold(unknownOrigins)(resolve(_, fields, seen, environment))

        case group:   TermGroup => resolve(group.inner, fields, seen, environment)
        case yielded: YieldOwnership => resolve(yielded.value, fields, seen, environment)
        case binding: BindOwnership => resolve(binding.body, fields, seen, environment)

        case cond: Cond =>
          resolve(cond.ifTrue, fields, seen, environment) ++
            resolve(cond.ifFalse, fields, seen, environment)

        case ref: Ref if ref.qualifier.isDefined =>
          val result = (ref.resolvedId, ref.qualifier)
            .mapN((fieldId, qualifier) => resolve(qualifier, fieldId :: fields, seen, environment))
            .getOrElse(unknownOrigins)
          if fields.isEmpty then result.copy(references = ref :: result.references) else result

        case ref: Ref =>
          val result = ref.resolvedId.filterNot(seen.contains).fold(unknownOrigins) { id =>
            environment.get(id) match

              case Some(source) =>
                val callerSeen = if exactCalls then source.seen else seen + id
                resolve(source.value, fields, callerSeen, source.environment)

              case None =>
                val sources = bindingValues.getOrElse(
                  id,
                  index.lookup(id).collect { case bnd: Bnd => bnd.value }.toList
                )
                sources
                  .map(resolve(_, fields, seen + id, environment))
                  .reduceOption(_ ++ _)
                  .getOrElse(unknownOrigins)
          }
          if fields.isEmpty then result.copy(references = ref :: result.references) else result

        case app: App =>
          stages(app).lastOption.fold(unknownOrigins) { stage =>
            val calleeId = stage.callee match
              case ref: Ref => ref.resolvedId
              case _ => none

            resolve(stage.callee, Nil, seen, environment).flatMap { origin =>
              val callerSeen = if exactCalls then seen else Set.empty[String]
              val supplied =
                origin.supplied ++ stage.arguments.map(LexicalValue(_, environment, callerSeen))
              if !stage.complete then
                if fields.isEmpty &&
                  stage.signature.exists(_.paramTypes.size > stage.arguments.size) &&
                  stage.arguments.forall(recovery.isAvailable)
                then OriginResult(List(origin.copy(supplied = supplied)), complete = true)
                else unknownOrigins
              else
                val constructorField = for
                  fieldId <- fields.headOption
                  marker <- origin.lambda.body.terms.collectFirst { case dc: DataConstructor => dc }
                  tpe <- marker.typeSpec.flatMap(TypeUtils.canonical(_, index))
                  struct <- tpe match
                    case ref: TypeRef =>
                      ref.resolvedId.flatMap(index.lookupType).collect { case struct: TypeStruct =>
                        struct
                      }
                    case struct: TypeStruct => struct.some
                    case _ => none
                  position <- struct.fields.zipWithIndex.collectFirst {
                    case (field, position) if field.id.contains(fieldId) => position
                  }
                  argument <- supplied.lift(position)
                yield argument

                constructorField match
                  case Some(argument) =>
                    val argumentSeen = if exactCalls then argument.seen else seen
                    resolve(argument.value, fields.drop(1), argumentSeen, argument.environment)

                  case None =>
                    val bodyEnvironment =
                      if exactCalls then
                        origin.environment ++ origin.lambda.params.zip(supplied).flatMap {
                          (parameter, argument) => parameter.id.map(_ -> argument)
                        }
                      else Map.empty[String, LexicalValue]

                    resolve(origin.lambda.body, fields, seen ++ calleeId, bodyEnvironment)
            }
          }

        case _ => unknownOrigins

    resolve(term, Nil, Set.empty, Map.empty)

  private def origins(term: Term): List[Origin] =
    resolveOrigins(term, bindings, exactCalls = false).known.distinct

  def lambdas(term: Term): List[Lambda] =
    origins(term).collect { case Origin(lambda, Nil, _) => lambda }

  /** A proof requires every lexical alternative; observed call arguments cannot close a formal. */
  def provenLambdas(term: Term): Option[List[Lambda]] =
    val result = resolveOrigins(term, lexicalBindings, exactCalls = true)
    Option
      .when(result.complete)(result.known)
      .flatMap:
        _.traverse {
          case Origin(lambda, Nil, _) => lambda.some
          case _ => none
        }
      .map(_.distinctBy(CallableIdentity(_)))
      .filter(_.nonEmpty)

  /** Result aliases retain their identities and exact lexical sources through invoked bodies. */
  def resultReferences(term: Term): List[Ref] =
    resolveOrigins(term, lexicalBindings, exactCalls = true).references.distinct

  /** Applications retain their typed call boundaries independently of callable origin facts. */
  def stages(app: App): List[CallStages.Stage] = CallStages(app, index, recovery)

  /** Materialized operands retain their lexical facts without widening formal-argument flow. */
  def withBindings(locals: List[(FnParam, Expr)]): CallableValues =
    locals.foldLeft(this) { case (flow, (parameter, value)) =>
      parameter.id.fold(flow) { id =>
        val updatedIndex = flow.index.updated(parameter)
        flow.copy(
          bindings        = flow.bindings.updated(id, List(value)),
          lexicalBindings = flow.lexicalBindings.updated(id, List(value)),
          index           = updatedIndex,
          recovery        = flow.recovery.withBinding(parameter, value)
        )
      }
    }

  /** The final completed stage determines the result contract, including nullary invocations. */
  def invokedLambdas(app: App): List[Lambda] =
    stages(app).lastOption
      .filter(_.complete)
      .toList
      .flatMap(stage => origins(stage.callee).map(_.lambda))
      .distinctBy(CallableIdentity(_))

  /** Invocation proofs include residual origins but require every lexical alternative. */
  def provenInvokedLambdas(app: App): Option[List[Lambda]] =
    stages(app).lastOption.filter(_.complete).flatMap { stage =>
      val result = resolveOrigins(stage.callee, lexicalBindings, exactCalls = true)
      Option
        .when(result.complete)(result.known.map(_.lambda).distinctBy(CallableIdentity(_)))
        .filter(_.nonEmpty)
    }

  def consumesOnCall(term: Term): Boolean =
    lambdas(term).exists(_.meta.exists(_.transferredCaptures.nonEmpty))

  /** A projected value retains its aggregate loan through aliases and local results. */
  def isFieldBorrow(term: Term): Boolean =

    def resolve(value: Term, seen: Set[String]): Boolean = value match

      case _ if !recovery.isAvailable(value) => false
      case ref: Ref if ref.qualifier.isDefined => true

      case ref: Ref =>
        ref.resolvedId.filterNot(seen.contains).exists { id =>
          val sources = bindings.getOrElse(
            id,
            index.lookup(id).collect { case binding: Bnd => binding.value }.toList
          )
          sources.exists(resolve(_, seen + id))
        }

      case expr:    Expr => expr.terms.lastOption.exists(resolve(_, seen))
      case group:   TermGroup => resolve(group.inner, seen)
      case yielded: YieldOwnership => resolve(yielded.value, seen)
      case binding: BindOwnership => resolve(binding.body, seen)
      case cond:    Cond => resolve(cond.ifTrue, seen) || resolve(cond.ifFalse, seen)

      case app: App =>
        stages(app).lastOption.filter(_.complete).exists { stage =>
          stage.callee match
            case lambda: Lambda => resolve(lambda.body, seen)
            case _ => false
        }

      case _ => false

    resolve(term, Set.empty)

  /** Include captures reached through callable operands when checking a value's last use. */
  def referencedCaptureIds(term: Term): Set[String] =
    @scala.annotation.tailrec
    def collect(pending: List[Ref], seen: Set[String]): Set[String] = pending match
      case Nil => seen
      case ref :: rest if ref.resolvedId.forall(seen.contains) => collect(rest, seen)
      case ref :: rest =>
        val captures = lambdas(ref).flatMap(_.captures.map(_.ref))
        collect(captures ++ rest, seen ++ ref.resolvedId)

    collect(TermTraversal.collect(term) { case ref: Ref => ref }, Set.empty)

  /** Exact substitutions can close conservative cycle edges in observed parameter flow. */
  private def parameterOrigins(term: Term): OriginResult =
    val observed = resolveOrigins(term, bindings, exactCalls = false)
    if observed.complete then observed
    else
      val lexical = resolveOrigins(term, lexicalBindings, exactCalls = true)
      if lexical.complete then lexical else observed

  def hasConsistentParameterOwnership(term: Term): Boolean =
    parameterOrigins(term).hasConsistentParameterOwnership

  def parameters(term: Term): List[FnParam] =
    // A source PAP has a residual signature even before its lambda is elaborated.
    val candidates = parameterOrigins(term)
    if candidates.hasConsistentParameterOwnership then
      candidates.known.headOption.toList.flatMap(_.remainingParams)
    else Nil

object CallableValues:
  val empty: CallableValues =
    CallableValues(Map.empty, Map.empty, ResolvablesIndex(), ValueAvailability.empty)
  def applications(term: Term): List[App] = term match
    case app: App =>
      val (callee, args) = application(app)
      app :: (applications(callee) ++ args.flatMap(applications))
    case other => TermTraversal.children(other).flatMap(applications)

  def application(app: App): (Ref | Lambda, List[Expr]) =
    @scala.annotation.tailrec
    def loop(fn: Ref | App | Lambda, args: List[Expr]): (Ref | Lambda, List[Expr]) =
      fn match
        case inner:  App => loop(inner.fn, inner.arg :: args)
        case callee: (Ref | Lambda) => (callee, args)
    loop(app.fn, List(app.arg))

  def fromModule(module: Module): CallableValues =
    val recovery = ValueAvailability.fromModule(module)
    val members  = module.members.collect { case binding: Bnd => binding }
    val applications = members.flatMap { binding =>
      CallableValues.applications(binding.value)
    }
    val initial = members.flatMap(binding => binding.id.map(_ -> binding.value)) ++
      members.flatMap { member =>
        TermTraversal
          .collect(member.value) { case binding: BindOwnership =>
            binding.valueParam.id.map(_ -> binding.initializer).toList
          }
          .flatten
      } ++
      applications.flatMap { app =>
        CallStages(app, module.resolvables, recovery).flatMap { stage =>
          stage.callee match
            case lambda: Lambda =>
              lambda.params.zip(stage.arguments).flatMap((param, arg) => param.id.map(_ -> arg))
            case _ => Nil
        }
      }
    val values = initial.groupMap(_._1)(_._2)

    @scala.annotation.tailrec
    def propagate(current: Map[String, List[Expr]]): CallableValues =
      val flow = CallableValues(current, values, module.resolvables, recovery)
      val incoming = applications.flatMap { app =>
        flow.stages(app).flatMap { stage =>
          flow.origins(stage.callee).flatMap { origin =>
            origin.remainingParams
              .zip(stage.arguments)
              .flatMap((param, arg) => param.id.map(_ -> arg))
          }
        }
      }
      val next = (current.toList.flatMap((id, expressions) => expressions.map(id -> _)) ++ incoming)
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.distinct)
        .toMap
      if next == current then flow else propagate(next)
    propagate(values)
