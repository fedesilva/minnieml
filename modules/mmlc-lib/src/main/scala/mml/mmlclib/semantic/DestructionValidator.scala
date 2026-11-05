package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.{DestructionTargets, TypeMemoryQueries}
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
    def error(reason: ExpressionError): SemanticError =
      SemanticError.InvalidExpression(
        Expr(node.source, List(node)),
        reason,
        "destruction-validation"
      )
    def check(valid: Boolean, reason: ExpressionError): List[SemanticError] =
      Option.unless(valid)(error(reason)).toList
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
                DestructionError.IncompatibleSignature(id)
              ) ++ (b.value.terms match
                case List(lambda: Lambda) =>
                  check(
                    lambda.params.size == 1 && lambda.params.headOption.exists(p =>
                      p.typeSpec.orElse(p.typeAsc).exists(t => expected.exists(same(t, _)))
                    ),
                    DestructionError.ParameterSignatureMismatch(id)
                  )
                case _ => List(error(DestructionError.MissingFunctionBody(id))))
            case _ => List(error(DestructionError.NonFunctionTarget(id)))
        case _ => List(error(DestructionError.MissingTarget(id)))
    val ptr = TypeRef(node.source, "RawPtr", "stdlib::typedef::RawPtr".some)
    val common = check(nominal(node.typeSpec, "Unit"), DestructionError.NonUnitResult) ++
      check(node.operand.terms.nonEmpty, DestructionError.MissingOperand) ++
      node.operand.terms.lastOption.toList.flatMap(t =>
        check(
          t.typeSpec.exists(a => node.operand.typeSpec.exists(same(a, _))),
          DestructionError.OperandResultTypeMismatch
        )
      ) ++
      TermTraversal.collect(node.operand) { case r: Ref => r }.flatMap { ref =>
        val resolved = ref.resolvedId.flatMap(index.lookup)
        check(resolved.isDefined, DestructionError.UnresolvedOperand) ++
          check(
            resolved
              .flatMap {
                case field: Field => field.typeSpec.some
                case value: Typeable => value.typeSpec.orElse(value.typeAsc)
                case _ => none
              }
              .exists(t => ref.typeSpec.exists(same(t, _))),
            DestructionError.OperandReferenceTypeMismatch
          )
      }
    val specific = node match
      case d: DestroyClosure =>
        check(
          d.operand.typeSpec.flatMap(canonical(_)).exists(_.isInstanceOf[TypeFn]),
          DestructionError.NonFunctionClosure
        ) ++ target(d.targetId, ptr.some)
      case _: DispatchClosureDestructor =>
        check(nominal(node.operand.typeSpec, "RawPtr"), DestructionError.NonPointerDispatch)
      case d: DisarmClosureEnvironment =>
        check(nominal(d.operand.typeSpec, "RawPtr"), DestructionError.NonPointerTransfer) ++
          target(d.targetId, ptr.some)
      case d: DestroyClosureEnvironment =>
        val operandErrors =
          check(nominal(d.operand.typeSpec, "RawPtr"), DestructionError.NonPointerDestruction)
        val duplicates =
          check(
            d.fields.map(_.fieldId).distinct.size == d.fields.size,
            DestructionError.DuplicateFieldCleanup
          )
        val layoutErrors = index.lookupType(d.layoutId) match
          case Some(layout: TypeStruct) =>
            val positions = d.fields.flatMap(c =>
              layout.fields.indexWhere(_.id.contains(c.fieldId)) match
                case -1 => None
                case n => n.some
            )
            check(layout.id.contains(d.layoutId), DestructionError.LayoutIdentityMismatch) ++
              check(
                layout.fields.forall(_.id.nonEmpty) &&
                  layout.fields.flatMap(_.id).distinct.size == layout.fields.size,
                DestructionError.FieldIdentityMismatch
              ) ++
              check(positions == positions.sorted, DestructionError.FieldOrderMismatch) ++
              check(
                layout.fields.headOption.exists(f => nominal(f.typeSpec.some, "RawPtr")),
                DestructionError.MissingDestructorPointer
              ) ++
              layout.fields.toList.drop(1).flatMap { field =>
                TypeMemoryQueries.freeFnFor(field.typeSpec, index) match
                  case None => Nil
                  case Some(_) if field.id.exists(d.borrowedFields.contains) => Nil
                  case Some(name) =>
                    DestructionTargets.forType(field.typeSpec, index) match
                      case None =>
                        List(
                          error(DestructionError.MissingRegisteredFieldDestructor(name, field))
                        )
                      case Some(id) =>
                        check(
                          field.id
                            .exists(fieldId => d.fields.contains(FieldCleanup.Value(fieldId, id))),
                          DestructionError.MissingFieldCleanup(field)
                        )
              } ++
              check(
                d.borrowedFields.subsetOf(layout.fields.toList.drop(1).flatMap(_.id).toSet) &&
                  !d.fields.exists(f => d.borrowedFields.contains(f.fieldId)),
                DestructionError.InvalidBorrowedFields
              ) ++ d.fields.flatMap { cleanup =>
                layout.fields.find(_.id.contains(cleanup.fieldId)) match
                  case None => List(error(DestructionError.MissingField(cleanup.fieldId)))
                  case Some(field) =>
                    check(
                      !layout.fields.headOption.contains(field),
                      DestructionError.DestructorSlotCleanup
                    ) ++
                      (cleanup match
                        case _: FieldCleanup.Closure =>
                          check(
                            canonical(field.typeSpec).exists(_.isInstanceOf[TypeFn]),
                            DestructionError.NonFunctionField
                          ) ++
                            target(cleanup.targetId, ptr.some)
                        case _: FieldCleanup.Value =>
                          check(
                            DestructionTargets
                              .forType(field.typeSpec, index)
                              .contains(cleanup.targetId),
                            DestructionError.FieldDestructorMismatch
                          ) ++
                            target(cleanup.targetId, field.typeSpec.some))
              }
          case _ => List(error(DestructionError.MissingLayout(d.layoutId)))
        operandErrors ++ duplicates ++ layoutErrors
    common ++ specific
