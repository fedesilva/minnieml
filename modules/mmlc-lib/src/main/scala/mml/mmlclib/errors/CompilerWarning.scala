package mml.mmlclib.errors

import mml.mmlclib.ast.{Bnd, LinkEntry}

enum CompilerWarning:
  case DiscardedLinkDirective(entry: LinkEntry)
  case TailRecPatternUnsupported(binding: Bnd)
