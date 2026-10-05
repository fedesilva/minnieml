package mml.mmlclib.errors

import mml.mmlclib.ast.LinkEntry

enum CompilerWarning:
  case Generic(message: String)
  case DiscardedLinkDirective(entry: LinkEntry)
  case TailRecPatternUnsupported(functionName: String, reason: String)
