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
      // Handle Bnd with Lambda (functions and operators)
      case bnd: Bnd if bnd.meta.isDefined =>
        extractLambdaParams(bnd) match
          case Some(params) if hasDuplicateParams(params) =>
            val displayName = bnd.meta.map(_.originalName).getOrElse(bnd.name)
            val duplicateParamNames =
              params.groupBy(_.name).filter(_._2.size > 1).keys.mkString(", ")
            errors += SemanticError.DuplicateName(
              s"$displayName parameters: $duplicateParamNames",
              params.filter(p => params.count(_.name == p.name) > 1),
              phaseName
            )
            InvalidMember(
              span           = bnd.span,
              originalMember = bnd,
              reason         = s"Duplicate parameter names: $duplicateParamNames"
            )
          case _ => bnd

      case other => other
    }

    // Also check for functions that conflict with operators (by original name)
    val operatorOriginalNames = decls.collect {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Operator) =>
        bnd.meta.get.originalName
    }.toSet

    decls.collect {
      case bnd: Bnd
          if bnd.meta.exists(_.origin == BindingOrigin.Function) &&
            operatorOriginalNames.contains(bnd.name) =>
        val allWithSameName = decls
          .filter {
            case d: Decl =>
              d.name == bnd.name ||
              (d match {
                case b: Bnd => b.meta.exists(_.originalName == bnd.name)
                case _ => false
              })
            case _ => false
          }
          .collect { case d: Decl => d }
        errors += SemanticError.DuplicateName(bnd.name, allWithSameName, phaseName)
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
    case bnd: Bnd =>
      bnd.meta match
        case Some(m) if m.origin == BindingOrigin.Operator && m.arity == CallableArity.Binary =>
          (m.originalName, "bin")
        case Some(m) if m.origin == BindingOrigin.Operator && m.arity == CallableArity.Unary =>
          (m.originalName, "unary")
        case Some(m) if m.origin == BindingOrigin.Function =>
          (r.name, "fn")
        case _ =>
          (r.name, "bnd")
    case _: TypeDef => (r.name, "typedef")
    case _: TypeAlias => (r.name, "typealias")
    case _ => (r.name, "other")

  private def hasDuplicateParams(params: List[FnParam]): Boolean =
    params.groupBy(_.name).exists(_._2.size > 1)

  /** Extract lambda params from a Bnd with Lambda body */
  private def extractLambdaParams(bnd: Bnd): Option[List[FnParam]] =
    bnd.value.terms.headOption.collect { case lambda: Lambda =>
      lambda.params
    }
