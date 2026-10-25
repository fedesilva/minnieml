# Keeping useful compiler results after an error

A compiler used by an editor spends much of its time reading unfinished programs. An argument
is missing, a type annotation is half written, or a function refers to a name that does not
exist yet. Those errors should prevent executable generation. They should still leave enough
of the program available to explain the real problem and inspect the surrounding code.

MinnieML's parser keeps recognizable syntax around malformed fragments, and its semantic
phases retain successful rewrites and partial type information. Diagnostics refer back to
the original errors that caused them. The command line and editor hide a downstream report
only when the compiler can explain it using retained primary errors.

Recovery continues through resolution, expression normalization, type checking, partial
applications, and ownership analysis. Each phase works with the information it can establish
and records failures for later phases. Cause and availability analyses reuse immutable
summaries to limit repeated work.

## The problem: one bad value, many apparent problems

Consider this example from the type-checker regression tests:

```mml
fn identity(value: Int): Int = value;;
let broken = identity true;
let alias = broken;
let downstream = identity alias;
```

The first application supplies a Boolean where an integer is required. The later bindings
depend on that failed application. Reporting additional inference or unavailable-value
errors at every use makes the original mistake harder to find.

Simply dropping later errors is insufficient. The compiler must distinguish a dependent
failure from a separate error. In this example, both problems should remain visible:

```mml
fn identity(value: Int): Int = value;;
let result = missing (identity true);
```

The callee is undefined, and the argument contains an independently invalid application.
An early return after finding `missing` loses the argument error. Suppressing everything
under the outer application has the same effect.

The compiler also needs enough structure to continue. Its abstract syntax tree (AST)
represents declarations, expressions, and types. Losing a function because its body contains
one malformed fragment also loses its parameter scope and the valid expressions around the
error. Later analysis needs those parts, so recovery starts in the parser and carries the
partially understood tree through the semantic phases.

The examples above are executable test fixtures in
[TypeCheckerCauseTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeCheckerCauseTests.scala).
They assert visible errors, diagnostic identities, retained types, and unavailable results,
rather than only counting reports.

## Recovery through the compiler pipeline

The implementation separates three questions:

| Question                                    | Information used                                     | Decision                                         |
| ------------------------------------------- | ---------------------------------------------------- | ------------------------------------------------ |
| What can still be understood?               | Preserved syntax, resolved identities, partial types | Continue analysis and editor queries             |
| Can this expression supply a usable result? | Value-availability analysis                          | Permit dependent typing and ownership operations |
| Why did this result fail?                   | Primary diagnostic IDs and cause evidence            | Classify a later diagnostic for presentation     |

A partial type retained for editor queries does not establish that the expression can
supply a usable value. Likewise, a list of known errors may explain only some of its
dependencies. Availability and completeness of cause evidence need separate checks.

The overall flow is:

```text
Source
  |
  v
Parse and retain recoverable syntax
  |
  v
Assign primary IDs to syntax errors -----+
  |                                     |
  v                                     v
Analyze preserved trees ----------> Ordered diagnostic store
  |              |                       |             |
  |              |                       |             +--------+
  v              |                       v                      v
Partial typed    +----------------> Check raw errors       Select visible
tree for editor                          |                 diagnostics
queries                                  |                 for CLI / LSP
                               +---------+---------+
                               |                   |
                          No errors           Errors remain
                               |                   |
                               v                   v
                         Generate code      Report compilation
                                                 failure
```

The Language Server Protocol, or LSP, is the interface used by editor integrations. Its
diagnostic selection follows the same presentation policy as the command line. Neither
selection changes whether compilation has failed.

The [compiler design reference](../../design/compiler-design.md) describes the phase
contracts.

## Diagnostic records and error classification

### Primary errors and their consequences

[DiagnosticStore](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/errors/DiagnosticStore.scala)
stores an ordered sequence of `DiagnosticRecord` values. These declarations show the record
and the types that identify it and describe its relationship to other errors; helper methods
are omitted:

```scala
final case class DiagnosticId(value: Long) derives CanEqual

enum DiagnosticRelation derives CanEqual:
  case Primary
  case Consequence(roots: Vector[DiagnosticId])

final case class DiagnosticRecord(
  id:       DiagnosticId,
  error:    CompilationError,
  relation: DiagnosticRelation
)
```

A primary record is an independently reported problem. A consequence refers directly to
the primary records that explain it. A consequence can have several roots: both branches
of a conditional may fail independently, making its overall result unavailable.

The `error` field supplies the message, location, and failed text. A diagnostic ID identifies
that occurrence within one compilation; two reports with equal payloads can have different
IDs. When a diagnostic is emitted with causes, the store follows them to primary records,
removes duplicate roots, and orders them by emission.

For the opening example, call the mismatch in `identity true` diagnostic `D1`. The dependency
chain carries that root through both later bindings:

```text
broken = identity true     alias = broken       downstream = identity alias
         |                         |                         |
         v                         v                         v
   mismatch D1                cause [D1]                cause [D1]
   unavailable                unavailable               unavailable

Diagnostic store                         CLI / LSP
  D1: Primary, original mismatch  ------> show D1
  later dependent reports:
      Consequence([D1])           ------> hide these reports
```

