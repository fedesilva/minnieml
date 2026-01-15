package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Rebuilds the resolvables index to include local function parameters. */
object ResolvablesIndexer:

  def rewriteModule(state: CompilerState): CompilerState =
    val module             = state.module
    val updatedResolvables = rebuildIndex(module)
    state.withModule(module.copy(resolvables = updatedResolvables))

  private def rebuildIndex(module: Module): ResolvablesIndex =
    module.members.foldLeft(ResolvablesIndex()) { (idx, member) =>
      updateIndexForMember(idx, member)
    }

  private def updateIndexForMember(idx: ResolvablesIndex, member: Member): ResolvablesIndex =
    member match
      case bnd: Bnd =>
        val params = collectParamsFromExpr(bnd.value)
        idx.updated(bnd).updatedAll(params)
      case td: TypeDef => idx.updatedType(td)
      case ta: TypeAlias => idx.updatedType(ta)
      case ts: TypeStruct => idx.updatedType(ts)
      case dm: DuplicateMember => updateIndexForMember(idx, dm.originalMember)
      case im: InvalidMember => updateIndexForMember(idx, im.originalMember)
      case _ => idx

  private def collectParamsFromExpr(expr: Expr): List[FnParam] =
    expr.terms.flatMap(collectParamsFromTerm)

  private def collectParamsFromTerm(term: Term): List[FnParam] =
    term match
      case lambda: Lambda =>
        lambda.params ++ collectParamsFromExpr(lambda.body)
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case cond: Cond =>
        collectParamsFromExpr(cond.cond) ++
          collectParamsFromExpr(cond.ifTrue) ++
          collectParamsFromExpr(cond.ifFalse)
      case group: TermGroup =>
        collectParamsFromExpr(group.inner)
      case tuple: Tuple =>
        tuple.elements.toList.flatMap(collectParamsFromExpr)
      case expr: Expr =>
        collectParamsFromExpr(expr)
      case inv: InvalidExpression =>
        collectParamsFromExpr(inv.originalExpr)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectParamsFromTerm)
      case _ => Nil

  private def collectParamsFromAppFn(fn: Ref | App | Lambda): List[FnParam] =
    fn match
      case lambda: Lambda =>
        lambda.params ++ collectParamsFromExpr(lambda.body)
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectParamsFromTerm)
