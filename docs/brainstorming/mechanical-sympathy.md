# Mechanical sympathy

### Native operation integration

```
# Direct LLVM IR template integration
# This will lower to llvm ir as `mul i64 %0, %1`
fn mult(a: I64, b: I64): I64 = @native[tpl="mul %type %operand1, %operand2"]

# Forward declarations for external functions
fn println(s: String) = @native;
```

### Types

```
# Signed integers
type I8 = @native[t:i8]
type I16 = @native[t:i16]
type I32 = @native[t:i32]
type I64 = @native[t:i64]

# Unsigned integers
type U8 = @native[t:i8 unsigned]
type U16 = @native[t:i16 unsigned]
type U32 = @native[t:i32 unsigned]
type U64 = @native[t:i64 unsigned]

# Floating point
type F32 = @native[t:float]
type F64 = @native[t:double]
```

### Protocol-based operations

```
// Generic numeric protocol
protocol Num 'T =
  op + (a: 'T, b: 'T): 'T;
  op - (a: 'T, b: 'T): 'T;
  op * (a: 'T, b: 'T): 'T;
  op / (a: 'T, b: 'T): 'T;
  op % (a: 'T, b: 'T): 'T;
  # Comparison operators
  op < (a: 'T, b: 'T): Bool;
  op > (a: 'T, b: 'T): Bool;
;

// Implementation for unsigned integers (speculative - flags not yet implemented)
instance Num U64 =
  op + (a: U64, b: U64): U64 = @native[tpl="add nuw %type %operand1, %operand2"];
  op * (a: U64, b: U64): U64 = @native[tpl="mul nuw %type %operand1, %operand2"];
  op / (a: U64, b: U64): U64 = @native[tpl="udiv %type %operand1, %operand2"];
  op % (a: U64, b: U64): U64 = @native[tpl="urem %type %operand1, %operand2"];
  op < (a: U64, b: U64): Bool = @native[tpl="icmp ult %type %operand1, %operand2"];
  # Other operations...
;

// Implementation for signed integers (speculative - flags not yet implemented)
instance Num I64 =
  op + (a: I64, b: I64): I64 = @native[tpl="add nsw %type %operand1, %operand2"];
  op * (a: I64, b: I64): I64 = @native[tpl="mul nsw %type %operand1, %operand2"];
  op / (a: I64, b: I64): I64 = @native[tpl="sdiv %type %operand1, %operand2"];
  op % (a: I64, b: I64): I64 = @native[tpl="srem %type %operand1, %operand2"];
  op < (a: I64, b: I64): Bool = @native[tpl="icmp slt %type %operand1, %operand2"];
  # Other operations...
;

// Implementation for floats
instance Num F32 =
  op + (a: F32, b: F32): F32 = @native[tpl="fadd %type %operand1, %operand2"];
  op * (a: F32, b: F32): F32 = @native[tpl="fmul %type %operand1, %operand2"];
  # Note: fcmp instead of icmp for floats
  op < (a: F32, b: F32): Bool = @native[tpl="fcmp olt %type %operand1, %operand2"];
;
```

## Region-based memory management

While the compiler will aggresively use stack allocation or static allocations when possible,
for heap allocation, the language uses a region-based memory management system.

Regions:

- are lexically scoped and nested
- are declared at function level
- are bump allocated
- are divided into typed lanes
- can be annonymous or named
- are first class and can be passed around as arguments
    - like zig's allocators; regions are after all scoped allocators.
    - one might want to create one and pass it.
- can have specialized memory layout strategies per lane
  - this gives control over the memory layout of the lane
  - allows strategies like soa (structure of arrays) or aof (array of structures)
- are not garbage collected, but can be freed manually or by going out of scope

While the compiler will infer regions, the user might
want to specify them explicilty for performance or architectural reasons.

### Typed lanes

In each region, types are allocated in lanes.
This allows the compiler to use different memory layouts for different types; for example,
a region can be declared to use a structure of arrays (SoA) layout for a specific type.

