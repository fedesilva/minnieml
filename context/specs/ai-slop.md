# Cleanup AI Slop 

Fucking AI agents (claude and codex) generated a bunch of ai slop.

## Unpack config in codegen

we are unpacking the config and passing a gazillion parameters
example:

```
private def compileNative(state: CompilerState): IO[CompilerState] =
    if !state.canEmitCode || state.llvmIr.isEmpty then IO.pure(state)
    else
      val irPath         = llvmIrPath(state)
      val outputDir      = state.config.outputDir.toString
      val verbose        = state.config.verbose
      val triple         = state.resolvedTriple
      val noStackCheck   = state.config.noStackCheck
      val mode           = state.config.mode
      val showTimings    = state.config.showTimings
      val emitOptIr      = state.config.emitOptIr
      val outputName     = state.config.outputName
      val explicitTriple = state.config.targetTriple
      val printPhases    = state.config.printPhases
      val optLevel       = state.config.optLevel
      val targetCpu      = resolveTargetCpu(state.config)

      val compileIo = selectCompileOperation(
        irPath,
        outputDir,
        mode,
        verbose,
        triple,
        noStackCheck,
        emitOptIr,
        showTimings,
        outputName,
        explicitTriple,
        printPhases,
        optLevel,
        targetCpu
      )
```

this is unacceptable, and happens all over the place.

you even convert the outputdir which is a path to string
to convert it later to a path again! brain dead work of an imbecile imbred moron.

