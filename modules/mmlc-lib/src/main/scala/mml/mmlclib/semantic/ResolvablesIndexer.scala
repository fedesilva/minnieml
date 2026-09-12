package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Rebuilds the resolvables index to include local function parameters. */
object ResolvablesIndexer:

  def rewriteModule(state: CompilerState): CompilerState =
    val module             = state.module
    val updatedResolvables = rebuildIndex(module)
    state.withModule(module.copy(resolvables = updatedResolvables))

  def refresh(module: Module): Module = module.copy(resolvables = rebuildIndex(module))

  /** Definition occurrences are collected independently of the map, including duplicate IDs. */
  def definitions(module: Module): List[Resolvable] =
    def memberDefinitions(member: Member): List[Resolvable] = member match
      case binding:    Bnd => binding :: collectParamsFromExpr(binding.value)
      case struct:     TypeStruct => struct :: struct.fields.toList
      case definition: TypeDef => List(definition)
      case alias:      TypeAlias => List(alias)
      case duplicate:  DuplicateMember => memberDefinitions(duplicate.originalMember)
      case invalid:    InvalidMember => memberDefinitions(invalid.originalMember)
      case _ => Nil

    module.members.flatMap(memberDefinitions)

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
      case ts: TypeStruct => idx.updatedType(ts).updatedAll(ts.fields)
      case dm: DuplicateMember => updateIndexForMember(idx, dm.originalMember)
      case im: InvalidMember => updateIndexForMember(idx, im.originalMember)
      case _ => idx

  private def collectParamsFromExpr(expr: Expr): List[FnParam] =
    expr.terms.flatMap(collectParamsFromTerm)

  private def collectParamsFromTerm(term: Term): List[FnParam] =
    term match
      case d: Destruction =>
        collectParamsFromExpr(d.operand)
      case lambda: Lambda =>
        lambda.params ++ lambda.meta.flatMap(_.environmentParam).toList ++
          collectParamsFromExpr(lambda.body)
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
        lambda.params ++ lambda.meta.flatMap(_.environmentParam).toList ++
          collectParamsFromExpr(lambda.body)
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectParamsFromTerm)
