package mml.mmlclib.test.llvm

import munit.{Assertions, Location}

import scala.util.matching.Regex

/** Inspection assertions for the textual LLVM IR emitted by the compiler. */
trait LlvmAssertions extends Assertions:
  /** Find exactly one definition by its literal LLVM symbol name. */
  protected def functionBody(ir: String, name: String)(using Location): String =
    functionBodyMatching(ir, s"${Regex.quote(name)}\\([^\\n]*")

  /** Match the entire signature after `@`, including parameters and attributes. */
  protected def functionBodyMatching(ir: String, signaturePattern: String)(using Location): String =
    val definitions = "(?ms)^define [^\\n]*?@([^\\n]+?)\\s+\\{\\n(.*?)^\\}".r
    val matching = definitions
      .findAllMatchIn(ir.replace("\r\n", "\n"))
      .filter(m => signaturePattern.r.matches(m.group(1)))
      .toList
    matching match
      case List(definition) => definition.group(2).stripSuffix("\n")
      case Nil => fail(s"Missing function definition for $signaturePattern. IR:\n$ir")
      case _ => fail(s"Ambiguous function definition for $signaturePattern. IR:\n$ir")

  private case class Block(label: String, instructions: List[String]):
    def phis: List[String] = instructions.filter(_.matches("%\\S+\\s*=\\s*phi\\b.*"))

  private val label = "[A-Za-z0-9$._-]+"

  private def basicBlocks(body: String)(using Location): List[Block] =
    val blockLabel = s"($label):".r
    val lines      = body.linesIterator.map(_.takeWhile(_ != ';').trim).filter(_.nonEmpty)
    val reversed = lines.foldLeft(List.empty[Block]) { (blocks, line) =>
      line match
        case blockLabel(name) => Block(name, Nil) :: blocks
        case _ =>
          blocks match
            case block :: rest => block.copy(instructions = line :: block.instructions) :: rest
            case Nil => fail(s"Expected an explicit block label before: $line")
    }
    reversed.reverse.map(block => block.copy(instructions = block.instructions.reverse))

  private def successors(block: Block)(using Location): List[String] =
    val direct = s"br\\s+label\\s+%($label)(?:,\\s*!.*)?".r
    val conditional =
      s"br\\s+i1\\s+[^,]+,\\s*label\\s+%($label),\\s*label\\s+%($label)(?:,\\s*!.*)?".r
    block.instructions.lastOption match
      case Some(direct(target)) => List(target)
      case Some(conditional(ifTrue, ifFalse)) => List(ifTrue, ifFalse)
      case Some(instruction) if instruction.startsWith("ret ") || instruction == "unreachable" =>
        Nil
      case instruction =>
        fail(s"Unsupported or missing terminator in block ${block.label}: $instruction")

  /** Count phi instructions; expected counts belong to the individual regression. */
  protected def phiCount(body: String)(using Location): Int =
    basicBlocks(body).map(_.phis.size).sum

  /** Check incoming predecessor edges for each phi, preserving repeated edges.
    *
    * Supports explicit unquoted block labels and `br`, `ret`, and `unreachable` terminators.
    * Unsupported control flow fails the assertion. This is not a full LLVM verifier.
    */
  protected def assertPhiPredecessors(body: String)(using Location): Unit =
    val blocks = basicBlocks(body)
    val labels = blocks.map(_.label)
    assertEquals(labels.distinct.size, labels.size, s"Duplicate block labels:\n$body")
    val edges = blocks.flatMap { block =>
      successors(block).map { target =>
        assert(labels.contains(target), s"Unknown branch target $target in ${block.label}:\n$body")
        target -> block.label
      }
    }
    val incoming = s",\\s*%($label)\\s*\\]".r
    blocks.foreach { block =>
      val predecessors = edges.collect {
        case (target, from) if target == block.label => from
      }.sorted
      block.phis.foreach { phi =>
        val declared = incoming.findAllMatchIn(phi).map(_.group(1)).toList.sorted
        assert(declared.nonEmpty, s"Cannot read phi incoming blocks in ${block.label}: $phi")
        assertEquals(declared, predecessors, s"Phi predecessor mismatch in ${block.label}:\n$body")
      }
    }