The store hides a consequence only if its roots are nonempty and all identify retained
primary records. Missing IDs, empty explanations, or invalid relationships leave the report
visible. The original mismatch therefore supplies the explanation even when later phases
emit several reports about values that depend on it.

`CompilerState.errors` exposes every raw error. `visibleErrors` exposes the presentation
selection. Code generation checks the raw state, so a module with hidden consequences still
fails compilation. Formatting functions that accept a bare error cannot decide
suppression; that decision needs the diagnostic store.

This policy is tested directly in
[DiagnosticStoreTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/errors/DiagnosticStoreTests.scala)
and across the editor boundary in
[DiagnosticPresentationTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/DiagnosticPresentationTests.scala).

### Syntax errors, unresolved types, and invalid expressions

The error nodes describe different problems:

| Node                 | Meaning                                                          |
| -------------------- | ---------------------------------------------------------------- |
| `ParseTermError`     | A malformed term fragment or a missing required term component   |
| `ParseTypeError`     | A written type annotation contains malformed or missing syntax   |
| `UndefinedTypeError` | A syntactically valid type reference has no matching declaration |
| `AmbiguousTypeError` | A type reference matches multiple declarations                   |
| `InvalidExpression`  | Semantic analysis cannot establish a usable expression result    |

Parsing and name resolution answer different questions. The parser can recognize a valid
type reference without knowing whether its declaration exists. A malformed fragment becomes
`ParseTypeError`; a valid reference proceeds to resolution, which either finds its
declaration or records an undefined or ambiguous reference. Resolution errors retain the
original reference, including the candidate declarations when more than one matches.

The parser also distinguishes an omitted annotation from a malformed one. Compare these
declarations:

```mml
let inferred = 1;
let empty: = 2;
```

The first declaration asks the compiler to infer a type from `1`. In the second, the colon
starts an annotation, but `=` arrives before any type. The parser records the missing type
at that position and continues with the initializer `2`:

```text
Bnd inferred                         Bnd empty
  typeAsc: None                        typeAsc: Some(ParseTypeError)
  value: LiteralInt(1)                  |         zero-width span before '='
                                       value: LiteralInt(2)
```

These trees omit expression containers and other fields to show the distinction. `None`
allows inference; `Some(ParseTypeError)` records a syntax failure that inference cannot
erase. Both initializers are available for further analysis.

### Assigning diagnostic IDs to accepted syntax

Parser alternatives can fail and retry. Allocating diagnostic IDs during those attempts
would associate records with abandoned parsing paths. The parser instead produces error
nodes without diagnostic IDs. The ingest phase's
[ParsingErrorChecker](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/ParsingErrorChecker.scala)
walks the accepted recovered tree, reports primary syntax errors, and attaches their IDs.

The traversal adds diagnostic metadata while preserving syntax, spans, messages, and failed
text. It also accounts for annotation caches: the parser may place the same annotation object
in more than one tree position. One source occurrence should produce one diagnostic, while
two separate occurrences with equal contents should still produce two. The ingest checker
and type resolver use occurrence identity to distinguish them.

### Complete and incomplete cause evidence

[DiagnosticCauses](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DiagnosticCauses.scala)
tracks the errors behind a failed result. Before the checker hides a later report, it needs
to know whether those errors explain the whole failure or only part of it:

```scala
enum CauseEvidence derives CanEqual:
  case Complete(roots: Vector[DiagnosticId])
  case Incomplete(roots: Vector[DiagnosticId])
```

`Complete` means all the dependencies have been accounted for. `Incomplete` means something
is still unresolved or has failed without an attributed diagnostic. Both store the errors
already known; incomplete evidence cannot justify hiding a report.

This example shows why the distinction is needed:

```mml
fn target(x: Missing): Int = missing;;
fn caller(): Int = target "wrong";;
```

First, type resolution finds that `Missing` has no declaration and reports error `T`.
Value-reference resolution has not run yet, so it is too early to explain what is wrong
with the body name `missing`. Cause analysis at this point sees the following tree, with
expression containers and unrelated metadata omitted:

```text
After TypeResolver

Lambda target                         evidence
+-- parameter x
|   `-- UndefinedTypeError(Missing)    Complete([T])
+-- result annotation: Int            Complete([])
`-- body: Ref(missing, unresolved)     Incomplete([])
                                      ----------------
combined                              Incomplete([T])
known roots                           [T]
roots usable for a consequence        []
```

The analysis knows about `T`, but the body still needs checking. That is what
`Incomplete([T])` means. Reporting the type error is not enough: the user also needs to hear
about the undefined value in the body.

Next, reference resolution finds that `missing` has no declaration either. It reports error
`R` and wraps the reference in `InvalidExpression`. Analyzing this tree accounts for both
failures:

```text
After RefResolver

Lambda target                         evidence
+-- parameter x
|   `-- UndefinedTypeError(Missing)    Complete([T])
+-- result annotation: Int            Complete([])
`-- body: InvalidExpression           Complete([R])
    +-- causes: [R]                   ----------------
    `-- originalExpr: Ref(missing)

