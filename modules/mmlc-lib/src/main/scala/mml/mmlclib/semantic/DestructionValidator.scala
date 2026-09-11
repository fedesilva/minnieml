package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

import scala.annotation.tailrec

/** Check compiler-generated destruction contracts against the final symbol index. */
object DestructionValidator:
  def rewriteModule(state: CompilerState): CompilerState =
    val index = state.module.resolvables
    val nodes = state.module.members
      .collect { case b: Bnd => b }
      .flatMap(b => TermTraversal.collect(b.value) { case d: Destruction => d })
    state.addErrors(nodes.flatMap(validate(_, index)))

  def validate(node: Destruction, index: ResolvablesIndex): List[SemanticError] =
    def error(message: String): SemanticError =
      SemanticError.InvalidExpression(
        Expr(node.source, List(node)),
        message,
        "destruction-validation"
      )
    def check(valid: Boolean, message: String): List[SemanticError] =
      Option.unless(valid)(error(message)).toList
    @tailrec
    def canonical(tpe: Type, seen: Set[String] = Set.empty): Option[Type] = tpe match
      case TypeGroup(_, List(inner)) => canonical(inner, seen)
      case ref: TypeRef =>
        ref.resolvedId.flatMap(index.lookupType) match
          case Some(alias: TypeAlias) if !ref.resolvedId.exists(seen.contains) =>
            canonical(alias.typeSpec.getOrElse(alias.typeRef), seen ++ ref.resolvedId)
          case Some(_: TypeAlias) => none
          case Some(_) => ref.some
          case None => none
      case other => other.some
    def nominal(tpe: Option[Type], name: String): Boolean =
      tpe.flatMap(canonical(_)).exists {
        case r: TypeRef =>
          r.resolvedId.contains(s"stdlib::typedef::$name") &&
          r.resolvedId.flatMap(index.lookupType).isDefined
        case _ => false
      }
    def same(left: Type, right: Type): Boolean =
      canonical(left).zip(canonical(right)).exists {
        case (a: TypeRef, b: TypeRef) => a.resolvedId == b.resolvedId
        case (a: TypeFn, b: TypeFn) =>
          a.paramTypes.size == b.paramTypes.size &&
          a.paramTypes.toList.zip(b.paramTypes.toList).forall(same) &&
          same(a.returnType, b.returnType)
        case (a, b) => a == b
      }
    def target(id: String, expected: Option[Type]): List[SemanticError] =
      index.lookup(id) match
        case Some(b: Bnd) =>
          b.typeSpec.flatMap(canonical(_)) match
            case Some(fn: TypeFn) =>
              check(
                fn.paramTypes.size == 1 && expected.exists(same(_, fn.paramTypes.head)) &&
                  nominal(fn.returnType.some, "Unit"),
                s"Incompatible destructor signature: $id"
              ) ++ (b.value.terms match
                case List(lambda: Lambda) =>
                  check(
                    lambda.params.size == 1 && lambda.params.headOption.exists(p =>
                      p.typeSpec.orElse(p.typeAsc).exists(t => expected.exists(same(t, _)))
                    ),
                    s"Destructor parameter disagrees with its signature: $id"
                  )
                case _ => List(error(s"Destructor target has no function body: $id")))
            case _ => List(error(s"Destructor target is not a function: $id"))
        case _ => List(error(s"Missing destructor target: $id"))
    val ptr = TypeRef(node.source, "RawPtr", "stdlib::typedef::RawPtr".some)
    val common = check(nominal(node.typeSpec, "Unit"), "Destruction result must be Unit") ++
      check(node.operand.terms.nonEmpty, "Destruction requires an operand") ++
      node.operand.terms.lastOption.toList.flatMap(t =>
        check(
          t.typeSpec.exists(a => node.operand.typeSpec.exists(same(a, _))),
          "Destruction operand expression type disagrees with its result"
        )
      ) ++
      TermTraversal.collect(node.operand) { case r: Ref => r }.flatMap { ref =>
        val resolved = ref.resolvedId.flatMap(index.lookup)
        check(resolved.isDefined, "Unresolved destruction operand reference") ++
          check(
            resolved
              .collect { case r: Typeable => r }
              .flatMap(r => r.typeSpec.orElse(r.typeAsc))
              .exists(t => ref.typeSpec.exists(same(t, _))),
            "Destruction operand reference type mismatch"
          )
      }
    val specific = node match
      case d: DestroyClosure =>
        check(
          d.operand.typeSpec.flatMap(canonical(_)).exists(_.isInstanceOf[TypeFn]),
          "DestroyClosure requires a function value"
        ) ++ target(d.targetId, ptr.some)
      case _: DispatchClosureDestructor =>
        check(nominal(node.operand.typeSpec, "RawPtr"), "Dispatch requires RawPtr")
      case d: DisarmClosureEnvironment =>
        check(nominal(d.operand.typeSpec, "RawPtr"), "Environment transfer requires RawPtr") ++
          target(d.targetId, ptr.some)
      case d: DestroyClosureEnvironment =>
        val operandErrors =
          check(nominal(d.operand.typeSpec, "RawPtr"), "Environment destruction requires RawPtr")
        val duplicates =
          check(d.fields.map(_.fieldId).distinct.size == d.fields.size, "Duplicate field cleanup")
        val layoutErrors = index.lookupType(d.layoutId) match
          case Some(layout: TypeStruct) =>
            val positions = d.fields.flatMap(c =>
              layout.fields.indexWhere(_.id.contains(c.fieldId)) match
                case -1 => None
                case n => n.some
            )
            check(layout.id.contains(d.layoutId), "Environment layout identity mismatch") ++
              check(
                layout.fields.forall(_.id.nonEmpty) &&
                  layout.fields.flatMap(_.id).distinct.size == layout.fields.size,
                "Environment fields require unique identities"
              ) ++
              check(positions == positions.sorted, "Field cleanup must follow layout order") ++
              check(
                layout.fields.headOption.exists(f => nominal(f.typeSpec.some, "RawPtr")),
                "Closure layout requires a destructor pointer"
              ) ++
              layout.fields.toList.drop(1).flatMap { field =>
                TypeUtils.getTypeName(field.typeSpec).flatMap(TypeUtils.freeFnFor(_, index)) match
                  case None => Nil
                  case Some(_) if field.id.exists(d.borrowedFields.contains) => Nil
                  case Some(name) =>
                    DestructionTargets.named(name, index) match
                      case None =>
                        List(
                          error(s"Missing registered destructor '$name' for field '${field.name}'")
                        )
                      case Some(id) =>
                        check(
                          field.id
                            .exists(fieldId => d.fields.contains(FieldCleanup.Value(fieldId, id))),
                          s"Missing required field cleanup: ${field.name}"
                        )
              } ++
              check(
                d.borrowedFields.subsetOf(layout.fields.toList.drop(1).flatMap(_.id).toSet) &&
                  !d.fields.exists(f => d.borrowedFields.contains(f.fieldId)),
                "Borrowed environment fields must exist and have no cleanup"
              ) ++ d.fields.flatMap { cleanup =>
                layout.fields.find(_.id.contains(cleanup.fieldId)) match
                  case None => List(error(s"Missing destruction field: ${cleanup.fieldId}"))
                  case Some(field) =>
                    check(
                      !layout.fields.headOption.contains(field),
                      "Cannot destroy destructor slot"
                    ) ++
                      (cleanup match
                        case _: FieldCleanup.Closure =>
                          check(
                            canonical(field.typeSpec).exists(_.isInstanceOf[TypeFn]),
                            "Closure field cleanup requires a function value"
                          ) ++
                            target(cleanup.targetId, ptr.some)
                        case _: FieldCleanup.Value =>
                          check(
                            DestructionTargets
                              .forType(field.typeSpec, index)
                              .contains(cleanup.targetId),
                            "Field cleanup must use its registered destructor"
                          ) ++
                            target(cleanup.targetId, field.typeSpec.some))
              }
          case _ => List(error(s"Missing closure environment layout: ${d.layoutId}"))
        operandErrors ++ duplicates ++ layoutErrors
    common ++ specific
