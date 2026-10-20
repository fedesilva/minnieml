# Author Notes

The following are notes that need to be turned into tasks or the Author wants to keep track of.
Do not read or act on them unless directed to do so explicitely.


## Notes


* LSP is broken, paps are displayed as $environment something
* LSP still has problems with coloring

* Protocols: need to create and define a task

* NEEDS A TASK: 
  * split ownership analyzer into files within a subpackage.


## Abridged hand-wavy roadmap

* finish lambdas+ownership work
* Protocols (required for the new mem stuff and also I have operators for int and float ( +, +.) that are a pain)
* Intrinsic arrays, make [] work, arrays are an intrinsic type like structs and they can have a type parameter    
  but are not really HM polymoprhic - I have monomorphic arrays and functions and I want that to go away.
  * Get: let x1 = xs[1];
  * Set: xs[2] = 1;
* Enums
* modules (nested module, single compilation unit)
* multiple compilation units (as of today I can only compile a single file), 
  * abridged version, include mode. not parallel or concurrent.
* with multiple compilation units I can make the prelude a file itself instead of a bunch or programatically injected ast nodes that uses ffi to call the c libcmml (a bunch of c functions I link against).
  * and we can reimplement most of the c runtime in mml, afaiu.
* SimpSeq
...
