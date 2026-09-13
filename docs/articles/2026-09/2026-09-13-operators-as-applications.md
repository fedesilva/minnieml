# MinnieML operators as applications

Operator definitions now desugar directly to bindings containing lambdas, just like function
definitions. Operator uses become applications. Precedence and associativity still determine
the grouping; the rewriter expresses that grouping with nested `App` nodes.

This article revisits the examples from
[expression rewriting](../2025-04/2025-04-12-expression-rewriting.md). The earlier
[custom operators article](../2025-02/2025-02-24-custom-operators.md) described this step as
future work.

## Definitions

The parser turns an operator definition into a `Bnd` containing a `Lambda`:

```mml
op * (a b) 80 left = ???;
```

After `IdAssigner` assigns IDs, the definition has this shape. The examples use
`example` as the module name.

```text
Bnd op.star.2
  id: example::bnd::op.star.2
  meta:
    origin: Operator
    arity: Binary
    precedence: 80
    associativity: Left
    originalName: *
    mangledName: op.star.2
  Expr
    Lambda
      params:
        FnParam a
          id: example::bnd::op.star.2::scope::0::a
        FnParam b
          id: example::bnd::op.star.2::scope::0::b
      body:
        Expr
          Hole
```

The generated name distinguishes operators by name and arity. Unary and binary `+`, for
example, become `op.plus.1` and `op.plus.2`. Both are bindings containing lambdas. The
rewriter reads their metadata to decide which binding an operator reference denotes and
how to group its operands.

References now store stable IDs instead of direct pointers to AST nodes. An index maps each
ID to the current node, so references survive rewrites. IDs stay stable during a compilation.

The application trees omit single-term `Expr` wrappers and put nodes beside `fn:` and `arg:`
to reduce indentation. Binding IDs and reference metadata are retained. Types, source locations,
and unrelated metadata are omitted. Standard operator IDs start with `stdlib::bnd::`;
our own definitions use `example::bnd::`.

## Rewrite rules

Each `App` supplies one argument. Binary operators and two-argument function calls therefore
produce the same shape:

```text
x + y   → App(App(Ref +, x), y)
f x y   → App(App(Ref f, x), y)

-x      → App(Ref -, x)
x!      → App(Ref !, x)
```

These are AST sketches, not MML source syntax. Each operator reference points to its resolved
binding; the lambda body stays in that binding.

Expressions still begin as flat term sequences. `RefResolver` records matching binding IDs
in `candidateIds`. With one candidate, it immediately sets `resolvedId` to that ID. With
multiple candidates, it leaves `resolvedId` as `None`; `ExpressionRewriter` selects the
operator from context. The candidate list is retained after selection, so a populated
`candidateIds` field does not mean the reference is unresolved.

`ExpressionRewriter` builds the applications using precedence climbing.

The examples use precedence 95 for prefix operators, 80 for `*`, 60 for binary `-`, 40 for
`and`, and 30 for `or`. Function application has precedence 101. The custom postfix factorial
operator `!` has precedence 94 and left associativity, so prefix `+` binds more tightly.

## Mixed operators

```mml
let a = +4! - 2!;
```

### Original flat AST

```text
Bnd a
  id: None
  Expr
    Ref +
      resolvedId: None
      candidateIds: []
    LiteralInt 4
    Ref !
      resolvedId: None
      candidateIds: []
    Ref -
      resolvedId: None
      candidateIds: []
    LiteralInt 2
    Ref !
      resolvedId: None
      candidateIds: []
```

### After reference resolution

The expression remains flat. Each `!` has one candidate, so `RefResolver` selects it
immediately. Both unary and binary definitions match `+` and `-`; their `resolvedId` fields
remain `None` until expression rewriting determines which one is used.

```text
Bnd a
  id: example::bnd::a
  Expr
    Ref +
      resolvedId: None
      candidateIds:
        stdlib::bnd::op.plus.2
        stdlib::bnd::op.plus.1
    LiteralInt 4
    Ref !
      resolvedId: example::bnd::op.bang.1
      candidateIds: [example::bnd::op.bang.1]
    Ref -
      resolvedId: None
      candidateIds:
        stdlib::bnd::op.minus.2
        stdlib::bnd::op.minus.1
    LiteralInt 2
    Ref !
      resolvedId: example::bnd::op.bang.1
      candidateIds: [example::bnd::op.bang.1]
```

