package mml.mmlclib.semantic

import mml.mmlclib.ast.*

/** Resolved value flow preserves callable ownership through bindings, returns, and arguments. */
final case class CallableValues private (
  bindings: Map[String, List[Expr]],
  index:    ResolvablesIndex
):

  private case class Origin(lambda: Lambda, supplied: List[Expr] = Nil):
    def remainingParams: List[FnParam] = lambda.params.drop(supplied.size)

  private def origins(term: Term): List[Origin] =
    def resolve(value: Term, fields: List[String], seen: Set[String]): List[Origin] = value match
      case lambda: Lambda if fields.isEmpty => List(Origin(lambda))
      case expr:   Expr => expr.terms.lastOption.toList.flatMap(resolve(_, fields, seen))
      case group:  TermGroup => resolve(group.inner, fields, seen)
      case cond:   Cond => resolve(cond.ifTrue, fields, seen) ++ resolve(cond.ifFalse, fields, seen)
      case ref:    Ref if ref.qualifier.isDefined =>
        ref.resolvedId.toList.flatMap { fieldId =>
          ref.qualifier.toList.flatMap(resolve(_, fieldId :: fields, seen))
        }
      case ref: Ref =>
        ref.resolvedId.toList.filterNot(seen.contains).flatMap { id =>
          val sources = bindings.getOrElse(
            id,
            index
              .lookup(id)
              .collect { case bnd: Bnd =>
                bnd.value
              }
              .toList
          )
          sources.flatMap(resolve(_, fields, seen + id))
        }
      case app: App =>
        val (callee, arguments) = CallableValues.application(app)
        val calleeId = callee match
          case ref: Ref => ref.resolvedId
          case _:   Lambda => None
        resolve(callee, Nil, seen).flatMap { origin =>
          if arguments.size < origin.remainingParams.size then
            if fields.isEmpty then List(origin.copy(supplied = origin.supplied ++ arguments))
            else Nil
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
                case struct: TypeStruct => Some(struct)
                case _ => None
              position <- struct.fields.zipWithIndex.collectFirst {
                case (field, position) if field.id.contains(fieldId) => position
              }
              argument <- (origin.supplied ++ arguments).lift(position)
            yield argument
            constructorField match
              case Some(argument) => resolve(argument, fields.drop(1), seen)
              case None => resolve(origin.lambda.body, fields, seen ++ calleeId)
        }
      case _ => Nil

    resolve(term, Nil, Set.empty).distinct

  def lambdas(term: Term): List[Lambda] =
    origins(term).collect { case Origin(lambda, Nil) => lambda }

  def consumesOnCall(term: Term): Boolean =
    lambdas(term).exists(_.meta.exists(_.transferredCaptures.nonEmpty))

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
  val empty: CallableValues = CallableValues(Map.empty, ResolvablesIndex())
  def applications(term: Term): List[App] = term match
    case app: App =>
      val (callee, args) = application(app)
      app :: (applications(callee) ++ args.flatMap(applications))
    case other => TermTraversal.children(other).flatMap(applications)

  def application(app: App): (Ref | Lambda, List[Expr]) =
    @scala.annotation.tailrec
    def loop(fn: Ref | App | Lambda, args: List[Expr]): (Ref | Lambda, List[Expr]) = fn match
      case inner:  App => loop(inner.fn, inner.arg :: args)
      case callee: (Ref | Lambda) => (callee, args)
    loop(app.fn, List(app.arg))

  def fromModule(module: Module): CallableValues =
    val members = module.members.collect { case binding: Bnd => binding }
    val applications = members.flatMap { binding =>
      CallableValues.applications(binding.value)
    }
    val initial = members.flatMap(binding => binding.id.map(_ -> binding.value)) ++
      applications.flatMap { app =>
        app.fn match
          case lambda: Lambda => lambda.params.headOption.flatMap(_.id).map(_ -> app.arg)
          case _ => Nil
      }
    val values = initial.groupMap(_._1)(_._2)

    @scala.annotation.tailrec
    def propagate(current: Map[String, List[Expr]]): CallableValues =
      val flow = CallableValues(current, module.resolvables)
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
