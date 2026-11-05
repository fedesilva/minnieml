package mml.mmlclib.lsp

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.test.BaseEffFunSuite

class AstLookupRecoveryTests extends BaseEffFunSuite:

  /** A source field access with independently replaceable owner metadata and type index. */
  private case class FieldFixture(
    state:     CompilerState,
    binding:   Bnd,
    access:    Ref,
    qualifier: Ref,
    struct:    TypeStruct,
    alias:     TypeAlias
  ):

    def moduleWith(ownerType: Type, declarations: List[Member], index: ResolvablesIndex): Module =
      val reference = access.copy(qualifier = qualifier.copy(typeSpec = ownerType.some).some)
      val use       = binding.copy(value = Expr(access.source, List(reference)))
      state.module.copy(members = declarations :+ use, resolvables = index)

  private def fixture: IO[FieldFixture] =
    val code = """struct Box { value: Int };
type BoxAlias = Box;
fn read(box: BoxAlias): Int = box.value;;"""

    semStateNotFailed(code).map { state =>
      val module = state.module
      val binding = module.members
        .collectFirst { case binding: Bnd if binding.source.isFromSource => binding }
        .getOrElse(fail("Missing source binding"))
      val access = TermTraversal
        .collect(binding.value) { case ref: Ref if ref.qualifier.nonEmpty => ref }
        .headOption
        .getOrElse(fail("Missing field access"))
      val qualifier = access.qualifier
        .collect { case ref: Ref => ref }
        .getOrElse(fail("Missing field owner"))
      val struct = module.members
        .collectFirst { case struct: TypeStruct => struct }
        .getOrElse(fail("Missing struct"))
      val alias = module.members
        .collectFirst { case alias: TypeAlias if alias.source.isFromSource => alias }
        .getOrElse(fail("Missing alias"))
      FieldFixture(state, binding, access, qualifier, struct, alias)
    }

  private def span(node: FromSource): SrcSpan =
    node.spanOpt.getOrElse(fail("Missing fixture source span"))

  private def assertFieldNavigation(fixture: FieldFixture, module: Module): Unit =
    val fieldSpan = span(fixture.struct.fields.head)
    val useSpan   = span(fixture.access)
    val usePoint  = useSpan.end.copy(col = useSpan.end.col - 1)

    assertEquals(
      AstLookup.findDefinitionAt(module, usePoint.line, usePoint.col),
      List(fieldSpan)
    )
    assertEquals(
      AstLookup.findReferencesAt(
        fixture.state.copy(module = module),
        fieldSpan.start.line,
        fieldSpan.start.col,
        includeDeclaration = false
      ),
      List(useSpan)
    )

  test("field navigation follows grouped cached aliases and schemes by identity"):
    fixture.map { fixture =>
      val structRef = TypeRef(SourceOrigin.Synth, fixture.struct.name, fixture.struct.id)
      val inner = fixture.alias.copy(
        nameNode = Name.synth("Inner"),
        id       = "inner-alias".some,
        typeSpec = structRef.some
      )
      val outer = fixture.alias.copy(typeSpec =
        TypeGroup(SourceOrigin.Synth, List(TypeRef(SourceOrigin.Synth, inner.name, inner.id))).some
      )
      val index = fixture.state.module.resolvables.updatedAllTypes(List(inner, outer))
      val owner = TypeScheme(
        SourceOrigin.Synth,
        Nil,
        TypeGroup(
          SourceOrigin.Synth,
          List(TypeRef(SourceOrigin.Synth, fixture.struct.name, outer.id))
        )
      )
      val module = fixture.moduleWith(owner, List(fixture.struct, outer, inner), index)

      assertFieldNavigation(fixture, module)
    }

  test("field navigation recovers missing and unindexed alias identities by source name"):
    fixture.map { fixture =>
      val alias = fixture.alias.copy(
        typeRef  = TypeRef(SourceOrigin.Synth, fixture.struct.name),
        typeSpec = None
      )

      List(None, "absent-alias".some).foreach { id =>
        val owner = TypeRef(SourceOrigin.Synth, alias.name, id)
        val module = fixture.moduleWith(
          owner,
          List(fixture.struct, alias),
          ResolvablesIndex()
        )
        assertFieldNavigation(fixture, module)
      }
    }

  test("name recovery retains the first declaration even when a later alias reaches a struct"):
    fixture.map { fixture =>
      val failed = fixture.alias.copy(
        typeRef  = ParseTypeError(SourceOrigin.Synth, SyntaxError.InvalidTypeSyntax, None),
        typeSpec = None
      )
      val owner = TypeRef(SourceOrigin.Synth, fixture.alias.name)
      val module = fixture.moduleWith(
        owner,
        List(failed, fixture.alias, fixture.struct),
        ResolvablesIndex()
      )
      val point = span(fixture.access).end

      assertEquals(AstLookup.findDefinitionAt(module, point.line, point.col - 1), Nil)
      assertFieldNavigation(
        fixture,
        fixture.moduleWith(
          owner,
          List(fixture.alias, failed, fixture.struct),
          ResolvablesIndex()
        )
      )
    }

  test("field navigation stops at indexed and name-recovered alias cycles"):
    fixture.map { fixture =>
      val owner   = TypeRef(SourceOrigin.Synth, fixture.alias.name, fixture.alias.id)
      val alias   = fixture.alias.copy(typeRef = owner, typeSpec = None)
      val indexed = fixture.state.module.resolvables.updatedType(alias)
      val point   = span(fixture.access).end

      List(indexed, ResolvablesIndex()).foreach { index =>
        val module = fixture.moduleWith(owner, List(fixture.struct, alias), index)
        assertEquals(AstLookup.findDefinitionAt(module, point.line, point.col - 1), Nil)
      }
    }

  test("an indexed nonstruct identity does not recover a same-named struct"):
    fixture.map { fixture =>
      val nominal = TypeDef(
        source   = SourceOrigin.Synth,
        nameNode = fixture.struct.nameNode,
        typeSpec = None,
        id       = "nominal-type".some
      )
      val owner  = TypeRef(SourceOrigin.Synth, fixture.struct.name, nominal.id)
      val index  = fixture.state.module.resolvables.updatedType(nominal)
      val module = fixture.moduleWith(owner, List(fixture.struct), index)
      val point  = span(fixture.access).end

      assertEquals(AstLookup.findDefinitionAt(module, point.line, point.col - 1), Nil)
    }
