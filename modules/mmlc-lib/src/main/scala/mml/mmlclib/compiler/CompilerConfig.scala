package mml.mmlclib.compiler

import mml.mmlclib.codegen.CompilationMode

import java.nio.file.{Path, Paths}

case class CompilerConfig(
  mode:            CompilationMode,
  outputDir:       Path,
  verbose:         Boolean,
  targetTriple:    Option[String],
  targetCpu:       Option[String],
  noStackCheck:    Boolean,
  emitOptIr:       Boolean,
  noTco:           Boolean,
  showTimings:     Boolean,
  outputAst:       Boolean,
  outputName:      Option[String],
  printPhases:     Boolean,
  optLevel:        Int,
  emitScopedAlias: Boolean,
  asan:            Boolean
)

object CompilerConfig:

  val default: CompilerConfig =
    CompilerConfig(
      mode            = CompilationMode.Library,
      outputDir       = Paths.get("build"),
      verbose         = false,
      targetTriple    = None,
      targetCpu       = None,
      noStackCheck    = false,
      emitOptIr       = false,
      noTco           = false,
      showTimings     = false,
      outputAst       = false,
      outputName      = None,
      printPhases     = false,
      optLevel        = 3,
      emitScopedAlias = false,
      asan            = false
    )

  def exe(
    outputDir:       String,
    verbose:         Boolean        = false,
    targetTriple:    Option[String] = None,
    targetCpu:       Option[String] = None,
    noStackCheck:    Boolean        = false,
    emitOptIr:       Boolean        = false,
    noTco:           Boolean        = false,
    showTimings:     Boolean        = false,
    outputAst:       Boolean        = false,
    outputName:      Option[String] = None,
    printPhases:     Boolean        = false,
    optLevel:        Int            = 3,
    emitScopedAlias: Boolean        = false,
    asan:            Boolean        = false
  ): CompilerConfig =
    CompilerConfig(
      mode            = CompilationMode.Exe,
      outputDir       = Paths.get(outputDir),
      verbose         = verbose,
      targetTriple    = targetTriple,
      targetCpu       = targetCpu,
      noStackCheck    = noStackCheck,
      emitOptIr       = emitOptIr,
      noTco           = noTco,
      showTimings     = showTimings,
      outputAst       = outputAst,
      outputName      = outputName,
      printPhases     = printPhases,
      optLevel        = optLevel,
      emitScopedAlias = emitScopedAlias,
      asan            = asan
    )

  def library(
    outputDir:       String,
    verbose:         Boolean        = false,
    targetTriple:    Option[String] = None,
    targetCpu:       Option[String] = None,
    noStackCheck:    Boolean        = false,
    emitOptIr:       Boolean        = false,
    noTco:           Boolean        = false,
    showTimings:     Boolean        = false,
    outputAst:       Boolean        = false,
    outputName:      Option[String] = None,
    printPhases:     Boolean        = false,
    optLevel:        Int            = 3,
    emitScopedAlias: Boolean        = false,
    asan:            Boolean        = false
  ): CompilerConfig =
    CompilerConfig(
      mode            = CompilationMode.Library,
      outputDir       = Paths.get(outputDir),
      verbose         = verbose,
      targetTriple    = targetTriple,
      targetCpu       = targetCpu,
      noStackCheck    = noStackCheck,
      emitOptIr       = emitOptIr,
      noTco           = noTco,
      showTimings     = showTimings,
      outputAst       = outputAst,
      outputName      = outputName,
      printPhases     = printPhases,
      optLevel        = optLevel,
      emitScopedAlias = emitScopedAlias,
      asan            = asan
    )

  def ast(
    outputDir:   String,
    verbose:     Boolean = false,
    showTimings: Boolean = false,
    noTco:       Boolean = false
  ): CompilerConfig =
    CompilerConfig(
      mode            = CompilationMode.Ast,
      outputDir       = Paths.get(outputDir),
      verbose         = verbose,
      targetTriple    = None,
      targetCpu       = None,
      noStackCheck    = false,
      emitOptIr       = false,
      noTco           = noTco,
      showTimings     = showTimings,
      outputAst       = false,
      outputName      = None,
      printPhases     = false,
      optLevel        = 0,
      emitScopedAlias = false,
      asan            = false
    )

  def ir(
    outputDir:   String,
    verbose:     Boolean = false,
    showTimings: Boolean = false,
    outputAst:   Boolean = false,
    noTco:       Boolean = false
  ): CompilerConfig =
    CompilerConfig(
      mode            = CompilationMode.Ir,
      outputDir       = Paths.get(outputDir),
      verbose         = verbose,
      targetTriple    = None,
      targetCpu       = None,
      noStackCheck    = false,
      emitOptIr       = false,
      noTco           = noTco,
      showTimings     = showTimings,
      outputAst       = outputAst,
      outputName      = None,
      printPhases     = false,
      optLevel        = 0,
      emitScopedAlias = false,
      asan            = false
    )

  def dev(
    outputDir: String  = "build",
    verbose:   Boolean = false
  ): CompilerConfig =
    CompilerConfig(
      mode            = CompilationMode.Dev,
      outputDir       = Paths.get(outputDir),
      verbose         = verbose,
      targetTriple    = None,
      targetCpu       = None,
      noStackCheck    = false,
      emitOptIr       = false,
      noTco           = false,
      showTimings     = false,
      outputAst       = false,
      outputName      = None,
      printPhases     = false,
      optLevel        = 0,
      emitScopedAlias = false,
      asan            = false
    )
