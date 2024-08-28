package mml.mmlclib.api

import cats.effect.IO
import scala.annotation.targetName

extension [T](instance: T)

  @targetName("pipe")
  def |>[R](f: T => R): R = f(instance)

  @targetName("ioPipe")
  def |>[R](f: T => IO[R]): IO[R] = f(instance)
