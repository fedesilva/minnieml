package mml.mmlclib.errors

enum CompilerWarning:
  case Generic(message: String)
  case TailRecPatternUnsupported(functionName: String, reason: String)
