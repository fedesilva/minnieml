# Specification: Fastparse External Metrics Instrumentation

## 1. Objective
To capture granular parsing metrics—including backtracking frequency, rule invocation counts, and input coverage—without modifying the source code of existing Fastparse parser definitions.

## 2. Technical Approach
The implementation leverages the `fastparse.internal.Instrument` trait. By manually instantiating a `fastparse.ParsingRun` and injecting a custom instrument, we intercept the parser's execution lifecycle at the call-site.

## 3. Metrics Definitions

| Metric | Calculation Logic |
| :--- | :--- |
| **Backtrack Count** | Incremented when `beforeParse` receives an `index` lower than the highest `index` previously recorded. |
| **Invocations** | A simple counter incremented every time `beforeParse` is triggered. |
| **Progress Ratio** | The ratio of the maximum `index` reached to the total `input.length`. |
| **Rule Heatmap** | A frequency map of the `parserName` string provided by the instrument. |

## 4. Implementation

```scala
import fastparse._
import fastparse.internal.Instrument

/**
 * Custom collector to store metrics during a single ParsingRun.
 */
class FastparseCollector extends Instrument {
  var totalInvocations: Long = 0
  var backtrackEvents: Long = 0
  var maxIndex: Int = 0
  val ruleFrequency = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)

  override def beforeParse(name: String, index: Int): Unit = {
    totalInvocations += 1
    ruleFrequency(name) += 1
    
    // Logic to detect backtracking: current index is behind furthest reached
    if (index < maxIndex) {
      backtrackEvents += 1
    } else {
      maxIndex = index
    }
  }

  override def afterParse(name: String, index: Int, success: Boolean): Unit = {
    // Hooks available for post-rule processing (e.g. failure rates)
  }
}

/**
 * Execution wrapper to apply the instrument to any parser.
 */
object FastparseMonitor {
  def parseWithMetrics[T](input: String, parser: P[_] => P[T]): (Parsed[T], FastparseCollector) = {
    // Manually setup the run context
    val run = new ParsingRun(
      input = input,
      startIndex = 0,
      collector = null,
      cursor = 0,
      verboseFailures = false
    )
    
    val collector = new FastparseCollector
    run.instrument = collector
    
    val result = parser(run)
    (result, collector)
  }
}
```

## 5. Usage Example

```scala
val (result, stats) = FastparseMonitor.parseWithMetrics(myInput, MyParser.root(_))

println(s"Efficiency: ${stats.backtrackEvents} backtracks for ${stats.totalInvocations} calls")
println(s"Coverage: ${stats.maxIndex.toDouble / myInput.length * 100}% of input reached")
```


## 6. Integration

In the IngestStage, put the generated metrics in the state, like other components do, using the `addTiming` member of state.
