# MML Data Plane Specification: AST to Arrow Mapping v1.2

**Abstract**
This document defines the physical memory layout for MML compilation artifacts using Apache Arrow. The architecture flattens the recursive AST into relational tables (Vectors) to enable:
1.  **Zero-Copy Access:** Compiler phases map files directly into memory.
2.  **Dense Packing:** Related data (e.g., all Function Calls) is contiguous.
3.  **Out-of-Core Processing:** Partial loading of Modules/Types without bodies.
4.  **Additive Enrichment:** Successive phases layer new data onto immutable IDs.

---

## 1. Common Primitives & Metadata

### 1.1 Source Spans (`SrcSpan`)
**Strategy:** "Parallel Array". Spans are stripped from the AST nodes and stored in a separate vector in the base phase. To find the span for `Term[i]`, access `TermSpans[i]`.
**Schema:** `Struct`
* `file_id`  : `u16` (Dictionary encoded file path)
* `start`    : `u32` (Absolute character index)
* `len`      : `u32` (Length of the span)

### 1.2 Identifiers
**Strategy:** Dictionary Encoding.
**Schema:** `Dictionary<Int32, Utf8>`
* Used for all Names (`FnDef.name`, `Ref.name`, `Param.name`).

---

## 2. The Type System (Interned)

We separate **Type Definitions** (Named entities like `String`) from **Type Specifications** (Usage like `String -> Int`).

### 2.1 Type Definitions (`TypeDefs`)
*The Registry. Stores the structure of named types once.*
**Vector:** `Union(Dense)`
* **Child 0: Primitives** (`NativePrimitive`)
    * `llvm_type`: `Enum` (i8, i16, i32, i64, float, double, void)
* **Child 1: Structs** (`NativeStruct`)
    * `fields_offset`: `u32` (Start index in `StructFields` vector)
    * `fields_len`   : `u16`
* **Child 2: Pointers** (`NativePointer`)
    * `inner_type_id`: `u32` (Pointer to `TypeDefs`)

### 2.2 Type Specifications (`TypeSpecs`)
*The Graph. Every AST node points here for its type.*
**Vector:** `Union(Dense)`
* **Child 0: Ref** (`TypeRef`)
    * `def_id`: `u32` (Foreign Key -> `TypeDefs`)
* **Child 1: Fn** (`TypeFn`)
    * `params_offset`: `u32` (Start index in `TypeLists`)
    * `params_len`   : `u16`
    * `ret_type_id`  : `u32` (Recursive Pointer -> `TypeSpecs`)
* **Child 2: Tuple** (`TypeTuple`)
    * `elems_offset` : `u32` (Start index in `TypeLists`)
    * `elems_len`    : `u16`
* **Child 3: Var** (`TypeVariable`) - *The IOU*
    * `name`: `String` (e.g., "'T")

---

## 3. The Execution Graph (`Expr` & `Term`)

This handles recursive structures (Currying).

### 3.1 Expression Blocks (`Exprs`)
*Wraps a list of terms. Even single terms are wrapped here to provide a standard addressable unit.*
**Vector:** `List<TermID>`
* **Offsets Buffer:** `[0, 1, 3, ...]`
* **Values Buffer:** `[TermID_0, TermID_1, TermID_2...]`
    * `TermID` is a struct `{ tag: u8, index: u32 }` pointing to the `Terms` Union.

### 3.2 Terms (`Terms`)
*The Polymorphic Core.*
**Vector:** `Union(Dense)`

#### Child 0: Literals (`Lits`)
| Column | Type | Notes |
| :--- | :--- | :--- |
| `kind` | `Enum` | Int, Float, Bool, String, Unit |
| `val_i64` | `i64` | Storage for Ints/Bools |
| `val_f64` | `f64` | Storage for Floats |
| `val_str` | `Utf8` | Storage for Strings |

#### Child 1: References (`Refs`)
| Column | Type | Notes |
| :--- | :--- | :--- |
| `name` | `Dict<String>` | |
| `res_kind` | `Enum` | Unresolved, Fn, Param, Local |
| `res_id` | `u32` | FK -> `FnDefs`, `Params`, or `Binds` |
| `candidates_offset` | `u32` | FK -> `CandidateLists` (Overloads) |

#### Child 2: Applications (`Apps`)
*Recursive Tree Structure.*
| Column | Type | Notes |
| :--- | :--- | :--- |
| `fn_tag` | `u8` | Is Fn a `Ref` or another `App`? |
| `fn_idx` | `u32` | FK -> `Terms` (Ref or App) |
| `arg_expr` | `u32` | FK -> `Exprs` (The argument block) |

#### Child 3: Native Ops (`Natives`)
*Handles `NativeImpl` nodes.*
| Column | Type | Notes |
| :--- | :--- | :--- |
| `opcode` | `Enum` | add, sub, icmp_eq, etc. |

#### Child 4: Conditionals (`Conds`)
| Column | Type | Notes |
| :--- | :--- | :--- |
| `cond_expr` | `u32` | FK -> `Exprs` |
| `true_expr` | `u32` | FK -> `Exprs` |
| `false_expr` | `u32` | FK -> `Exprs` |

### 3.3 Example Mapping
**Source:** `concat "Hola" a`
**AST:** `App(fn: App(fn: Ref(concat), arg: "Hola"), arg: Ref(a))`