combined                              Complete([T, R])
known roots                           [T, R]
roots usable for a consequence        [T, R]
```

There are now two diagnosed problems, `T` and `R`, and no unresolved dependency in this
lambda. Its evidence is `Complete([T, R])`. This comes from analyzing the new tree.
Merely appending `R` to the old evidence would not resolve anything: `combine` keeps the
result incomplete if either input is incomplete, while merging and deduplicating its roots.

Finally, type checking reaches `caller`. The compiler has no usable parameter type for
`target`, so it cannot claim that `"wrong"` has the wrong type. It reports the two source
errors instead. These phase snapshots follow the resolver and cause-analysis rules;
[TypeCheckerCauseTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeCheckerCauseTests.scala)
checks the final diagnostics, and
[DiagnosticCausesTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/DiagnosticCausesTests.scala)
checks the complete/incomplete combination rules directly.

The empty case needs a separate explanation. A focused unit test constructs a literal
without type metadata to check that missing information does not invent a diagnostic cause:

```scala
val untyped = LiteralInt(source, 1L, typeSpec = None)

assert(!ValueAvailability.empty.isAvailable(untyped))
assertEquals(DiagnosticCauses.empty.evidenceOf(untyped), CauseEvidence.empty)
```

`CauseEvidence.empty` is `Complete(Vector.empty)`. Compare these two internal AST states:

```text
Without type metadata                 With resolved type metadata

LiteralInt(1)                         LiteralInt(1)
  typeSpec: None                        typeSpec: Some(resolved Int)
  cause evidence: Complete([])          cause evidence: Complete([])
  available: false                      available: true
```

Neither tree contains a diagnosed failure, so both have an empty list of causes. Only the
second has the type information that later checks need. These are test states: the parser
normally gives integer literals an `Int` type reference immediately, and type resolution
resolves that reference. The deliberately missing metadata isolates the distinction between
"there is no diagnostic cause" and "there is enough information to use this value."

Complete evidence also does not give permission to hide an unrelated error. The checker
must establish that the new failure comes from the value carrying those roots. The
independent bad argument in the opening `missing (identity true)` example still gets its
own diagnostic.

### Result dependencies through local bindings

Cause analysis follows the value an expression produces. It does not collect every error
anywhere below the expression and assign all of them to its result. For example:

```mml
fn identity(value: Int): Int = value;;
let continued = let broken = identity true; 42;
```

The local `let` is represented by an immediately applied lambda. Its parameter receives the
initializer, and its body computes the continuation. After checking the failed initializer,
the relevant tree is:

```text
continued
`-- App
    +-- fn: Lambda
    |   +-- parameter broken, identity B
    |   `-- body: LiteralInt(42)
    `-- arg: InvalidExpression(typeSpec = Int, causes = [D])
        `-- originalExpr: identity true
```

The analysis associates `B` with the initializer's evidence, then follows the body:

```text
initializer identity true  -> Complete([D])
local environment B        -> Complete([D])
body 42                    -> Complete([])
whole expression result   -> Complete([]), available
```

Because `42` does not use `B`, its result has no dependency on the failed initializer. A
body that references `broken` instead would inherit `D`. The diagnostic store records the
initializer's mismatch in either case, so this module fails compilation even though the
continuation result is available for analysis.

## Semantic analysis of partially valid trees

### Resolution and expression normalization

[TypeResolver](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala)
and [RefResolver](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala)
retain rewritten nodes alongside accumulated diagnostics. A failure in one type or reference
does not discard independently resolved siblings.

Undefined references become preserved invalid expressions carrying their primary IDs.
Malformed and unresolved types retain their specific error nodes. Existing AST restrictions
still apply: a callee is represented by a reference, application, or lambda. Recovery does
not widen that union indiscriminately; an invalid callee is retained inside an enclosing
expression that can carry the failed result.

[ExpressionRewriter](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala)
turns parsed term sequences into application trees, using operator precedence to determine
their structure. For example:

```mml
fn increment(x: Int): Int = x + 1;;
```

The parser records the body as three consecutive terms. After references have been resolved,
normalization makes their application structure explicit:

```text
Parsed body                           Normalized body

Expr                                  Expr
+-- Ref(x)                            `-- App
+-- Ref(+)                                +-- fn: App
`-- LiteralInt(1)                         |   +-- fn: Ref(+)
                                         |   `-- arg: Ref(x)
                                         `-- arg: LiteralInt(1)
```

The right-hand tree omits single-term `Expr` wrappers around arguments. Each `App` applies
one function to one argument, so binary addition becomes two applications. Nested operands
are normalized before their enclosing sequence. If the outer sequence fails, its error
payload includes the successfully normalized children. The malformed-function walkthrough
below shows that case in detail.

A failed flat region keeps its terms for later inspection. Recovery does not assign it
application or sequencing semantics that normalization could not derive.

### Partial types and value availability

The function in `identity true` declares an `Int` result, although the argument is invalid.
The editor can use that partial type to inspect the surrounding program. The checker stores
it in an `InvalidExpression` alongside the failed expression and its diagnostic roots:

```scala
case class InvalidExpression(
  source:       SourceOrigin,
  originalExpr: Expr,
  typeSpec:     Option[Type]         = None,
  typeAsc:      Option[Type]         = None,
  causes:       Vector[DiagnosticId] = Vector.empty
) extends Term,
      InvalidNode,
      FromSource
