package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.UUID

/** Supplied arguments become values before a partial application's lambda is created. */
object PartialApplicationElaborator:

  private case class PayloadBinding(local: SyntheticLocals.Local, value: Expr, tpe: Type)

  private case class PreparedValue(value: Term, bindings: List[PayloadBinding] = Nil):
    def expression: Expr = value match
      case expr: Expr => expr
      case _ => Expr(value.source, List(value), typeSpec = value.typeSpec)

    def materialize: Term = bindings.foldRight(value) { (binding, result) =>
      val body = Expr(result.source, List(result), typeSpec = result.typeSpec)
      val scopeType = result.typeSpec.map { tpe =>
        TypeFn(result.source, NonEmptyList.one(binding.tpe), tpe)
      }
      val scope = Lambda(
        result.source,
        List(binding.local.param),
        body,
        Nil,
        typeSpec = scopeType
      )
      App(result.source, scope, binding.value, typeSpec = result.typeSpec)
    }

  def rewriteModule(state: CompilerState): CompilerState =
    stabilizeCaptures(state)

  /** Generated PAPs can themselves be partially applied. Refresh their value flow and captures
    * until each enclosing PAP knows whether invoking its captured callee transfers ownership.
    */
  @scala.annotation.tailrec
  private def stabilizeCaptures(state: CompilerState): CompilerState =
    if state.hasErrors then state
    else
      val values = CallableValues.fromModule(state.module)
      val members = state.module.members.map {
        case binding: Bnd =>
          val owner = SyntheticOwner.binding(state.module.name, binding.name)
          binding.copy(value = rewriteExpr(binding.value, owner, state.module.resolvables, values))
        case other => other
      }
      val next =
        CaptureAnalyzer.rewriteModule(state.withModule(state.module.copy(members = members)))
      if next.module.members == state.module.members then next
      else stabilizeCaptures(next)

  private def rewriteExpr(
    expr:   Expr,
    owner:  SyntheticOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): Expr =
    expr.copy(terms = expr.terms.map(rewriteTerm(_, owner, index, values).materialize))

  private def prepareExpr(
    expr:   Expr,
    owner:  SyntheticOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): PreparedValue =
    expr.terms match
      case List(term) => rewriteTerm(term, owner, index, values)
      case _ => PreparedValue(rewriteExpr(expr, owner, index, values))

  private def rewriteTerm(
    term:   Term,
    owner:  SyntheticOwner,
    index:  ResolvablesIndex,
    values: CallableValues
  ): PreparedValue =
    term match
      case app: App =>
        app.fn match
          case scope: Lambda =>
            val body = rewriteExpr(scope.body, owner, index, values)
            val arg  = prepareExpr(app.arg, owner, index, values)
            PreparedValue(
              app.copy(fn = scope.copy(body = body), arg = arg.expression),
              arg.bindings
            )
          case _ =>
            val (callee, args) = CallableValues.application(app)
            val rewrittenArgs  = args.map(prepareExpr(_, owner, index, values))
            callee.typeSpec.flatMap(TypeUtils.canonical(_, index)).collect {
              case signature: TypeFn => signature
            } match
              case Some(signature) if rewrittenArgs.size < signature.paramTypes.length =>
                elaborate(app, callee, rewrittenArgs, signature, owner, index, values)
              case _ =>
                val preparedArgs =
                  if rewrittenArgs.exists(_.bindings.nonEmpty) then
                    rewrittenArgs.map { arg =>
                      arg.value.typeSpec.fold(arg)(prepareArgument(arg, _, owner, index))
                    }
                  else rewrittenArgs
                PreparedValue(
                  rebuild(app, callee, preparedArgs.map(_.expression)),
                  preparedArgs.flatMap(_.bindings)
                )
      case lambda: Lambda =>
        val ownedCaptureIds = lambda.captures
          .map(_.ref)
          .filter { ref =>
            ref.typeSpec.exists(TypeUtils.requiresDestruction(_, index))
          }
          .flatMap(_.resolvedId)
          .toSet
        val meta = lambda.meta.map { meta =>
          val sourceBorrows =
            if lambda.isMove then Set.empty[String]
            else ownedCaptureIds -- meta.transferredCaptures
          val transferredCallees = lambda.captures
            .map(_.ref)
            .filter { ref =>
              ref.resolvedId.exists(meta.borrowedCaptures.contains) && values.consumesOnCall(ref)
            }
            .flatMap(_.resolvedId)
            .toSet
          meta.copy(
            transferredCaptures = meta.transferredCaptures.intersect(ownedCaptureIds) ++
              transferredCallees,
            borrowedCaptures =
              (meta.borrowedCaptures.intersect(ownedCaptureIds) ++ sourceBorrows) --
                transferredCallees
          )
        }
        PreparedValue(
          lambda.copy(
            body = rewriteExpr(lambda.body, owner, index, values),
            meta = meta,
            isMove = lambda.isMove || meta.exists(m =>
              m.isPartialApplication &&
                (m.transferredCaptures.nonEmpty || m.borrowedCaptures.isEmpty)
            )
          )
        )
      case expr:  Expr => prepareExpr(expr, owner, index, values)
      case group: TermGroup =>
        val inner = prepareExpr(group.inner, owner, index, values)
        inner.copy(value = group.copy(inner = inner.expression))
      case cond: Cond =>
        PreparedValue(
          cond.copy(
            cond    = rewriteExpr(cond.cond, owner, index, values),
            ifTrue  = rewriteExpr(cond.ifTrue, owner, index, values),
            ifFalse = rewriteExpr(cond.ifFalse, owner, index, values)
          )
        )
      case tuple: Tuple =>
        PreparedValue(
          tuple.copy(elements = tuple.elements.map(rewriteExpr(_, owner, index, values)))
        )
      case ref: Ref =>
        PreparedValue(
          ref.copy(qualifier = ref.qualifier.map(rewriteTerm(_, owner, index, values).materialize))
        )
      case other => PreparedValue(other)

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
    owner:     SyntheticOwner,
    tpe:       Type,
    consuming: Boolean = false
  ): SyntheticLocals.Local =
    val name = s"$$pap_${UUID.randomUUID().toString.take(8)}"
    SyntheticLocals.local(owner, name, typeSpec = tpe.some, consuming = consuming)

  private def prepareArgument(
    arg:          PreparedValue,
    tpe:          Type,
    owner:        SyntheticOwner,
    index:        ResolvablesIndex,
    forceBinding: Boolean = false
  ): PreparedValue =
    arg.value match
      case ref: Ref if ref.qualifier.isEmpty => arg
      case _:   LiteralValue if !forceBinding => arg
      case _ =>
        val local = fresh(owner, tpe)
        val value: Term =
          if TypeUtils.resolveNativeType(tpe, index).exists {
              case NativePrimitive(_, "void", _, _) => true
              case _ => false
            }
          then LiteralUnit(arg.value.source, tpe.some)
          else local.ref
        PreparedValue(value, arg.bindings :+ PayloadBinding(local, arg.expression, tpe))

  private def elaborate(
    app:       App,
    callee:    Ref | Lambda,
    args:      List[PreparedValue],
    signature: TypeFn,
    owner:     SyntheticOwner,
    index:     ResolvablesIndex,
    values:    CallableValues
  ): PreparedValue =
    val params = values.parameters(callee)
    val remaining =
      signature.paramTypes.toList.zipWithIndex.drop(args.size).map { (tpe, position) =>
        fresh(owner, tpe, consuming = params.lift(position).exists(_.consuming))
      }
    val supplied =
      args.zip(signature.paramTypes.toList).zipWithIndex.map { case ((arg, tpe), position) =>
        val consumes =
          params.lift(position).exists(_.consuming) && TypeUtils.requiresDestruction(tpe, index)
        prepareArgument(arg, tpe, owner, index, forceBinding = consumes)
      }
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
    val transferredCallee = callee match
      case ref: Ref if consumesCallee => ref.resolvedId.toSet
      case _ => Set.empty[String]
    val transferred = payloads.collect { case (ref: Ref, true) => ref.resolvedId }.flatten.toSet ++
      transferredCallee
    val borrowed      = payloads.collect { case (ref: Ref, false) => ref.resolvedId }.flatten.toSet
    val borrowsHeap   = borrowed.nonEmpty
    val calleeLambdas = values.lambdas(callee)
    val borrowsCallee = callee match
      case ref: Ref =>
        !ref.resolvedId.flatMap(index.lookup).exists(_.isInstanceOf[Bnd]) &&
        (calleeLambdas.isEmpty || calleeLambdas.exists(_.captures.nonEmpty))
      case _: Lambda => true
    val borrowedCallee = callee match
      case ref: Ref if borrowsCallee && !consumesCallee => ref.resolvedId.toSet
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
