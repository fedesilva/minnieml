package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class FnInstantiationDependenciesTests extends BaseEffFunSuite:

  private val intType  = ProtocolTypeKey.Nominal("types::Int")
  private val boolType = ProtocolTypeKey.Nominal("types::Bool")
  private val fInt     = FnInstanceKey("functions::f", List(intType))
  private val fBool    = FnInstanceKey("functions::f", List(boolType))
  private val gInt     = FnInstanceKey("functions::g", List(intType))

  private def dependency(from: FnInstanceKey, to: FnInstanceKey): FnInstantiationDependency =
    FnInstantiationDependency(
      from,
      to,
      Ref(SourceOrigin.Synth, "use", resolvedId = Some(to.functionId))
    )

  private def accepted(edges: FnInstantiationDependency*): FnInstantiationDependencies =
    edges.foldLeft(FnInstantiationDependencies.empty) { (graph, edge) =>
      graph.add(edge).fold(error => fail(s"Unexpected recursion: $error"), identity)
    }

  private def rejected(
    graph: FnInstantiationDependencies,
    edge:  FnInstantiationDependency
  ): ChangingTypeRecursion =
    graph.add(edge).swap.toOption.getOrElse(fail("Expected changing-type recursion"))

  test("same-substitution direct recursion reuses its dependency"):
    val edge  = dependency(fInt, fInt)
    val graph = accepted(edge, edge)
    assertEquals(graph.edges, Vector(edge))

  test("same-substitution mutual recursion is accepted"):
    val outward = dependency(fInt, gInt)
    val inward  = dependency(gInt, fInt)
    assertEquals(accepted(outward, inward).edges, Vector(outward, inward))

  test("independent uses of one function at different types are accepted"):
    val first  = dependency(gInt, fInt)
    val second = dependency(gInt, fBool)
    assertEquals(accepted(first, second).edges, Vector(first, second))

  test("changing-type direct recursion retains its source reference"):
    val edge = dependency(fInt, fBool)
    assertEquals(rejected(FnInstantiationDependencies.empty, edge).path.toList, List(edge))

  test("mutual recursion is rejected in either discovery order"):
    val first  = dependency(fInt, gInt)
    val second = dependency(gInt, fBool)
    assertEquals(rejected(accepted(first), second).path.toList, List(first, second))
    assertEquals(rejected(accepted(second), first).path.toList, List(first, second))

  test("joining cached paths checks both ancestors and descendants"):
    val hInt   = FnInstanceKey("functions::h", List(intType))
    val first  = dependency(fInt, gInt)
    val middle = dependency(gInt, hInt)
    val last   = dependency(hInt, fBool)
    assertEquals(rejected(accepted(first, last), middle).path.toList, List(first, middle, last))

  test("every discovery order retains the same directed recursion evidence"):
    val hInt = FnInstanceKey("functions::h", List(intType))
    val path = List(dependency(fInt, gInt), dependency(gInt, hInt), dependency(hInt, fBool))
    path.permutations.foreach { order =>
      assertEquals(rejected(accepted(order.init*), order.last).path.toList, path)
    }

  test("a finite changing-type cycle is rejected before expanding its second instance"):
    val first     = dependency(fInt, gInt)
    val second    = dependency(gInt, fBool)
    val returning = dependency(fBool, fInt)
    assertEquals(rejected(accepted(first), second).path.toList, List(first, second))
    assertEquals(
      rejected(FnInstantiationDependencies.empty, returning).path.toList,
      List(returning)
    )

  test("same-type cycles terminate while searching for changing-type recursion"):
    val first     = dependency(fInt, gInt)
    val returning = dependency(gInt, fInt)
    val changed   = dependency(gInt, fBool)
    assertEquals(rejected(accepted(first, returning), changed).path.toList, List(first, changed))

  test("local instances include enclosing substitutions in recursion checks"):
    val localInt  = FnInstanceKey("locals::f", Nil, Map("outer::T" -> intType))
    val localBool = FnInstanceKey("locals::f", Nil, Map("outer::T" -> boolType))
    val edge      = dependency(localInt, localBool)
    assertEquals(rejected(FnInstantiationDependencies.empty, edge).path.toList, List(edge))

  test("different lexical functions may have the same concrete arguments"):
    val local = FnInstanceKey("locals::f", fInt.arguments)
    val edge  = dependency(fInt, local)
    assertEquals(accepted(edge).edges, Vector(edge))

  test("enclosing substitution order does not create a different instance"):
    val first  = FnInstanceKey("locals::f", Nil, Map("outer::A" -> intType, "outer::B" -> boolType))
    val second = FnInstanceKey("locals::f", Nil, Map("outer::B" -> boolType, "outer::A" -> intType))
    val edge   = dependency(first, second)
    assertEquals(accepted(edge).edges, Vector(edge))
