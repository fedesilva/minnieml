package mml.mmlclib.ast

sealed trait AstNode

case class Module(name: String, members: List[Member]) extends AstNode

case class Comment(text: String) extends Member

// Represents different kinds of members within a module
sealed trait Member extends AstNode

// Represents expressions (can be expanded later)
sealed trait Expression extends AstNode

case class Binding(name: String, value: Expression) extends Member

sealed trait Literal extends Expression

case class LiteralInt(value: Int) extends Literal
case class LiteralString(value: String) extends Literal
case class LiteralBool(value: Boolean) extends Literal


// Represents type specifications
// TODO, expand this to include more complex types
case class TypeSpec(name: String) extends AstNode



