package mml.mmlclib.semantic

import mml.mmlclib.ast.*

object DuplicateNameChecker:

  private val phaseName = "mml.mmlclib.semantic.DuplicateNameChecker"

  /** Rewrite module to replace duplicate members with invalid nodes, accumulating errors in the
    * state.
    */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState =
    val (rewrittenMembers, errors) = processMembers(state.module.members)
    state
      .withModule(state.module.copy(members = rewrittenMembers))
      .addErrors(errors)

  private def processMembers(members: List[Member]): (List[Member], List[SemanticError]) =
    val errors = scala.collection.mutable.ListBuffer[SemanticError]()

    // Partition members into declarations and other nodes (like MemberError)
    val (decls, otherMembers) = members.partition(_.isInstanceOf[Decl])

    // First, handle duplicate member names on declarations only
    val membersByKey = groupMembersByKey(decls)

    // Process declarations in original order to preserve member ordering
    val membersAfterDuplicates = decls.map { member =>
      member match
        case d: Decl =>
          val key              = resolvableKey(d)
          val itemsWithSameKey = membersByKey(key)
          if itemsWithSameKey.size > 1 then
            // Report error only once per duplicate group (when we encounter the first occurrence)
            if itemsWithSameKey.head == member then
              errors += SemanticError.DuplicateName(
                key._1,
                itemsWithSameKey.collect { case d: Decl => d },
                phaseName
              )
            // Keep first valid, convert rest to DuplicateMember
            if itemsWithSameKey.head == member then member // First occurrence stays as-is
            else
              DuplicateMember(
                span = member match {
                  case m: FromSource => m.span
                  case _ => SrcSpan(SrcPoint(0, 0, 0), SrcPoint(0, 0, 0))
                },
                originalMember  = member,
                firstOccurrence = itemsWithSameKey.head
              )
          else member
        case other => other
    }

    // Then, check for duplicate parameters and convert to InvalidMember if needed
    val finalDecls = membersAfterDuplicates.map {
      case fn: FnDef if hasDuplicateParams(fn.params) =>
        val duplicateParamNames =
          fn.params.groupBy(_.name).filter(_._2.size > 1).keys.mkString(", ")
        errors += SemanticError.DuplicateName(
          s"${fn.name} parameters: $duplicateParamNames",
          fn.params.filter(p => fn.params.count(_.name == p.name) > 1),
          phaseName
        )
        InvalidMember(
          span           = fn.span,
          originalMember = fn,
          reason         = s"Duplicate parameter names: $duplicateParamNames"
        )

      case op: BinOpDef if op.param1.name == op.param2.name =>
        errors += SemanticError.DuplicateName(
          s"${op.name} parameters: ${op.param1.name}",
          List(op.param1, op.param2),
          phaseName
        )
        InvalidMember(
          span           = op.span,
          originalMember = op,
          reason         = s"Duplicate parameter name: ${op.param1.name}"
        )

      case op: UnaryOpDef =>
        // Unary ops have only one parameter, no duplicates possible
        op

      case other => other
    }

    // Also check for functions that conflict with operators
    val operatorNames = decls.collect { case op: OpDef => op.name }.toSet
    decls.collect {
      case fn: FnDef if operatorNames.contains(fn.name) =>
        val allWithSameName = decls
          .filter {
            case d: Decl => d.name == fn.name
            case _ => false
          }
          .collect { case d: Decl => d }
        errors += SemanticError.DuplicateName(fn.name, allWithSameName, phaseName)
    }

    (finalDecls ++ otherMembers, errors.toList)

  private def groupMembersByKey(members: List[Member]): Map[(String, String), List[Member]] =
    members
      .foldLeft(Map.empty[(String, String), List[Member]]) { (acc, member) =>
        member match
          case d: Decl =>
            val key = resolvableKey(d)
            acc.updatedWith(key) {
              case Some(existing) => Some(member :: existing)
              case None => Some(List(member))
            }
          case _ => acc
      }
      .view
      .mapValues(_.reverse)
      .toMap

  private def resolvableKey(r: Resolvable): (String, String) = r match
    case _: BinOpDef => (r.name, "bin")
    case _: UnaryOpDef => (r.name, "unary")
    case _: TypeDef => (r.name, "typedef")
    case _: TypeAlias => (r.name, "typealias")
    case _: FnDef => (r.name, "fn")
    case _: Bnd => (r.name, "bnd")
    case _ => (r.name, "other")

  private def hasDuplicateParams(params: List[FnParam]): Boolean =
    params.groupBy(_.name).exists(_._2.size > 1)
