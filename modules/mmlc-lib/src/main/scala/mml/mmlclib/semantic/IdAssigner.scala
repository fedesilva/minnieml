package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import BindingIds.Allocation

/** Assigns stable IDs to all definition nodes and builds the ResolvablesIndex.
  *
  * This phase runs before RefResolver to ensure all definitions have IDs that can be referenced.
  */
object IdAssigner:

  private def declSegment(member: Member): Option[String] =
    member match
      case _: Bnd => Some("bnd")
      case _: TypeDef => Some("typedef")
      case _: TypeStruct => Some("typestruct")
      case _: TypeAlias => Some("typealias")
      case _ => None

  private def topLevelId(moduleName: String, member: Decl): Option[String] =
    declSegment(member).map(segment => BindingIds.declaration(moduleName, member.name, segment))

  private def fieldId(moduleName: String, structName: String, fieldName: String): Option[String] =
    Some(s"${BindingIds.declaration(moduleName, structName, "typestruct")}::$fieldName")

  /** Assign IDs to all definitions in the module and build the resolvables index. */
  def rewriteModule(state: CompilerState): CompilerState =
    val module = state.module
    val (supply, updatedMembers) = module.members
      .traverse(member =>
        assignIdToMember(module.name)(member).flatTap {
          case declaration: Decl => BindingIds.reserveDeclaration(declaration)
          case _ => ().pure[Allocation]
        }
      )
      .run(state.bindingIds.include(module))
      .value

    state
      .copy(bindingIds = supply)
      .withModule(ResolvablesIndexer.refresh(module.copy(members = updatedMembers)))

  /** Assign ID to a member if it doesn't have one */
  private def assignIdToMember(moduleName: String)(member: Member): Allocation[Member] =
    member match
      case bnd: Bnd =>
        val owner = BindingOwner.binding(moduleName, bnd.name)
        assignIdsToExpr(bnd.value, owner).map { updatedValue =>
          bnd.copy(id = bnd.id.orElse(topLevelId(moduleName, bnd)), value = updatedValue)
        }
      case td: TypeDef =>
        val updatedId = td.id.orElse(topLevelId(moduleName, td))
        td.copy(id = updatedId).pure[Allocation]
      case ta: TypeAlias =>
        val updatedId = ta.id.orElse(topLevelId(moduleName, ta))
        ta.copy(id = updatedId).pure[Allocation]
      case ts: TypeStruct =>
        val updatedId = ts.id.orElse(topLevelId(moduleName, ts))
        val updatedFields = ts.fields.map { field =>
          val newId = field.id.orElse(fieldId(moduleName, ts.name, field.name))
          field.copy(id = newId)
        }
        ts.copy(id = updatedId, fields = updatedFields).pure[Allocation]
      case other => other.pure[Allocation]

  private def assignIdsToExpr(expr: Expr, owner: BindingOwner): Allocation[Expr] =
    expr.terms.traverse(assignIdsToTerm(_, owner)).map(terms => expr.copy(terms = terms))

  private def assignLambda(lambda: Lambda, owner: BindingOwner): Allocation[Lambda] =
    for
      nested <- BindingIds.scope(owner)
      params <- lambda.params.traverse(BindingIds.assign(_, nested))
      body <- assignIdsToExpr(lambda.body, nested)
    yield lambda.copy(params = params, body = body)

  private def assignIdsToTerm(term: Term, owner: BindingOwner): Allocation[Term] = term match
    case lambda: Lambda => assignLambda(lambda, owner).widen
    case expr:   Expr => assignIdsToExpr(expr, owner).widen
    case group:  TermGroup =>
      assignIdsToExpr(group.inner, owner).map(inner => group.copy(inner = inner))
    case tuple: Tuple =>
      tuple.elements
        .traverse(assignIdsToExpr(_, owner))
        .map(elements => tuple.copy(elements = elements))
    case cond: Cond =>
      for
        predicate <- assignIdsToExpr(cond.cond, owner)
        yes <- assignIdsToExpr(cond.ifTrue, owner)
        no <- assignIdsToExpr(cond.ifFalse, owner)
      yield cond.copy(cond = predicate, ifTrue = yes, ifFalse = no)
    case app: App =>
      for
        fn <- assignIdsToAppFn(app.fn, owner)
        arg <- assignIdsToExpr(app.arg, owner)
      yield app.copy(fn = fn, arg = arg)
    case ref: Ref =>
      ref.qualifier.traverse(assignIdsToTerm(_, owner)).map(q => ref.copy(qualifier = q))
    case other => other.pure[Allocation]

  private def assignIdsToAppFn(
    fn:    Ref | App | Lambda,
    owner: BindingOwner
  ): Allocation[Ref | App | Lambda] = fn match
    case lambda: Lambda => assignLambda(lambda, owner).widen
    case app:    App =>
      for
        fn <- assignIdsToAppFn(app.fn, owner)
        arg <- assignIdsToExpr(app.arg, owner)
      yield app.copy(fn = fn, arg = arg)
    case ref: Ref =>
      ref.qualifier.traverse(assignIdsToTerm(_, owner)).map(q => ref.copy(qualifier = q))
