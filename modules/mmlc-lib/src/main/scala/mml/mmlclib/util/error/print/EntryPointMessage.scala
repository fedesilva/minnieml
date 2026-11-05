package mml.mmlclib.util.error.print

import mml.mmlclib.semantic.EntryPointError

/** Entry-point wording shared by terminal, editor, and plain API output. */
def entryPointMessage(error: EntryPointError): String = error match
  case EntryPointError.NotFound(_) =>
    "No entry point 'main' found for binary compilation"

  case EntryPointError.NotPublic(binding) =>
    s"Entry point '${binding.name}' must be declared 'pub'"

  case EntryPointError.InvalidReturnType(binding) =>
    s"Entry point '${binding.name}' must have a return type of 'Unit', 'Int32', or 'Int64'"
