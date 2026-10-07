package mml.mmlclib.semantic

import cats.data.{NonEmptyList, State}
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import BindingIds.Allocation

/** Supplied arguments become values before a partial application's lambda is created. Field
  * qualifiers retain their owners in local bindings around the field's consumer.
  */
object PartialApplicationElaborator:

  private case class PayloadBinding(local: LocalBindings.Local, value: Expr)

  private case class PreparedValue[+T <: Term](value: T, bindings: List[PayloadBinding] = Nil):

    def expression: Expr = value match
      case expr: Expr => expr
      case _ => Expr(value.source, List(value), typeSpec = value.typeSpec)

    def materialize: Term = bindings.foldRight[Term](value) { (binding, result) =>
      val body = Expr(result.source, List(result), typeSpec = result.typeSpec)
      LocalBindings.bind(binding.local.param, binding.value, body, result.source)
    }

  def rewriteModule(state: CompilerState): CompilerState =
    stabilizeCaptures(state)

  /** Generated PAPs can themselves be partially applied. Refresh their value flow and captures
    * until each enclosing PAP knows whether invoking its captured callee transfers ownership.
    */
  @scala.annotation.tailrec
  private def stabilizeCaptures(state: CompilerState): CompilerState =
    val values = CallableValues.fromModule(state.module)
    val allocation = state.module.members.traverse {
      case binding: Bnd =>
        val owner = BindingOwner.binding(state.module.name, binding.name)
        rewriteExpr(binding.value, owner, state.module.resolvables, values)
          .map(value => binding.copy(value = value): Member)
      case other => other.pure[Allocation]
    }
    val (supply, members) = allocation.run(state.bindingIds.include(state.module)).value
    val captured = CaptureAnalyzer.rewriteModule(
      state.copy(bindingIds = supply).withModule(state.module.copy(members = members))
    )
    val next = ResolvablesIndexer.rewriteModule(captured)
    if next.module.members == state.module.members then next
    else stabilizeCaptures(next)

  private def rewriteExpr(
    expr:   Expr,
    owner:  BindingOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): Allocation[Expr] =
    expr.terms
      .traverse(rewriteTerm(_, owner, index, values))
      .map(terms => expr.copy(terms = terms.map(_.materialize)))

  private def prepareExpr(
    expr:   Expr,
    owner:  BindingOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): Allocation[PreparedValue[Term]] =
    expr.terms match
      case List(term) => rewriteTerm(term, owner, index, values)
      case _ => rewriteExpr(expr, owner, index, values).map(PreparedValue(_))

  private def rewriteTerm(
    term:   Term,
    owner:  BindingOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): Allocation[PreparedValue[Term]] = term match
    case app: App =>
      app.fn match
        case scope: Lambda =>
          for
            nested <- BindingIds.within(scope.params, owner)
            body <- rewriteExpr(scope.body, nested, index, values)
            arg <- prepareExpr(app.arg, owner, index, values)
          yield PreparedValue(
            app.copy(fn = scope.copy(body = body), arg = arg.expression),
            arg.bindings
          )
        case _ =>
          val (callee, args) = CallableValues.application(app)
          val preparedCallee = callee match
            case ref:    Ref => rewriteRef(ref, owner, index, values)
            case lambda: Lambda => PreparedValue(lambda).pure[Allocation]
          for
            rewrittenArgs <- args.traverse(prepareExpr(_, owner, index, values))
            function <- preparedCallee
            result <- callee.typeSpec.flatMap(TypeUtils.canonical(_, index)).collect {
              case signature: TypeFn => signature
            } match
              // Fresh qualifier bindings need indexed callable origins before PAP capture analysis.
              case Some(signature)
                  if values.recovery.isAvailable(app) && function.bindings.isEmpty &&
                    rewrittenArgs.size < signature.paramTypes.length =>
                elaborate(app, function.value, rewrittenArgs, signature, owner, index, values)
              case _ =>
                val prepared =
                  if function.bindings.nonEmpty || rewrittenArgs.exists(_.bindings.nonEmpty) then
                    rewrittenArgs.traverse { arg =>
                      arg.value.typeSpec.fold(arg.pure[Allocation])(
                        prepareArgument(arg, _, owner, index)
                      )
                    }
                  else rewrittenArgs.pure[Allocation]
                prepared.map { args =>
                  PreparedValue(
                    rebuild(app, function.value, args.map(_.expression)),
                    args.flatMap(_.bindings) ++ function.bindings
                  )
                }
          yield result
    case lambda: Lambda =>
      val ownedCaptureIds = lambda.captures
        .map(_.ref)
        .filter { ref =>
          values.recovery.isAvailable(ref) &&
          ref.typeSpec.exists(TypeUtils.requiresDestruction(_, index))
        }
        .flatMap(_.resolvedId)
        .toSet
      val bodyTransfers = CaptureTransfers.consumedByBody(lambda, values).intersect(ownedCaptureIds)
      val meta = lambda.meta.orElse(Option.when(bodyTransfers.nonEmpty)(LambdaMeta())).map { meta =>
        val sourceBorrows =
          if lambda.isMove then Set.empty[String]
          else ownedCaptureIds -- meta.transferredCaptures
        val transferredCallees = lambda.captures
          .map(_.ref)
          .filter { ref =>
            ref.resolvedId.exists(meta.borrowedCaptures.contains) && values.consumesOnCall(ref) &&
            !values.isFieldBorrow(ref)
          }
          .flatMap(_.resolvedId)
          .toSet
        meta.copy(
          transferredCaptures = meta.transferredCaptures.intersect(ownedCaptureIds) ++
            transferredCallees ++ bodyTransfers,
          borrowedCaptures = (meta.borrowedCaptures.intersect(ownedCaptureIds) ++ sourceBorrows) --
            transferredCallees
        )
      }
      BindingIds
        .within(lambda.params, owner)
        .flatMap { nested =>
          rewriteExpr(lambda.body, nested, index, values)
        }
        .map { body =>
          PreparedValue(
            lambda.copy(
              body = body,
              meta = meta,
              isMove = lambda.isMove || meta.exists(m =>
                m.isPartialApplication && (m.transferredCaptures.nonEmpty || m.borrowedCaptures.isEmpty)
              )
            )
          )
        }
    case invalid: InvalidExpression =>
      rewriteExpr(invalid.originalExpr, owner, index, values)
        .map(original => PreparedValue(invalid.copy(originalExpr = original)))
    case expr:  Expr => prepareExpr(expr, owner, index, values)
    case group: TermGroup =>
      prepareExpr(group.inner, owner, index, values).map { inner =>
        inner.copy(value = group.copy(inner = inner.expression))
      }
    case cond: Cond =>
      for
        predicate <- rewriteExpr(cond.cond, owner, index, values)
        yes <- rewriteExpr(cond.ifTrue, owner, index, values)
        no <- rewriteExpr(cond.ifFalse, owner, index, values)
      yield PreparedValue(cond.copy(cond = predicate, ifTrue = yes, ifFalse = no))
    case tuple: Tuple =>
      tuple.elements
        .traverse(rewriteExpr(_, owner, index, values))
        .map(elements => PreparedValue(tuple.copy(elements = elements)))
    case ref: Ref => rewriteRef(ref, owner, index, values).map[PreparedValue[Term]](identity)
    case other => PreparedValue(other).pure[Allocation]

  /** A field borrow keeps its qualifier binding around the enclosing consumer. */
  private def rewriteRef(
    ref:    Ref,
    owner:  BindingOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): Allocation[PreparedValue[Ref]] =
    ref.qualifier.traverse(rewriteTerm(_, owner, index, values)).flatMap {

      case Some(qualifier) =>
        val prepared = qualifier.value.typeSpec
          .filter(TypeUtils.requiresDestruction(_, index))
          .fold(qualifier.pure[Allocation])(prepareArgument(qualifier, _, owner, index))
        prepared.map { result =>
          PreparedValue(ref.copy(qualifier = result.value.some), result.bindings)
        }

      case None => PreparedValue(ref).pure[Allocation]
    }

  private def appliedType(signature: TypeFn, count: Int): Type =
    NonEmptyList
      .fromList(signature.paramTypes.toList.drop(count))
      .fold(signature.returnType)(remaining => signature.copy(paramTypes = remaining))

  private def rebuild(app: App, callee: Ref | Lambda, args: List[Expr]): App =
    @scala.annotation.tailrec
    def nodes(current: App, outer: List[App]): List[App] = current.fn match
      case inner: App => nodes(inner, current :: outer)
      case _ => current :: outer
    nodes(app, Nil).zip(args).foldLeft[Ref | App | Lambda](callee) { case (fn, (original, arg)) =>
      original.copy(fn = fn, arg = arg)
    } match
      case result: App => result
      case _ => app

  private def fresh(
    owner:     BindingOwner,
    tpe:       Type,
    consuming: Boolean = false
  ): Allocation[LocalBindings.Local] =
    State.get[BindingIdSupply].flatMap { supply =>
      LocalBindings.local(
        owner,
        s"$$pap_${supply.next}",
        typeSpec  = tpe.some,
        consuming = consuming,
        purpose   = "pap"
      )
    }

  private def prepareArgument(
    arg:          PreparedValue[Term],
    tpe:          Type,
    owner:        BindingOwner,
    index:        ResolvablesIndex,
    forceBinding: Boolean = false
  ): Allocation[PreparedValue[Term]] =
    arg.value match
      case ref: Ref if ref.qualifier.isEmpty => arg.pure[Allocation]
      case _:   LiteralValue if !forceBinding => arg.pure[Allocation]
      case _ =>
        fresh(owner, tpe).map { local =>
          val value: Term =
            if TypeUtils.resolveNativeType(tpe, index).exists {
                case NativePrimitive(_, "void", _, _) => true
                case _ => false
              }
            then LiteralUnit(arg.value.source, tpe.some)
            else local.ref
          PreparedValue(value, arg.bindings :+ PayloadBinding(local, arg.expression))
        }

  private def elaborate(
    app:       App,
    callee:    Ref | Lambda,
    args:      List[PreparedValue[Term]],
    signature: TypeFn,
    owner:     BindingOwner,
    index:     ResolvablesIndex,
    values:    CallableValues
  ): Allocation[PreparedValue[Term]] =
    val params = values.parameters(callee)
    val remaining =
      signature.paramTypes.toList.zipWithIndex.drop(args.size).traverse { (tpe, position) =>
        fresh(owner, tpe, consuming = params.lift(position).exists(_.consuming))
      }
    val supplied =
      args.zip(signature.paramTypes.toList).zipWithIndex.traverse { case ((arg, tpe), position) =>
        val consumes =
          params.lift(position).exists(_.consuming) && TypeUtils.requiresDestruction(tpe, index)
        prepareArgument(arg, tpe, owner, index, forceBinding = consumes)
      }
    for
      remaining <- remaining
      supplied <- supplied
    yield
      val allArgs = supplied.map(_.expression) ++ remaining.map { local =>
        Expr(app.source, List(local.ref), typeSpec = local.param.typeSpec)
      }
      val fullApp = allArgs.zipWithIndex.foldLeft[Ref | App | Lambda](callee) {
        case (fn, (arg, position)) =>
          App(app.source, fn, arg, typeSpec = appliedType(signature, position + 1).some)
      }
      val payloads = supplied.zip(signature.paramTypes.toList).zipWithIndex.collect {
        case ((prepared, tpe), position) if TypeUtils.requiresDestruction(tpe, index) =>
          (prepared.value, params.lift(position).exists(_.consuming))
      }
      val consumesCallee = values.consumesOnCall(callee)
      val borrowsField   = values.isFieldBorrow(callee)
      val transferredCallee = callee match
        case ref: Ref if consumesCallee && !borrowsField => ref.resolvedId.toSet
        case _ => Set.empty[String]
      val transferred =
        payloads.collect { case (ref: Ref, true) => ref.resolvedId }.flatten.toSet ++
          transferredCallee
      val borrowed    = payloads.collect { case (ref: Ref, false) => ref.resolvedId }.flatten.toSet
      val borrowsHeap = borrowed.nonEmpty
      val calleeLambdas = values.lambdas(callee)
      val borrowsCallee = callee match
        case ref: Ref =>
          borrowsField ||
          (!ref.resolvedId.flatMap(index.lookup).exists(_.isInstanceOf[Bnd]) &&
            (calleeLambdas.isEmpty || calleeLambdas.exists(_.captures.nonEmpty)))
        case _: Lambda => true
      val borrowedCallee = callee match
        case ref: Ref if ref.qualifier.isDefined =>
          ref.qualifier.toList.flatMap { qualifier =>
            TermTraversal
              .collect(qualifier) {
                case owner: Ref if owner.qualifier.isEmpty => owner
              }
              .flatMap(_.resolvedId)
          }.toSet
        case ref: Ref if borrowsField || (borrowsCallee && !consumesCallee) => ref.resolvedId.toSet
        case _ => Set.empty[String]
      val lambda = Lambda(
        app.source,
        remaining.map(_.param),
        Expr(app.source, List(fullApp), typeSpec = signature.returnType.some),
        Nil,
        typeSpec = app.typeSpec,
        isMove   = transferred.nonEmpty || (!borrowsHeap && !borrowsCallee),
        meta = LambdaMeta(
          isPartialApplication = true,
          transferredCaptures  = transferred,
          borrowedCaptures     = borrowed ++ borrowedCallee
        ).some
      )
      PreparedValue(lambda, supplied.flatMap(_.bindings))
