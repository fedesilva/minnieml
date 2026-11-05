package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.test.BaseEffFunSuite

import java.nio.file.Files

class ToolchainPathTests extends BaseEffFunSuite:

  for mode <- List(CompilationMode.Exe, CompilationMode.Library) do
    test(s"$mode preserves spaced build and output paths"):
      withSourceFixture("pub fn main(): Int = 37;;", "PathFixture.mml") { (path, initialConfig) =>
        val directory = path.getParent
        val binary    = directory.resolve("output space/program file")
        val output =
          if mode == CompilationMode.Library then directory.resolve("output space/module file.o")
          else binary
        val config = initialConfig.copy(
          mode       = mode,
          outputDir  = directory.resolve("build space"),
          outputName = output.toString.some,
          emitOptIr  = true
        )

        for
          compiled <- CompilerApi.compileNativeQuiet(path, config)
          state  = compiled.fold(error => fail(error), identity)
          _      = assertEquals(state.errors, Vector.empty)
          triple = state.resolvedTriple.getOrElse(fail("Missing native target triple"))
          optimizedIr = config.outputDir
            .resolve("out")
            .resolve(triple)
            .resolve(s"${state.module.name}_opt.ll")
          _ <- commandNotFailed(
            List("llvm-as", optimizedIr.toString, "-o", directory.resolve("checked.bc").toString),
            directory
          )
          _ <-
            if mode == CompilationMode.Library then
              val target = state.clangTarget.getOrElse(fail("Missing native target"))
              val driver = directory.resolve("driver.c")
              val runtime = config.outputDir
                .resolve("target")
                .resolve(s"mml_runtime-$triple.o")

              IO.blocking {
                Files.writeString(
                  driver,
                  "extern int pathfixture_main(void); " +
                    "int main(void) { return pathfixture_main(); }\n"
                )
              } *> commandNotFailed(
                List(target.executable.toString, "-fuse-ld=lld") ++ target.flags ++ List(
                  driver.toString,
                  output.toString,
                  runtime.toString,
                  "-lm",
                  "-o",
                  binary.toString
                ),
                directory
              )
            else IO.unit
          _ <- programExits(List(binary.toString), directory, expectedCode = 37)
        yield ()
      }
