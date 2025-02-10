package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import cats.syntax.option.*
import mml.mmlclib.api.CodeGenApi

def codegen(input: String) =
  CodeGenApi
    .generateFromString(input)
    .unsafeRunSync() match
    case Right(code) => println(s"Generated code:\n$code")
    case Left(error) => println(s"CodeGen error:\n$error")
