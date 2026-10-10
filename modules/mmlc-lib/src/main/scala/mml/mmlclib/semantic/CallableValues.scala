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

  /** Known targets remain usable for flow even when another alternative is unresolved. */
  private case class OriginResult(known: List[Origin], complete: Boolean):

    def ++(other: OriginResult): OriginResult =
      OriginResult(known ++ other.known, complete && other.complete)

    def flatMap(f: Origin => OriginResult): OriginResult =
      known.map(f).foldLeft(OriginResult(Nil, complete))(_ ++ _)

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
          (ref.resolvedId, ref.qualifier)
            .mapN((fieldId, qualifier) => resolve(qualifier, fieldId :: fields, seen, environment))
            .getOrElse(unknownOrigins)

        case ref: Ref =>
          ref.resolvedId.filterNot(seen.contains).fold(unknownOrigins) { id =>
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

        case app: App =>
          val (callee, arguments) = CallableValues.application(app)
          val calleeId = callee match
            case ref: Ref => ref.resolvedId
            case _:   Lambda => none

          resolve(callee, Nil, seen, environment).flatMap { origin =>
            val callerSeen = if exactCalls then seen else Set.empty[String]
            val supplied =
              origin.supplied ++ arguments.map(LexicalValue(_, environment, callerSeen))
            if arguments.size < origin.remainingParams.size then
              if fields.isEmpty then
                OriginResult(
                  List(origin.copy(supplied = supplied)),
                  complete = true
                )
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
        val (callee, arguments) = CallableValues.application(app)
        callee match
          case lambda: Lambda if arguments.size >= lambda.params.size =>
            resolve(lambda.body, seen)
          case _ => false

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

  def hasConsistentParameterOwnership(term: Term): Boolean =
    origins(term).map(_.remainingParams.map(_.consuming)).distinct.size <= 1

  def parameters(term: Term): List[FnParam] =
    // A source PAP has a residual signature even before its lambda is elaborated.
    val candidates = origins(term).map(_.remainingParams)
    candidates.headOption.toList.flatten.zipWithIndex.map { (param, position) =>
      param.copy(consuming = candidates.exists(_.lift(position).exists(_.consuming)))
    }

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
        app.fn match
          case lambda: Lambda => lambda.params.headOption.flatMap(_.id).map(_ -> app.arg)
          case _ => Nil
      }
    val values = initial.groupMap(_._1)(_._2)

    @scala.annotation.tailrec
    def propagate(current: Map[String, List[Expr]]): CallableValues =
      val flow = CallableValues(current, values, module.resolvables, recovery)
      val incoming = applications.flatMap { app =>
        val (callee, args) = application(app)
        flow.origins(callee).flatMap { origin =>
          origin.remainingParams.zip(args).flatMap((param, arg) => param.id.map(_ -> arg))
        }
      }
      val next = (current.toList.flatMap((id, expressions) => expressions.map(id -> _)) ++ incoming)
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.distinct)
        .toMap
      if next == current then flow else propagate(next)
    propagate(values)
