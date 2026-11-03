package mml.mmlclib.ast

import mml.mmlclib.ast.queries.SourceQueries
import mml.mmlclib.test.BaseEffFunSuite

class SourceQueriesTests extends BaseEffFunSuite:

  test("source spans include their start and exclude their end across lines"):
    val span = SrcSpan(SrcPoint(2, 4, 12), SrcPoint(4, 3, 33))

    assert(!SourceQueries.containsPosition(span, 2, 3))
    assert(SourceQueries.containsPosition(span, 2, 4))
    assert(SourceQueries.containsPosition(span, 3, 1))
    assert(SourceQueries.containsPosition(span, 4, 2))
    assert(!SourceQueries.containsPosition(span, 4, 3))
    assert(!SourceQueries.containsPosition(span, 5, 1))

  test("empty spans have coordinates but contain no position"):
    val point = SrcPoint(2, 4, 12)
    val span  = SrcSpan(point, point)

    assert(SourceQueries.hasCoordinates(span))
    assert(!SourceQueries.containsPosition(span, 2, 4))
    assert(!SourceQueries.hasCoordinates(span.copy(start = point.copy(line = 0))))
    assert(!SourceQueries.hasCoordinates(span.copy(end = point.copy(col = 0))))
