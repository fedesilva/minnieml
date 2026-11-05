package mml.mmlclib.util.error.print

import mml.mmlclib.errors.CompilerWarning

/** Shared wording for compiler warnings. */
def warningMessage(warning: CompilerWarning): String = warning match
  case CompilerWarning.DiscardedLinkDirective(_) =>
    "linking directives are discarded in library mode"
  case CompilerWarning.TailRecPatternUnsupported(binding) =>
    s"Function '${binding.name}': tail recursion pattern not recognized, falling back to standard recursion"
