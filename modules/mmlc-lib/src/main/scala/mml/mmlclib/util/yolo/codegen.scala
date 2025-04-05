package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import mml.mmlclib.api.CodeGenApi

def codegen(input: String) =
  CodeGenApi
    .generateFromString(input)
    .value
    .unsafeRunSync() match
    case Right(code) => println(s"Generated code:\n$code")
    case Left(error) => println(s"CodeGen error:\n$error")
