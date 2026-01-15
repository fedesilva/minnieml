# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* *Always read* `context/coding-rules.md` 
  before working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules in those documents faithfully.
* No exceptions

## Active Tasks

### Codegen improvements

Generate ir with this:

attributes #0 = { "target-cpu"="skylake" }
and attach the #0 to ALL function definitions, 
including forward declarations (native impl)

read the llvm-check-ok file and pull the 
`  Host CPU: skylake`

using the string on the right as target-cpu.

if we can't find it, do not generate.

* there is code that attempts to do this but the previous agent failed miserably.

### Lsp command errors fail to propagate

In VScode console:

```
Compiling to binary... file:///Users/f/Workshop/mine/mml/mml/mml/samples/no-main.mml
[Error] Header must provide a Content-Length property.
{"\n\u001b[31mcompilation failed":"\u001b[0m\n\n\u001b[33mFile: mml/samples/no-main.mml\n\n\u001b[31mNo entry point 'main' found for binary compilation at [1:1]-[4:2]\u001b[0m\n\n\u001b[36m   1 |\u001b[0m \n\u001b[36m   2 |\u001b[0m \u001b[31m\u001b[1mfn this_is_not_main() =\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   3 |\u001b[0m \u001b[31m\u001b[1m  println \"nopes\"\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   4 |\u001b[0m \u001b[31m\u001b[1m;\u001b[0m\n       \u001b[32m\u001b[1m^\u001b[0m\u001b[0m\n\nContent-Length: 66"}
[Error - 9:08:15 PM] Client MinnieML Language Server: connection to server is erroring.
Header must provide a Content-Length property.
{"\n\u001b[31mcompilation failed":"\u001b[0m\n\n\u001b[33mFile: mml/samples/no-main.mml\n\n\u001b[31mNo entry point 'main' found for binary compilation at [1:1]-[4:2]\u001b[0m\n\n\u001b[36m   1 |\u001b[0m \n\u001b[36m   2 |\u001b[0m \u001b[31m\u001b[1mfn this_is_not_main() =\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   3 |\u001b[0m \u001b[31m\u001b[1m  println \"nopes\"\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   4 |\u001b[0m \u001b[31m\u001b[1m;\u001b[0m\n       \u001b[32m\u001b[1m^\u001b[0m\u001b[0m\n\nContent-Length: 66"}
```

1. it should not use just the error printer.
2. it should return useful information the editor can display

---

## Recent Changes


