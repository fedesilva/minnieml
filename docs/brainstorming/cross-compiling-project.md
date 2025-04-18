# Cross-Compilation Support for MML

## Overview

MinnieML currently targets the host platform by default (macOS x86_64 or Linux x86_64).
With minor enhancements to the LLVM orchestrator,
we can allow explicitly specifying target platforms,
enabling cross-compilation for any platform supported by LLVM.

## Feasibility Assessment

The code already contains the foundation for supporting multiple targets:

- LLVM tools accept target specification via `-mtriple` and `-target` flags
- The runtime C code uses standard C and POSIX APIs with no platform-specific code
- Final binaries are already named with target information (`programName-targetTriple`)

## Implementation Plan

### 1. Target Triple Specification

Allow direct specification of LLVM target triples:

- Accept any valid LLVM target triple as input
- Common examples: "x86_64-apple-macosx", "aarch64-apple-macosx", "x86_64-pc-linux-gnu"
- Detect local system architecture and OS for default behavior when no triple is specified

### 2. Local Triple Detection

Add functionality to detect the current system's triple:

```scala
def getCurrentSystemTriple: String =
  // Detect OS (Linux, macOS)
  val os = System.getProperty("os.name").toLowerCase match
    case os if os.contains("mac") => "apple-macosx"
    case os if os.contains("linux") => "pc-linux-gnu"
    case other => throw new Exception(s"Unsupported OS: $other")

  // Detect architecture (x86_64, aarch64)
  val arch = System.getProperty("os.arch").toLowerCase match
    case "x86_64" => "x86_64"
    case "amd64" => "x86_64"
    case "aarch64" => "aarch64"
    case other => throw new Exception(s"Unsupported architecture: $other")

  s"$arch-$os"
```

### 3. Fixing Output Organization

To support multiple targets without conflicts, we need to adjust build artifact organization:

1. Create target-specific output directories:

   ```scala
   // Current structure
   val outputDir = s"$workingDirectory/out"

   // New structure - keep all outputs separate by target triple
   val outputDir = s"$workingDirectory/out/$targetTriple"
   ```

2. Add target information to the runtime object filename:

   ```scala
   // Current runtime object filename
   private val mmlRuntimeObjectFilename = "mml_runtime.o"

   // New runtime object filename with target triple
   private val mmlRuntimeObjectFilename = s"mml_runtime-$targetTriple.o"
   ```

3. Update runtime compilation to include the target triple:

   ```scala
   // Current compilation command
   val cmd = s"clang -c -std=c17 -O2 -fPIC -o $objPath $sourcePath"

   // Updated compilation command with target triple
   val cmd = s"clang -target $targetTriple -c -std=c17 -O2 -fPIC -o $objPath $sourcePath"
   ```

Note: The LlvmOrchestrator already handles target-specific naming for final artifacts:

- Binary executables: `$programName-$targetTriple`
- Library objects: `$programName-$targetTriple.o`

The orchestrator needs to be aware of the file names at all times, which I think is already
working because we pass File instances.

### 4. Update APIs and CLI

1. Add target parameter to relevant methods in `LlvmOrchestrator`
2. Add `--target` option to the CLI for bin/lib commands
3. Update the `CompilationPipeline` to pass target information through
4. Validate input triples and provide user-friendly messages for failures
5. Use the local system triple as default when no target is specified