```

`typeSpec` holds the available type information; `typeAsc` holds an explicit annotation.
The wrapper's `originalExpr` lets later phases visit the operands that produced the failure.
Using `D` for the argument mismatch, application checking transforms the relevant part of
the tree as follows:

```text
Before argument checking                  After argument checking

App                                       InvalidExpression
+-- Ref(identity)                         +-- typeSpec: Some(Int)
`-- LiteralBool(true)                     +-- causes: [D]
                                          `-- originalExpr
                                              `-- App
                                                  +-- Ref(identity)
                                                  `-- LiteralBool(true)
```

[ValueAvailability](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ValueAvailability.scala)
rejects that result despite the retained type. It propagates unavailable values through
resolved binding IDs and aliases. This prevents later phases from treating an erroneous
value as evidence for a valid call, capture, or ownership transfer.

Stable IDs matter here. Names can be shadowed, and rewrites replace node instances. Analysis
uses the declaration identity and the refreshed index mapping that identity to its current
node. It does not infer relationships from equal names or equal source spans.

The checker still examines independent operands: a bad argument alongside an invalid callee,
or incompatible branch results alongside an invalid predicate. The tests exercise both
cases and require the separate primary diagnostics to survive.

### Conditional inference and provisional diagnostics

Some conditional checks first inspect both branches, infer a useful expected type, and then
check the branches again with that expectation. A provisional diagnostic can disappear
legitimately on the second pass. For example, an initially untyped hole can receive its
expected type from the other branch.

```mml
let result = if true then 1; else ???; ;
```

```text
Initial branch checks             Recheck with expected type Int

1    -> Int                       1    -> Int
???  -> no expected type          ???  -> Int
        provisional diagnostic           no diagnostic
```

[TypeChecker](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala)
discards the provisional branch trees and their diagnostic allocations together before
rechecking. Keeping the first pass's IDs while replacing its trees would leave stale errors
or references to diagnostic occurrences that no longer belong to the surviving analysis.

### Cause propagation across declarations and recursion

Cause and availability information can cross declarations in either source order.
The module analyses repeatedly update their summaries until another pass changes nothing.
This is a fixed point: a stable result reached by propagating information through all known
dependencies. Cause discovery adds existing roots or incomplete evidence; availability
discovery adds unavailable binding IDs.

Type checking orders acyclic dependencies before their callers. Recursive declarations can
use declared signatures while their bodies are checked. Availability during member checking
therefore includes checked members, while retaining the index needed to resolve signatures.
Preemptively treating every unfinished recursive body as an unavailable definition would
reject valid recursion.

### Availability in ownership and partial-application analysis

A partial application supplies some arguments to a callable and leaves the rest for a
later call. Its elaboration may create a closure and capture supplied values. That requires
usable callable and argument information. A failed result cannot establish that contract,
but independent members and continuations still pass through elaboration.

Ownership analysis has the same distinction. It visits preserved operands to find
independent use-after-move or borrowing errors. It does not use an unavailable result to
establish allocation, consumption, cloning, or an alias relationship. A malformed flat
expression stays malformed instead of being treated as an ordinary sequence of operations.

Some ownership diagnostics can themselves be consequences. If lexical value flow proves
that every possible supplying call has a reported invalid return, a downstream borrowed
capture report can refer to those primary return errors. Observed calls to an independently
checked function parameter do not supply that proof. Unknown alternatives and borrowing
parameter boundaries keep the report independent.

The following test case shows both classifications. Parameters borrow by default. The
`~{ ... }` form requests move capture, so the closure needs ownership of the captured values.
Here `c` captures a heap-backed string, and `pick2` tries to return its borrowed parameter:

```mml
fn pick2(c: Int -> Int -> Int): Int -> Int -> Int = c;;

fn capture(f: Int -> Int -> Int): Int =
  let g = ~{ f 1 2; };
  g ();
;

fn main(): Int =
  let seed = int_to_str 12345;
  let c = { a: Int, b: Int -> a + b + seed.length; };
  let f = pick2 c;
  let g = ~{ f 10 2; };
  g () + capture (pick2 c);
;
```

Ownership analysis reports the return inside `pick2` as primary error `R`. In `main`, the
local binding `f` receives that rejected return. The compiler can follow the initializer
to explain why capturing `f` fails. The parameter named `f` inside `capture` has a different
contract: that function must be valid for its borrowed parameter, regardless of which calls
happen to appear in this module.

```text
Known local initializer                  Independently checked parameter

pick2's rejected return: R               observed call: capture (pick2 c)
             |                                        |
             v                                        v
let f = pick2 c                          parameter f is borrowed
             |                                        |
             v                                        v
~{ f 10 2; }                             ~{ f 1 2; }
             |                                        |
             v                                        v
borrowed-capture report C1               borrowed-capture report C2
Consequence([R])                         Primary
show R, hide C1                          show C2
```

The left-hand explanation requires proof for every alternative that can supply `f`. One
known rejected return is insufficient when another branch supplies an ordinary borrowed
parameter:

```text
pick2 borrowed: rejected return R -----+
                                      +--> selected --> capture report is Primary
