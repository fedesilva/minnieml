# MinnieML

A statically typed, functional language, with an ML inspired syntax,
declarative low level capabilities and first class native interface.

I loathe the notion of domain specific languages, I'd like to learn a lang, its stdlib and use
it to build webservers, websites, microservices, monoliths, kernel modules, wikis, embedded systems, mobile apps, desktop apps, shell scripts, etc. A list is a list, a string is a string, some platforms might not understand what a file or a thread is - the language and build tool need to know about this and help me.

From edge to cloud, from mobile to homes, and when you are sitting with your laptop - one lang to rule them all.

It's all about the fun and learning and exploring ideas.

Here's a small syntax showcase

```
    // Literal Number
    1;
    // Literal String
    "string";
    // Let Binding
    let a = 1;
    let b = 2;
    // Abstraction
    fn times a b = a + b;
    // Application
    times 1 2;
    times a b;
```

#### Legalese

This is not at all connected to my employer's IP, work, interests, opinions or anything else.

#### Fair warning

    "El que avisa no traiciona"

    Spanish for "He who warns you, does not betray you".


I'm only working on the grammar at this point and when I move on, it might take 
ages for me to complete the next stage.

This is not something you want to use, I'm making this public to share with friends, so if you continue, hello friend!

And remember, this is _my_ hobby and learning sandbox, no more, no less. 

## (Intended) Features

- Everything is an expression or a binding, ... mostly.
- Functional, strict (lazy semantics are opt in), pure (of sorts, no one is)
- Statically typed with \*ML style inference
- Protocols
  - type parametric overloading
  - type classes-ish, without the class in the name, tho, :)

- Common types

  - The primitives we all love, Int, Long, String, etc.
    - More specific lower level types, too.
      - everything that is supported by llvm
  - Tuples (product types)
  - Data types (product types, labeled)
  - Enums (sum types, homogeneous member types, no constructors, only names)
  - Variants (sum types, heterogeneous member types)
    - unions 
      - use as much memory as the largest type.
      - have constructors
    - enums  
      - no constructors
      - bit sized by default 
      - the compiler will pick the smallest integer possible to represent them
  - Cells
    - mutable memory locations (see effects below)
      - serializable write access (CAS)
      - concurrent read
  - Arrays
    - contigous fixed size memory blocks


- Refinement types
  - primitives with a predicate attached
  - defined in terms of patten matching

- Type level functions and values
  - singleton values
  - any value can be lifted to type level
    - even functions if pure

- Pattern matching and destructuring

  - for data types
  - primitives
  - match on any type
  - can be used in bindings (let (a,b) = (1,2))
  - match functions
    - functions defined in terms of pattern matching
      - compiled to multiple functions, dispatch on parameters (overloading)
      - if possible, inline at use site
  - binary pattern matching
  - structural pattern matching

- Modules
  - container of declarations
  - entry point for low level capabilities (see below)
  - clear and explicit export rules
    - private by default, design your apis, don't just leak them
    - export tables or access modifiers or both
    - circular deps are an error
    - memory management unit (see below).
    - first class modules
      - take parameters (mandatory)
      - define module templates, 
      - make instances at runtime
      - pass them around
  - can be composed
    - prototype modules
      - a module can mix in prototypes
      - not subtyping
        - (EXPLORE) if subtyping problems don't exist maybe enable row polymorphism
          - but uninclined
    - (EXPLORE)
      - dot calculus
    
  
    

- Resources, Handlers and Effects

  - Handlers: protocols that operate on resources.
  - the type of any expression operating on resources is "tainted" by an effect
    - so an effect is the result of applying a handler to a resource.
  - resources 
    - os resources 
      - processes 
      - io (stdin/stdout)
      - sockets
      - network      
    - mutable cells 
    - Native Interface 
        (if you think about it, NI is the source of all effects)
          - all stdlib os resources are backed by NI
    - uniqueness tracking
      - automatic or explicit
      - Effect is a protocol, so you can derive it.      
        - defines
          - acquisition
          - release
      - built in implementations  for above mentioned resources, canonical
  - effect boundaries
    - using a resource means crossing a boundary, 
      - which enables some interesting avenues for the typesystem to enforce and or optimize things,
        like who cares about uniqueness for something that is not mutable for example
        you can read it no problem if it's in scope.
    - mutually exclusive effects can be tracked
    - composable effects can be composed
    - irrelevant effects can be ignored
    
        

