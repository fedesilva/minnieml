package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** A protocol supplies required signatures for one type parameter, for example:
  * {{{protocol Eq 'T = fn equal(left: 'T, right: 'T): Bool; ;}}}
  */
private[parser] def protocolP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info) ~ visibilityP.? ~ pointP(info) ~ protocolKw ~
      tokenP(info)(typeIdP) ~ typeVariableP(info).rep ~ headerEndP(
        info,
        "Protocol prerequisites are not supported"
      ) ~
      protocolCallableP(info).rep ~ requiredBindingTokenP(info, ";") ~~ pointP(info)
  ).map { case (doc, visibility, start, name, variables, tail, members, endError, end) =>
    val parameterErrors = Option
      .when(variables.size != 1)(
        "Protocols require exactly one type parameter; multiple parameters are not supported"
      )
      .toList
    val headerErrors = tail
    val memberErrors = members.toList.flatMap { member =>
      member.errors ++
        Option.when(member.types.exists(hasTypeApplication))(
          "Parameterized signature types are not supported"
        ) ++
        Option.when(member.body.nonEmpty)("Default protocol implementations are not supported") ++
        Option.when(member.parameters.exists(_.typeAsc.isEmpty) || member.result.isEmpty)(
          "Protocol signatures require parameter and return types"
        ) ++
        Option.when(
          member.types.flatMap(typeVariables).exists(v => !variables.exists(_.name == v))
        )(
          "Member-local type variables are not supported"
        )
    }
    val errors = parameterErrors ++ headerErrors ++ memberErrors ++ endError.map(_.message)
    variables.toList match
      case parameter :: Nil if errors.isEmpty =>
        Protocol(
          source        = SourceOrigin.Loc(span(start, end)),
          nameNode      = Name(name.location, name.value),
          typeParameter = parameter,
          signatures    = members.toList.flatMap(_.signature),
          visibility    = visibility.getOrElse(Visibility.Protected),
          docComment    = doc
        )
      case _ => declarationError(info, start, end, errors)
  }

/** Instance members keep omitted annotations and operator attributes absent for signature checking.
  * {{{instance Eq Int = fn equal(left, right) = left == right;; ;}}}
  */
private[parser] def instanceP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info) ~ pointP(info) ~ instanceKw ~ tokenP(info)(typeIdP) ~
      typeSpecP(info, TypeContext.Protocol(TypeContext.BindingHeader)) ~ headerEndP(
        info,
        "Parameterized instances and instance constraints are not supported"
      ) ~
      protocolCallableP(info).rep ~ requiredBindingTokenP(info, ";") ~~ pointP(info)
  ).map { case (doc, start, name, concreteType, tail, members, endError, end) =>
    val headerErrors = tail ++ Option
      .when(typeVariables(concreteType).nonEmpty || hasTypeApplication(concreteType))(
        "Parameterized instances and instance constraints are not supported"
      )
      .toList
    val memberErrors = members.toList.flatMap { member =>
      member.errors ++ Option.when(member.body.isEmpty)("Instance members require a body") ++
        Option.when(member.types.flatMap(typeVariables).nonEmpty)(
          "Member-local type variables are not supported"
        )
    }
    val errors = headerErrors ++ memberErrors ++ endError.map(_.message)
    if errors.nonEmpty then declarationError(info, start, end, errors)
    else
      ProtocolInstance(
        source   = SourceOrigin.Loc(span(start, end)),
        protocol = ProtocolRef(SourceOrigin.Loc(name.location), Name(name.location, name.value)),
        concreteType = concreteType,
        members      = members.toList.flatMap(_.instanceMember),
        docComment   = doc
      )
  }

/** Header recovery stops before the body or the next declaration. */
private def headerEndP(info: SourceInfo, unsupported: String)(using P[Any]): P[List[String]] =
  val context =
    Recovery.Context(
      List(Recovery.Anchor.Token("=")),
      Recovery.Anchor.Token(";") :: declarationStops
    )
  P(pointP(info) ~~ recoveryP(info, context) ~ requiredBindingTokenP(info, "="))
    .map { case (start, recovered, missing) =>
      val text = info.slice(start.index, recovered.end).trim
      Option.when(text.nonEmpty)(unsupported).toList ++ missing.map(_.message)
    }

private def declarationError(
  info:   SourceInfo,
  start:  SrcPoint,
  end:    SrcPoint,
  errors: List[String]
): ParsingMemberError =
  ParsingMemberError(
    span(start, end),
    errors.distinct.mkString("; "),
    info.slice(start.index, end.index).some
  )

