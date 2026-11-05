package mml.mmlclib.util.error.print

import mml.mmlclib.codegen.*

/** Shared wording for external-tool failures; external output remains opaque data. */
def toolchainMessage(error: LlvmCompilationError): String = error match
  case LlvmCompilationError.UnsupportedOperatingSystem(name) =>
    s"Unsupported operating system: $name"
  case LlvmCompilationError.UnsupportedArchitecture(name) => s"Unsupported architecture: $name"
  case LlvmCompilationError.CommandExecutionError(command, operation, stderr, exitCode) =>
    val context = toolOperationMessage(operation)
    val detail  = List(context, stderr).filter(_.nonEmpty).mkString("\n")
    s"Command '${command.mkString(" ")}' failed (exit $exitCode): $detail"
  case LlvmCompilationError.ProcessStartError(command, detail) =>
    s"Could not start command '${command.mkString(" ")}': $detail"
  case LlvmCompilationError.ToolNotFound(tool, _) => s"Tool not found: $tool"
  case LlvmCompilationError.ExecutableRunError(path, exitCode) =>
    s"Executable '$path' failed with exit code $exitCode"
  case LlvmCompilationError.LlvmNotInstalled(tools) =>
    s"LLVM tools not installed: ${tools.mkString(", ")}"
  case LlvmCompilationError.TemporaryFileCreationError(reason) =>
    reason match
      case FileFailure.MissingIr(path) => s"LLVM IR file not found: $path"
      case FileFailure.WriteIr(_, detail) => s"Error writing LLVM IR to file: $detail"
  case LlvmCompilationError.RuntimeResourceError(reason) =>
    reason match
      case RuntimeFailure.ReadResource(_, detail) => detail
      case RuntimeFailure.ExtractResource(_, detail) => detail
      case RuntimeFailure.CopyObject(_, _, detail) =>
        s"Failed to copy runtime object to target: $detail"
  case LlvmCompilationError.TripleResolutionError(reason) =>
    reason match
      case TripleFailure.ReadCache(_, detail) => s"Failed to read cached triple: $detail"
      case TripleFailure.EmptyOutput => "clang -print-target-triple returned empty output"
      case TripleFailure.Query(detail) => s"Failed to query clang for target triple: $detail"
  case LlvmCompilationError.TargetResolutionError(reason) =>
    reason match
      case TargetFailure.UnknownHostArchitecture =>
        "Cannot determine host architecture for --cpu native"
      case TargetFailure.NativeCpuArchitectureMismatch(target, host) =>
        s"--cpu native requires the host architecture ($host); select an explicit CPU for $target"
      case TargetFailure.FindClang(detail) => detail
      case TargetFailure.InspectClang(_, detail) => detail
      case TargetFailure.StartProbe(_, detail) => detail
      case TargetFailure.InvalidProbeOutput(_) => "Clang returned no valid probe attributes"
      case TargetFailure.CacheAttributes(_, detail) => s"Cannot cache target attributes: $detail"

private def toolOperationMessage(operation: ToolOperation): String = operation match
  case ToolOperation.Assemble => "Failed to convert IR to Bitcode"
  case ToolOperation.Optimize => "Failed to optimize bitcode"
  case ToolOperation.Disassemble => "Failed to generate optimized IR"
  case ToolOperation.EmitAssembly => "Failed to convert Bitcode to Assembly"
  case ToolOperation.CompileRuntime => "Failed to compile MML runtime"
  case ToolOperation.CompileRuntimeBitcode => "Failed to compile MML runtime bitcode"
  case ToolOperation.LinkRuntime => "Failed to link runtime bitcode"
  case ToolOperation.LinkExecutable => "Failed to compile and link"
  case ToolOperation.CompileLibrary => "Failed to compile library object"
  case ToolOperation.ProbeTarget | ToolOperation.Invoke => ""