**Arrow Layout:**
1.  `Ref:20` -> "concat"
2.  `Lit:50` -> "Hola"
3.  `Ref:21` -> "a"
4.  `App:90` -> `{ fn: Ref:20, arg: [Lit:50] }`  *(Inner)*
5.  `App:91` -> `{ fn: App:90, arg: [Ref:21] }`  *(Outer)*

---

## 4. Interface & Declarations (`Members`)

The entry points for the compiler.

### 4.1 Function Definitions (`FnDefs`)
| Column | Type | Notes |
| :--- | :--- | :--- |
| `name` | `Dict<String>` | |
| `visibility` | `Enum` | Pub, Prot, Priv |
| `params_offset` | `u32` | Start index in `FnParams` |
| `params_len` | `u16` | |
| `body_expr` | `u32` | **The Root Pointer** (FK -> `Exprs`) |

### 4.2 Function Parameters (`FnParams`)
*Contiguous vector referenced by `FnDefs`.*
| Column | Type | Notes |
| :--- | :--- | :--- |
| `name` | `Dict<String>` | |

### 4.3 Operator Definitions (`OpDefs`)
| Column | Type | Notes |
| :--- | :--- | :--- |
| `name` | `Dict<String>` | |
| `kind` | `Enum` | Binary, Unary |
| `prec` | `u8` | Precedence |
| `assoc` | `Enum` | Left, Right |
| `body_expr` | `u32` | FK -> `Exprs` |
| `params_offset` | `u32` | FK -> `FnParams` |

---

## 5. Auxiliary Vectors (For Lists)

Arrow lists are physically stored as contiguous slices of a child vector.

* `TypeLists`: Backing store for `TypeTuple` and `TypeFn` params. Contains `TypeID`s.
* `CandidateLists`: Backing store for `Ref.candidates`. Contains `ResolvableID`s.
* `StructFields`: Backing store for `NativeStruct`. Contains `{name, type_id}` pairs.

---

## 6. Access Patterns (Examples)

### Example A: Type Checking "Int"
1.  Parser sees `TypeRef("Int")`.
2.  Lookup "Int" in `TypeDefs` (loaded from Registry). Found at row **10**.
3.  Write to `TypeSpecs` (in Phase 3 artifact) at row **100**: `{ Tag: Ref, DefID: 10 }`.
4.  Return **100**.

### Example B: Traversing `concat "Hola"`
1.  Load `FnDef`. Read `body_expr` -> **500**.
2.  Load `Exprs[500]`. Value is `TermID(App, 91)`.
3.  Load `Terms.App[91]`.
    * `fn`: `TermID(App, 90)` -> Inner App
    * `arg`: `ExprID(600)` -> `[Ref("a")]`

---

## 7. Incremental Phase Storage (Additive Enrichment)

### 7.1 Strategy: The Layer Cake

The compilation process is treated as a progressive enrichment of the data model. Each phase generates a new Arrow artifact that **layers new information** on top of the immutable IDs established in previous phases.

Note: this is an oversimplification, for the final stages and their phases are not fully defined yet

**The Chain of Custody:**
* **Phase 1 (Base):** Defines the **AST IDs** (The "Primary Keys").
* **Phase 2 (Symbols):** Adds a "Resolution Column" keyed by AST ID.
* **Phase 3 (Types):** Adds a "Type Column" keyed by AST ID.

### 7.2 Structure: Foreign Key Overlays

Later phases do not replicate AST structure. They contain **associative vectors** that map IDs from previous phases to new data.

**Phase 1 Artifact (`parse.arrow`):**
* **Vector:** `Terms`
    * `Row 10`: `Ref("x")`
    * `Row 11`: `Lit(1)`

**Phase 2 Artifact (`resolve.arrow`):**
* **Vector:** `Resolutions` (Maps `TermID` -> `SymbolID`)
    * `Row 10`: `Symbol(50)` (Resolved "x" to a param)
    * *Note: Row index matches TermID, or explicit `term_id` column is used.*

**Phase 3 Artifact (`type.arrow`):**
* **Vector:** `TypeAssignments` (Maps `TermID` -> `TypeID`)
    * `Row 10`: `Type(Int)`
    * `Row 11`: `Type(Int)`

### 7.3 Read Strategy (The Stack)

When **Phase 4** needs to process a node:
1.  **Identity:** It has the `TermID` (e.g., 10) from Phase 1.
2.  **Context:** It queries the **Symbol Table** (loaded from Phase 2) using ID 10 to get the symbol.
3.  **Semantics:** It queries the **Type Map** (loaded from Phase 3) using ID 10 to get the type.
4.  **Action:** It generates Phase 4 data (e.g., LLVM IR) and writes it to `codegen.arrow`, keyed by ID 10.

---

## 8. Advanced: Region Summaries (Out-of-Core)

This is an example of an advance later stage which generates memory regions and managing code.

To support global region inference without global loading, we store function summaries.

**Vector:** `RegionSummaries`
* `Fn_ID`: FK -> `FnDefs`
* `Return_Escapes`: `Boolean`
* `Args_Escape_Mask`: `u64` (Bitmask representing which arguments escape)

**Usage:**
When checking `main`, if it calls `concat`, we load `concat`'s row from `RegionSummaries` instead of loading `concat`'s body.
