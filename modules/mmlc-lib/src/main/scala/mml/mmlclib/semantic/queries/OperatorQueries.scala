package mml.mmlclib.semantic.queries

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.semantic.ProtocolRegistry

/** Operator facts shared by declaration checking and expression grouping. */
object OperatorQueries:

  /** A recognized operator declaration and its complete syntax attributes. */
  case class Candidate(node: Resolvable, arity: CallableArity, grouping: BindingKind.Operator)

  /** The first declaration of an arity and a later declaration that disagrees with it. */
  case class Disagreement(first: Resolvable, other: Resolvable)

  enum Grouping:
    case Absent
    case Coherent(candidates: NonEmptyList[Candidate], attributes: BindingKind.Operator)
    case Conflict(disagreements: NonEmptyList[Disagreement])

  /** Retains recognized operators in input order, ignoring missing or non-operator declarations. */
  def candidates(ids: List[String], index: ResolvablesIndex): List[Candidate] =
    ids.flatMap { id =>
      for
        node <- index.lookup(id)
        meta <- ProtocolRegistry.metadata(node)
        grouping <- meta.kind match
          case operator: BindingKind.Operator => operator.some
          case _ => none
      yield Candidate(node, meta.arity, grouping)
    }

  /** Returns Absent for non-references or no recognized candidates of the requested arity, Coherent
    * when their grouping agrees, and Conflict otherwise. Reference candidates are deduplicated in
    * input order; missing declarations and non-operators are ignored during recovery.
    */
  def grouping(term: Term, arity: CallableArity, index: ResolvablesIndex): Grouping = term match
    case ref: Ref => analyze(candidates(ref.candidateIds.distinct, index), arity)
    case _ => Grouping.Absent

  /** Compares every candidate of one arity with its first declaration. Input candidates must share
    * one source operator name; candidates of other arities are ignored.
    */
  def analyze(candidates: List[Candidate], arity: CallableArity): Grouping =
    NonEmptyList.fromList(candidates.filter(_.arity == arity)) match
      case None => Grouping.Absent
      case Some(matching) =>
        NonEmptyList.fromList(conflicts(matching.toList)) match
          case Some(conflicts) => Grouping.Conflict(conflicts)
          case None => Grouping.Coherent(matching, matching.head.grouping)

  /** Reports conflicts in declaration order, comparing each arity against its first declaration.
    * Input candidates must share one source operator name; this query does not partition by name.
    */
  def conflicts(candidates: List[Candidate]): List[Disagreement] =
    val checked = candidates.foldLeft(Checked()) { (current, candidate) =>
      current.firstByArity.get(candidate.arity) match
        case Some(first) =>
          current.copy(disagreements = current.disagreements ++ compare(first, candidate))
        case None =>
          current.copy(firstByArity = current.firstByArity.updated(candidate.arity, candidate))
    }
    checked.disagreements.toList

  private case class Checked(
    firstByArity:  Map[CallableArity, Candidate] = Map.empty,
    disagreements: Vector[Disagreement]          = Vector.empty
  )

  /** True for a reference with any indexed operator candidate, regardless of arity or grouping
    * conflicts. Missing declarations, non-operator candidates, and other terms do not qualify.
    */
  def isOperator(term: Term, index: ResolvablesIndex): Boolean = term match
    case ref: Ref => ref.candidateIds.exists(id => candidates(List(id), index).nonEmpty)
    case _ => false

  private def compare(first: Candidate, other: Candidate): Option[Disagreement] =
    Option.when(first.grouping != other.grouping)(Disagreement(first.node, other.node))