borrowed parameter: no return error ---+
```

Cause tracing stops at borrowing parameter boundaries. It cannot treat the parameter as
owning an incoming value or use a caller's error to excuse the function's capture of it.

The relevant integration points are
[PartialApplicationElaborator](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PartialApplicationElaborator.scala)
and [OwnershipAnalyzer](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala).
[OwnershipDiagnosticCauseTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipDiagnosticCauseTests.scala)
and [ElaborationRecoveryTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ElaborationRecoveryTests.scala)
cover dependent suppression and independent analysis at those boundaries.

## Parser commitment and local recovery

### Parser alternatives, backtracking, and cuts

FastParse builds parsers by combining smaller parsers and alternatives. Backtracking lets
an alternative rewind and try another interpretation. A cut commits to the current
interpretation, preventing that rewind beyond the cut.

Commitment determines which recovery paths are reachable. Once a boundary-checked `let` or
`fn` identifies a binding, the binding parser owns its errors. Once a written colon
identifies an annotation, the parser must return a
type result, including a failed type when necessary. The lambda arrow similarly commits
to its body.

The parameterized-lambda parser places that cut at `~/`, immediately after `arrowKw`:

```scala
private def lambdaWithParamsP(info: SourceInfo)(using P[Any]): P[(List[FnParam], Expr)] =
  P(
    fnParamListP(info, TypeContext.LambdaParameter).filter(_.nonEmpty) ~ arrowKw ~/
      lambdaBodyExprP(info)
  )
    .map { case (params, body) => params -> body }
```

Nested cuts must not escape speculative outer alternatives. A parenthesized lambda might
be a group, part of a tuple, or the receiver of a field selection. A cut inside that lambda
must not force the surrounding parentheses to be followed by a dot. `NoCut` contains such
commitment while the outer parser is still deciding which construct it has. The sequence
lookahead also contains cuts because it is probing rather than accepting another expression.

For example:

```mml
let identity = ({ x: Int -> x; });
```

The member-expression parser tries selection before an ordinary group. This trace shows
the relevant alternatives, omitting the other rules each attempt visits:

```text
At the opening '('
|
+-- Try field selection
|     Parse ({ x: Int -> x; }) as the receiver
|     Require a following .field
|     No dot follows: this alternative fails
|
`-- Try an ordinary group
      Parse ({ x: Int -> x; })
      Accept TermGroup containing the lambda
```

The lambda arrow commits to parsing the body `x;`. It cannot commit the outer expression
to a field selection: the parser has not seen a dot. The selection wrapper in
[expressions.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala)
contains that inner cut:

```scala
private[parser] def selectionTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  NoCut(selectionP(info, selectionBaseMemberP(info)))
```

The ordinary group alternative also uses `NoCut`. In `({ x: Int -> x; }, 2)`, it must be
able to fail at the comma so the tuple alternative can parse the same input. This choice of
commitment points controls backtracking and recovery without changing operator precedence
or the accepted type syntax.

### Recovery boundaries, nesting, and delimiters

Recovery needs a place to resume parsing. Stopping at the next semicolon is too crude: the
semicolon may occur inside a nested construct, a string, or a comment. Consuming the next
closing delimiter is also wrong when that delimiter belongs to an enclosing parser.

The shared [Recovery scanner](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/Recovery.scala)
tracks delimiter nesting and lexical modes for strings and comments. Its context supplies
ordinary anchors and hard stops. Ordinary anchors apply outside nested delimiters. Hard
stops apply at any depth and are reserved for syntax excluded from the fragment being
recovered. The scanner returns the boundary without consuming it.

This permits the owning parser to consume its own comma, closing parenthesis, declaration
boundary, or clause keyword. Different type contexts choose boundaries for parameters,
fields, aliases, operators, and expression ascriptions.

Closed strings and comments mask their contents. Binding recovery can opt into continuing
after a quote when no later closing quote exists. If a later quote exists, the text may be
a valid multiline string, so recovery remains conservative rather than inventing a
newline-based string rule.

### Zero-width errors and parser progress

A missing component can have a zero-width error span. For example, the parser can identify
a missing type immediately before `=` without consuming the initializer. That is safe when
the owning parser has already consumed the colon.

A repeated parser that succeeds without consuming anything can loop forever. Optional
repeated frames therefore have to advance. Required empty frames are allowed only after
their owner consumes an introducer or boundary. EOF cases test this explicitly, alongside
the returned tree and error locations.

### Recovering malformed types

Type recovery preserves valid siblings around malformed components:

```mml
type Broken = Int -> wrong -> String -> ;
type Good = Boolean;
```

Here `wrong` is malformed type syntax under the current naming rules, and the final arrow
has no following type. The parser constructs one function type with three parameter types
and a return type:

```text
Module
+-- TypeAlias Broken
|   `-- TypeFn
|       +-- paramTypes
|       |   +-- TypeRef(Int)
|       |   +-- ParseTypeError
|       |   |     message: "Invalid type syntax"
|       |   |     failedCode: Some("wrong ")
|       |   `-- TypeRef(String)
|       `-- returnType: ParseTypeError
|             message: "Expected type"
|             failedCode: None
|             span: zero width before ';'
`-- TypeAlias Good
    `-- TypeRef(Boolean)