This also mitigates fragmentation since all the values in the same lane take
the same space, so the compiler can reuse the space of free values without
leaving holes.

### Lifetimes of regions and values associated with them

Lifecycles of regions are determined by their lexical scope.
Similarly, the lifetimes of values are determined by the region they are in.

Access vioations will result in compile time errors.

If a function and its caller do not share a region
return values are moved to the caller's region.

There are several ways to control or avoid return value copying:

- do not declare a region and the caller's will be used.
- design functions to take regions as arguments.

Users will place regions strategically to avoid copying values,
and create children regions for short lived values.

If the compiler is left to infer regions, it will try to minimize copying
by pruning regions to the mininum possible by using escape analysis.

### Anon local region

This function creates a local region for its operations.
The region is anonymous and scoped to the function.

```
fn persons(names: Array String): Array Person
  region =
    map to_person (filter ( s -> s == "fede" ) names)
;
```

### Using a specific region

This function takes a region as an argument and uses it for its operations.

The with clause binds the named region to the function's local region.
Later you will see another use of with.

```
fn explicit_region(r: Region s1: String s2: String): String =
  region with r =
    ???

;
```

### Named local region

Here the region is named so it can be referenced and passed to
the `explicit_region` function.

```
fn named_region (s1 s2) =
  region r =
    explicit_region r s1 s2
;
```

### Named and with

Here the function takes a region as an argument, installs it via the with
keyword and names it locally (kind of an alias).

Note: this seems a bit contrived, because why change the name, but
given that the region can be tuned (see next example), this might
prove useful.

This might go away, tbd.

```
fn named_and_with(r: Region) =
  region rr with r =
    ???
;
```

### Type driven layout

A list of type to layout strategy (a protocol) can be provided to the with clause.
This states that for any instance of Person, the SoA layout should be used
in its lane.

```
fn with_lane_layout ( data ): Array User =
  region r with [
    Person -> SoA
  ] =
    # This will use the soa lane layout for Person in this region
    map to_user data

```

## Practical region usage patterns

While region inference is the end goal, explicit region control remains valuable for expressing architectural intent. Consider this example of a web server's memory organization:

```
WebServer (top-level region)
├── NetworkIO Region
│   └── (Buffers, connection state, etc.)
├── RequestHandler Region
│   ├── Request1 Region (short-lived)
│   ├── Request2 Region (short-lived)
│   └── ...
├── Metrics Region
└── Observability Region
```

This hierarchical structure maps naturally to different concerns and lifetimes in a server:

1. The **NetworkIO region** might need specific memory layouts for efficient buffering and connection management
2. The **RequestHandler region** spawns and discards per-request regions, creating a perfect isolation boundary for request processing
3. Metrics and Observability regions likely have different access patterns and lifetimes than the request-handling code

Even with sophisticated region inference, these architectural decisions would be difficult for a compiler to make automatically since they're based on domain knowledge about the application's behavior patterns. The manual region boundaries express intent about the server's memory architecture in a way that's both functional and machine-aware.

Region inference would focus on optimizing lower-level allocations while preserving these higher-level architectural boundaries that the developer has explicitly defined.

## Type-based alias analysis (TBAA)

Type-Based Alias Analysis (TBAA) is a powerful optimization technique that allows the compiler to understand how different types interact with each other in memory. By annotating types with metadata about their aliasing behavior, the compiler can make more informed decisions about optimizations like reordering loads and stores, which can lead to significant performance improvements.

For each module we compile, we will generate a TBAA hierarchy that describes the relationships between types. This hierarchy will be used to annotate loads and stores in the generated LLVM IR, allowing the compiler to apply optimizations based on the aliasing behavior of different types.

## Summary of design principles

1. High-level functional abstractions with protocols, immutable data, and pure functions
2. Direct LLVM IR integration through the `@native` annotation system
3. Type-safe memory management through lexically-scoped regions
4. Machine-level control through explicit type representations and operation flags
5. Performance optimizations through specialized memory layouts and TBAA metadata
