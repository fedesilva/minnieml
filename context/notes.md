# Author Notes

The following are notes that need to be turned into tasks 
or the Author wants to keep track of.

Do not read or act on them unless directed to do so explicitely.


## Notes


* LSP is broken, paps are displayed as $environment something
* LSP still has problems with coloring

* Intrinsic Arrays: check if we have a coherent task

* Protocols: need to create and define a task

* the parser improvements need to be turned into a task and scheduled soon. the improvement in error reporting will make writing mml code enjoyable again.


## Abridged hand-wavy roadmap

* finish lambdas+ownership work
* introduce protocols (required for the new mem stuff and also I have operators for int and float ( +, +.) that are a pain)
* detour: Intrinsic arrays, make [] work, arrays are an intrinsic type like structs and they can have a type parameter but are not really HM polymoprhic - I have monomorphic arrays and functions and I want that to go away.
* modules (nested module, single compilation unit)
* multiple compilation units (as of today I can only compile a single file), multithreaded pipeline (where we can par we par, if we need to join and then par again we do it)
* with multiple compilation units I can make the prelude a file itself instead of a bunch or programatically injected ast that uses ffi to call the c libcmml (a bunch of c functions I link against).
* add a pragma so a top level module can declare a native .o the linker needs to link to, so native declarations (which can already be compiled and work) and the code that uses them can link to the relevant binary.
* SimpSeq
...
