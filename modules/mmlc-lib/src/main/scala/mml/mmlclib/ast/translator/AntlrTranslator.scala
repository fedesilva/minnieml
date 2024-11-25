package mml.mmlclib.ast.translator

import mml.mmlclib.api.*
import mml.mmlclib.ast.*
import mml.mmlclib.parser.antlr.MinnieMLParser
import org.antlr.v4.runtime.tree.{ParseTree, TerminalNode}
import org.antlr.v4.runtime.ParserRuleContext
import cats.Monad
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*

object AntlrTranslator:
  given moduleTranslator[F[_]: Monad](using api: AstApi[F]): AstTranslator[F, ParseTree] with
    def translate(input: ParseTree): F[AstNode] =
      for
        nodes <- walkTree(input)
        node <- nodes.headOption match
          case Some(n) => n.pure[F]
          case None => api.createComment("Empty node").widen
      yield node

  def walkTree[F[_]: Monad](tree: ParseTree)(using api: AstApi[F]): F[List[AstNode]] =
    tree match
      case ctx:      MinnieMLParser.ModuleContext => translateModule(ctx).map(List(_))
      case ctx:      MinnieMLParser.LetBndContext => translateLetBinding(ctx)
      case ctx:      MinnieMLParser.BndContext => translateBinding(ctx).map(List(_))
      case ctx:      MinnieMLParser.IdContext => translateId(ctx).map(List(_))
      case ctx:      MinnieMLParser.LitContext => translateLiteral(ctx).map(List(_))
      case ctx:      ParserRuleContext => walkChildren(ctx)
      case terminal: TerminalNode => api.createLiteralString(terminal.getText).map(List(_))

  def walkChildren[F[_]: Monad](ctx: ParserRuleContext)(using api: AstApi[F]): F[List[AstNode]] =
    ctx.children.asScala.toList.traverse(walkTree[F]).map(_.flatten)

  def translateModule[F[_]: Monad](ctx: MinnieMLParser.ModuleContext)(using
    api: AstApi[F]
  ): F[AstNode] =
    val name = Option(ctx.moduleId()).map(_.getText).getOrElse("unnamed")
    println(s"Translating module $name")
    for
      children <- ctx.member().asScala.toList.flatTraverse(walkTree[F])
      members = children.collect { case m: Member => m }
      module <- api.createModule(name, members).widen
    yield module

  def translateLetBinding[F[_]: Monad](ctx: MinnieMLParser.LetBndContext)(using
    api: AstApi[F]
  ): F[List[AstNode]] =
    ctx.bnd().asScala.toList.traverse(translateBinding[F])

  def translateBinding[F[_]: Monad](
    ctx:       MinnieMLParser.BndContext
  )(using api: AstApi[F]): F[AstNode] =
    for
      name <- translateId(ctx.idMWT().id())
      value <- translateExpression(ctx.exp())
      binding <- api
        .createBinding(name.asInstanceOf[LiteralString].value, value.asInstanceOf[Expression])
        .widen
    yield binding

  def translateId[F[_]: Monad](ctx: MinnieMLParser.IdContext)(using api: AstApi[F]): F[AstNode] =
    api.createLiteralString(ctx.getText).widen

  def translateExpression[F[_]: Monad](ctx: MinnieMLParser.ExpContext)(using
    api: AstApi[F]
  ): F[AstNode] =
    ctx match
      case flatExp: MinnieMLParser.FlatExpLContext => translateFlatExp(flatExp.flatExp())
      case _ => api.createLiteralString("Unimplemented expression").widen

  def translateFlatExp[F[_]: Monad](ctx: MinnieMLParser.FlatExpContext)(using
    api: AstApi[F]
  ): F[AstNode] =
    // FIXME: WRONG lit is a list of literals
    ctx.lit().asScala.headOption match
      case Some(lit) => translateLiteral(lit)
      case None =>
        ctx.id().asScala.headOption match
          case Some(id) => translateId(id)
          case None => api.createLiteralString("Unimplemented flat expression").widen

  def translateLiteral[F[_]: Monad](ctx: MinnieMLParser.LitContext)(using
    api: AstApi[F]
  ): F[AstNode] =
    Option(ctx.litInt()) match
      case Some(litInt) => api.createLiteralInt(litInt.getText.toInt).widen
      case None =>
        Option(ctx.litStr()) match
          case Some(litStr) =>
            api.createLiteralString(litStr.getText.stripPrefix("\"").stripSuffix("\"")).widen
          case None =>
            Option(ctx.litBoolean()) match
              case Some(litBool) => api.createLiteralBool(litBool.getText.toBoolean).widen
              case None => api.createLiteralString(s"Unimplemented literal: ${ctx.getText}").widen
