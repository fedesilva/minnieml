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
  private def genId(name: String): Option[String] =
    Some(s"user::$name::${UUID.randomUUID().toString.take(8)}")

  /** Assign IDs to all definitions in the module and build the resolvables index. */
  def rewriteModule(state: CompilerState): CompilerState =
    val module         = state.module
    val updatedMembers = module.members.map(assignIdToMember)

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
  private def assignIdToMember(member: Member): Member =
    member match
      case bnd: Bnd if bnd.id.isEmpty =>
        val updatedValue = assignIdsToExpr(bnd.value)
        bnd.copy(id = genId(bnd.name), value = updatedValue)
      case bnd: Bnd =>
        val updatedValue = assignIdsToExpr(bnd.value)
        bnd.copy(value = updatedValue)
      case td: TypeDef if td.id.isEmpty =>
        td.copy(id = genId(td.name))
      case ta: TypeAlias if ta.id.isEmpty =>
        ta.copy(id = genId(ta.name))
      case ts: TypeStruct if ts.id.isEmpty =>
        ts.copy(id = genId(ts.name))
      case other => other

  /** Assign IDs to FnParams and nested lambdas in an expression */
  private def assignIdsToExpr(expr: Expr): Expr =
    expr.copy(terms = expr.terms.map(assignIdsToTerm))

  /** Assign IDs to terms, handling lambdas specially */
  private def assignIdsToTerm(term: Term): Term =
    term match
      case lambda: Lambda =>
        val updatedParams = lambda.params.map { param =>
          if param.id.isEmpty then param.copy(id = genId(param.name))
          else param
        }
        val updatedBody = assignIdsToExpr(lambda.body)
        lambda.copy(params = updatedParams, body = updatedBody)

      case group: TermGroup =>
        group.copy(inner = assignIdsToExpr(group.inner))

      case e: Expr =>
        assignIdsToExpr(e)

      case t: Tuple =>
        t.copy(elements = t.elements.map(assignIdsToExpr))

      case cond: Cond =>
        cond.copy(
          cond    = assignIdsToExpr(cond.cond),
          ifTrue  = assignIdsToExpr(cond.ifTrue),
          ifFalse = assignIdsToExpr(cond.ifFalse)
        )

      case app: App =>
        val newFn  = assignIdsToAppFn(app.fn)
        val newArg = assignIdsToExpr(app.arg)
        app.copy(fn = newFn, arg = newArg)

      case other => other

  /** Assign IDs in App.fn */
  private def assignIdsToAppFn(fn: Ref | App | Lambda): Ref | App | Lambda =
    fn match
      case lambda: Lambda =>
        val updatedParams = lambda.params.map { param =>
          if param.id.isEmpty then param.copy(id = genId(param.name))
          else param
        }
        val updatedBody = assignIdsToExpr(lambda.body)
        lambda.copy(params = updatedParams, body = updatedBody)
      case app: App =>
        val newFn  = assignIdsToAppFn(app.fn)
        val newArg = assignIdsToExpr(app.arg)
        app.copy(fn = newFn, arg = newArg)
      case ref: Ref => ref
