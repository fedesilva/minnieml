package mml.mmlclib.ast

import cats.syntax.all.*
import cats.{Applicative, Id}

/** Structural type traversal preserves source origins and declaration metadata. Refinement
  * expressions belong to term traversal. Failed-reference originals and diagnostic candidates are
  * evidence links, not children of a type.
  */
object TypeTraversal:

  /** Direct children in declaration order: function parameters precede the result, and an
    * application's base precedes its arguments. References do not follow declaration IDs.
    */
  def children(tpe: Type): List[Type] = tpe match
    case function:     TypeFn => function.paramTypes.toList :+ function.returnType
    case application:  TypeApplication => application.base :: application.args
    case tuple:        TypeTuple => tuple.elements
    case group:        TypeGroup => group.types
    case union:        Union => union.types
    case intersection: Intersection => intersection.types
    case scheme:       TypeScheme => List(scheme.bodyType)
    case record:       TypeOpenRecord => record.fields.map(_._2)
    case native:       NativeStruct => native.fields.map(_._2)
    case struct:       TypeStruct => struct.fields.map(_.typeSpec).toList
    case _: TypeRef | _: NativePrimitive | _: NativePointer | _: TypeUnit | _: TypeVariable |
        _: TypeRefinement | _: ParseTypeError | _: TypeResolutionError =>
      Nil

  /** Collect matches from the root and descendants, for example all `TypeVariable` occurrences. */
  def collect[A](tpe: Type)(query: PartialFunction[Type, A]): List[A] =
    query.lift(tpe).toList ++ children(tpe).flatMap(collect(_)(query))

  /** Test the root before its descendants, stopping at the first match. */
  def exists(tpe: Type)(predicate: Type => Boolean): Boolean =
    predicate(tpe) || children(tpe).exists(exists(_)(predicate))

  /** Transform direct children with an effect, retaining their order and the enclosing metadata. */
  def traverseChildren[F[_]: Applicative](tpe: Type)(f: Type => F[Type]): F[Type] = tpe match

    case function: TypeFn =>
      (function.paramTypes.traverse(f), f(function.returnType)).mapN { (params, result) =>
        function.copy(paramTypes = params, returnType = result)
      }

    case application: TypeApplication =>
      (f(application.base), application.args.traverse(f)).mapN { (base, args) =>
        application.copy(base = base, args = args)
      }

    case tuple: TypeTuple =>
      tuple.elements.traverse(f).map(elements => tuple.copy(elements = elements))

    case group: TypeGroup =>
      group.types.traverse(f).map(types => group.copy(types = types))

    case union: Union =>
      union.types.traverse(f).map(types => union.copy(types = types))

    case intersection: Intersection =>
      intersection.types.traverse(f).map(types => intersection.copy(types = types))

    case scheme: TypeScheme =>
      f(scheme.bodyType).map(body => scheme.copy(bodyType = body))

    case record: TypeOpenRecord =>
      record.fields
        .traverse { case (name, fieldType) => f(fieldType).map(name -> _) }
        .map(fields => record.copy(fields = fields))

    case native: NativeStruct =>
      native.fields
        .traverse { case (name, fieldType) => f(fieldType).map(name -> _) }
        .map(fields => native.copy(fields = fields))

    case struct: TypeStruct =>
      struct.fields
        .traverse(field => f(field.typeSpec).map(tpe => field.copy(typeSpec = tpe)))
        .map(fields => struct.copy(fields = fields))

    case _: TypeRef | _: NativePrimitive | _: NativePointer | _: TypeUnit | _: TypeVariable |
        _: TypeRefinement | _: ParseTypeError | _: TypeResolutionError =>
      tpe.pure[F]

  /** Replace matching types from the root downward; replacements are not visited again. For
    * example, substituting `'T` with a function type keeps that replacement intact.
    */
  def rewrite(tpe: Type)(replace: PartialFunction[Type, Type]): Type =
    replace.lift(tpe).getOrElse(traverseChildren[Id](tpe)(rewrite(_)(replace)))
