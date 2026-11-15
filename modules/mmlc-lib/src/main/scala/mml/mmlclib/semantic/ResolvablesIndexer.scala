package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.DeclarationQueries
import mml.mmlclib.compiler.CompilerState

/** Rebuilds definition and parameter entries while retaining checked generic templates. */
object ResolvablesIndexer:

  def rewriteModule(state: CompilerState): CompilerState =
    val module             = state.module
    val updatedResolvables = rebuildIndex(module)
    state.withModule(module.copy(resolvables = updatedResolvables))

  def refresh(module: Module): Module = module.copy(resolvables = rebuildIndex(module))

  /** Returns definition occurrences, including retained templates and duplicate IDs in module
    * bodies.
    */
  def definitions(module: Module): List[Resolvable] =
    def memberDefinitions(member: Member): List[Resolvable] = member match
      case protocol: Protocol => protocol :: protocol.signatures.flatMap(s => s :: s.params)
      case instance: ProtocolInstance =>
        instance.members.flatMap(member => memberDefinitions(member.binding))
      case binding:    Bnd => binding :: collectParamsFromExpr(binding.value)
      case struct:     TypeStruct => struct :: struct.fields.toList
      case definition: TypeDef => List(definition)
      case alias:      TypeAlias => List(alias)
      case duplicate:  DuplicateMember => memberDefinitions(duplicate.originalMember)
      case invalid:    InvalidMember => memberDefinitions(invalid.originalMember)
      case _ => Nil

    retainedTemplates(module).flatMap(memberDefinitions) ++ module.members.flatMap(
      memberDefinitions
    )

  private def rebuildIndex(module: Module): ResolvablesIndex =
    (retainedTemplates(module) ++ module.members).foldLeft(ResolvablesIndex()) { (idx, member) =>
      updateIndexForMember(idx, member)
    }

  /** Returns indexed templates absent from the current member list. These include local generic
    * declarations and top-level templates set aside during concrete lowering. Ordinary definitions
    * always come from the current module bodies.
    */
  private def retainedTemplates(module: Module): List[Bnd] =
    val memberIds = module.members.collect { case binding: Bnd => binding.id }.flatten.toSet
    DeclarationQueries
      .genericFunctions(module.resolvables)
      .filterNot(_.id.exists(memberIds.contains))

  private def updateIndexForMember(idx: ResolvablesIndex, member: Member): ResolvablesIndex =
    member match
      case protocol: Protocol =>
        idx.updated(protocol).updatedAll(protocol.signatures.flatMap(s => s :: s.params))
      case instance: ProtocolInstance =>
        instance.members.foldLeft(idx) { (index, member) =>
          updateIndexForMember(index, member.binding)
        }
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
      case binding: BindOwnership =>
        List(binding.valueParam, binding.witnessParam) ++
          collectParamsFromExpr(binding.initializer) ++ collectParamsFromExpr(binding.body)
      case yielded: YieldOwnership =>
        collectParamsFromExpr(yielded.value) ++ collectParamsFromExpr(yielded.owns)
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

  private def collectParamsFromAppFn(fn: Callee): List[FnParam] =
    fn match
      case lambda: Lambda =>
        lambda.params ++ lambda.meta.flatMap(_.environmentParam).toList ++
          collectParamsFromExpr(lambda.body)
      case app: App =>
        collectParamsFromAppFn(app.fn) ++ collectParamsFromExpr(app.arg)
      case ref: Ref =>
        ref.qualifier.toList.flatMap(collectParamsFromTerm)
