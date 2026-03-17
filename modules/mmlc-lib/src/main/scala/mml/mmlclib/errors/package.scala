package mml.mmlclib.errors

/** Marker trait for all compilation errors */
trait CompilationError:
  def message: String
