# MML formatter

Draft for discussion. The representation, formatting rules, and CLI below are proposals.

## Goal

Add a `fmt` tool for MML. Scalafmt is the main reference for the desired experience:
consistent indentation, useful line wrapping, and formatting that works across a whole file.

The first design problem is comment preservation. The parser skips single-line comments as
whitespace. A formatter needs their text and enough source context to keep them in the right place.
It also needs to distinguish replaceable spacing from intentional blank-line separation.

## Survey

### Go

`gofmt` enables `parser.ParseComments`. Go represents comments with their text and source
position, and groups adjacent comments when no tokens or empty lines separate them. The printer
uses positions to insert comments among the syntax it prints. It generates whitespace while
consulting source line positions for vertical separation.

This gives us a relatively small starting point: explicit comments, accurate syntax positions,
and rules for their placement. It does not require every original space to become an AST node.

Sources: [gofmt parser setup](https://go.dev/src/cmd/gofmt/gofmt.go),
[comment representation](https://go.dev/src/go/ast/ast.go),
[printer](https://go.dev/src/go/printer/printer.go).

### Rust

Rustfmt uses the compiler's AST before macro expansion. Ordinary comments and whitespace are
absent from that AST; doc comments are retained. Rustfmt uses source spans to inspect the original
text between nodes and recover comments. Its missing-span machinery also preserves source that
was not reformatted.

The rustfmt contributor guide explicitly calls comment recovery a difficult part of the design.
For MML, retaining comments when reading the source looks preferable to rediscovering them in
each formatting rule.

Sources: [rustfmt architecture](https://github.com/rust-lang/rustfmt/blob/master/Contributing.md#a-quick-tour-of-rustfmt),
[missing spans](https://github.com/rust-lang/rustfmt/blob/master/src/missed_spans.rs).

### Scalafmt

Scalafmt's original design uses Scalameta syntax and tokens, with the original whitespace between
adjacent non-whitespace tokens. Formatting rules offer possible splits: no space, a space, or a
line break. A search chooses a layout using costs, indentation, and constraints. A writer produces
the result.

Scalafmt remains a reference for formatting behavior. MML can borrow its wrapping and alignment
choices without adopting its token representation or search algorithm.

Source: [Scalafmt author's thesis, sections 2.2 and 3](https://geirsson.com/assets/olafur.geirsson-scalafmt-thesis.pdf).
This describes the original architecture, not a claim that every implementation detail is current.

## What MML has today

- [MmlWhitespace](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/MmlWhitespace.scala)
  consumes spaces, tabs, line endings, and `//` comments without returning their contents.
- [docCommentP](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/literals.scala)
  parses block doc comments explicitly. It retains their spans but cleans their text, so that text
  alone cannot reproduce the original spelling.
- [SourceInfo](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/srcinfo.scala)
  holds the input text and maps character offsets to source locations.
- The [parser's source-boundary contract](../../design/compiler-design.md#source-boundaries)
  excludes trailing whitespace and comments from successful expression and token spans. Internal
  whitespace can still occur inside a composite span.

The compiler also lowers some syntax during parsing. In
[expressions.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala),
`mkStatementApplication` gives its generated lambda and application the same span, covering the
statement and its continuation. `mkScopedBinding` likewise assigns the whole binding-and-rest
span to both generated nodes. These spans identify originating code; they do not describe the
written syntax of the generated nodes. Adding comments to the current AST alone would not give
us a faithful source printer.

## Proposed source representation

Prefer Go's model for its simplicity: retain comments with source positions and print them
alongside a tree that represents written syntax. Keep the original source and line information
so the printer can distinguish trailing comments, standalone groups, and blank-line separation.
A complete token-and-whitespace stream is not the preferred direction.

Each comment records its kind and exact span. Its original text can be read from the source
buffer. Doc comments may still have a cleaned semantic value, but formatting uses the original
source spelling. Comments remain ordered by source position, including file headers, footers,
and comments in otherwise empty files. Comment markers inside literals are not comments.

The prerequisite is preserving written syntax before lowering changes its shape. Two options
need investigation: defer parser lowering until after a source tree is available, or retain a
source representation alongside the lowered result. The choice should follow an inventory of
syntax lost during parsing, including punctuation positions needed to place comments.

Ordinary comments do not need to travel through semantic analysis. The formatter operates on
the preserved source representation. Existing diagnostic spans on generated nodes cannot
substitute for that representation.

Comment collection must handle FastParse backtracking without retaining duplicates or abandoned
parse results. Whether collection belongs in parser capture or a separate lexical pass remains
open. A separate pass must agree with the parser on literals, comments, and delimiters.

## Comment and whitespace rules

For the first version:

- Preserve comment text, order, and position relative to surrounding syntax tokens.
- Keep a trailing `//` comment after the code it follows. Keep standalone comments on their own
  lines. Width limits must not force a comment onto unrelated code.
- Require a newline after every line comment when another token follows it. A missing newline
  must never turn code into comment text.
- Preserve comment groups and their blank-line separation. Decide separately how many excess
  blank lines to retain.
- Preserve doc-comment attachment to the declaration or parameter it documents.
- Adjust whitespace outside comments. Leave prose wrapping and the contents of multiline
  comments unchanged in this version.

The formatter chooses indentation and wrapping using written syntax. Original line breaks may
guide those choices, but exact policies for wrapping, alignment, and blank lines need examples
and review. The [MML style guide](../../mml-style-guide.md) is the starting point for that work.

## Proposed interface

| Command | Behavior |
| --- | --- |
| `mmlc fmt file.mml` | Print the formatted file to stdout. |
| `mmlc fmt --write file.mml` | Replace the file after formatting succeeds. |
| `mmlc fmt --check file.mml` | Report whether formatting would change the file; do not write. |

Formatting runs without type checking, LLVM, or native compilation. A syntactically valid file
can be formatted even when names or types are unresolved. Parse errors produce diagnostics and
leave the file unchanged, including errors represented by recovered parser nodes.

Proposed check exit codes: `0` for unchanged, `1` for formatting differences, and `2` for errors.
Write and check modes are mutually exclusive. Replacing a file must not expose partially written
output. Stdin, multiple files, directory traversal, and editor formatting can follow once the
single-file contract is settled.

## Acceptance criteria

Formatting must be deterministic and idempotent: formatting its output produces identical text.
The output must parse to the same structure after ignoring source positions and other metadata
unrelated to syntax. V1 preserves syntax-token spelling and order as well as comment contents;
it performs no syntax rewrites.

Tests should cover trailing and standalone comments, comments between expression terms and near
delimiters, consecutive comment groups, file headers and footers, empty and comment-only files,
and a final line comment without a newline. Include CRLF input, Unicode before comments, comment
markers inside strings, and nested block doc comments. Invalid input must not be overwritten.

Use expected-output fixtures for layout decisions. Check comment preservation and parse
equivalence separately: an AST comparison alone cannot detect a missing ordinary comment.

## Decisions still needed

- Parser capture or a separate lexical pass?
- What written structure is missing from the current parser result, and how should formatting
  retain it?
- Which Scalafmt behaviors do we want for wrapping and alignment? Choose concrete examples before
  choosing the layout algorithm.
- Which settings should users control? Indentation, width, and blank-line limits are candidates.
- Preserve input line endings or normalize them? What is the final-newline rule?
- Do we need format-off regions in the first version?

The first implementation slice should establish the written-syntax boundary and comment
preservation tests. Layout rules and CLI integration can then build on that representation.
