# MinnieML: Style Guide

This document records source-formatting conventions for MML programs. For syntax and
semantic rules, see the [language reference](language-reference.md).

Use common sense and develop an MML formatting intuition from the existing samples.
This guide calls out the most aggravating style problems explicitly; it is not meant
to force mechanical rewrites of clear, readable code. Prefer preserving local
aesthetics when the code already makes the expression structure evident.

## Semicolons

MML uses semicolons as terminators, not separators. See the language reference on
[declarations](language-reference.md#1-declarations),
[semicolons](language-reference.md#semicolons), and
[expression sequencing](language-reference.md#expression-sequencing).

For a single-line expression, attach its terminator to the same line:

```mml
let total = sum 1 2;
```

Put a standalone terminator that closes a vertically structured expression or construct
on its own line, aligned with the beginning of what it closes, like a closing delimiter:

```mml
let total =
  sum
    1
    2
  ;
```

For multi-line function bodies, put the expression terminator and declaration
terminator on separate lines:

```mml
fn something() =
  let a = 1;
  let b = 2;
  a + b;
;

fn one() =
  1;
;
```

Do not write the two terminators together for a multi-line body:

```text
fn something() =
  ???
;;
```

Using two semicolons on one line is appropriate for compact single-line declarations
or expressions:

```mml
fn sum(a, b) = a + b;;
let d = if a + b > 2 then 3; else 4;;
```

For vertically split expressions, a standalone terminator may close the whole
expression when it aligns with the expression's indentation:

```mml
let grid =
  Grid
    width
    height
    (ar_int_new size)
  ;
```

The standalone terminator acts as a visual closing marker for the expression. Do not
confuse this with writing both expression and declaration terminators together as `;;`
for a multi-line body.

## Function Calls

Keep a call entirely on one line, or lay it out entirely vertically. Short, clear
calls can stay inline:

```mml
draw_loop next;
```

Prefer vertical layout when a call has many arguments or complex argument expressions
that make an inline call difficult to scan. Put the function reference on its own line,
then each argument on a separate line indented one level farther. Put the terminating
semicolon on its own line, aligned with the function reference:

```mml
advance_playback
  grid
  cells
  result
  interval
  (Playback (playback.step + 1) (playback.elapsed -. interval))
;
```

Do not put some arguments beside the function reference and wrap only the remaining
arguments onto another line:

```text
advance_playback grid cells result interval
  (Playback (playback.step + 1) (playback.elapsed -. interval));
```

The same rule applies to constructor calls. An argument may contain an inline nested
call, as `Playback` does above.

When binding a vertical call, put the function reference below `let name =`. Align
the call's semicolon with the function reference, not with `let`:

```mml
let next =
  advance_playback
    grid
    cells
    result
    interval
    (Playback playback.step (playback.elapsed +. (get_frame_time ())))
  ;
```

## Let Bindings

Let bindings are single expressions. See
[Let bindings](language-reference.md#let-bindings) and
[Expression sequencing](language-reference.md#expression-sequencing).

Do not add an extra semicolon to close a `let` construct. The semicolon terminates the
binding expression itself:

```mml
let a = 1;
let b = 2;
let c =
  if a + b > 2 then 3;
  else 4;
  ;
let u = if n > 50 then
  User (int_to_str n) "DynamicRole";
else
  User "StaticName" "StaticRole";
;
```

## Conditionals

Conditionals are expressions. See
[Conditionals](language-reference.md#conditionals).

When a conditional spans multiple lines, terminate each branch expression and then
terminate the conditional expression:

```mml
let a =
  if a + b > 2 then 3;
  else 4;
  ;
let b =
  if a + b > 2
  then 3;
  else 4;
  ;
```

For compact one-line expressions, keep the branch terminators and surrounding
declaration terminator on the same line:

```mml
let d = if a + b > 2 then 3; else 4;;
```

## Function Bodies

Function declarations are declarations whose bodies are expressions. See
[Function declarations](language-reference.md#function-declarations).

Use the same semicolon style for named functions, inner functions, operators, and
lambda bodies: expression terminators stay with the expression they terminate, and
declaration terminators close the declaration.
