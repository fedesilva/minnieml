package mml.mmlclib.util

trait PipeDef {

  /** Pipe applies the function `f` returning something of type `R` to the piped thing (of type T).
   */
  implicit class Pipe[T](any: T) {
    def |>[R](f: T => R ): R = f(any)
  }

}
