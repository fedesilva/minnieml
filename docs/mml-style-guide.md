# MinnieML: Style Guide

This document records source-formatting conventions for MML programs. For syntax and
semantic rules, see the [language reference](language-reference.md).

## Semicolons

MML uses semicolons as terminators, not separators. See the language reference on
[declarations](language-reference.md#1-declarations),
[semicolons](language-reference.md#semicolons), and
[expression sequencing](language-reference.md#expression-sequencing).

Put each semicolon in the same line and column as the statement or expression it
terminates.

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
let grid = Grid
  width
  height
  (ar_int_new size)
;
```

The standalone terminator acts as a visual closing marker for the expression. Do not
confuse this with writing both expression and declaration terminators together as `;;`
for a multi-line body.

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