```

The malformed parameter occupies its place in `paramTypes`, so the surrounding `Int` and
`String` do not disappear or become adjacent. The final missing type occupies `returnType`.
Recovery stops before the semicolon, allowing the alias parser to finish the declaration
and continue with `Good`. Ingest then assigns a primary diagnostic ID to each error node.
The abbreviated tree and exact failed slices are covered by
[TypeRecoveryTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/TypeRecoveryTests.scala).

### Recovering function bodies and normalizing their valid parts

A function body can contain both a successfully parsed prefix and a malformed suffix:

```mml
fn broken(x: Int): Int = x + 1 @;;
let after = 7;
```

The parser builds the following structure. Source locations, name wrappers, and annotation
caches are omitted; the nested prefix `Expr` is part of the actual tree:

```text
Module
+-- Bnd broken
|   `-- value: Expr
|       `-- Lambda
|           +-- params: [x annotated Int]
|           `-- body: Expr
|               +-- Expr
|               |   +-- Ref(x)
|               |   +-- Ref(+)
|               |   `-- LiteralInt(1)
|               `-- ParseTermError("@", causes = [])
`-- Bnd after
    `-- value: Expr
        `-- LiteralInt(7)
```

The function's `Int` result annotation is stored on the binding and lambda. Its parameter
scope is also available, so reference resolution can connect the body reference `x` to the
declared parameter. The syntax error covers `@`; it does not absorb the next binding.

Before semantic analysis, `ParsingErrorChecker` reports the parser error and attaches its
diagnostic roots. The relevant case is:

```scala
case error: ParseTermError =>
  report(SemanticError.ParseTermErrorFound(error, phaseName))
    .map(causes => error.copy(causes = causes))
```

Call that primary diagnostic `D`. Expression normalization visits the nested prefix before
handling the enclosing body's error. The sequence `x + 1` becomes two unary applications:
first apply `+` to `x`, then apply that result to `1`. The whole body cannot produce a usable
result, so it receives an `InvalidExpression` wrapper whose payload includes the normalized
prefix:

```text
After expression normalization

Lambda.body: Expr
`-- InvalidExpression(causes = [D])
    `-- originalExpr: Expr
        +-- Expr
        |   `-- App
        |       +-- fn: App
        |       |   +-- fn: Ref(+)
        |       |   `-- arg: Expr
        |       |       `-- Ref(x)
        |       `-- arg: Expr
        |           `-- LiteralInt(1)
        `-- ParseTermError("@", causes = [D])
```

Later phases can inspect `x + 1` as a normalized application and check its operands. They
cannot assign guessed sequencing or application semantics to the whole `x + 1 @` fragment.
[BindingRecoveryIntegrationTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/BindingRecoveryIntegrationTests.scala)
checks the retained parameter identity, the single visible syntax error, the unavailable
function result, and the independently available following binding. The detailed application
shape above follows the normalizer's binary-operator construction.

Missing syntax can be represented without consuming a valid body. For example, a missing
`=` can produce a zero-width error while recovery continues into the initializer.

Incomplete headers need more care. A real parameter prefix can be retained, but it must not
be advertised as a complete callable signature. A malformed parameter list with no real
parameters must not become an invented nullary lambda. These distinctions are asserted in
[BindingRecoveryTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/BindingRecoveryTests.scala).

### Conditional clauses and missing branches

Conditional parsing shares the initial clause, repeated `elif` clauses, optional `else`,
and their lowering. Each recognized clause owns recovery of its condition or body. If
`then` is missing, the parser keeps the parsed condition terms and inserts a missing-boundary
error; it does not guess how to split those terms into a condition and a body.

For example, this grouped conditional is missing `then`:

```mml
let value = (if true 11; else 22;);
```

Both `true` and `11` have parsed as condition terms. Recovery cannot establish that `11`
was intended as the true branch, so it records the missing boundary without moving that
term into a guessed branch:

```text
Input fragment       Parser action

true 11              Keep both terms in the condition Expr
;                    Stop the condition before this token
                     Insert zero-width "Expected 'then'" in the true branch
                     Let the clause parser consume this optional semicolon
else                 Start the else clause
22;                  Parse the else body and its terminator
)                    Return this delimiter to the enclosing group
;                    Finish the top-level let
```

Conditions use one of two grammars. An "expression condition" uses the ordinary expression
grammar, as inside a lambda body. A "member condition" uses the restricted variant found
directly in a top-level `let` initializer. The ordinary grammar permits semicolon sequencing;
the member variant stops before the next outer semicolon. The clause parser can then consume
that semicolon while reporting a missing `then`.

These two parser fixtures are separate programs:

```mml
// Accepted: the condition inside this lambda sequences () and true.
let value = { if (); true then 11; else 22; };

// Syntax errors: the member condition stops at the semicolon after ().
let value = if (); true then 11; else 22;;
```

Both variants allow sequencing inside their branches. Function bodies also use the ordinary
expression path; "member condition" does not mean every conditional somewhere inside a
top-level declaration.

A conditional without `else` has a concrete false branch in the tree:

```mml
let value = if true then ();;
```

```text
Cond
+-- typeAsc:  Some(TypeRef(Unit))
+-- typeSpec: Some(TypeRef(Unit))
+-- cond: Expr
|   `-- LiteralBool(true)
+-- ifTrue: Expr
|   `-- LiteralUnit               written ()
`-- ifFalse: Expr
    `-- LiteralUnit               implicit, zero-width source span
