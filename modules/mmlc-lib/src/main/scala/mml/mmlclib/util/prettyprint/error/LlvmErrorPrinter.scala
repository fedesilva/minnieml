package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.codegen.LlvmCompilationError

/** Pretty printer for LLVM compilation errors */
object LlvmErrorPrinter:

  /** Pretty print a list of LLVM compilation errors
    *
    * @param errors
    *   List of LLVM compilation errors
    * @return
    *   A human-readable error message
    */
  def prettyPrint(errors: List[LlvmCompilationError]): String =
    if errors.isEmpty then "No LLVM compilation errors"
    else
      val errorMessages = errors.map(prettyPrintSingle)
      s"LLVM compilation errors:\n${errorMessages.mkString("\n")}"

  /** Pretty print a single LLVM compilation error
    *
    * @param error
    *   The LLVM compilation error to pretty print
    * @return
    *   A human-readable error message
    */
  private def prettyPrintSingle(error: LlvmCompilationError): String = error match
    case LlvmCompilationError.TemporaryFileCreationError(message) =>
      s"Failed to create temporary file: $message"
    case LlvmCompilationError.UnsupportedOperatingSystem(osName) =>
      s"Unsupported operating system: $osName"
    case LlvmCompilationError.UnsupportedArchitecture(archName) =>
      s"Unsupported architecture: $archName"
    case LlvmCompilationError.CommandExecutionError(command, errorMessage, exitCode) =>
      s"Command execution failed (exit code $exitCode): $command\n$errorMessage"
    case LlvmCompilationError.ExecutableRunError(path, exitCode) =>
      s"Executable run failed (exit code $exitCode): $path"
    case LlvmCompilationError.LlvmNotInstalled(missingTools) =>
      s"LLVM tools not installed: ${missingTools.mkString(", ")}"
    case LlvmCompilationError.RuntimeResourceError(message) =>
      s"MML runtime resource error: $message"
