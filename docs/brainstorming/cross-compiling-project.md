# Cross-Compilation Support for MML

## Overview

MinnieML currently targets the host platform by default (macOS x86_64 or Linux x86_64). With minor enhancements to the LLVM orchestrator, we can allow explicitly specifying target platforms, enabling cross-compilation for Darwin x86_64, Darwin aarch64, and Linux x86_64.

## Feasibility Assessment

The code already contains the foundation for supporting multiple targets:

- LLVM tools accept target specification via `-mtriple` and `-target` flags
- The runtime C code uses standard C and POSIX APIs with no platform-specific code
- Final binaries are already named with target information (`programName-targetTriple`)

## Implementation Plan

### 1. Create a Target Type

```scala
enum Target derives CanEqual:
  case DarwinX86     // x86_64-apple-macosx
  case DarwinAarch64 // aarch64-apple-macosx
  case LinuxX86      // x86_64-pc-linux-gnu

  def toTriple: String = this match
    case DarwinX86     => "x86_64-apple-macosx"
    case DarwinAarch64 => "aarch64-apple-macosx"
    case LinuxX86      => "x86_64-pc-linux-gnu"
```

### 2. Fixing Output Organization

To support multiple targets without conflicts, we need to adjust build artifact organization:

1. Create target-specific output directories:

   ```scala
   val outputDir = s"$workingDirectory/out/$targetTriple"
   ```

2. Add target information to the runtime object filename:

   ```scala
   val runtimeObjectFilename = s"mml_runtime-$targetTriple.o"
   ```

3. Use the target when compiling the runtime:
   ```scala
   val cmd = s"clang -target $targetTriple -c -std=c11 -O2 -fPIC -o $objPath $sourcePath"
   ```

### 3. Update APIs and CLI

1. Add target parameter to relevant methods in `LlvmOrchestrator`
2. Add `--target` option to the CLI for bin/lib commands
3. Update the `CompilationPipeline` to pass target information through
4. The input should be validated and user friendly messages emitted for failures.

## Benefits

- Cross-compile MML programs for different architectures from a single host
- Enable CI/CD for multiple targets even with limited hardware
- As long as we can run graal vm, graal's native image, and some other tools, we can build the compiler.

## Testing Strategy

- Verify build artifacts are created in the correct target-specific directories
- Ensure runtime objects include target information
- Test the resulting binaries on target platforms where possible