```

The Unit fields shown here belong to `Cond`; the implicit false-branch `Expr` has neither
an annotation nor a cached type of its own. That `Expr` and its literal both have zero-width
source spans. An `elif` lowers to another `Cond` inside the preceding condition's false
branch. Without `else`, only the outer `Cond` gets both Unit
fields; each nested one gets `typeSpec`, with `typeAsc = None`. The final false branch is
the implicit Unit expression.

[ConditionalRecoveryTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ConditionalRecoveryTests.scala)
checks missing components, nested clauses, implicit Unit branches, retained delimiters,
and termination. An unmatched opener in an opaque malformed fragment can still hide later
clause keywords from ordinary anchors; the implementation does not promise to recover
structure whose nesting cannot be established.

### Direct application of grouped lambdas

Grouping a lambda does not change how its arguments bind:

```mml
let result = ({ x: Int, y: Int -> x + y; }) 1 2;
```

The normalizer unwraps grouping around the call head and uses the same unary direct-call
lowering for grouped and bare lambdas. It binds supplied arguments left to right. If there
are fewer arguments than parameters, the result is a residual lambda; further arguments
are handled by ordinary application construction. Parameter identities connect each body
reference to the same parameter throughout lowering, and argument evaluation follows the
source order.

[GroupedLambdaApplicationTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GroupedLambdaApplicationTests.scala)
covers complete, partial, nullary, returned-callable, and invalid calls, including parameter
identities and argument association.
[RuntimeTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/RuntimeTests.scala)
checks that arguments execute once in source order, using printed effects and exact output
assertions for complete calls, partial application, and calls to returned lambdas.

## Analysis reuse and compilation cost

The additional analysis initially repeated too much work. Before checking each member,
the type checker rebuilt whole-module cause and availability summaries. Type queries also
repeatedly followed the same aliases and named type declarations. Existing ownership code
called these more expensive queries often enough to increase its cost too.

On `astar3_animated.mml`, the initial implementation's type-checker median rose to roughly
1.36 seconds, compared with about 39 milliseconds for the baseline compiler. Whole-command
time rose from about 2.04 to 3.66 seconds. A faster parser did not compensate for that
semantic regression.

Profiling located repeated type-evidence and failed-type traversals. Named-type summaries
and module-level analysis state avoid those repeated traversals. Reuse is restricted to
cases whose evidence can be checked cheaply; other cases run dependency analysis again.

### Cached analysis of named types

Explicit failed-type checks, named-type cause evidence, and named-type availability are
summarized against the immutable type-declaration index. Repeated references can consult
those summaries while inline compound types still receive structural checks.

Recursive declarations require care. Reaching a node already visited in one traversal
allows that traversal to stop following the cycle. It does not supply a complete answer
for a traversal starting at another root. Each named summary is therefore computed from
an independent traversal; partially explored cycle results are not published as complete
cached answers.

Explicit failed types remain distinct from alias cycles or missing information. A cycle
without a failed node does not automatically establish a usable type. Declaration changes
invalidate the applicable named summaries.

### Reusing module analysis while checking members

`TypeChecker.ModuleCheckingState` carries the analyses between member checks. Its fields are:

```scala
private case class ModuleCheckingState(
  module:       Module,
  checkedIds:   Set[String],
  causes:       DiagnosticCauses,
  availability: ValueAvailability
)
```

Checking a binding produces a new module and updates its resolvable index. The binding's ID
joins `checkedIds`, and the two analyses decide whether they can reuse their summaries with
the updated index. Each update returns a new state value; the shared summaries are immutable.

Cause reuse requires unchanged type declarations, preserved local-definition identities,
and complete, empty evidence before and after the binding update. Otherwise dependency
discovery runs again. Availability reuse requires an unchanged type index, an available
checked prefix, and an available new declaration and its local values. Failures rebuild the
availability result from an empty unavailable set.

Rebuilding from an empty set matters when more information resolves an earlier failure.
The availability tests isolate this with a checked binding `forward` that refers to a
declaration `later`. The declaration is in the index, but it has no usable type yet, so the
analysis marks `forward` unavailable. When checking `later` supplies its type, an update
that only adds unavailable IDs cannot clear the earlier result:

```text
Initial analysis                     After checking later

forward --> later                    forward --> later
            type unknown                         type known

unavailable: {forward}               adding IDs only: {forward}   stale
                                     recomputing:     {}          usable
