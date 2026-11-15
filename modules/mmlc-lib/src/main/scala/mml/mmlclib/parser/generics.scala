package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** Parsed variable-constraint entries and malformed clauses retained for ingest diagnostics. */
private[parser] case class ParsedConstraints(
  entries: List[TypeVariableConstraints],
  errors:  List[ParseTermError]
)

/** A valid where clause leaves the following body available when its equals is missing. Malformed
  * clauses recover to an equals, semicolon, or declaration boundary.
  */
private[parser] def functionConstraintsP(info: SourceInfo)(using P[Any]): P[ParsedConstraints] =

  def protocolRefP: P[ProtocolRef] = tokenP(info)(typeIdP).map { token =>
    ProtocolRef(SourceOrigin.Loc(token.location), Name(token.location, token.value))
  }

  def variableConstraintsP: P[TypeVariableConstraints] =
    P(typeVariableP(info) ~ ":" ~ protocolRefP.rep(min = 1, sep = "&")).map {
      case (variable, constraints) =>
        val end = sourceEnd(constraints.last, locSpan(variable).end)
        TypeVariableConstraints(
          SourceOrigin.Loc(span(locSpan(variable).start, end)),
          variable,
          constraints.toList
        )
    }

  val recovery = Recovery.Context(
    List("=", ";").map(Recovery.Anchor.Token(_)),
    declarationStops
  )

  def clauseP: P[ParsedConstraints] =
    P(
      pointP(info) ~ whereKw ~/ (
        P(variableConstraintsP.rep(min = 1, sep = ",") ~ !("&" | "," | ":")).map { entries =>
          ParsedConstraints(entries.toList, Nil)
        } |
          P(pointP(info) ~~ recoveryP(info, recovery)).map { case (start, result) =>
            ParsedConstraints(
              Nil,
              List(
                bindingError(
                  info,
                  start,
                  info.pointAt(result.end),
                  SyntaxError.InvalidFunctionConstraints
                )
              )
            )
          }
      )
    ).map { case (_, parsed) => parsed }

  P(clauseP.?).map(_.getOrElse(ParsedConstraints(Nil, Nil)))

/** A certain type argument makes a candidate application explicit. Bare uppercase names and groups
  * containing only names keep their ordinary term syntax until semantic disambiguation. Candidate
  * arguments still require semantic classification when a constructor value is possible.
  */
private[parser] def functionTypeAppP(info: SourceInfo)(using P[Any]): P[Term] =

  def calleeP: P[Term] =
    P(
      tokenP(info)(bindingIdP).map(token => Ref(SourceOrigin.Loc(token.location), token.value)) |
        (pointP(info) ~ "(" ~ calleeP ~ tokenP(info)(")")).map { case (start, callee, close) =>
          val source = SourceOrigin.Loc(span(start, close.location.end))
          TermGroup(source, Expr(source, List(callee)))
        }
    )

  def argumentP: P[Type] =
    P(typeVariableP(info) | typeRefP(info) | groupedP)

  // Speculation accepts only type-shaped tokens; value ascriptions must reach the term parser.
  // An arrow may lack its result here so the recovering type parser can diagnose that omission.
  def atomSyntaxP: P[Unit] =
    P(typeIdP.map(_ => ()) | ("'" ~~ typeIdP).map(_ => ()) | groupSyntaxP)

  def groupSyntaxP: P[Unit] =
    P("(" ~ atomSyntaxP.rep(min = 1) ~ (arrowKw ~ atomSyntaxP.rep).rep ~ ")")

  def groupedP: P[Type] =
    P(
      &(groupSyntaxP) ~ pointP(info) ~ "(" ~ &("'" | typeIdP | "(") ~
        typeSpecP(info, TypeContext.Generic(TypeContext.Expression)) ~ tokenP(info)(")")
    )
      .map { case (start, tpe, close) =>
        TypeGroup(span(start, close.location.end), List(tpe))
      }

  def certain(tpe: Type): Boolean = TypeTraversal.exists(tpe) {
    case _: TypeVariable | _: TypeFn => true
    case _ => false
  }

  NoCut(P(calleeP ~ argumentP.rep(min = 1)))
    .filter { case (_, arguments) => arguments.exists(certain) }
    .map { case (callee, arguments) =>
      FnTypeApp(
        source = SourceOrigin
          .Loc(span(locSpan(callee).start, sourceEnd(arguments.last, locSpan(callee).end))),
        fn   = callee,
        args = arguments.toList
      )
    }
