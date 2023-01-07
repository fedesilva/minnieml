package mml.mmlclib.api

extension [T](self: T)
  /** Takes a function that goes from the extended type T to a value of type R.
    *
    * In practical terms it applies the function to the extended instance `self`, resulting in a value of
    * type R.
    */
  def |>[R](f: T => R): R = f(self)
