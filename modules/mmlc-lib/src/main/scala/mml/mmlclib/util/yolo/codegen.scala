package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.compiler.CodegenStage

def codegen(input: String): Unit =
  FrontEndApi
    .compile(input, "Anon")
    .value
    .unsafeRunSync() match
    case Right(state) =>
      if state.errors.nonEmpty then println(s"Compilation failed:\n${state.errors}")
      else
        CodegenStage.emitIrOnly(state).unsafeRunSync() match
          case finalState =>
            finalState.llvmIr match
              case Some(ir) => println(s"Generated code:\n$ir")
              case None => println(s"CodeGen error:\n${finalState.errors}")
    case Left(error) => println(s"Compilation error:\n$error")
