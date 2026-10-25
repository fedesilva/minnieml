# A persistent compiler graph, and possible INIR work

Internal brainstorming note — 2026-10-25

This records the direction discussed so far. It is not an implementation plan or a settled design.

## What we want

The starting idea is to make the compiler’s graph relationships explicit and store them persistently. Compiler phases and tools could query the same data, add analysis results, and record changes without rebuilding the whole program in memory.

The goals are:

- Reduce total compilation time for release builds.
- Reuse previous work when only part of a program changes.
- Run independent analyses and transformations in parallel.
- Support expensive analysis: ownership, array-size proofs, mutation tracking, borrow invalidation, and interactions between effects and modes.
- Let the compiler and LSP work with a program database without loading the entire thing into memory.

The graph representation, storage layout, and scheduling need to be designed together. None of these goals requires adopting interaction nets first.

## Where the idea came from

Many of our semantic phases already walk structures that can be understood as graphs. There are syntax relationships, references to declarations, types, captures, calls, and ownership relationships. We keep traversing and rebuilding parts of these structures, sometimes repeating analysis after a transformation changes them.

Partial application elaboration is one example. It uses type information to turn partial calls into explicit closures, preserve evaluation of supplied arguments, and establish their captures. It runs after type checking. This led back to the broader question: can we make those graph relationships explicit and provide common tools for querying and changing them?

There are two connected ideas here:

1. A persistent representation shared by compiler phases and development tools.
2. An interaction-net representation for some of the compiler's transformations.

We can develop the first without committing every analysis to the second.

## The graph representation

The syntax tree describes how expressions are nested. Semantic analysis adds relationships that cross that tree: a name refers to a declaration, an expression has a type, a closure captures a binding, and a call depends on another function.

The proposal is to represent these relationships directly, with typed nodes and explicit links. For example:

| Data | Relationships we want to follow |
| --- | --- |
| Expression | Children in source order, assigned type, source location |
| Name use | Declaration it refers to |
| Function | Parameters, body, callers, effect summary |
| Closure | Captured bindings and how each is used |
| Ownership result | Value, owner, borrows, relevant uses |
| Analysis result | Nodes and other facts it depended on |

These links have different meanings. Following syntax children is different from following calls or ownership dependencies. Traversal tools should let a phase choose the relationships it needs, the direction to follow, and where to stop. Ordered relationships, such as arguments, must keep their order.

Common operations could include finding all uses of a binding, walking a function body without entering nested functions, following dependencies after a change, and revisiting only nodes whose inputs have changed. Cycles need explicit handling, particularly for recursive calls and analyses that repeat until their results stop changing.

A phase could add a table of facts keyed by existing node IDs rather than copy an entire tree just to attach more information. A transformation that changes program structure would create replacements and record their relationship to the original nodes. That relationship is useful for diagnostics: an error involving a generated closure should still point back to the source expression that caused it.

We need a distinction between adding knowledge about an existing program and changing the program representation. We also need a way to ask which version of a node or fact belongs to a particular compilation snapshot.

This does not imply a generic graph database with string properties for everything. The logical model is a graph; the physical layout can use specific tables and compact columns for each kind of data. Indexes can support reverse lookups, such as finding every use of a declaration, without scanning all nodes.

The graph API would describe what to follow or change. The storage layer would decide how to access the corresponding columns and indexes. Keeping that boundary clear would let us improve the layout without rewriting every semantic phase.

## Persistent storage is part of the idea

The intended storage is a data-oriented layout backed by files. Nodes of a particular kind can have their own tables or columns. Relationships use IDs and indexes. New kinds of nodes or analysis results can be added as separate tables linked to existing data.

Memory-mapped files let the operating system bring pages into RAM as they are accessed and reclaim them under memory pressure. The complete database does not have to become a large collection of heap objects. Active work still uses RAM; the point is to keep that working set bounded.

This also gives the LSP a way to query declarations, references, types, and diagnostics without keeping the whole compiler state alive.

The earlier notes considered Arrow IPC for directly accessible data, Parquet for compact distribution or storage, and SQLite for build state and coordination. Those are candidates, not a requirement that every kind of data use the same format.

There are still storage decisions to make:

- Keep logical node identity separate from its location in a file.
- Distinguish a source revision from a compiler phase's view of that revision.
- Publish related changes together so readers see a consistent state.
- Track which old versions are still needed, then reclaim the rest.
- Arrange data so common traversals touch nearby pages. Mapping files does not make scattered access cheap.

## Extending the idea to INIR

INIR means an interaction-net intermediate representation: a form of the program that the compiler can simplify through small graph rewrites. This is a further idea built on the graph discussion, not a requirement for the persistent representation.

The interest is in compile-time analysis and optimization. We are not proposing an HVM runtime for MML. The compiler would produce native code through its normal backend after working on this representation.

A general graph gives us nodes and edges. Interaction nets also restrict how changes happen.

Nodes have a distinguished connection called a principal port. When two nodes meet at those ports, they can form an active pair: a small piece of the graph ready for a rewrite. The rewrite replaces that pair and reconnects the surrounding wires.

With the appropriate interaction rules, independent pairs can be reduced in parallel. The rules have a property called strong confluence: choosing a different reduction order does not change the eventual result. Arbitrary compiler rewrites do not automatically have that property.

The useful compiler pattern would be:

1. Change a small part of the graph.
2. Identify the work that this change makes possible or invalidates.
3. Schedule that work without walking the entire program again.

That is the part worth investigating. We would need to define which INIR rules satisfy these conditions and how they interact with other optimizations.

## What we took from HVM and Interaction Calculus

