package mml.mmlclib.interpreter

import mml.mmlclib.ast.*
import scala.collection.mutable

/** Represents a runtime value in our interpreter */
enum Value derives CanEqual:
  case IntV(value: Int)
  case FloatV(value: Float)
  case StringV(value: String)
  case BoolV(value: Boolean)
  case UnitV
  case FunctionV(params: List[String], body: Expr, closure: Environment)
  case NativeFunctionV(name: String, fn: List[Value] => Value)

/** Environment for storing variable bindings */
class Environment(val parent: Option[Environment] = None):
  private val bindings = mutable.Map[String, Value]()

  def define(name: String, value: Value): Unit = bindings(name) = value
  def get(name: String): Option[Value] = bindings.get(name).orElse(parent.flatMap(_.get(name)))

/** MML Interpreter
 * This is both rudimentary and incomplete, but it should be enough to get us started.
 * It is very mutable and probably not thread-safe, but that's fine for now.
 */
class Interpreter:
  private var globalEnv = new Environment()

  // Initialize built-in functions and operators
  locally:
    def defineOp(name: String, op: (Value, Value) => Value) =
      globalEnv.define(name, Value.NativeFunctionV(name, args =>
        if args.length != 2 then throw InterpretError(s"Operator $name requires exactly 2 arguments")
        op(args(0), args(1))
      ))

    // Binary arithmetic operators
    def numericOp(name: String, intOp: (Int, Int) => Int, floatOp: (Float, Float) => Float) =
      defineOp(name, (a, b) => (a, b) match
        case (Value.IntV(x), Value.IntV(y)) => Value.IntV(intOp(x, y))
        case (Value.FloatV(x), Value.FloatV(y)) => Value.FloatV(floatOp(x, y))
        case (Value.IntV(x), Value.FloatV(y)) => Value.FloatV(floatOp(x.toFloat, y))
        case (Value.FloatV(x), Value.IntV(y)) => Value.FloatV(floatOp(x, y.toFloat))
        case _ => throw InterpretError(s"Invalid types for operator $name: $a, $b")
      )

    // Define basic arithmetic operators
    numericOp("+", _ + _, _ + _)
    numericOp("-", _ - _, _ - _)
    numericOp("*", _ * _, _ * _)
    numericOp("/", _ / _, _ / _)

    // Print functions
    globalEnv.define("print", Value.NativeFunctionV("print", args =>
      args.foreach(v => print(valueToString(v)))
      Value.UnitV
    ))
    globalEnv.define("println", Value.NativeFunctionV("println", args =>
      args.foreach(v => print(valueToString(v)))
      println()
      Value.UnitV
    ))

  /** Interpret a module by finding and executing a specific function */
  def interpret(module: Module, entryPoint: String = "main"): Value =
    // First pass: declare all module members to handle forward references
    for member <- module.members do
      declareMember(member, globalEnv)

    // Second pass: evaluate all module members
    for member <- module.members do
      interpretNode(member, globalEnv)

    // Find and execute the requested function
    globalEnv.get(entryPoint) match
      case Some(Value.FunctionV(params, body, closure)) =>
        if params.nonEmpty then throw InterpretError(s"Function $entryPoint must take no parameters")
        interpretNode(body, closure)
      case Some(_) => throw InterpretError(s"$entryPoint is not a function")
      case None => throw InterpretError(s"Function $entryPoint not found in module ${module.name}")

  private def declareMember(member: Member, env: Environment): Unit = member match
    case fn: FnDef =>
      env.define(fn.name, Value.FunctionV(fn.params.map(_.name), fn.body, env))
    case bnd: Bnd =>
      env.define(bnd.name, Value.UnitV) // Will be properly set in second pass
    case _ => ()

  private def interpretNode(node: AstNode, env: Environment): Value = node match
    case Module(_, _, _, members, _, _) =>
      var result = Value.UnitV
      for member <- members do
        result = interpretNode(member, env)
      result

    case bnd: Bnd =>
      val value = interpretNode(bnd.value, env)
      env.define(bnd.name, value)
      value

    case fn: FnDef =>
      env.get(fn.name).getOrElse(
        throw InterpretError(s"Internal error: function ${fn.name} not found during interpretation")
      )

    case expr: Expr =>
      interpretExpr(expr, env)

    case Cond(_, cond, ifTrue, ifFalse, _, _) =>
      interpretNode(cond, env) match
        case Value.BoolV(true) => interpretNode(ifTrue, env)
        case Value.BoolV(false) => interpretNode(ifFalse, env)
        case other => throw InterpretError(s"Condition must be boolean, got: $other")

    case ref: Ref =>
      env.get(ref.name) match
        case Some(value) => value
        case None => throw InterpretError(s"Undefined reference: ${ref.name}")

    case LiteralInt(_, value) => Value.IntV(value)
    case LiteralFloat(_, value) => Value.FloatV(value)
    case LiteralString(_, value) => Value.StringV(value)
    case LiteralBool(_, value) => Value.BoolV(value)
    case LiteralUnit(_) => Value.UnitV

    case other => throw InterpretError(s"Unsupported AST node: $other")

  private def interpretExpr(expr: Expr, env: Environment): Value =
    expr.terms match
      case List(term) =>
        interpretNode(term, env)

      // Handle infix operator expressions
      case left :: Ref(_, opName, _, _) :: right :: Nil =>
        env.get(opName) match
          case Some(Value.NativeFunctionV(_, op)) =>
            val leftVal = interpretNode(left, env)
            val rightVal = interpretNode(right, env)
            op(List(leftVal, rightVal))
          case Some(_) =>
            throw InterpretError(s"$opName is not an operator")
          case None =>
            throw InterpretError(s"Undefined operator: $opName")

      // Handle function application
      case fn :: args =>
        // First check if args contain any operators and evaluate those first
        val evaluatedArgs = args match
          case List(left, op: Ref, right) =>
            env.get(op.name) match
              case Some(Value.NativeFunctionV(_, opFn)) =>
                List(opFn(List(interpretNode(left, env), interpretNode(right, env))))
              case _ => args.map(interpretNode(_, env))
          case _ => args.map(interpretNode(_, env))

        val function = interpretNode(fn, env)
        function match
          case Value.FunctionV(params, body, closure) =>
            if params.length != evaluatedArgs.length then
              throw InterpretError(s"Wrong number of arguments: expected ${params.length}, got ${evaluatedArgs.length}")
            val fnEnv = new Environment(Some(closure))
            params.zip(evaluatedArgs).foreach((name, value) => fnEnv.define(name, value))
            interpretNode(body, fnEnv)
          case Value.NativeFunctionV(_, fn) =>
            fn(evaluatedArgs)
          case other =>
            throw InterpretError(s"Cannot call non-function value: $other")

      case Nil =>
        throw InterpretError("Empty expression")

  private def valueToString(value: Value): String = value match
    case Value.IntV(v) => v.toString
    case Value.FloatV(v) => v.toString
    case Value.StringV(v) => v
    case Value.BoolV(v) => v.toString
    case Value.UnitV => "()"
    case Value.FunctionV(_, _, _) => "<function>"
    case Value.NativeFunctionV(name, _) => s"<native function: $name>"

case class InterpretError(message: String) extends RuntimeException(message)