```

This transition is checked in
[ValueAvailabilityTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ValueAvailabilityTests.scala).
Recursive dependencies make stale cause roots similarly difficult to remove. Failed and
incomplete cases therefore rerun dependency discovery instead of incrementally invalidating
the old results. The cheaper reuse path handles complete, failure-free summaries.

### Skipping unnecessary traversals

Type-ascription validation (checking an explicit annotation) returns immediately when no
annotation is present. Cause roots are collected when an unavailable result needs a
diagnostic, rather than before every result check. Ownership attribution avoids scanning
borrowed parameters when there are no relevant return failures. Lambdas without captures
do not need a capture-availability traversal.

Consistent type metadata also affects reuse. A no-`else` conditional has a synthetic `Unit`
annotation and a cached result type. `TypeResolver` resolves both positions while preserving
shared occurrence identity. Leaving the cache unresolved would mark the evidence incomplete
and force dependency analysis again, even though the annotation already identifies `Unit`.

These caches are immutable analysis state scoped to one compilation. They are not shared
between compiler invocations.

### Parser and compiler measurements

The [raw-parser comparison](../../../benchmark/parser/comparison-final.md) measures successful
parses with recovery enabled, before the source-boundary correction described below. Three
fresh JVMs use warmup batches and repeated measured parses of four inputs. In that comparison,
source and raw-tree identities match the baseline, and every timed input has zero recovered
parser errors. The later boundary correction changes source locations in those raw trees;
its [separate measurement report](../../../benchmark/parser/source-boundaries.md) records
the corrected trees and fresh timings.

| Input                 | Baseline median | Final median | Observed change |
| --------------------- | --------------: | -----------: | --------------: |
| N-Queens              |        0.256 ms |     0.245 ms |           −4.3% |
| Matrix multiplication |        0.438 ms |     0.331 ms |          −24.5% |
| A* (`astar2`)         |        1.862 ms |     1.388 ms |          −25.5% |
| Raytracer             |        1.200 ms |     0.894 ms |          −25.5% |

Separately collected instrumentation reports 39.7% fewer rule invocations and 50.4% fewer
backtrack-counter events. That counter records a rule starting before the furthest previous
rule-start position. It is not a literal count of alternative rewinds or discarded trees;
rule factoring can change it. The timed runs provide the separate evidence for elapsed time.

The semantic comparison uses whole compiler invocations on `astar3_animated.mml`, with a
fresh JVM each time and the same `-mpPaI` flags. One priming compile per compiler is excluded;
five measured compiles per compiler alternate order. The corrected-source comparison is:

| Measurement, median | Baseline compiler | Corrected compiler |
| ------------------- | ----------------: | -----------------: |
| Type checking       |          41.30 ms |          145.93 ms |
| Ownership           |         447.04 ms |          340.56 ms |
| All semantic phases |         922.65 ms |          833.35 ms |
| Whole command       |        2088.29 ms |         2048.92 ms |

The whole-command result is near baseline on this input. Type checking still costs about
105 ms more than the baseline compiler. Lower ownership time and other phase differences
offset that in these runs; the measurements do not establish type-checker parity.

Both experiments are local observations on arm64 macOS with GraalVM Java 25.0.4.1. The
parser experiment uses repeated parses within each JVM; the whole-command experiment
starts a fresh JVM per compile. Their numbers describe different workloads. N-Queens
continued warming during the parser baseline; those results establish neither fully
steady-state performance nor statistical significance. Neither experiment establishes
general throughput, malformed-input latency, or cross-platform performance.
The [task's verification record](../../../context/tasks/error-recovery-and-parser-backtracking.md#semantic-performance-correction-verified)
retains the input hash, measurement method, paired samples, and limitations.

## Verification and recovery limits

The regression tests check several distinct contracts: retained syntax and valid siblings;
stable binding and diagnostic identities; complete versus incomplete evidence; independent
errors; availability through aliases, recursion, and discarded initializers; exact diagnostic
messages and selected spans; and progress at malformed input and EOF.

With the source-boundary correction included, formatting and lint pass, along with 1,946
library tests and nine CLI tests. The smoke collection passes eight fixtures, 37 MML
benchmark builds succeed, and 52 memory fixtures pass AddressSanitizer and LeakSanitizer.
The [source-boundary verification record](../../../context/tasks/parser-source-boundaries.md#verification)
records the commands, results, and review status.

Benchmark builds check build compatibility. Native execution tests check results and
evaluation order for their fixtures. Sanitizers cover the paths those programs execute,
and the timing experiments report local measurements. These checks answer different
questions.

Source spans tell the editor and diagnostic printer which characters belong to a node.
The parser captures token endpoints before trailing whitespace and comments, then builds
enclosing spans from their final child or closing delimiter. In this example, the binding's
type error ends after its semicolon:

```mml
let b: Int = "2";
// This comment belongs to the next declaration.
let c = b + 1;
```

```text
let b: Int = "2";
    ^^^^^^^^^^^^^  type mismatch
```

A comment between owned tokens still falls inside their enclosing span. Recovery also
retains whitespace within malformed text: it measures from the scanner's start to its
stopping point, independently of the successful prefix's endpoint. The
[source-boundary design](../../design/compiler-design.md#source-boundaries) explains these
ownership rules and how written annotations contribute to an expression's span.

Recovery also remains conservative around ambiguous boundaries. A missing terminator before
`let` or `fn` is not always an unambiguous new member, because both can occur inside bodies.
An unmatched opener can conceal later clauses, and a later quote can make a malformed region
look like a multiline string. Recovery cannot reliably infer source structure across those
ambiguous boundaries.

For code reading, begin with `DiagnosticStore`, then `DiagnosticCauses` and
`ValueAvailability`. Follow their use in `TypeChecker` before examining ownership integration.
For syntax, begin with `Recovery.scala`, then the type, binding, and conditional parsers and
their corresponding recovery tests. The
[compiler design reference](../../design/compiler-design.md#7-parser-architecture) connects
those files to the complete phase sequence.
