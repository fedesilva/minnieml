package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.UUID

/** Assigns stable IDs to all definition nodes and builds the ResolvablesIndex.
  *
  * This phase runs before RefResolver to ensure all definitions have IDs that can be referenced.
  */
object IdAssigner:

  /** Generate a unique ID for a user-defined member */
  private def genId(moduleName: String, ownerName: String, name: String): Option[String] =
    Some(s"$moduleName::$ownerName::$name::${UUID.randomUUID().toString.take(8)}")

  /** Assign IDs to all definitions in the module and build the resolvables index. */
  def rewriteModule(state: CompilerState): CompilerState =
    val module         = state.module
    val updatedMembers = module.members.map(assignIdToMember(module.name))

    // Build resolvables index from all members
    val resolvables = updatedMembers.foldLeft(module.resolvables) { (idx, member) =>
      member match
        case bnd: Bnd => idx.updated(bnd)
        case td:  TypeDef => idx.updatedType(td)
        case ta:  TypeAlias => idx.updatedType(ta)
        case ts:  TypeStruct => idx.updatedType(ts)
        case _:   DuplicateMember => idx
        case _:   InvalidMember => idx
        case _:   ParsingMemberError => idx
        case _:   ParsingIdError => idx
    }

    state.withModule(module.copy(members = updatedMembers, resolvables = resolvables))

  /** Assign ID to a member if it doesn't have one */
  private def assignIdToMember(moduleName: String)(member: Member): Member =
    member match
      case bnd: Bnd if bnd.id.isEmpty =>
        val updatedValue = assignIdsToExpr(bnd.value, moduleName, bnd.name)
        bnd.copy(id = genId(moduleName, bnd.name, bnd.name), value = updatedValue)
      case bnd: Bnd =>
        val updatedValue = assignIdsToExpr(bnd.value, moduleName, bnd.name)
        bnd.copy(value = updatedValue)
      case td: TypeDef if td.id.isEmpty =>
        td.copy(id = genId(moduleName, td.name, td.name))
      case ta: TypeAlias if ta.id.isEmpty =>
        ta.copy(id = genId(moduleName, ta.name, ta.name))
      case ts: TypeStruct if ts.id.isEmpty =>
        ts.copy(id = genId(moduleName, ts.name, ts.name))
      case other => other

  /** Assign IDs to FnParams and nested lambdas in an expression */
  private def assignIdsToExpr(expr: Expr, moduleName: String, ownerName: String): Expr =
    expr.copy(terms = expr.terms.map(assignIdsToTerm(_, moduleName, ownerName)))

  /** Assign IDs to terms, handling lambdas specially */
  private def assignIdsToTerm(term: Term, moduleName: String, ownerName: String): Term =
    term match
      case lambda: Lambda =>
        val updatedParams = lambda.params.map { param =>
          if param.id.isEmpty then param.copy(id = genId(moduleName, ownerName, param.name))
          else param
        }
        val updatedBody = assignIdsToExpr(lambda.body, moduleName, ownerName)
        lambda.copy(params = updatedParams, body = updatedBody)

      case group: TermGroup =>
        group.copy(inner = assignIdsToExpr(group.inner, moduleName, ownerName))

      case e: Expr =>
        assignIdsToExpr(e, moduleName, ownerName)

      case t: Tuple =>
        t.copy(elements = t.elements.map(assignIdsToExpr(_, moduleName, ownerName)))

      case cond: Cond =>
        cond.copy(
          cond    = assignIdsToExpr(cond.cond, moduleName, ownerName),
          ifTrue  = assignIdsToExpr(cond.ifTrue, moduleName, ownerName),
          ifFalse = assignIdsToExpr(cond.ifFalse, moduleName, ownerName)
        )

      case app: App =>
        val newFn  = assignIdsToAppFn(app.fn, moduleName, ownerName)
        val newArg = assignIdsToExpr(app.arg, moduleName, ownerName)
        app.copy(fn = newFn, arg = newArg)

      case other => other

  /** Assign IDs in App.fn */
  private def assignIdsToAppFn(
    fn:         Ref | App | Lambda,
    moduleName: String,
    ownerName:  String
  ): Ref | App | Lambda =
    fn match
      case lambda: Lambda =>
        val updatedParams = lambda.params.map { param =>
          if param.id.isEmpty then param.copy(id = genId(moduleName, ownerName, param.name))
          else param
        }
        val updatedBody = assignIdsToExpr(lambda.body, moduleName, ownerName)
        lambda.copy(params = updatedParams, body = updatedBody)
      case app: App =>
        val newFn  = assignIdsToAppFn(app.fn, moduleName, ownerName)
        val newArg = assignIdsToExpr(app.arg, moduleName, ownerName)
        app.copy(fn = newFn, arg = newArg)
      case ref: Ref => ref