- Native Interface

  - leverage clang+llvm to interact with c as transparently as possible
    - c abi exports
  - Integrated with effects system
    - as resources and handlers
    - since everything not pure comes from "outside" this is natural.
  - Platform Tracking
    - protocols implementations and handlers can be annotated to denote platform specific implementations
    - type system tracks this
    - integrated with effect tracking 
      - when compiling to native this will be enforced

- Compiler 
  - Staged compilation model

    - Pod
      - Multi Platform binary file
      - non executable by itself (basically an ast, so interpretable)
      - share like a java jar, link to in FL land.
      - pods can reference native libs and can be compiled to native
    - Native
      - when compiling to native, optimization starts before passing to llvm even
      - native lib.
      - native executable.

  - Graph DB based AST
    - After parsing  the compiler traverses the parse tree
      - and "loads" it into graph db
      - instead of having several ast models 
      - work with annotated vertex and edges      
      - write traversals - queries and mutations - with a single dsl
      - use indexing for fast access
        - adjacency lists
      - parquet storage for modules for fast reads
      - keep db around (unless `clean`ed) 
        - fast incremental compilation
        - keep build "warm" even when closing tools
          - no need to forever reindex
      - can build tooling around.
        - expose some query mechanisms
        - as a lib
        - and from command line tool (using lib)

- Memory Management

  - by default, mix of gc and ref counted arenas
    - but this can be changed
    - arenas are basically scopes.
      - in fact, modules are mapped 1-1 to arenas
        - which are fused on optimization - if possible.
        - function level, we only allocate in the stack.
          - and performa escape analysis to find out 
    - gc for long lived arenas, specially the global one - the main heap - to avoid fragmentation.
    - gc is opt out
    - infered arenas 
      - tree of allocators, module based
      - escape analysis used to select correct arena

  - whenever posible, use columnar representation of arrays of data types
  - programmer control is possible via declarative means
  - declarative arenas and allocation strategies
    - you are in command, but must comply with rules
    - defined at the module level (each module IS an arena)
    - with type granularity
    - write your own allocators and declare where and when they are to be used.
      - in collaboration with the type and module systems
      - and the small runtime allocation framework (central book keeper)
    - ability to lift memory management into an observable effect
      - opt in
      - using this and bit pattern matching you can write very low level code
        including allocator/destructors, while keeping safety.

- Lower level capabilities

  - the compiler makes memory management transparent but:
    - you can integrate (see above) declaratively
      - with the allocator
      - even with the machinery to encode/decode chunks of memory into data types
        - write your own packing instructions, per platform even.
  - you can also explicitly declare things like uniqueness and borrowing
    - so you can control precisely how memory is used, when and where it's copied.
      - and enables you to hint the memory management inference algorithm
        - enforce not escaping, not sharing, etc

- Pod and Module system
  - pods are top level modules
  - canon, like modules    
  - native exports (to compile to c libraries) need to specify specialization rules for parametric types
    - can compile to .h and .o so c programs can use.
      - allows c users to specify to which type you want to specialize
  - higher order modules
    - modules can be instanced with params and passed around
    - like objects without subtyping,
      - Native Interface uses this to map to c++ classes (maybe objc)

* A scripting interpreter

  - compiles fast to ast then interpret.
  - a bit more relaxed, particularly about resources and effects
  - expose interface, usable for debugging, and runtime inspection during development.
  - also to write and run scripts (as in shell scripts, but with a real language)
  - so usable as a library and as an executable

* Tooling
  - every bit is a library, compiler, repl, interpreter, etc.
  - so we can implement a language server, build tool and refactoring tools by using the same
    code the compiler uses.

### Docs

TBD (some day, not "mucho" to document now)

### How

For now writen in Scala using Antlr4, and sbt-antlr4.

When the time comes to generate code I'll see how to `llvm` from java/scala.

For now the near term strategy is to write an interpreter - and maybe the interpreter can call c, then use that until bootstraping is achieved. Or else just continue to the codegen phase and use that.

I really want to have an interpreter, which will of course be slower but will allow debugging,
shell scripting, and an early implementation before I have fully grokked llvm and the memory allocation system.

Not important for now, I'm still working on the grammar and the initial post parse IR.

### Running

No.

Not, yet.

...

Soon, now. Really.

No, I don't mean that last thing.

Forever.
