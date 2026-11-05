package mml.mmlclib.util.error.print

import mml.mmlclib.ast.{ClosureEnvironmentError, ClosureEnvironmentReason}

/** Shared wording for invalid closure environment associations. */
def closureEnvironmentMessage(error: ClosureEnvironmentError): String = error.reason match
  case ClosureEnvironmentReason.MissingIdentity =>
    "Capturing lambda missing environment layout identity"
  case ClosureEnvironmentReason.MissingLayout(id) => s"Missing closure environment layout: $id"
  case ClosureEnvironmentReason.ConflictingMetadata(id) =>
    s"Conflicting closure environment metadata: $id"
  case ClosureEnvironmentReason.CaptureCountMismatch(id, _, _) =>
    s"Closure environment capture count mismatch: $id"
