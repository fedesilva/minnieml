package mml.mmlclib.util.error.print

import mml.mmlclib.ast.{ParseTermError, Term}

/** CLI term labels are independent of the parse-error AST class name. */
private[print] def diagnosticTermName(term: Term): String = term match
  case _: ParseTermError => "TermError"
  case other => other.getClass.getSimpleName
