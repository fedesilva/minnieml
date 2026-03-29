package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Populates `Lambda.captures` for lambda literals that reference bindings from enclosing scopes.
  *
  * Runs after RefResolver (all refs have resolvedId). Walks each top-level Bnd, tracking local
  * scope (params introduced by let-desugaring chains). When a real lambda literal is found (any
  * Lambda NOT in App.fn position), collects refs whose resolvedId points to a local-scope param
  * rather than a module-level member or the lambda's own params.
  *
  * For nested lambdas, inner captures that reference bindings outside the enclosing lambda
  * propagate outward: the enclosing lambda must also capture them.
  */
object CaptureAnalyzer:

  def rewriteModule(state: CompilerState): CompilerState =
    val moduleIds      = collectModuleIds(state.module)
    val updatedMembers = state.module.members.map(rewriteMember(_, moduleIds))
    state.withModule(state.module.copy(members = updatedMembers))

  private def collectModuleIds(module: Module): Set[String] =
    module.members.collect { case bnd: Bnd => bnd.id }.flatten.toSet

  private def rewriteMember(member: Member, moduleIds: Set[String]): Member =
    member match
      case bnd: Bnd =>
        bnd.value.terms match
          case (lambda: Lambda) :: rest =>
            val ownParamIds = lambda.params.flatMap(_.id).toSet
            val newBody     = analyzeExpr(lambda.body, ownParamIds, moduleIds)
            if newBody ne lambda.body then
              val updated = lambda.copy(body = newBody)
              bnd.copy(value = bnd.value.copy(terms = updated :: rest))
            else bnd
          case _ => bnd
      case other => other

  private def analyzeExpr(
    expr:      Expr,
    localIds:  Set[String],
    moduleIds: Set[String]
  ): Expr =
    val newTerms = expr.terms.map(analyzeTerm(_, localIds, moduleIds))
    if termsChanged(expr.terms, newTerms) then expr.copy(terms = newTerms)
    else expr

  private def analyzeTerm(
    term:      Term,
    localIds:  Set[String],
    moduleIds: Set[String]
  ): Term =
    term match
      case app: App =>
        app.fn match
          // Let-desugaring: fn is a Lambda that extends scope
          case fnLambda: Lambda =>
            val newArg      = analyzeExpr(app.arg, localIds, moduleIds)
            val extendedIds = localIds ++ fnLambda.params.flatMap(_.id)
            val newBody     = analyzeExpr(fnLambda.body, extendedIds, moduleIds)
            val newFn =
              if newBody ne fnLambda.body then fnLambda.copy(body = newBody)
              else fnLambda
            if (newFn ne fnLambda) || (newArg ne app.arg) then app.copy(fn = newFn, arg = newArg)
            else app
          case _ =>
            val newFn  = analyzeAppFn(app.fn, localIds, moduleIds)
            val newArg = analyzeExpr(app.arg, localIds, moduleIds)
            if (newFn ne app.fn) || (newArg ne app.arg) then app.copy(fn = newFn, arg = newArg)
            else app

      // Real lambda literal — capture boundary
      case lambda: Lambda =>
        analyzeLambda(lambda, localIds, moduleIds)

      case cond: Cond =>
        val newC = analyzeExpr(cond.cond, localIds, moduleIds)
        val newT = analyzeExpr(cond.ifTrue, localIds, moduleIds)
        val newF = analyzeExpr(cond.ifFalse, localIds, moduleIds)
        if (newC ne cond.cond) || (newT ne cond.ifTrue) ||
          (newF ne cond.ifFalse)
        then cond.copy(cond = newC, ifTrue = newT, ifFalse = newF)
        else cond

      case e: Expr =>
        analyzeExpr(e, localIds, moduleIds)

      case group: TermGroup =>
        val newInner = analyzeExpr(group.inner, localIds, moduleIds)
        if newInner ne group.inner then group.copy(inner = newInner)
        else group

      case t: Tuple =>
        val newElems =
          t.elements.map(analyzeExpr(_, localIds, moduleIds))
        if elementsChanged(t.elements.toList, newElems.toList) then t.copy(elements = newElems)
        else t

      case _ => term

  /** Analyze a real lambda literal. Collects captures and processes nested lambdas.
    */
  private def analyzeLambda(
    lambda:    Lambda,
    localIds:  Set[String],
    moduleIds: Set[String]
  ): Lambda =
    val ownParamIds   = lambda.params.flatMap(_.id).toSet
    val innerLocalIds = localIds ++ ownParamIds
    val newBody       = analyzeExpr(lambda.body, innerLocalIds, moduleIds)

    // Direct captures: refs in body pointing to localIds, not own params
    val directCaptures =
      collectCaptureRefs(newBody, localIds, ownParamIds, moduleIds)

    // Propagated: nested lambdas may capture from our enclosing scope
    val nestedCaptures =
      collectNestedCaptures(newBody, localIds, ownParamIds)

    val allCaptures = deduplicateRefs(directCaptures ++ nestedCaptures)

    if allCaptures.nonEmpty || (newBody ne lambda.body) then
      lambda.copy(body = newBody, captures = allCaptures.map(Capture.CapturedRef(_)))
    else lambda

  private def collectCaptureRefs(
    expr:       Expr,
    targetIds:  Set[String],
    excludeIds: Set[String],
    moduleIds:  Set[String]
  ): List[Ref] =
    collectRefsFromExpr(expr).filter { ref =>
      ref.resolvedId.exists { id =>
        targetIds.contains(id) &&
        !excludeIds.contains(id) &&
        !moduleIds.contains(id)
      }
    }

  private def collectNestedCaptures(
    expr:        Expr,
    localIds:    Set[String],
    ownParamIds: Set[String]
  ): List[Ref] =
    collectNestedLambdas(expr).flatMap { nested =>
      nested.captures.map(_.ref).filter { ref =>
        ref.resolvedId.exists { id =>
          localIds.contains(id) && !ownParamIds.contains(id)
        }
      }
    }

  /** Collect all Refs from an expression, descending into let-desugarings but stopping at real
    * lambda boundaries (those are handled separately via nested capture propagation).
    */
  private def collectRefsFromExpr(expr: Expr): List[Ref] =
    expr.terms.flatMap(collectRefsFromTerm)

  private def collectRefsFromTerm(term: Term): List[Ref] =
    term match
      case ref: Ref =>
        val qualifierRefs = ref.qualifier.toList.flatMap(collectRefsFromTerm)
        ref :: qualifierRefs
      case e:   Expr => collectRefsFromExpr(e)
      case app: App =>
        app.fn match
          case fnLambda: Lambda =>
            val letParamIds = fnLambda.params.flatMap(_.id).toSet
            collectRefsFromExpr(app.arg) ++
              collectRefsFromExpr(fnLambda.body).filterNot { ref =>
                ref.resolvedId.exists(letParamIds.contains)
              }
          case _ =>
            collectRefsFromAppFn(app.fn) ++
              collectRefsFromExpr(app.arg)
      // Stop at real lambda boundaries
      case _:    Lambda => Nil
      case cond: Cond =>
        collectRefsFromExpr(cond.cond) ++
          collectRefsFromExpr(cond.ifTrue) ++
          collectRefsFromExpr(cond.ifFalse)
      case group: TermGroup => collectRefsFromExpr(group.inner)
      case t:     Tuple =>
        t.elements.toList.flatMap(collectRefsFromExpr)
      case _ => Nil

  private def collectRefsFromAppFn(fn: Ref | App | Lambda): List[Ref] =
    fn match
      case ref: Ref =>
        val qualifierRefs = ref.qualifier.toList.flatMap(collectRefsFromTerm)
        ref :: qualifierRefs
      case app: App =>
        collectRefsFromAppFn(app.fn) ++ collectRefsFromExpr(app.arg)
      case _: Lambda => Nil

  /** Find nested real lambda literals (not let-desugaring fn-lambdas). */
  private def collectNestedLambdas(expr: Expr): List[Lambda] =
    expr.terms.flatMap(collectNestedLambdasTerm)

  private def collectNestedLambdasTerm(term: Term): List[Lambda] =
    term match
      case lambda: Lambda => List(lambda)
      case app:    App =>
        app.fn match
          case fnLambda: Lambda =>
            collectNestedLambdas(app.arg) ++
              collectNestedLambdas(fnLambda.body)
          case _ =>
            collectNestedLambdasAppFn(app.fn) ++
              collectNestedLambdas(app.arg)
      case cond: Cond =>
        collectNestedLambdas(cond.cond) ++
          collectNestedLambdas(cond.ifTrue) ++
          collectNestedLambdas(cond.ifFalse)
      case e:     Expr => collectNestedLambdas(e)
      case group: TermGroup => collectNestedLambdas(group.inner)
      case t:     Tuple =>
        t.elements.toList.flatMap(collectNestedLambdas)
      case _ => Nil

  private def collectNestedLambdasAppFn(
    fn: Ref | App | Lambda
  ): List[Lambda] =
    fn match
      case _:   Ref => Nil
      case app: App =>
        collectNestedLambdasAppFn(app.fn) ++
          collectNestedLambdas(app.arg)
      case lambda: Lambda => List(lambda)

  private def analyzeAppFn(
    fn:        Ref | App | Lambda,
    localIds:  Set[String],
    moduleIds: Set[String]
  ): Ref | App | Lambda =
    fn match
      case ref: Ref => ref
      case app: App =>
        val newFn  = analyzeAppFn(app.fn, localIds, moduleIds)
        val newArg = analyzeExpr(app.arg, localIds, moduleIds)
        if (newFn ne app.fn) || (newArg ne app.arg) then app.copy(fn = newFn, arg = newArg)
        else app
      case lambda: Lambda =>
        analyzeLambda(lambda, localIds, moduleIds)

  // mutable set is used only locally within deduplication
  private def deduplicateRefs(refs: List[Ref]): List[Ref] =
    val seen = scala.collection.mutable.Set.empty[String]
    refs.filter { ref =>
      ref.resolvedId.exists(seen.add)
    }

  private def termsChanged(
    old:     List[Term],
    updated: List[Term]
  ): Boolean =
    old.zip(updated).exists { case (o, n) => o ne n }

  private def elementsChanged(
    old:     List[Expr],
    updated: List[Expr]
  ): Boolean =
    old.zip(updated).exists { case (o, n) => o ne n }
