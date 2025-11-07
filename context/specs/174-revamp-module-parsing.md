# Issue #174: Revamp Module Parsing

## Problem

Module parsing is fragile. When invalid syntax appears at the top level, the parser fails with a misleading error:

```mml
let a = 1;
rubbish-at-the-end
```

Produces:
```
Parse error:
  Failure(Expected moduleP:1:1 / (namedModuleP | anonModuleP):2:1, found "let a = 1;")
Failed to parse module
```

The error points to valid code (line 1) instead of the actual problem (line 2).

## Root Cause

**Complex module dispatch**: Current architecture has `moduleP` dispatching between `namedModuleP` and `anonModuleP` with conditional logic based on whether a name is provided. This creates fragile parsing with confusing error messages.

## Solution

Simplify to a single top-level module parser that always requires a name (provided by the CLI from the filename).

---

## Changes

### 1. Parser.scala

**Before:**
```scala
def parseModule(source: String, name: Option[String] = "Anon".some): ParserResult =
  parse(source, moduleP(name, source, _)) match
    // ...
```

**After:**
```scala
def parseModule(source: String, name: String): ParserResult =
  parse(source, topLevelModuleP(name, source, _)) match
    case Parsed.Success(result, _) => result.asRight
    case f: Parsed.Failure => ParserError.Failure(f.trace().longMsg).asLeft
```

- Name is required (not `Option`)
 `namedModuleP` function
- `anonModuleP` function
- `moduleEndKw` function
- `modVisibilityP` function

### 3. AstNode.scala

**Before:**
```scala
case class Module(
  span:       SrcSpan,
  name:       String,
  visibility: ModVisibility,
  members:    List[Member],
  isImplicit: Boolean            = false,
  docComment: Option[DocComment] = None
) extends AstNode, FromSource
```

**After:**
```scala
case class Module(
  span:       SrcSpan,
  name:       String,
  visibility: ModVisibility,
  members:    List[Member],
  docComment: Option[DocComment] = None
) extends AstNode, FromSource
```

- Remove `isImplicit` field entirely

### 4. ParserApi.scala

**Before:**
```scala
def parseModuleString(
  source: String,
  name:   Option[String] = None
): ParserEffect[Module] =
  val n = name.map(sanitizeModuleName)
  EitherT(IO.pure(Parser.parseModule(source, n)))
```

**After:**
```scala
def parseModuleString(
  source: String,
  name:   String
): ParserEffect[Module] =
  val sanitized = sanitizeModuleName(name)
  EitherT(IO.pure(Parser.parseModule(source, sanitized)))
```

- Name is required (not `Option`)

### 5. CompilerApi.scala

**Before:**
```scala
def compileString(
  source: String,
  name:   Option[String] = None
): CompilerEffect[Module] =
  for
    parsedModule <- ParserApi
      .parseModuleString(source, name)
      // ...
```

**After:**
```scala
def compileString(
  source: String,
  name:   String
): CompilerEffect[Module] =
  for
    parsedModule <- ParserApi
      .parseModuleString(source, name)
      .leftMap(error => CompilerError.ParserErrors(List(error)))
    module <- SemanticApi.rewriteModule(parsedModule)
  yield module
```

- Name is required (not `Option`)

### 6. Test Helpers - BaseEffFunSuite.scala

**Before:**
```scala
def parseNotFailed(
  source: String,
  name:   Option[String] = None
): IO[Module] = ...

def parseFailed(
  source: String,
  name:   Option[String] = None
): IO[Unit] = ...
```

**After:**
```scala
def parseNotFailed(
  source: String,
  name:   String
): IO[Module] = ...

def parseFailed(
  source: String,
  name:   String
): IO[Unit] = ...
```

- Change from `Option[String]` to `String`
- **No defaults**: every callsite must pass an explicit module name (test helpers may keep a
  convenience default since there is no backing file)

### 7. Tests - ModuleTests.scala

**Changes:**
- Delete "explicit module. name passed, ignored" test (lines 10-22)
- Rename "implicit module, name passed" → "top-level module"
- Remove all `.isImplicit` assertions
- Update `parseFailed` test to pass name explicitly (remove `None`)
- Enable ignored tests: "anon module with rubbish at the end should not abort" and "module with rubbish at the end should not abort"
- Remove the second ignored test (was for named modules)

### 8. Documentation - docs/design-and-semantics.md

**Updates:**
- Section 6, Module Structure: Remove `isImplicit` field from Module case class
- Remove references to "anonymous" vs "named" modules
- Document: top-level modules are just sequences of members, no keywords, no trailing semicolon
- Note: nested modules (future feature) will use `module Name = ...;` syntax as member declarations

---

## Summary

**Delete:**
- 5 parser functions: `moduleP`, `namedModuleP`, `anonModuleP`, `moduleEndKw`, `modVisibilityP`
- 1 AST field: `Module.isImplicit`

**Create:**
- 1 parser function: `topLevelModuleP`

**Modify:**
- 6 files: Parser.scala, modules.scala, AstNode.scala, ParserApi.scala, CompilerApi.scala, BaseEffFunSuite.scala
- Update: ModuleTests.scala, docs/design-and-semantics.md
- Ensure **all** module-related APIs/tests pass names explicitly (no default parameters) except for
  testing/yolo helper conveniences that generate synthetic modules.

**Result:**
- Simpler architecture (one module parser, no dispatch logic)
- Module name always required (from filename in CLI)
- Clearer separation: top-level modules vs future nested modules

---

## Future Work

When nested modules are added:
- Reintroduce `module Name = ...;` syntax as a member declaration
- Nested modules will be parsed by a member parser, not the top-level parser
- They will require trailing semicolons like other members
