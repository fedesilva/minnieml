package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState

/** Groups operators by their shared syntax while retaining every possible implementation. */
object OperatorGrouping:

  /** Visible declarations of the same operator and arity must agree even when unused. For example,
    * two protocols may declare binary + at precedence 70, but one cannot declare it at 80: the
    * expression tree must be known before operand types select a protocol instance.
    */
  def checkModule(state: CompilerState): CompilerState =
    val index = state.module.resolvables
    val scope = ProtocolLookupScope.module(state.module)
    val names = scope.levels.flatten.flatMap(index.lookup).map(ProtocolRegistry.sourceName).distinct
    val errors = names.flatMap { name =>
      state.protocols.visibleCandidates(name, scope, index) match
        case Left(ProtocolLookupError.RejectedDeclaration(_)) => Nil
        case Left(error) => List(ProtocolError.RegistryFailure(state.module, error))
        case Right(candidates) =>
          val operators = candidates.flatMap(candidate => operator(candidate.id, index))
          val checked = operators.foldLeft(CheckedOperators()) { (current, declaration) =>
            current.firstByArity.get(declaration.meta.arity) match
              case Some(first) if !sameGrouping(first.meta, declaration.meta) =>
                current.copy(errors =
                  current.errors :+
                    ProtocolError.OperatorGroupingConflict(first.node, declaration.node)
                )
              case Some(_) => current
              case None =>
                current.copy(firstByArity =
                  current.firstByArity.updated(declaration.meta.arity, declaration)
                )
          }
          checked.errors
    }
    state.addErrors(errors.map(SemanticError.ProtocolErrorFound(_, phaseName)))

  /** Binary syntax retains all binary candidates; it does not choose a concrete instance. */
  def binary(term: Term, index: ResolvablesIndex): Option[GroupedOperator] =
    grouped(term, CallableArity.Binary, index)

  /** Right-associative unary declarations are prefix operators, such as -value. */
  def prefix(term: Term, index: ResolvablesIndex): Option[GroupedOperator] =
    grouped(term, CallableArity.Unary, index).filter(_.associativity == Associativity.Right)

  /** Left-associative unary declarations are postfix operators, such as value!. */
  def postfix(term: Term, index: ResolvablesIndex): Option[GroupedOperator] =
    grouped(term, CallableArity.Unary, index).filter(_.associativity == Associativity.Left)

  /** Operator names end an ordinary juxtaposed argument sequence. */
  def isOperator(term: Term, index: ResolvablesIndex): Boolean = term match
    case ref: Ref => ref.candidateIds.exists(id => operator(id, index).nonEmpty)
    case _ => false

  /** Explain a refused grouping using the same declarations and arity as the successful query. A
    * binary + conflict does not invalidate a consistent unary + used in prefix position.
    */
  def conflict(
    term:  Term,
    arity: CallableArity,
    index: ResolvablesIndex
  ): Option[ProtocolError.OperatorGroupingConflict] = term match
    case ref: Ref =>
      val candidates = operatorCandidates(ref, arity, index)
      for
        first <- candidates.headOption
        other <- candidates.find(candidate => !sameGrouping(first.meta, candidate.meta))
      yield ProtocolError.OperatorGroupingConflict(first.node, other.node)
    case _ => none

  private val phaseName = "mml.mmlclib.semantic.OperatorGrouping"

  private def grouped(
    term:  Term,
    arity: CallableArity,
    index: ResolvablesIndex
  ): Option[GroupedOperator] = term match
    case ref: Ref =>
      val candidates = operatorCandidates(ref, arity, index)
      for
        first <- candidates.headOption
        if candidates.forall(candidate => sameGrouping(first.meta, candidate.meta))
        associativity <- first.meta.associativity
      yield
        val ids = candidates.flatMap(_.node.id)
        val resolvedId = candidates match
          case OperatorDeclaration(binding: Bnd, _) :: Nil => binding.id
          case _ => none
        GroupedOperator(
          ref.copy(candidateIds = ids, resolvedId = resolvedId),
          first.meta.precedence,
          associativity
        )
    case _ => none

  private def operatorCandidates(
    ref:   Ref,
    arity: CallableArity,
    index: ResolvablesIndex
  ): List[OperatorDeclaration] =
    ref.candidateIds.distinct.flatMap(id => operator(id, index)).filter(_.meta.arity == arity)

  private def operator(id: String, index: ResolvablesIndex): Option[OperatorDeclaration] =
    for
      node <- index.lookup(id)
      meta <- ProtocolRegistry.metadata(node)
      if meta.origin == BindingOrigin.Operator
    yield OperatorDeclaration(node, meta)

  private def sameGrouping(left: BindingMeta, right: BindingMeta): Boolean =
    left.precedence == right.precedence && left.associativity == right.associativity

  private case class OperatorDeclaration(node: Resolvable, meta: BindingMeta)

  private case class CheckedOperators(
    firstByArity: Map[CallableArity, OperatorDeclaration] = Map.empty,
    errors:       List[ProtocolError]                     = Nil
  )

/** The expression rewriter consumes shared grouping attributes and an arity-narrowed reference.
  * Even one protocol signature remains unresolved: its implementation depends on operand types.
  */
case class GroupedOperator(ref: Ref, precedence: Int, associativity: Associativity)
