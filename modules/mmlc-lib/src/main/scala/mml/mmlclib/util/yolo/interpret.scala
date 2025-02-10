package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import cats.syntax.option.*
import mml.mmlclib.api.InterpreterApi

def interpret(input: String) =
  InterpreterApi
    .interpretModuleString(input)
    .unsafeRunSync()
