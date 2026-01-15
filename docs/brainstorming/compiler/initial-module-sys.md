
## What

Implement an abridged version of north star architecture (in `docs/brainstorming/compiler/reactive-data-oriented-compiler`)

### Preliminary work

Ideally, independent tasks.

#### Introduce nested modules 
  - why? we need to make this work before proceeding with multifile compilation - 
      for the simple internal case.
  
  - using `module` keyword inside a file (which is itself a module)
    ```
      module A = 
        ...
      ;
      module B = 
        module C = 
          ...
        ;
      ;
    ```    
  - we need imports
    - could get away without them using fq names but:
      - we want to reference operators!
        - imagine doing 2 ModuleA.ModuleB.+ 2;
          - not sure this will even parse! 
    - imports are only allowed top level
  - we need selection 
    - (Module.member or Module.Module.member)
      - ref resolver will have to work with this.
  - all the semantic phases need to be able to walk through selections
    - ref resolver
    - type resolver
    - typeChecker
    * expression rewriter probably does not need it.
      
 
#### Introduce the concept of package.

A Package is the top level container for MML code.
A container for all the modules of the program and the build metadata.


  - Compiler can take a file or a folder
    - if file, package is implicit
      - use defaults or flags passed by cli (whatever they are)
    - if folder package is implicit unless there is config 
            - if no file use defaults or flags passed by cli (whatever they are)
      - config TBD (prolly toml or yaml or hocon)

## Compilation algorithm

in parallel:
* find a parse all files.
* each folder inside the package is a module.
* initial phase
   * resolve internal value references
      * what can't be resolved waits.
   * resolve internal type references
      * what can't be resolved waits.
   * export public symbos (values and types)
   * create dependency list (using import declarations)
      * put dependencies in a share system list.
* middle phase (also in parallel)
   * when other modules you have dependencies on save their exports
      * compiler goes through IOU list and wakes waiting modules.
   * load external symbols (types and values)
   * rewrite expressions (using local and external metadata)
   * do local type checking - using the simple typechecker I shared
      * instead of failing with unknown types, create type variables, constrained if info is enough
* sequential phase.
   * once we processed all files and all modules are compiled and rewritten
   * run second typechecker 
      * bidi
      * generalizing
      * this one fails if it can work out the types

## Migration

The algorithm described above needs to be weaved into the current architecture; we need to use the same stage with phases approach.

* We need to split the semantic phase in two 
  - because we can just go ahead an rewrite expressions
  - But Ingest is the same for example.

* Stages draft:

   * IngestPhase.
     * Sequentially:
       - walks the folders, collecting files and
         creating the module tree
     * In Parallel
       - parse
       - lift parsing errors to compiler state
     
   * InitialSemantic 
       - the first three are the same as now
       - create exports list
         - fn, op, types 
       - create imports list.
          
   *  SecondSemantic
       * In Parallel 
         - find exports from requirements
         - duplicate names checker
           - consider also imported names 
         - ref resolver
           - need to walk through to other modules via imports
         - type resolver
           - need to walk through to other modules via imports
         - ExpressionRewriter 
           - purely local
         - TypeChecker
           - SimpTyper

     
        
   









