package mml.mmlclib.util.error.print

import mml.mmlclib.ast.*

/** Shared wording for recovered syntax, preserving the order of grouped failures. */
def syntaxMessage(error: SyntaxError): String = error match
  case SyntaxError.ExpectedBindingName => "Expected binding name"
  case SyntaxError.InvalidParameterList => "Invalid parameter list"
  case SyntaxError.ExpectedExpression => "Expected expression"
  case SyntaxError.InvalidExpressionSyntax => "Invalid expression syntax"
  case SyntaxError.ExpectedType => "Expected type"
  case SyntaxError.InvalidTypeSyntax => "Invalid type syntax"
  case SyntaxError.EmptyStruct => "Struct declarations must define at least one field"
  case SyntaxError.EmptyTuple => "Tuple must have at least one element"
  case SyntaxError.InvalidMemberSyntax => "Failed to parse member"
  case SyntaxError.MisplacedLink => "@link must occur once, before all module members"
  case SyntaxError.ProtocolPrerequisites => "Protocol prerequisites are not supported"
  case SyntaxError.ProtocolParameterCount =>
    "Protocols require exactly one type parameter; multiple parameters are not supported"
  case SyntaxError.ParameterizedSignature => "Parameterized signature types are not supported"
  case SyntaxError.DefaultProtocolImplementation =>
    "Default protocol implementations are not supported"
  case SyntaxError.MissingSignatureTypes => "Protocol signatures require parameter and return types"
  case SyntaxError.MemberTypeVariables => "Member-local type variables are not supported"
  case SyntaxError.ParameterizedInstance =>
    "Parameterized instances and instance constraints are not supported"
  case SyntaxError.MissingInstanceBody => "Instance members require a body"
  case SyntaxError.OperatorParameterCount => "Operators require one or two parameters"
  case SyntaxError.FunctionOperatorAttributes => "Only operators have precedence and associativity"
  case SyntaxError.MemberConstraints => "Member constraints are not supported"
  case SyntaxError.ExpectedToken(token) => s"Expected '$token'"
  case SyntaxError.InvalidIdentifier(name, kind, explainRules) =>
    val nameRule = "must start with a lowercase letter (a-z) " +
      "followed by letters, digits, or underscores"
    kind match
      case IdentifierKind.Binding =>
        s"Invalid identifier '$name'" + (if explainRules then s". Identifiers $nameRule" else "")
      case IdentifierKind.Function =>
        s"Invalid function name '$name'" + (if explainRules then s". Function names $nameRule"
                                            else "")
      case IdentifierKind.Operator =>
        s"Invalid operator name '$name'" +
          (if explainRules then
             ". Operator names must be symbolic or follow binding identifier rules."
           else "")
  case SyntaxError.InvalidLink =>
    "Expected @link[\"library\", \"library\"]; " +
      "with nonempty library names, not paths or linker arguments"
  case SyntaxError.Group(errors) => errors.map(e => syntaxMessage(e.reason)).distinct.mkString("; ")

/** Describes why a recovered member cannot participate in semantic analysis. */
def memberInvalidityMessage(reason: mml.mmlclib.ast.MemberInvalidity): String = reason match
  case mml.mmlclib.ast.MemberInvalidity.DuplicateParameters(parameters) =>
    s"Duplicate parameter names: ${parameters.map(_.name).distinct.mkString(", ")}"
