# Parsing Error Handling: Invalid Identifiers

## Overview

This document describes the implementation plan for improving error handling when the parser encounters invalid
identifiers. Currently, invalid identifiers cause the entire member parsing to fail with a generic
`ParsingMemberError`. This enhancement will create specific error nodes for invalid identifiers, allowing the parser
to continue processing and providing better error messages to users.

## Background

The MML parser currently has three types of identifier parsers:

- `bindingIdP`: For function names, let bindings, and alphabetic operators (must start with lowercase a-z)
- `typeIdP`: For type names (must start with uppercase A-Z)
- `operatorIdP`: For operators (either symbolic or alphabetic)

When these parsers fail (e.g., `fn 123invalid()` or `type lowercase = Int`), the entire member parse fails and
creates a generic `ParsingMemberError` that provides little context about what went wrong.

## Goals

1. Allow the parser to continue processing after encountering invalid identifiers
2. Provide specific, educational error messages that explain identifier rules
3. Maintain consistency with existing error handling patterns
4. Keep implementation and testing effort reasonable

## Implementation Details

### 1. New AST Node

Add a new error node to `AstNode.scala` following the existing `ParsingMemberError` pattern:

```scala
case class ParsingIdError(
  span:       SrcSpan,
  message:    String,
  failedCode: Option[String],
  invalidId:  String,
  idType:     String  // "binding", "operator", or "type"
) extends Member, Error
```

### 2. Wrapper Parsers

In `Parser.scala`, create wrapper functions that attempt ID parsing and return `Either[String, String]`:

```scala
private def bindingIdOrError[$: P]: P[Either[String, String]] =
  // Attempt to capture any identifier using the existing parser, then validate
  // Return Right(id) if valid, Left(invalidId) if not

private def typeIdOrError[$: P]: P[Either[String, String]] =
  // Similar pattern for type identifiers

private def operatorIdOrError[$: P]: P[Either[String, String]] =
  // Similar pattern for operators
```

### 3. Update Member Parsers

Modify the member parsers (`fnDefP`, `letP`, `binOpDefP`, `unaryOpDefP`) to handle Either results:

```scala
// Example for letP
private def letP(source: String)(using P[Any]): P[Member] =
  P(spP(source) ~ letKw ~ ws ~ bindingIdOrError ~ /* rest of parser */)
    .map { case (sp, idOrError, typeAsc, value, end) =>
      idOrError match {
        case Left(invalidId) =>
          ParsingIdError(
            span = SrcSpan(sp, end),
            message = s"Invalid identifier '$invalidId'. Identifiers must start with a lowercase letter (a-z) " +
              "followed by letters, digits, or underscores",
            failedCode = Some(invalidId),
            invalidId = invalidId,
            idType = "binding"
          )
        case Right(id) =>
          Bnd(
            span = SrcSpan(sp, end),
            name = id,
            value = value,
            typeAsc = typeAsc
          )
      }
    }
```

This approach:

- Parses all components once
- Checks the Either result after parsing
- Creates appropriate node based on success/failure
- Avoids duplicating the parser structure

### 4. Error Messages

Each identifier type should have a specific error message explaining the rules:

- **Binding IDs**: `"Invalid identifier 'X'. Identifiers must start with a lowercase letter (a-z) followed by
  letters, digits, or underscores"`
- **Type IDs**: `"Invalid identifier 'X'. Type identifiers must start with an uppercase letter (A-Z) followed by
  letters or digits"`
- **Operator IDs**: `"Invalid identifier 'X'. Operators must be either symbolic (using =!#$%^&*+<>?/\|~-) or
  follow identifier rules (lowercase start + alphanumeric)"`

### 5. Update Error Processing

In `ParsingErrorChecker.scala`, update to handle the new error type:

```scala
def checkModule(state: SemanticPhaseState): SemanticPhaseState =
  val errors = state.module.members.collect {
    case error: ParsingMemberError =>
      SemanticError.MemberErrorFound(error, phaseName)
    case error: ParsingIdError =>
      SemanticError.MemberErrorFound(error, phaseName)
  }
  state.addErrors(errors)
```

## Testing Strategy

Create focused, representative tests without over-testing:

1. **InvalidIdParsingTest.scala** - New test file with:
   - Test invalid binding ID: `let 123invalid = 5`
   - Test invalid type ID: `type lowercase = Int`
   - Test invalid operator ID: `op bad@op(x, y) = x`
   - Test parser continuation: Multiple members with one invalid ID
   - Test error message content: Verify rules are included

Total: ~5 tests covering the main scenarios

## Implementation Order

1. Add `ParsingIdError` to `AstNode.scala`
2. Implement wrapper parsers in `Parser.scala`
3. Update `letP` as proof of concept
4. Create basic test for `letP` changes
5. Update remaining member parsers (`fnDefP`, `binOpDefP`, `unaryOpDefP`)
6. Update `ParsingErrorChecker.scala`
7. Complete test suite

## Files to Modify

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/AstNode.scala` - Add new error node
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/Parser.scala` - Add wrapper parsers and update member
  parsers
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ParsingErrorChecker.scala` - Handle new error type
- `modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/InvalidIdParsingTest.scala` - New test file

## Success Criteria

- Parser continues processing after invalid identifiers
- Clear error messages that explain identifier rules
- All existing tests continue to pass
- New tests demonstrate the feature works correctly
