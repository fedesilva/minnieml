package mml.mmlclib.util.parser

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.{ErrorNode, ParseTreeListener, TerminalNode}

class StdOutParseListener extends ParseTreeListener {
  
  override def visitTerminal(node: TerminalNode): Unit = {}
  
  override def visitErrorNode(node: ErrorNode): Unit = {
    println(node.getPayload)
    println(node.getText)
  }
  
  override def enterEveryRule(ctx: ParserRuleContext): Unit = {}
  
  override def exitEveryRule(ctx: ParserRuleContext): Unit = {}
  
}
