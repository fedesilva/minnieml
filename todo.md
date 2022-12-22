    
# Todo

Warning, there be dragons here.

Meaning this is a brain dump, and as people that know me know (!?), I tend to 
  leave phrases unfin

Proceed at your own risk.

## Syntax/Grammar 

* make use of tree matching (see below in testing)
  - it has happened to me several times that somethig parses but the resulting tree makes no sense.
  - use tree matchers (antlr thingie) so that shape expectations can be asserted

* protocols
  * grammar
    * definition
    * impl

* tests
  
  - cover more cases for grammar tests
    - union types
    - more patterns
    - more tpSpecs
    - more type parameters
    

* `open`
    - Pods (external references) are `open`d
      
    - you `open` a pod, then you're able to `use` it's exports         


              open MML.Std use
                #  ^^^^^^^ this is the pod
                # Stuff inside is relative to the pod
                Math                  # A full module
                Console.println       # A single name from the module
              ;


* Pod syntax
    
  - members:
    - export
        - same as in module
    - native
        - same as in module
    - canon
        - declare an impl for a type canonical, can't be redefined
    - pragmas
        - compiler flags
    - dependencies
        - module names and versions

* Modules
  - native modifier, include clause 
       
       module native A = 
         include = "stdio.h";
     
     - ffi syntax and rules
 
* Refinement types 
  - match types are the predicates
    - a type defined in terms of cases
      - can examine values
    - how do we create constants of this type? (like `refinedM`)
      - type ascription
    - how do we refine types in runtime? (like `refinedMV`)
      - pattern matching on the type 
        - must have at least two branches
        - one branch representing the ok case
          - at least, more if granted, of course.
        - one branch representing the not ok case

## Tools

### Compiler 

  * ParserApi  
    - OK make them classes 
    - pass config
      - stdOut, 
      - diagnostics
      
  * Merge Hidden Tokens
    
  * --> graph db
    * walk the parse tree and generate a graph
      - Walk the tree from a module, creating nodes
        - start at module          
          - walk members
            * letBnd   
            * fn          
            * fnM         
            * op          
            * nestedModule
            * dt          
            * variant     
            * tpAlias     
          - walk imports
          - walk exports

##Test

* parse tree matchers
  * so tree shape expectations can be asserted.
    - create a new tool in parser
       

## Post parse 

* Ast creation
* Post processing 
    * (complimentary to parse)  
    * check visibility rules
      * check for conflicting or nonsensical combinations
    * module initialization: all let expressions should be stable or lazy.
    * const check 
  * app disambiguation 
      * once we know which symbol is a function and we know arity, 
        * possibly triggers rewriting:
          * consume all parameters to the right until exhausting arity or finding another app.
        * partial application - if not all params are provided type of exp will be fnType.
        
* References table (external names)
    * Internalize Pod signatures and types immediately (stable,  already compiled).
    
* Modules Reference Index
  * used to organize direction of analysis: 
    - first terminal modules - that is no dep modules

* Symbol table 
  * Name finding
  * `_` (meh) does not allocate a source accesible symbol
  * Ascribe simple types (see Typer1)


## Type checker

* Typer 1 (run mixed with previous processes?)
  * not able to type all expressions
  * function 
    * arity count
    * explicitly typed params  
    * naming of type variables (not yet typed parameters get a sequential name)
  * simple type ascription from literals and typed parameters
  * solve explicit type parameters (can only be spec'd once)

* Typer 2 (no type should be left unknown: either concrete or TVar )  
  * app
    * infer types by looking at body and call sites
      * normal functions, use type of param if known
      * search protocols 
        * first those declared as dependency,
        * then in scope
          * protocol selection
            * by function name
            * by suspect type
            * If arity >= 1, prioritize Single type protocols
            * if match is exact for all types
            * if multiple types, 
              * filter those that type matches parameter position
              * find types of other parameters to further reduce list
            * finally if multiple matches, fail.
    
    
