package mml.mmlclib.ast

/** Recovered syntax requirements; nested failures retain their own source and failed text. */
enum SyntaxError derives CanEqual:
  case ExpectedBindingName
  case InvalidParameterList
  case ExpectedExpression
  case InvalidExpressionSyntax
  case ExpectedType
  case InvalidTypeSyntax
  case EmptyStruct
  case EmptyTuple
  case InvalidMemberSyntax
  case MisplacedLink
  case InvalidProtocolHeader
  case ProtocolParameterCount
  case ParameterizedSignature
  case DefaultProtocolImplementation
  case MissingSignatureTypes
  case ParameterizedInstance
  case MissingInstanceBody
  case OperatorParameterCount
  case FunctionOperatorAttributes
  case InvalidConstraints
  case InstanceMemberConstraints
  case ExpectedToken(token: String)
  case InvalidIdentifier(name: String, kind: IdentifierKind, explainRules: Boolean)
  case InvalidLink
  case Group(errors: List[Error])

enum IdentifierKind:
  case Binding, Function, Operator