/** Both required signatures and instance bodies use ordinary parameter and expression parsers. */
private def protocolCallableP(info: SourceInfo)(using P[Any]): P[ParsedProtocolCallable] =
  P(
    docCommentP(info) ~ pointP(info) ~ protocolCallableNameP(info) ~ pointP(info) ~
      parsedParametersP(info, TypeContext.Protocol(TypeContext.Parameter)) ~
      typeAscP(info, TypeContext.Protocol(TypeContext.Operator)) ~
      precedenceP.? ~ assocP.? ~ unsupportedMemberConstraintsP(info).? ~
      ("=" ~ terminatedBindingExprP(info)).? ~ requiredBindingTokenP(info, ";") ~~ pointP(info)
  ).map {
    case (
          doc,
          start,
          (origin, parsedName),
          lambdaStart,
          parameters,
          result,
          precedence,
          assoc,
          constraints,
          body,
          endError,
          end
        ) =>
      val name = parsedName.value.map(Name(parsedName.location, _))
      val errors =
        parsedName.error.map(_.message).toList ++ parameters.error.map(_.message) ++
          endError.map(_.message) ++ constraints ++
          Option.when(
            origin == BindingOrigin.Operator && !Set(1, 2).contains(parameters.values.size)
          )(
            "Operators require one or two parameters"
          ) ++
          Option.when(origin == BindingOrigin.Function && (precedence.nonEmpty || assoc.nonEmpty))(
            "Only operators have precedence and associativity"
          )
      ParsedProtocolCallable(
        span(start, end),
        name,
        origin,
        parameters.values,
        result,
        precedence,
        assoc,
        body.map { case (expression, bodyEnd) =>
          Lambda(span(lambdaStart, bodyEnd), parameters.values, expression, Nil, typeAsc = result)
        },
        doc,
        errors
      )
  }

/** Function names use the ordinary binding grammar; operators also admit symbolic names. */
private def protocolCallableNameP(info: SourceInfo)(using
  P[Any]
): P[(BindingOrigin, ParsedBindingName)] =
  P(
    (fnKw ~ parsedBindingNameP(info)).map(name => (BindingOrigin.Function, name)) |
      (opKw ~ tokenP(info)(operatorIdOrError)).map { token =>
        val error = token.value.left.toOption.map { invalid =>
          bindingError(
            info,
            token.location.start,
            token.location.end,
            s"Invalid operator name '$invalid'"
          )
        }
        val name = ParsedBindingName(token.location, token.value.toOption, error)
        (BindingOrigin.Operator, name)
      }
  )

/** A deferred where clause ends before the body or signature terminator. */
private def unsupportedMemberConstraintsP(info: SourceInfo)(using P[Any]): P[String] =
  val context = Recovery.Context(
    List("=", ";").map(Recovery.Anchor.Token(_)),
    declarationStops
  )
  P(whereKw ~ recoveryP(info, context)).map(_ => "Member constraints are not supported")

private def hasTypeApplication(tpe: Type): Boolean =
  TypeTraversal.exists(tpe)(_.isInstanceOf[TypeApplication])

private def typeVariables(tpe: Type): List[String] =
  TypeTraversal.collect(tpe) { case variable: TypeVariable => variable.name }

/** Written attributes are kept separate until the declaration determines whether defaults apply. */
private case class ParsedProtocolCallable(
  location:      SrcSpan,
  name:          Option[Name],
  origin:        BindingOrigin,
  parameters:    List[FnParam],
  result:        Option[Type],
  precedence:    Option[Int],
  associativity: Option[Associativity],
  body:          Option[Lambda],
  doc:           Option[DocComment],
  errors:        List[String]
):

  def types: List[Type] = parameters.flatMap(_.typeAsc) ++ result

  def signature: Option[ProtocolSignature] = for
    writtenName <- name
    returnType <- result
  yield ProtocolSignature(
    SourceOrigin.Loc(location),
    writtenName,
    parameters,
    returnType,
    metadata(writtenName),
    docComment = doc
  )

  def instanceMember: Option[ProtocolInstanceMember] = for
    writtenName <- name
    lambda <- body
  yield ProtocolInstanceMember(
    binding = Bnd(
      source     = writtenName.source,
      nameNode   = writtenName,
      value      = Expr(lambda.source, List(lambda)),
      typeAsc    = result,
      docComment = doc
    ),
    origin               = origin,
    writtenPrecedence    = precedence,
    writtenAssociativity = associativity
  )

  private def metadata(name: Name): BindingMeta =
    val operator = origin == BindingOrigin.Operator
    BindingMeta(
      origin,
      CallableArity.fromParameterCount(parameters.size),
      if operator then precedence.getOrElse(OperatorDefaults.precedence) else Precedence.Function,
      Option.when(operator)(
        associativity.getOrElse(OperatorDefaults.associativity(parameters.size))
      ),
      name.value,
      if operator then OpMangling.mangleOp(name.value, parameters.size) else name.value
    )
