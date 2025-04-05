package mml.mmlclib.util.pipe

import cats.syntax.all.*
import cats.{FlatMap, Monad}

extension [A](self: A)
  /** Basic pipe operator that simply applies a function */
  def |>[B](f: A => B): B = f(self)

extension [F[_]: FlatMap, A](self: F[A])
  /** Generalized pipe for any monadic type with flatMap */
  def |>[B](f: A => F[B]): F[B] = self.flatMap(f)

  /** Pipe with a context-ignoring continuation */
  def |>[B](fb: => F[B]): F[B] = self.flatMap(_ => fb)

extension [F[_]: Monad, E, A](self: F[Either[E, A]])
  /** Specialized pipe for nested monadic/Either contexts like IO[Either[E, A]] */
  def |>[B](f: A => F[Either[E, B]]): F[Either[E, B]] =
    self.flatMap {
      case Right(value) => f(value)
      case Left(error) => Monad[F].pure(Left(error))
    }

  /** Specialized pipe for nested monadic/Either contexts with a context-ignoring continuation */
  def |>[B](f: => F[Either[E, B]]): F[Either[E, B]] =
    self.flatMap {
      case Right(_) => f
      case Left(error) => Monad[F].pure(Left(error))
    }
