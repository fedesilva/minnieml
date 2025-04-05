package mml.mmlclib.api

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.CompilerEffect
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.*

enum CompilerError:
  case SemanticErrors(errors: List[SemanticError])
  case ParserErrors(errors: List[ParserError])
  case Unknown(msg: String)

object CompilerApi:
  /** Compile a string source into a Module
    *
    * This function parses and semantically analyzes the source into a Module. All errors are
    * captured in an EitherT.
    *
    * @param source
    *   the source code to compile
    * @param name
    *   an optional name for the module
    * @return
    *   a CompilerEffect that, when run, yields either a CompilerError or a Module
    */
  def compileString(
    source: String,
    name:   Option[String] = None
  ): CompilerEffect[Module] =
    for
      // Use leftMap to convert ParserError to CompilerError
      parsedModule <- ParserApi
        .parseModuleString(source, name)
        .leftMap(error => CompilerError.ParserErrors(List(error)))
      // SemanticApi already returns CompilerEffect[Module]
      module <- SemanticApi.rewriteModule(parsedModule)
    yield module
