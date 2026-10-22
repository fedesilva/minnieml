package mml.mmlclib.test.llvm

import munit.Location

import scala.util.matching.Regex

/** Follows closure operands without depending on generated entry or environment names. */
trait ClosureAssertions extends LlvmAssertions:

  protected final case class ClosurePair(value: String, environment: String)

  protected final case class CaptureField(
    ownerEnvironment:    String,
    consumerEnvironment: String,
    layout:              String,
    fieldIndex:          String,
    storedValue:         String,
    loadedValue:         String
  )

  protected final case class OwnedEnvironment(destructor: String, capture: CaptureField)

  protected final case class DestructorSlot(
    layout:      String,
    environment: String,
    tbaaTag:     Option[String],
    start:       Int
  )

  protected final case class EnvironmentMetadata(scalarNodes: List[String], matched: String)

  protected val nullEnvironmentClosure: String =
    "\\{ ptr @(?<entry>[^ ,]+), ptr null \\}"

  protected def uniqueMatch(text: String, pattern: String)(using Location): Regex.Match =
    val expression = pattern.r
    assertEquals(expression.findAllIn(text).size, 1, s"Expected one match for $pattern:\n$text")
    expression.findFirstMatchIn(text).getOrElse(fail(s"Expected a match for $pattern"))

  protected def closureEntry(body: String)(using Location): String =
    uniqueMatch(body, "insertvalue \\{ ptr, ptr \\} undef, ptr @([^ ,]+), 0").group(1)

  /** Both inserts build one closure value with the selected entry and its environment. */
  protected def closurePair(body: String, entry: String)(using Location): ClosurePair =
    val pair = uniqueMatch(
      body,
      s"(?<head>%[^ ]+) = insertvalue \\{ ptr, ptr \\} undef, " +
        s"ptr @${Regex.quote(entry)}, 0\\n" +
        "  (?<value>%[^ ]+) = insertvalue \\{ ptr, ptr \\} \\k<head>, " +
        "ptr (?<environment>%[^ ,\\n]+), 1"
    )
    ClosurePair(value = pair.group("value"), environment = pair.group("environment"))

  protected def closureValue(
    body:        String,
    entry:       String,
    environment: String
  )(using Location): String =
    val pair = closurePair(body, entry)
    assertEquals(pair.environment, environment)
    pair.value

  /** A direct entry receives the environment extracted immediately before its call. */
  protected def closureInvocation(
    body:            String,
    returnType:      String,
    argumentPattern: String,
    pairPattern:     String = "%[^,\\n]+",
    entryPattern:    String = "[^ (]+"
  )(using Location): Regex.Match =
    val result    = if returnType == "void" then "" else "(?<result>%[^ ]+) = "
    val arguments = if argumentPattern.isEmpty then "" else s"$argumentPattern, "

    uniqueMatch(
      body,
      s"(?<environment>%[^ ]+) = extractvalue \\{ ptr, ptr \\} " +
        s"(?<pair>$pairPattern), 1\\n  ${result}call ${Regex.quote(returnType)} " +
        s"@(?<entry>$entryPattern)\\(${arguments}ptr \\k<environment>\\)"
    )

  /** An indirect call uses both operands extracted from the same closure value. */
  protected def indirectClosureInvocation(
    body:       String,
    pair:       String,
    returnType: String,
    arguments:  String
  )(using Location): Regex.Match =
    val result = if returnType == "void" then "" else "(?<result>%[^ ]+) = "
    val prefix = if arguments.isEmpty then "" else s"${Regex.quote(arguments)}, "

    uniqueMatch(
      body,
      s"(?<entry>%[^ ]+) = extractvalue \\{ ptr, ptr \\} ${Regex.quote(pair)}, 0\\n  " +
        s"(?<environment>%[^ ]+) = extractvalue \\{ ptr, ptr \\} ${Regex.quote(pair)}, 1\\n  " +
        s"${result}call ${Regex.quote(returnType)} \\k<entry>" +
        s"\\(${prefix}ptr \\k<environment>\\)"
    )

  protected def storedDestructor(body: String)(using Location): String =
    uniqueMatch(body, "store ptr @([^ ,]+), ptr %[^ ,\\n]+").group(1)

  /** The destructor is stored in field zero of the selected environment layout. */
  protected def destructorSlot(body: String, destructor: String)(using Location): DestructorSlot =
    val slot = uniqueMatch(
      body,
      "(?<slot>%[^ ]+) = getelementptr (?<layout>%struct\\.[^,]+), " +
        "ptr (?<environment>%[^,]+), i32 0, i32 0\\n  " +
        s"store ptr @${Regex.quote(destructor)}, ptr \\k<slot>" +
        "(?:, !tbaa (?<tag>!\\d+))?(?=,|\\n|$)"
    )
    DestructorSlot(
      layout      = slot.group("layout"),
      environment = slot.group("environment"),
      tbaaTag     = Option(slot.group("tag")),
      start       = slot.start
    )

  protected def assertReturnedNext(
    body:       String,
    invocation: Regex.Match,
    returnType: String
  )(using Location): Unit =
    val result   = Regex.quote(invocation.group("result"))
    val returned = s"\\n  ret ${Regex.quote(returnType)} $result(?=\\n|$$)".r
    assert(returned.findPrefixOf(body.substring(invocation.end)).nonEmpty, body)

  protected def callsTo(body: String, symbol: String): List[Regex.Match] =
    s"\\bcall\\b[^@\\n]*@${Regex.quote(symbol)}\\([^\\n]*".r.findAllMatchIn(body).toList

  protected def assertCalls(body: String, symbol: String, count: Int)(using Location): Unit =
    assertEquals(callsTo(body, symbol).size, count, body)

  /** A Unit closure is invoked and destroyed through the same captured environment. */
  protected def assertCaptureLifetime(
    owner:      String,
    capture:    CaptureField,
    entry:      String,
    destructor: String
  )(using Location): Unit =
    val slot = destructorSlot(owner, destructor)
    assertEquals(slot.layout, capture.layout)
    assertEquals(slot.environment, capture.ownerEnvironment)

    val value = closureValue(owner, entry, capture.ownerEnvironment)

    List(entry, destructor).foreach { target =>
      assertCalls(owner, target, 1)
      closureInvocation(
        owner,
        returnType      = "void",
        argumentPattern = "",
        pairPattern     = Regex.quote(value),
        entryPattern    = Regex.quote(target)
      )
    }
    assert(callsTo(owner, entry).head.start < callsTo(owner, destructor).head.start, owner)

  /** The stored destructor owns one payload and releases it before its environment. */
  protected def assertOwnedEnvironment(
    ir:                String,
    owner:             String,
    payloadType:       String,
    payloadDestructor: String
  )(using Location): OwnedEnvironment =
    val destructor = storedDestructor(owner)
    val environment = uniqueMatch(
      ir,
      s"(?m)^define [^\\n]+@${Regex.quote(destructor)}\\(ptr (%[^ )]+)\\)"
    ).group(1)

    val body    = functionBody(ir, destructor)
    val capture = assertCaptureField(owner, body, payloadType)
    assertEquals(capture.consumerEnvironment, environment)

    val payloadRelease = uniqueMatch(
      body,
      s"call void @([^ (]+)\\(${Regex.quote(payloadType)} " +
        s"${Regex.quote(capture.loadedValue)}\\)"
    )
    assertCalls(expandNativeAdapters(ir, payloadRelease.matched), payloadDestructor, 1)

    val expanded = expandNativeAdapters(ir, body)
    assertCalls(expanded, payloadDestructor, 1)
    assertCalls(expanded, "mml_free_raw", 1)
    uniqueMatch(body, s"call void @mml_free_raw\\(ptr ${Regex.quote(environment)}\\)")
    assert(
      payloadRelease.start < callsTo(body, "mml_free_raw").head.start,
      body
    )

    OwnedEnvironment(destructor = destructor, capture = capture)

  /** The captured value is stored and loaded through the same layout field. */
  protected def assertCaptureField(
    owner:     String,
    consumer:  String,
    valueType: String
  )(using Location): CaptureField =
    captureFieldMatching(owner, consumer, valueType, "%[^,]+", "[^,\\n]+")

  /** Selects one capture by its source operand and the owning closure environment. */
  protected def assertCaptureField(
    owner:            String,
    consumer:         String,
    valueType:        String,
    ownerEnvironment: String,
    storedValue:      String
  )(using Location): CaptureField =
    captureFieldMatching(
      owner,
      consumer,
      valueType,
      Regex.quote(ownerEnvironment),
      Regex.quote(storedValue)
    )

  private def captureFieldMatching(
    owner:              String,
    consumer:           String,
    valueType:          String,
    environmentPattern: String,
    storedValuePattern: String
  )(using Location): CaptureField =
    val aggregate = Regex.quote(valueType)
    val field = uniqueMatch(
      owner,
      s"(%[^ ]+) = getelementptr (%struct\\.[^,]+), " +
        s"ptr ($environmentPattern), i32 0, i32 ([0-9]+)\\n" +
        s"  store $aggregate ($storedValuePattern), ptr \\1(?=,|\\n|$$)"
    )

    val layout = Regex.quote(field.group(2))
    val loaded = uniqueMatch(
      consumer,
      s"(%[^ ]+) = getelementptr $layout, ptr (%[^,]+), i32 0, i32 ${field.group(4)}\\n" +
        s"  (%[^ ]+) = load $aggregate, ptr \\1(?=,|\\n|$$)"
    )

    CaptureField(
      ownerEnvironment    = field.group(3),
      consumerEnvironment = loaded.group(2),
      layout              = field.group(2),
      fieldIndex          = field.group(4),
      storedValue         = field.group(5),
      loadedValue         = loaded.group(3)
    )

  /** Capture stores and loads carry the same TBAA access tag. */
  protected def captureTbaaTag(
    owner:     String,
    consumer:  String,
    capture:   CaptureField,
    valueType: String
  )(using Location): String =
    val store = uniqueMatch(
      owner,
      s"store ${Regex.quote(valueType)} ${Regex.quote(capture.storedValue)}, " +
        "ptr %[^,]+, !tbaa (!\\d+)"
    )
    uniqueMatch(
      consumer,
      s"${Regex.quote(capture.loadedValue)} = load ${Regex.quote(valueType)}, " +
        s"ptr %[^,]+, !tbaa ${store.group(1)}(?=,|\\n|$$)"
    )
    store.group(1)

  /** Each access tag selects its scalar node at the declared environment field offset. */
  protected def assertEnvironmentMetadata(
    ir:     String,
    layout: String,
    fields: List[(String, Int)]
  )(using Location): EnvironmentMetadata =
    assert(fields.nonEmpty, "Expected at least one environment metadata field")
    val fieldPattern = fields.map { case (_, offset) => s"(!\\d+), i64 $offset" }.mkString(", ")
    val node = uniqueMatch(
      ir,
      s"(?m)^(!\\d+) = !\\{!\"${Regex.quote(layout.stripPrefix("%struct."))}\", " +
        s"$fieldPattern\\}$$"
    )

    fields.zipWithIndex.foreach { case ((tag, offset), index) =>
      uniqueMatch(
        ir,
        s"(?m)^${Regex.quote(tag)} = !\\{${node.group(1)}, " +
          s"${node.group(index + 2)}, i64 $offset\\}$$"
      )
    }
    EnvironmentMetadata(
      scalarNodes = fields.indices.map(index => node.group(index + 2)).toList,
      matched     = node.matched
    )