### After expression rewriting

The rewriter selects unary `+` at the start and binary `-` between the operands. Prefix `+`
has precedence 95 and postfix `!` has precedence 94, so `+` applies to `4` first.
The grouping is `(+4)! - (2!)`.

```text
Bnd a
  id: example::bnd::a
  value: App
    fn: App
      fn: Ref -
        resolvedId: stdlib::bnd::op.minus.2
        candidateIds:
          stdlib::bnd::op.minus.2
          stdlib::bnd::op.minus.1
      arg: App
        fn: Ref !
          resolvedId: example::bnd::op.bang.1
          candidateIds: [example::bnd::op.bang.1]
        arg: App
          fn: Ref +
            resolvedId: stdlib::bnd::op.plus.1
            candidateIds:
              stdlib::bnd::op.plus.2
              stdlib::bnd::op.plus.1
          arg: LiteralInt 4
    arg: App
      fn: Ref !
        resolvedId: example::bnd::op.bang.1
        candidateIds: [example::bnd::op.bang.1]
      arg: LiteralInt 2
```

## Function application with operators

```mml
fn mult (a b) = ???;
let a = 2 * 4! - mult 2 2;
```

### Original flat AST

```text
Bnd a
  id: None
  Expr
    LiteralInt 2
    Ref *
      resolvedId: None
      candidateIds: []
    LiteralInt 4
    Ref !
      resolvedId: None
      candidateIds: []
    Ref -
      resolvedId: None
      candidateIds: []
    Ref mult
      resolvedId: None
      candidateIds: []
    LiteralInt 2
    LiteralInt 2
```

### After reference resolution

`*`, `!`, and `mult` each have one candidate and are resolved immediately. `-` has two
candidates and remains unresolved until expression rewriting.

```text
Bnd a
  id: example::bnd::a
  Expr
    LiteralInt 2
    Ref *
      resolvedId: stdlib::bnd::op.star.2
      candidateIds: [stdlib::bnd::op.star.2]
    LiteralInt 4
    Ref !
      resolvedId: example::bnd::op.bang.1
      candidateIds: [example::bnd::op.bang.1]
    Ref -
      resolvedId: None
      candidateIds:
        stdlib::bnd::op.minus.2
        stdlib::bnd::op.minus.1
    Ref mult
      resolvedId: example::bnd::mult
      candidateIds: [example::bnd::mult]
    LiteralInt 2
    LiteralInt 2
```

### After expression rewriting

The rewriter selects binary `-` and groups the expression as
`(2 * (4!)) - ((mult 2) 2)`.

```text
Bnd a
  id: example::bnd::a
  value: App
    fn: App
      fn: Ref -
        resolvedId: stdlib::bnd::op.minus.2
        candidateIds:
          stdlib::bnd::op.minus.2
          stdlib::bnd::op.minus.1
      arg: App
        fn: App
          fn: Ref *
            resolvedId: stdlib::bnd::op.star.2
            candidateIds: [stdlib::bnd::op.star.2]
          arg: LiteralInt 2
        arg: App
          fn: Ref !
            resolvedId: example::bnd::op.bang.1
            candidateIds: [example::bnd::op.bang.1]
          arg: LiteralInt 4
    arg: App
      fn: App
        fn: Ref mult
          resolvedId: example::bnd::mult
          candidateIds: [example::bnd::mult]
        arg: LiteralInt 2
      arg: LiteralInt 2
```

`mult 2 2` and the binary operator uses each form a chain of two applications. The postfix
operator takes one argument, so `4!` becomes one application.

## Boolean operators

```mml
let flag1 = false;
let flag2 = true;

let result =
  if not flag1 and flag2 or not flag1 and not flag2 then
    "NADA"
  else
    "ALGO"
;
```

### Original flat AST

