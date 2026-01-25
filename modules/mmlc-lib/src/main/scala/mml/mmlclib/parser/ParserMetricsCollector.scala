package mml.mmlclib.parser

import fastparse.internal.Instrument
import mml.mmlclib.compiler.Counter

import scala.collection.mutable

/** Collects parsing metrics via FastParse's Instrument callback API.
  *
  * Note: Uses mutable state because FastParse's Instrument trait has Unit-returning callbacks that
  * we don't control. The collector is short-lived (one parse call) and state never escapes.
  */
class ParserMetricsCollector(inputLength: Int) extends Instrument:

  private var _totalInvocations: Long = 0
  private var _backtrackEvents:  Long = 0
  private var _maxIndex:         Int  = 0

  private val _ruleFrequency = mutable.Map[String, Long]().withDefaultValue(0L)
  private val _ruleTiming    = mutable.Map[String, Long]().withDefaultValue(0L)
  // Stack entries: (name, startTime, childTimeAccumulator)
  private val _callStack = mutable.Stack[(String, Long, Long)]()

  override def beforeParse(name: String, index: Int): Unit =
    _totalInvocations += 1
    _ruleFrequency(name) += 1
    if index < _maxIndex then _backtrackEvents += 1
    else _maxIndex = index
    _callStack.push((name, System.nanoTime(), 0L))

  override def afterParse(name: String, index: Int, success: Boolean): Unit =
    if _callStack.nonEmpty then
      val (ruleName, startTime, childTime) = _callStack.pop()
      if ruleName == name then
        val totalDuration = System.nanoTime() - startTime
        val exclusiveTime = totalDuration - childTime
        _ruleTiming(name) += exclusiveTime
        // Add this rule's total duration to parent's child accumulator
        if _callStack.nonEmpty then
          val (pName, pStart, pChild) = _callStack.pop()
          _callStack.push((pName, pStart, pChild + totalDuration))

  def toCounters(stage: String): List[Counter] =
    val progressPct = if inputLength == 0 then 100L else (_maxIndex * 100L) / inputLength

    val summaryCounters = List(
      Counter(stage, "invocations", _totalInvocations),
      Counter(stage, "backtracks", _backtrackEvents),
      Counter(stage, "progress-pct", progressPct),
      Counter(stage, "unique-rules", _ruleFrequency.size.toLong)
    )

    val rulesByCount = _ruleFrequency.toList
      .sortBy(-_._2)
      .map { case (name, count) => Counter(stage, s"rule:$name", count) }

    val rulesByTime = _ruleTiming.toList
      .sortBy(-_._2)
      .map { case (name, nanos) => Counter(stage, s"time:$name", nanos) }

    summaryCounters ++ rulesByCount ++ rulesByTime
