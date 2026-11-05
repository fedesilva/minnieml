package mml.mmlclib.semantic

import mml.mmlclib.ast.{Bnd, SourceOrigin}

/** Failed executable entry-point requirements and the source evidence for each failure. */
enum EntryPointError:
  /** No main has accepted parameters; this also covers a declared main with unsupported parameters.
    */
  case NotFound(origin: SourceOrigin)
  case NotPublic(binding: Bnd)
  case InvalidReturnType(binding: Bnd)

  def source: SourceOrigin = this match
    case NotFound(origin) => origin
    case NotPublic(binding) => binding.source
    case InvalidReturnType(binding) => binding.source