```text
Bnd result
  id: None
  Expr
    Cond
      cond:
        Expr
          Ref not
            resolvedId: None
            candidateIds: []
          Ref flag1
            resolvedId: None
            candidateIds: []
          Ref and
            resolvedId: None
            candidateIds: []
          Ref flag2
            resolvedId: None
            candidateIds: []
          Ref or
            resolvedId: None
            candidateIds: []
          Ref not
            resolvedId: None
            candidateIds: []
          Ref flag1
            resolvedId: None
            candidateIds: []
          Ref and
            resolvedId: None
            candidateIds: []
          Ref not
            resolvedId: None
            candidateIds: []
          Ref flag2
            resolvedId: None
            candidateIds: []
      ifTrue:
        Expr
          LiteralString "NADA"
      ifFalse:
        Expr
          LiteralString "ALGO"
```

### After reference resolution

Each reference has one candidate here, so `RefResolver` sets its `resolvedId`.

```text
Bnd result
  id: example::bnd::result
  Expr
    Cond
      cond:
        Expr
          Ref not
            resolvedId: stdlib::bnd::op.not.1
            candidateIds: [stdlib::bnd::op.not.1]
          Ref flag1
            resolvedId: example::bnd::flag1
            candidateIds: [example::bnd::flag1]
          Ref and
            resolvedId: stdlib::bnd::op.and.2
            candidateIds: [stdlib::bnd::op.and.2]
          Ref flag2
            resolvedId: example::bnd::flag2
            candidateIds: [example::bnd::flag2]
          Ref or
            resolvedId: stdlib::bnd::op.or.2
            candidateIds: [stdlib::bnd::op.or.2]
          Ref not
            resolvedId: stdlib::bnd::op.not.1
            candidateIds: [stdlib::bnd::op.not.1]
          Ref flag1
            resolvedId: example::bnd::flag1
            candidateIds: [example::bnd::flag1]
          Ref and
            resolvedId: stdlib::bnd::op.and.2
            candidateIds: [stdlib::bnd::op.and.2]
          Ref not
            resolvedId: stdlib::bnd::op.not.1
            candidateIds: [stdlib::bnd::op.not.1]
          Ref flag2
            resolvedId: example::bnd::flag2
            candidateIds: [example::bnd::flag2]
      ifTrue:
        Expr
          LiteralString "NADA"
      ifFalse:
        Expr
          LiteralString "ALGO"
```

### After expression rewriting

The rewriter uses the operator metadata to group the condition:

```mml
((not flag1) and flag2) or ((not flag1) and (not flag2))
```

The resulting applications sit inside the conditional:

```text
Bnd result
  id: example::bnd::result
  value: Cond
    cond: App
      fn: App
        fn: Ref or
          resolvedId: stdlib::bnd::op.or.2
          candidateIds: [stdlib::bnd::op.or.2]
        arg: App
          fn: App
            fn: Ref and
              resolvedId: stdlib::bnd::op.and.2
              candidateIds: [stdlib::bnd::op.and.2]
            arg: App
              fn: Ref not
                resolvedId: stdlib::bnd::op.not.1
                candidateIds: [stdlib::bnd::op.not.1]
              arg: Ref flag1
                resolvedId: example::bnd::flag1
                candidateIds: [example::bnd::flag1]
          arg: Ref flag2
            resolvedId: example::bnd::flag2
            candidateIds: [example::bnd::flag2]
      arg: App
        fn: App
          fn: Ref and
            resolvedId: stdlib::bnd::op.and.2
            candidateIds: [stdlib::bnd::op.and.2]
          arg: App
            fn: Ref not
              resolvedId: stdlib::bnd::op.not.1
              candidateIds: [stdlib::bnd::op.not.1]
            arg: Ref flag1
              resolvedId: example::bnd::flag1
              candidateIds: [example::bnd::flag1]
        arg: App
          fn: Ref not
            resolvedId: stdlib::bnd::op.not.1
            candidateIds: [stdlib::bnd::op.not.1]
          arg: Ref flag2
            resolvedId: example::bnd::flag2
            candidateIds: [example::bnd::flag2]
    ifTrue: LiteralString "NADA"
    ifFalse: LiteralString "ALGO"
```

`not` binds more tightly than `and`, which binds more tightly than `or`. Each unary use
becomes one `App`; each binary use becomes two. `Cond` keeps its condition and branches.