HVM shows practical ways to implement local rewrites with compact nodes, explicit wiring, atomic updates, and queues of available work. Interaction Calculus provides a textual way to express related operations, including function application, duplication, and erasure.

Explicit duplication is interesting for MML, but we need to distinguish three things:

- Copying compiler representation, such as a function body for specialization.
- Copying a program value, which may be restricted by ownership and modes.
- Repeating a computation, which may change effects, evaluation order, or termination.

A duplication node is not permission to copy an owned resource or repeat an effect. Those permissions must come from MML's semantics and analysis.

The HVM2 paper also documents restrictions in its higher-order duplication encoding and problems with eagerly reducing everything. We should not adopt that encoding or reduction policy without checking that it preserves MML programs.

For a compiler, reduction needs a stopping policy. Unknown inputs, recursion, and code growth all matter. The output is a remaining program, not necessarily a fully evaluated result.

For example:

```mml
fn add(a: Int, b: Int): Int = a + b;;

fn example(x: Int): Int =
  let plus10 = add 10;
  plus10 x;
;
```

The optimizer can remove the partial application and simplify the body to `10 + x` without knowing `x`. INIR would be one way to express that transformation. We still have to preserve eager evaluation, captures, and ownership in more complicated cases.

Turning the remaining graph into backend code is a separate part of the design. It must preserve sharing rather than accidentally expand shared work into repeated computation.

## Analysis and parallel work

Not every analysis needs to become an interaction rule. A useful arrangement could be:

| Work | Possible unit of work |
| --- | --- |
| Simplifying applications and closures | Small graph region |
| Tracking ownership and mutation | Changed facts and affected constraints |
| Proving array sizes or bounds | Related constraints passed to a solver |
| Computing effects across calls | Function summaries, with recursive groups handled together |
| Reusing previous results | Recorded dependencies on bodies, signatures, or analysis facts |

For example, a solver could publish a proof that an access is in bounds. That fact could enable a local rewrite that removes the check. The solver and the rewrite engine can share IDs and scheduling without using the same algorithm.

The Kotlin K2 discussion was relevant here: resolving individual declarations to the phase needed by a query suggests work units such as `(declaration, analysis phase)`. This is a useful scheduling idea alongside local graph rewrites. It does not mean every function is independent of every other function.

There are also two different forms of parallelism to keep separate:

- The compiler doing analyses or rewrites in parallel.
- The generated program doing work in parallel, including SIMD: applying one machine instruction to several values.

INIR may help expose independent computations. Producing useful SIMD still needs information about loops, array layouts, aliasing, and memory dependencies. We should keep those operations recognizable rather than encode everything into tiny generic nodes too early.

## Incremental compilation

Local rewrites are not enough to decide what can be reused after an edit. We need to record what each result depended on.

An optimization that inspected a callee's body depends on that body. An analysis that only read its effect summary may remain valid when the body changes but the summary does not.

Function boundaries and summaries could therefore provide useful cache boundaries. Cross-function optimization would record the additional dependencies it introduces. Recursive groups may require repeated analysis until their summaries stop changing.

A possible storage approach is to rewrite bounded regions and publish batches of changes as new versions. The LSP could keep reading a consistent snapshot while compilation continues. This is still a proposal; storing every tiny rewrite permanently could create too much data and bookkeeping.

## Existing notes and prototype

The earlier MML discussion lives under:

`docs/brainstorming/compiler/reactive-data-oriented-compiler/`

- `multi-module-1.md`: persistent build state and reactive module scheduling.
- `multi-module-2.md`: RAM as a cache, mapped data, storage formats, dependency handling, and LSP access.
- `multi-module-3.md`: an AST-to-Arrow layout with typed columns, IDs, source locations, and phase-specific data.

The separate [lang-db-prototype](https://github.com/fedesilva/lang-db-prototype) explores the broader graph idea. The inspected code has language frontends, a generic graph API, traversals, and Arrow serialization. Its architecture notes discuss phase roots, sharing, provenance, and eventual interaction-net work.

The inspected serializer reads the stored graph back into Scala collections. It does not yet provide graph queries directly over mapped files. General rewriting, reliable dependency tracking, and incremental execution also remain unfinished. The prototype is a starting point, not an implementation of the architecture described here.

## Questions left open

- Which transformations should use interaction rules first?
- What types, ownership information, modes, and effects belong on a region's boundary?
- How do we preserve or invalidate analysis facts when a rewrite replaces nodes?
- How do we represent higher-order sharing soundly?
- What limits specialization and recursive expansion?
- How do workers coordinate changes without sacrificing storage locality?
- How much change history is useful for diagnostics and debugging?
- Does this reduce end-to-end release build time once storage, scheduling, and backend work are included?

A small experiment could start with partial applications and known closures, while keeping runtime inputs unknown. It should measure compilation time, memory use, generated code, and how much work is repeated after a small edit. That would give us evidence before expanding the design to the rest of the compiler.

## Links from the discussion

- [HVM2 paper and implementation details](https://github.com/HigherOrderCO/HVM2/blob/main/paper/HVM2.typst)
- [HVM3 representation and operations](https://github.com/HigherOrderCO/HVM3/blob/main/HVM.md)
- [Interaction Calculus](https://github.com/VictorTaelin/Interaction-Calculus)
- [Absal / abstract-algorithm](https://github.com/VictorTaelin/abstract-algorithm): the earlier JavaScript lambda-to-net implementation we found; its documented limitations matter.
- [Kotlin K2 architecture discussion](https://blog.jetbrains.com/idea/2025/04/the-story-behind-k2-mode-and-how-it-works/)
