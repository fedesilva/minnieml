package mml.mmlclib.semantic

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.test.BaseEffFunSuite

import scala.io.Source

class StandardConversionTests extends BaseEffFunSuite:

  List(
    ("Int", "-42", "int_to_str"),
    ("Float", "1.25", "float_to_str"),
    ("Int64", "large ()", "int64_to_str"),
    ("Int8", "small8 ()", "int8_to_str"),
    ("Int16", "small16 ()", "int16_to_str"),
    ("Double", "precise ()", "double_to_str"),
    ("Bool", "true", "bool_to_str"),
    ("Char", "letter ()", "char_to_str"),
    ("SizeT", "large_size ()", "sizet_to_str"),
    ("Unit", "()", "unit_to_str"),
    ("String", "\"text\"", "__clone_String"),
    ("CString", "to_cstr \"text\"", "cstring_to_str")
  ).foreach { (typeName, argument, symbol) =>
    test(s"ToString $typeName resolves to its concrete allocating runtime member"):
      val source = scalarInputs + s"let result = to_str ($argument);"
      parseNotFailed(source).map { parsed =>
        val initial = prepareForTypeChecking(source, parsed)
        assertEquals(initial.errors, Vector.empty)
        val checked = TypeChecker.rewriteModule(initial)
        assertEquals(checked.errors, Vector.empty)

        val implementation = protocolImplementation(checked, "ToString", typeName, "to_str")
        val result = checked.module.members
          .collectFirst {
            case binding: Bnd if binding.name == "result" => binding
          }
          .getOrElse(fail("Expected result binding"))
        val references = TermTraversal.collect(result.value) {
          case reference: Ref if reference.name == "to_str" => reference
        }
        assertEquals(references.map(_.resolvedId), List(implementation.id))
        assertEquals(references.map(_.candidateIds), List(implementation.id.toList))

        val index = checked.module.resolvables
        val function = implementation.typeSpec
          .flatMap(TypeUtils.canonical(_, index))
          .collect { case function: TypeFn => function }
          .getOrElse(fail("Expected a concrete callable type"))
        assertEquals(function.paramTypes.size, 1)
        val inputType = typeReference(typeName, index)
        assert(TypeCompatibility.same(function.paramTypes.head, inputType, index))
        assert(TypeCompatibility.same(function.returnType, typeReference("String", index), index))
        assertEquals(nativeBody(implementation).nativeSymbol, Some(symbol))
        assertEquals(nativeBody(implementation).memEffect, Some(MemEffect.Alloc))
      }
  }

  test("the reference prelude and injected conversions expose the same contract"):
    Resource.fromAutoCloseable(IO.blocking(Source.fromResource("prelude.mml"))).use { resource =>
      val section = resource.mkString
        .split("// String conversion protocol", 2)
        .last
        .split("// I/O and strings", 2)
        .head
      parseNotFailed(section).map { parsed =>
        assertConversionContract(parsed)
        assertConversionContract(injectStandardConversions(parsed.copy(members = Nil)))
      }
    }

  List(
    ("Int32", "Int", "add i32 7, 0"),
    ("Byte", "Int8", "add i8 7, 0"),
    ("Word", "Int8", "add i8 7, 0")
  ).foreach { (alias, instanceType, template) =>
    test(s"ToString $alias reuses the $instanceType instance"):
      val source = s"""
        fn input(): $alias = @metal:tpl["$template"];;
        let result = to_str (input ());
      """
      parseNotFailed(source).map { parsed =>
        val initial = prepareForTypeChecking(source, parsed)
        assertEquals(initial.errors, Vector.empty)
        val checked = TypeChecker.rewriteModule(initial)
        assertEquals(checked.errors, Vector.empty)
        val canonical = protocolImplementation(checked, "ToString", instanceType, "to_str")
        val aliased   = protocolImplementation(checked, "ToString", alias, "to_str")
        assertEquals(aliased.id, canonical.id)
      }
  }

  test("ToString does not invent an instance for arrays"):
    val source = "let text = to_str (ar_int_new 1);"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val checked = TypeChecker.rewriteModule(initial)
      assert(checked.visibleErrors.exists {
        case SemanticError.ProtocolErrorFound(_: ProtocolError.MissingInstance, _) => true
        case _ => false
      })
    }

  test("converting a null CString reports the runtime precondition"):
    val source = """
      fn null_cstring(): CString = @metal:tpl["inttoptr i64 0 to ptr"];;
      pub fn main(): Unit = println (to_str (null_cstring ()));;
    """
    withSourceFixture(source, "NullCStringConversion.mml") { (path, initialConfig) =>
      val directory = path.getParent
      val binary    = directory.resolve("program")
      val config = initialConfig.copy(
        mode       = CompilationMode.Exe,
        outputName = binary.toString.some
      )
      for
        compiled <- CompilerApi.compileNativeQuiet(path, config)
        _ = compiled.fold(error => fail(error), state => assertEquals(state.errors, Vector.empty))
        _ <- programExits(
          List(binary.toString),
          directory,
          expectedCode  = 134,
          expectedError = "panic: cstring_to_str: null CString".some
        )
      yield ()
    }

  List(0, 3).foreach { optimization =>
    test(s"ToString direct calls and inferred function values execute at O$optimization"):
      runSanitizedProgram(
        scalarInputs + """
          pub fn main(): Unit =
            println (to_str (-42));
            println (to_str 1.25);
            println (to_str (large ()));
            println (to_str (small8 ()));
            println (to_str (small16 ()));
            println (to_str (precise ()));
            println (to_str true);
            println (to_str false);
            println (to_str (letter ()));
            println (to_str (large_size ()));
            println (to_str ());
            println (to_str "text");
            let ctext = to_cstr "ctext";
            println (to_str ctext);
            println (to_str ctext);
            let integer = to_str;
            let floating = to_str;
            let wide = to_str;
            println (integer 7);
            println (floating 2.5);
            println (wide (large ()));
          ;
        """,
        s"StandardConversionsO$optimization",
        optLevel = optimization,
        expectedOutput = List(
          "-42",
          "1.25",
          "4294967303",
          "-128",
          "-32768",
          "1.0000000000000002",
          "true",
          "false",
          "A",
          "18446744073709551615",
          "()",
          "text",
          "ctext",
          "ctext",
          "7",
          "2.5",
          "4294967303"
        )
      )

    test(s"string conversions preserve source ownership and embedded NUL at O$optimization"):
      runSanitizedProgram(
        scalarInputs + """
          fn consume(~value: String): Unit = println (to_str value.length);;
          pub fn main(): Unit =
            let nul = to_str (zero_char ());
            println (to_str nul.length);
            let source = "a" ++ nul ++ "b";
            let copy = to_str source;
            println (to_str (str_eq source copy));
            consume copy;
            println (to_str source.length);
            let empty = to_str "";
            println (to_str empty.length);
            let ctext = to_cstr source;
            let from_c = to_str ctext;
            consume from_c;
            println (to_str ctext);
          ;
        """,
        s"StandardStringConversionOwnershipO$optimization",
        optLevel       = optimization,
        expectedOutput = List("1", "true", "3", "3", "0", "1", "a")
      )
  }

  /** Compare source declarations and injected ASTs by their language and runtime contracts. */
  private def assertConversionContract(module: Module): Unit =
    val protocols = module.members.collect { case protocol: Protocol => protocol }
    assertEquals(protocols.map(_.name), List("ToString"))
    val protocol  = protocols.head
    val signature = protocolSignature(module, "ToString", "to_str")
    assertEquals(signature.params.size, 1)
    assertEquals(signature.params.head.consuming, false)
    assertEquals(
      signature.params.head.typeAsc.collect { case t: TypeVariable => t.name },
      Some(protocol.typeParameter.name)
    )
    val resultName = signature.returnType match
      case t: TypeRef => t.name
      case other => fail(s"Expected String reference, got $other")
    assertEquals(resultName, "String")
    assertEquals(signature.meta.origin, BindingOrigin.Function)
    assertEquals(signature.meta.arity, CallableArity.Unary)

    val instances = module.members.collect { case instance: ProtocolInstance => instance }
    val implementations = instances.map { instance =>
      assertEquals(instance.protocol.nameNode.value, "ToString")
      assertEquals(instance.members.size, 1)
      val member = instance.members.head
      assertEquals(member.origin, BindingOrigin.Function)
      assertEquals(member.binding.name, "to_str")
      val native = nativeBody(member.binding)
      assertEquals(native.memEffect, Some(MemEffect.Alloc))
      val typeName = instance.concreteType match
        case t: TypeRef => t.name
        case other => fail(s"Expected concrete type reference, got $other")
      typeName -> native.nativeSymbol
    }
    assertEquals(
      implementations.toMap,
      Map(
        "Int" -> Some("int_to_str"),
        "Float" -> Some("float_to_str"),
        "Int64" -> Some("int64_to_str"),
        "Int8" -> Some("int8_to_str"),
        "Int16" -> Some("int16_to_str"),
        "Double" -> Some("double_to_str"),
        "Bool" -> Some("bool_to_str"),
        "Char" -> Some("char_to_str"),
        "SizeT" -> Some("sizet_to_str"),
        "Unit" -> Some("unit_to_str"),
        "String" -> Some("__clone_String"),
        "CString" -> Some("cstring_to_str")
      )
    )
    assertEquals(instances.size, 12)

  private val scalarInputs = """
    fn large(): Int64 = @metal:tpl["add i64 4294967296, 7"];;
    fn small8(): Int8 = @metal:tpl["add i8 -128, 0"];;
    fn small16(): Int16 = @metal:tpl["add i16 -32768, 0"];;
    fn precise(): Double = @metal:tpl["fadd double 0x3FF0000000000001, 0.0"];;
    fn letter(): Char = @metal:tpl["add i8 65, 0"];;
    fn zero_char(): Char = @metal:tpl["add i8 0, 0"];;
    fn large_size(): SizeT = @metal:tpl["add i64 -1, 0"];;
  """

  private def nativeBody(binding: Bnd): NativeImpl =
    TermTraversal.collect(binding.value) { case native: NativeImpl => native } match
      case List(native) => native
      case other => fail(s"Expected one native implementation, got $other")
