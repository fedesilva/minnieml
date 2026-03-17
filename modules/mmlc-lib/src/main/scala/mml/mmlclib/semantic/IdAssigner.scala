package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import java.util.UUID

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
    declSegment(member).map(seg => s"$moduleName::$seg::${member.name}")

  private def fieldId(moduleName: String, structName: String, fieldName: String): Option[String] =
    Some(s"$moduleName::typestruct::$structName::$fieldName")

  /** Generate a unique ID for nested params/lambdas */
  private def nestedId(
    moduleName:   String,
    ownerSegment: String,
    ownerName:    String,
    name:         String
  ): Option[String] =
    Some(
      s"$moduleName::$ownerSegment::$ownerName::$name::${UUID.randomUUID().toString.take(8)}"
    )

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
      case bnd: Bnd =>
        val ownerSegment = "bnd"
        val updatedValue = assignIdsToExpr(bnd.value, moduleName, ownerSegment, bnd.name)
        val updatedId    = bnd.id.orElse(topLevelId(moduleName, bnd))
        bnd.copy(id = updatedId, value = updatedValue)
      case td: TypeDef =>
        val updatedId = td.id.orElse(topLevelId(moduleName, td))
        td.copy(id = updatedId)
      case ta: TypeAlias =>
        val updatedId = ta.id.orElse(topLevelId(moduleName, ta))
        ta.copy(id = updatedId)
      case ts: TypeStruct =>
        val updatedId = ts.id.orElse(topLevelId(moduleName, ts))
        val updatedFields = ts.fields.map { field =>
          val newId = field.id.orElse(fieldId(moduleName, ts.name, field.name))
          field.copy(id = newId)
        }
        ts.copy(id = updatedId, fields = updatedFields)
      case other => other

  /** Assign IDs to FnParams and nested lambdas in an expression */
  private def assignIdsToExpr(
    expr:         Expr,
    moduleName:   String,
    ownerSegment: String,
    ownerName:    String
  ): Expr =
    expr.copy(terms = expr.terms.map(assignIdsToTerm(_, moduleName, ownerSegment, ownerName)))

  /** Assign IDs to terms, handling lambdas specially */
  private def assignIdsToTerm(
    term:         Term,
    moduleName:   String,
    ownerSegment: String,
    ownerName:    String
  ): Term =
    term match
      case lambda: Lambda =>
        val updatedParams = lambda.params.map { param =>
          if param.id.isEmpty then
            param.copy(id = nestedId(moduleName, ownerSegment, ownerName, param.name))
          else param
        }
        val updatedBody = assignIdsToExpr(lambda.body, moduleName, ownerSegment, ownerName)
        lambda.copy(params = updatedParams, body = updatedBody)

      case group: TermGroup =>
        group.copy(inner = assignIdsToExpr(group.inner, moduleName, ownerSegment, ownerName))

      case e: Expr =>
        assignIdsToExpr(e, moduleName, ownerSegment, ownerName)

      case t: Tuple =>
        t.copy(elements = t.elements.map(assignIdsToExpr(_, moduleName, ownerSegment, ownerName)))

      case cond: Cond =>
        cond.copy(
          cond    = assignIdsToExpr(cond.cond, moduleName, ownerSegment, ownerName),
          ifTrue  = assignIdsToExpr(cond.ifTrue, moduleName, ownerSegment, ownerName),
          ifFalse = assignIdsToExpr(cond.ifFalse, moduleName, ownerSegment, ownerName)
        )

      case app: App =>
        val newFn  = assignIdsToAppFn(app.fn, moduleName, ownerSegment, ownerName)
        val newArg = assignIdsToExpr(app.arg, moduleName, ownerSegment, ownerName)
        app.copy(fn = newFn, arg = newArg)

      case other => other

  /** Assign IDs in App.fn */
  private def assignIdsToAppFn(
    fn:           Ref | App | Lambda,
    moduleName:   String,
    ownerSegment: String,
    ownerName:    String
  ): Ref | App | Lambda =
    fn match
      case lambda: Lambda =>
        val updatedParams = lambda.params.map { param =>
          if param.id.isEmpty then
            param.copy(id = nestedId(moduleName, ownerSegment, ownerName, param.name))
          else param
        }
        val updatedBody = assignIdsToExpr(lambda.body, moduleName, ownerSegment, ownerName)
        lambda.copy(params = updatedParams, body = updatedBody)
      case app: App =>
        val newFn  = assignIdsToAppFn(app.fn, moduleName, ownerSegment, ownerName)
        val newArg = assignIdsToExpr(app.arg, moduleName, ownerSegment, ownerName)
        app.copy(fn = newFn, arg = newArg)
      case ref: Ref => ref
