import mml.mmlclib.api.*
import mml.mmlclib.util.yolo.*
import mml.mmlclib.util.*
import cats.syntax.all.*
import mml.mmlclib.semantic.*

val src =
  """
    let a = 2 * 2 + 1;
  """

def rewrite(src: String) =
  parseModule(src) match {
    case Some(module) =>
      println(s"Original module: \n${prettyPrintAst(module)} ")
      val member = module.members.head
      ExpressionBloomer.rewriteModule(module) match {
        case Right(mod) =>
          println(s"Rewritten module: \n${prettyPrintAst(mod)} ")
        case Left(err) =>
          println(s"Error: $err")
      }
    case None => println("Failed to parse module")
  }
