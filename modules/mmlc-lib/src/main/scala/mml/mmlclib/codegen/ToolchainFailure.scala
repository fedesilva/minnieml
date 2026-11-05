package mml.mmlclib.codegen

/** The operation requested from an external process. */
enum ToolOperation derives CanEqual:
  case Assemble, Optimize, Disassemble, EmitAssembly, CompileRuntime, CompileRuntimeBitcode
  case LinkRuntime, LinkExecutable, CompileLibrary, ProbeTarget, Invoke

enum FileFailure derives CanEqual:
  case MissingIr(path: String)
  case WriteIr(path: String, detail: String)

enum RuntimeFailure derives CanEqual:
  case ReadResource(path: String, detail: String)
  case ExtractResource(directory: String, detail: String)
  case CopyObject(source: String, destination: String, detail: String)

enum TripleFailure derives CanEqual:
  case ReadCache(path: String, detail: String)
  case EmptyOutput
  case Query(detail: String)

enum TargetFailure derives CanEqual:
  case UnknownHostArchitecture
  case NativeCpuArchitectureMismatch(target: String, host: String)
  case FindClang(detail: String)
  case InspectClang(executable: String, detail: String)
  case StartProbe(command: List[String], detail: String)
  case InvalidProbeOutput(output: String)
  case CacheAttributes(path: String, detail: String)
