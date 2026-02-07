# Plan: update language reference

Rename `docs/design/semantics.md` → `docs/design/language-reference.md` and fill the gaps
that prevent it from working as an actual language reference.

## Problems with the current document

1. **No declaration syntax** - `let`, `fn`, `op`, `struct`, `type` are never formally defined.
   A reader can't tell what a valid program looks like without inferring from scattered examples.
2. **No program structure** - semicolons as terminators, expression sequencing in function bodies,
   top-level module structure, how a file becomes a module - none of this is described.
3. **Structs are second-class** - struct declaration syntax and field access (dot notation) only
   appear as asides under memory management. No standalone section.
4. **Scoping rules are vague** - "parameters first, then module members" is implied but never stated.
5. **Error categories leak compiler internals** - `SemanticError.UndefinedRef` is a compiler type,
   not a language concept. This section belongs in `compiler-design.md` or should be rewritten
   in terms of the language.
6. **Missing constructs** - tail recursion, `if`/`elif`/`end` syntax, expression blocks,
   single-branch `if` returning Unit.
7. **Missing limitations** - no nested functions, no loops. These trip up contributors
   (see `astar.mml` where inner functions had to be manually lifted to top-level).
8. **No control flow section** - no mention that MML has no `while`/`for`/loops at all.
   Iteration is done via recursion (preferably tail recursion).

## Plan

### Step 1: rename the file

- `git mv docs/design/semantics.md docs/design/language-reference.md`
- Update any references to the old filename (check CLAUDE.md, compiler-design.md, etc.)

### Step 2: add declaration syntax section (new section, after lexical rules)

Cover every declaration form with syntax, rules, and examples:

- **`let` bindings**: `let NAME = EXPR;` / `let NAME: TYPE = EXPR;`
  - Immutable, no reassignment.
  - Type can be inferred or ascribed.
- **`fn` declarations**: `fn NAME(PARAMS): RETURN_TYPE = BODY;`
  - Parameter types required, return type optional (inferred).
  - Body is a single expression (possibly conditional or sequenced with `let`).
- **`op` declarations**: `op NAME(PARAMS): RETURN_TYPE PREC ASSOC = BODY;`
  - Precedence integer and associativity (`left`/`right`) required.
  - Unary (1 param) or binary (2 params).
- **`struct` declarations**: `struct NAME { FIELD: TYPE, ... };`
  - Constructor is the struct name, applied to fields in order.
  - Field access via dot notation: `value.field`.
- **`type` definitions and aliases**: `type NAME = @native[...];` / `type NAME = EXISTING;`

### Step 3: add program structure section (new section, after declarations)

- **Semicolons**: every top-level declaration and every `let` binding ends with `;`.
  Semicolons are terminators, not separators.
- **Expression sequencing**: function bodies can contain `let` bindings followed by a
  final expression. Each `let` is terminated by `;`. The body's value is the last expression.
  ```mml
  fn example(): Int =
    let x = 1;
    let y = 2;
    x + y
  ;
  ```
- **Top-level structure**: a file is a module. Top-level members are `let`, `fn`, `op`,
  `struct`, `type`. No `module` keyword at file scope (the compiler derives the module name
  from the file path).
- **Single-branch `if`**: `if COND then EXPR;` returns `Unit` (compiler adds implicit
  `else ()`). Only use `else` when the `if` returns a value.

### Step 3b: add control flow section

- **Conditionals**: full syntax is `if`/`elif`/`else`/`end`:
  ```mml
  if cond1 then
    expr1
  elif cond2 then
    expr2
  else
    expr3
  end
  ```
  `end` is required when using `elif`. Simple `if`/`else` without `elif` uses `;` as terminator
  (document the exact rules by checking the parser).
- **No loops**: MML has no `while`, `for`, or any loop construct. All iteration is done via
  recursion. The compiler detects and optimizes tail calls automatically (no annotation needed).
  ```mml
  fn count_down(n: Int): Unit =
    if n > 0 then
      println (to_string n);
      count_down (n - 1)
    end
  ;
  ```
- **No nested functions**: functions cannot be defined inside other functions. MML does not
  support closures. If a function needs access to values from an outer scope, those values
  must be passed as explicit parameters and the function must be lifted to the top level.
  This is a temporary limitation - closures and nested functions are planned but blocked on
  finishing the memory model first, since lambdas and captures will be affected by ownership
  semantics. Contributors have submitted broken code assuming nested functions work
  (see `astar.mml` where 4 inner functions had to be manually lifted with their captured
  variables passed as parameters).

### Step 4: add struct section to type system

Move struct-related material out of memory management into the type system section.
Cover:

- Declaration syntax
- Constructor syntax (struct name as function, applied to field values in order)
- Field access (dot notation)
- Heap vs non-heap structs (structs containing heap fields are heap types)

Keep the memory management section focused on ownership rules, with struct construction
cloning behavior as a subsection there.

### Step 5: add scoping rules

Under semantic rules, add a scoping subsection:

- Bindings are visible from point of declaration to end of enclosing scope.
- Function parameters are visible within the function body.
- Module-level declarations are visible to all members in the module (no forward-declaration needed).
- No nested functions (closures not supported) - see control flow section for details.

### Step 6: clean up error categories

Two options (decide during implementation):

- **Option A**: Remove the error categories section entirely. It's compiler internals.
  The compiler-design.md already documents errors per phase.
- **Option B**: Rewrite in language terms. Instead of `SemanticError.UndefinedRef`, say
  "Referencing an undefined name is an error." No compiler types, just rules.

Leaning toward Option B - a language reference should say what's an error, just not
in terms of compiler implementation types.

### Step 7: review and fill remaining gaps

- Verify all MML code examples use `mml` as the code fence language (not `rust` or `scala`).
- Add tail recursion: MML detects and optimizes tail calls automatically (no annotation needed).
- Check for any constructs in samples/ that aren't documented.
- Read through the result end-to-end for coherence.

## Out of scope

- Union/intersection types, type variables, pattern matching - these are not implemented yet.
  Mention them as future work where they appear in the type system section, but don't document
  unimplemented syntax.
- Compiler internals - those stay in `compiler-design.md`.

## Estimated scope

Moderate. Mostly reorganizing and writing prose, no code changes. The existing content is
accurate, it just needs structure and the missing pieces filled in.
