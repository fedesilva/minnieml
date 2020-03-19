
# Todo

Warning, there be dragons here.
(meaning this is a brain dump, and as people that know me know (!?), I tend to 
  leave phrases unfin ... among other weirdness)

Proceed at your own risk.

## Syntax/Grammar 

* Review syntax

  - Match
    - reference to deconstructed exp itself (id '@' exp)

    
  - Exports tests
    
  - op needs syntax for:
    - precedence syntax 
    - associativity 

* protocols 
  * grammar
    * definition
    * impl

* `open`
    - Pods (external references) are `open`d
    - `use` declares what we use fom any opened pod
        - `use package.package2.Module`
      
    - you `open` a pod, then you're able to `use` it's exports         

            
              open MML.Std {
                #  ^^^^^^^ this is the pod
                # Stuff inside is relative to the pod
                Math
                Console.println
              }


* Pod syntax
  - multiple pods can be nested, like modules
  - components:
    - export
    - native
    - canon
    - pragmas
    - dependencies


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
      - pattern matching on the type (must have at least two branches)
 
 

## Tools

### Replacing IDEA now

  * Tool to display trees
    - java fx
      - with a webview and d3js or something like that
      - feed it from outside
      - provide a view with a text or a "callable view" so we can use it from the console


## Compiler 

  * ParserApi  
    - OK make them classes 
    - pass config
      - stdOut, 
      - diagnostics
      
  * Merge Hidden Tokens
    
  * State ADT to pass to stages
    * recover the hidden tokens
    * lexing error reporting 
  
  * better typed view of the tokens?

### Test

* parse tree matchers
  * so tree shape expectations can be asserted.

 
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
  * `_` (meh) does not allocate a symbol   
  * Lift simple types (see Typer1)


## Type checker

* Typer 1 (run mixed with previous processes?)
  * might not be able to type all expressions
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
    
    
