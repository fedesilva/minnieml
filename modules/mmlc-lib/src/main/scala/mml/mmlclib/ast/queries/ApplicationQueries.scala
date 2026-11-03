package mml.mmlclib.ast.queries

import cats.syntax.all.*
import mml.mmlclib.ast.*

/** Application structure and callable signatures, without value-flow or availability analysis. */
object ApplicationQueries:

  /** Returns each outermost application chain, then chains in its callee and arguments. Inner nodes
    * of the same left-associated chain are represented by its outermost application only.
    */
  def applications(term: Term): List[App] = term match
    case app: App =>
      val (callee, args) = application(app)
      app :: (applications(callee) ++ args.flatMap(applications))
    case other => TermTraversal.children(other).flatMap(applications)

  /** Returns the base callee and arguments in their written order across a complete chain. */
  def application(app: App): (Ref | Lambda, List[Expr]) =
    val (callee, nodes) = applicationNodes(app)
    (callee, nodes.map(_.arg))

  /** Retains original application nodes from innermost to outermost, including each invocation's
    * source, annotations, and result type. Invocation boundaries are not inferred here.
    */
  def applicationNodes(app: App): (Ref | Lambda, List[App]) =

    @scala.annotation.tailrec
    def collect(current: App, outer: List[App]): (Ref | Lambda, List[App]) = current.fn match
      case inner: App => collect(inner, current :: outer)
      case root:  (Ref | Lambda) => (root, current :: outer)

    collect(app, Nil)

  /** Reads the callee's computed type, falling back to its indexed declaration only when absent.
    * Missing or dangling reference IDs provide no fallback. Canonical aliases, groups, and schemes
    * may supply a function type; an unsuitable callee type does not retry the declaration. A
    * signature alone does not establish that the callee or its operands are available.
    */
  def signature(callee: Callee, index: ResolvablesIndex): Option[TypeFn] =
    val declaredType = callee match
      case ref: Ref =>
        ref.resolvedId
          .flatMap(index.lookup)
          .collect { case value: Typeable => value }
          .flatMap(_.typeSpec)
      case _ => none

    callee.typeSpec
      .orElse(declaredType)
      .flatMap(TypeQueries.canonical(_, index))
      .collect { case callable: TypeFn => callable }
