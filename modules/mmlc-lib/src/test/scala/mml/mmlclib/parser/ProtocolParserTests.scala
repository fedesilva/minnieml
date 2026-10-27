package mml.mmlclib.parser

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.lsp.{AstLookup, Diagnostics, SymbolKind}
import mml.mmlclib.semantic.{IdAssigner, ResolvablesIndexer}
import mml.mmlclib.test.BaseEffFunSuite

class ProtocolParserTests extends BaseEffFunSuite:

  test("a protocol declares one type parameter and a required named signature") {
    val source = "protocol Eq 'T = fn equal(left: 'T, right: 'T): Bool; ;"
    parseNotFailed(source).map { module =>
      val declaration = protocol(module)
      assertEquals(declaration.name, "Eq")
      assertEquals(declaration.typeParameter.name, "'T")
      assertEquals(declaration.signatures.size, 1)
      val signature = declaration.signatures.head
      assertEquals(signature.name, "equal")
      assertEquals(signature.params.map(_.name), List("left", "right"))
      assertEquals(signature.params.flatMap(_.typeAsc).map(typeName), List("'T", "'T"))
      assertEquals(typeName(signature.returnType), "Bool")
      assertEquals(signature.defaultBody, None)
      assertEquals(written(declaration, source), source)
      assertEquals(written(signature, source), "fn equal(left: 'T, right: 'T): Bool;")
    }
  }

  test("required unary and binary operators retain their grouping attributes") {
    val source = "protocol Num 'T = op -(x: 'T): 'T 95 right; op +(x: 'T, y: 'T): 'T 60 left; ;"
    parseNotFailed(source).map { module =>
      val signatures = protocol(module).signatures
      assertEquals(signatures.map(_.meta.arity), List(CallableArity.Unary, CallableArity.Binary))
      assertEquals(signatures.map(_.meta.precedence), List(95, 60))
      assertEquals(
        signatures.map(_.meta.associativity),
        List(Some(Associativity.Right), Some(Associativity.Left))
      )
    }
  }

  test("instance annotations and operator attributes remain absent when omitted") {
    val source = "instance Num Int = op +(left, right) = left + right;; ;"
    parseNotFailed(source).map { module =>
      val declaration = instance(module)
      val member      = declaration.members.head
      val binding     = member.binding
      val body        = lambda(binding)
      assertEquals(declaration.protocol.nameNode.value, "Num")
      assertEquals(typeName(declaration.concreteType), "Int")
      assertEquals(body.params.map(_.typeAsc), List(None, None))
      assertEquals(body.typeAsc, None)
      assertEquals(binding.typeAsc, None)
      assertEquals(member.writtenPrecedence, None)
      assertEquals(member.writtenAssociativity, None)
      assertEquals(binding.meta, None)
      assertEquals(written(declaration, source), source)
      assertEquals(written(body, source), "(left, right) = left + right;")
    }
  }

  test("explicit instance types and attributes remain available for signature comparison") {
    val source = "instance Num Int = op +(left: Int, right: Int): Int 60 left = left;; ;"
    parseNotFailed(source).map { module =>
      val member  = instance(module).members.head
      val binding = member.binding
      assertEquals(lambda(binding).params.flatMap(_.typeAsc).map(typeName), List("Int", "Int"))
      assertEquals(binding.typeAsc.map(typeName), Some("Int"))
      assertEquals(member.writtenPrecedence, Some(60))
      assertEquals(member.writtenAssociativity, Some(Associativity.Left))
      assertEquals(binding.meta, None)
    }
  }

  test("named instance members use ordinary lambda bodies") {
    parseNotFailed("instance Eq Int = fn equal(left, right) = left == right;; ;").map { module =>
      val member  = instance(module).members.head
      val binding = member.binding
      assertEquals(member.origin, BindingOrigin.Function)
      assertEquals(binding.meta, None)
      assertEquals(lambda(binding).params.map(_.name), List("left", "right"))
      assertEquals(
        lambda(binding).body.terms.collect { case ref: Ref => ref.name },
        List("left", "==", "right")
      )
    }
  }

  test("unary and binary instance operators have distinct stable binding identities") {
    val source = """
      instance Num Int =
        op -(value) = value;;
        op -(left, right) = left;;
      ;
    """
    parseNotFailed(source).map { module =>
      val initial     = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val assigned    = IdAssigner.rewriteModule(initial)
      val declaration = instance(assigned.module)
      val unary       = declaration.members.head.binding
      val binary      = declaration.members(1).binding

      assertEquals(unary.name, "-")
      assertEquals(binary.name, "-")
      assertEquals(unary.meta, None)
      assertEquals(binary.meta, None)
      assert(unary.id.nonEmpty)
      assert(binary.id.nonEmpty)
      assertNotEquals(unary.id, binary.id)
      assertEquals(unary.id.flatMap(assigned.module.resolvables.lookup), Some(unary))
      assertEquals(binary.id.flatMap(assigned.module.resolvables.lookup), Some(binary))
      assertEquals(IdAssigner.rewriteModule(assigned).module, assigned.module)
    }
  }

  test("unlowered instance members retain their operator and function editor symbols") {
    val source = """
      instance Num Int =
        op +(left, right) = left;;
        fn identity(value) = value;;
      ;
    """
    parseNotFailed(source).map { module =>
      val initial     = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val assigned    = IdAssigner.rewriteModule(initial)
      val declaration = instance(assigned.module)
      val operator    = declaration.members.head.binding
      val function    = declaration.members(1).binding
      val symbols     = AstLookup.collectSymbols(assigned.module, "file:///instance.mml")
      val plus        = symbols.find(_.name == "+").getOrElse(fail("Expected the operator symbol"))
      val identity = symbols
        .find(_.name == "identity")
        .getOrElse(fail("Expected the function symbol"))

      assertEquals(operator.meta, None)
      assertEquals(function.meta, None)
      assertEquals(plus.kind, SymbolKind.Operator)
      assertEquals(identity.kind, SymbolKind.Function)
      assertEquals(operator.id.flatMap(assigned.module.resolvables.lookup), Some(operator))
      assertEquals(function.id.flatMap(assigned.module.resolvables.lookup), Some(function))
      assertEquals(IdAssigner.rewriteModule(assigned).module, assigned.module)
    }
  }

  test("consuming parameters and nested function types use the ordinary type grammar") {
    parseNotFailed("protocol Apply 'T = fn apply(~value: 'T, f: ('T -> Int)): Int; ;").map {
      module =>
        val signature = protocol(module).signatures.head
        assertEquals(signature.params.map(_.consuming), List(true, false))
        signature.params(1).typeAsc match
          case Some(TypeGroup(_, List(function: TypeFn))) =>
            assertEquals(function.paramTypes.toList.map(typeName), List("'T"))
            assertEquals(typeName(function.returnType), "Int")
          case other => fail(s"Expected grouped function type, got $other")
    }
  }

  test("nullary signatures have no invented parameter") {
    parseNotFailed("protocol Empty 'T = fn empty(): 'T; ;").map { module =>
      val signature = protocol(module).signatures.head
      assertEquals(signature.params, Nil)
      assertEquals(signature.meta.arity, CallableArity.Nullary)
    }
  }

  test("a default implementation is rejected and the following declaration survives") {
    justParse("protocol Eq 'T = fn equal(x: 'T): Bool = true;; ;\nlet after = 1;").map { module =>
      assertEquals(error(module).message, "Default protocol implementations are not supported")
      assertEquals(module.members.collect { case b: Bnd => b.name }, List("after"))
    }
  }

  test("multiple protocol parameters receive a focused unsupported diagnostic") {
    justParse("protocol Pair 'A 'B = fn first(x: 'A): 'A; ;").map { module =>
      assertEquals(
        error(module).message,
        "Protocols require exactly one type parameter; multiple parameters are not supported"
      )
    }
  }

  test("protocol prerequisites receive a focused unsupported diagnostic") {
    justParse("protocol Monoid 'T: Semigroup = fn empty(): 'T; ;").map { module =>
      assertEquals(error(module).message, "Protocol prerequisites are not supported")
    }
  }

  test("an instance with a type variable is rejected") {
    justParse("instance Eq 'T = fn equal(x, y) = true;; ;").map { module =>
      assertEquals(
        error(module).message,
        "Parameterized instances and instance constraints are not supported"
      )
    }
  }

  test("a signature cannot introduce a member-local type variable") {
    justParse("protocol Convert 'T = fn convert(x: 'T): 'U; ;").map { module =>
      assertEquals(error(module).message, "Member-local type variables are not supported")
    }
  }

  test("missing signature annotations are rejected") {
    justParse("protocol Eq 'T = fn equal(x, y); ;").map { module =>
      assertEquals(error(module).message, "Protocol signatures require parameter and return types")
    }
  }

  test("a missing signature terminator still allows the next signature to be checked") {
    justParse(
      "protocol Eq 'T = fn equal(x: 'T): Bool\nfn other(x): Bool; ;\nlet after = 1;"
    ).map { module =>
      assertEquals(
        error(module).message,
        "Expected ';'; Protocol signatures require parameter and return types"
      )
      assertEquals(module.members.collect { case b: Bnd => b.name }, List("after"))
    }
  }

  test("ordinary free operators keep their default precedence and associativity") {
    parseNotFailed("op +(x: Int, y: Int): Int = x;;").map { module =>
      val binding =
        module.members.collectFirst { case b: Bnd => b }.getOrElse(fail("Expected operator"))
      assertEquals(binding.meta.map(_.precedence), Some(50))
      assertEquals(binding.meta.flatMap(_.associativity), Some(Associativity.Left))
    }
  }

  test("missing protocol equals is diagnosed without losing the following declaration") {
    justParse("protocol Eq 'T fn equal(x: 'T): Bool; ;\nlet after = 1;").map { module =>
      assertEquals(error(module).message, "Expected '='")
      assertEquals(module.members.collect { case b: Bnd => b.name }, List("after"))
    }
  }

  test("missing protocol terminator leaves a following value declaration intact") {
    justParse("protocol Eq 'T = fn equal(x: 'T): Bool;\nlet after = 1;").map { module =>
      assertEquals(error(module).message, "Expected ';'")
      assertEquals(module.members.collect { case b: Bnd => b.name }, List("after"))
    }
  }

  test("member where clauses are diagnosed as deferred syntax") {
    justParse("protocol Copy 'T = fn copy(x: 'T): 'T where 'T: Clone; ;").map { module =>
      assertEquals(error(module).message, "Member constraints are not supported")
    }
  }

  test("a malformed protocol header preserves the next protocol's public visibility") {
    val source = """
      protocol Broken 'T
      pub protocol Keep 'T = fn keep(value: 'T): 'T; ;
    """
    justParse(source).map { module =>
      assertEquals(error(module).message, "Expected '='; Expected ';'")
      assertEquals(protocol(module).name, "Keep")
      assertEquals(protocol(module).visibility, Visibility.Public)
      assert(!written(error(module), source).contains("pub"))
    }
  }

  test("a malformed instance header preserves the next protocol's private visibility") {
    val source = """
      instance Broken Int
      priv protocol Keep 'T = fn keep(value: 'T): 'T; ;
    """
    justParse(source).map { module =>
      assertEquals(error(module).message, "Expected '='; Expected ';'")
      assertEquals(protocol(module).name, "Keep")
      assertEquals(protocol(module).visibility, Visibility.Private)
      assert(!written(error(module), source).contains("priv"))
    }
  }

  test("an unterminated member constraint preserves a following type declaration") {
    val source = """
      protocol Copy 'T = fn copy(value: 'T): 'T where 'T: Clone
      type Count = Int;
    """
    justParse(source).map { module =>
      assert(error(module).message.contains("Member constraints are not supported"))
      assert(!written(error(module), source).contains("type Count"))
      assertEquals(
        module.members.collect { case declaration: TypeAlias => declaration.name },
        List("Count")
      )
    }
  }

  test("an unterminated member constraint preserves a following struct declaration") {
    val source = """
      protocol Copy 'T = fn copy(value: 'T): 'T where 'T: Clone
      struct Count { value: Int };
    """
    justParse(source).map { module =>
      assert(error(module).message.contains("Member constraints are not supported"))
      assert(!written(error(module), source).contains("struct Count"))
      assertEquals(
        module.members.collect { case declaration: TypeStruct => declaration.name },
        List("Count")
      )
    }
  }

  test("where remains an ordinary binding name outside a constraint header") {
    parseNotFailed("let where = 1;").map { module =>
      assertEquals(module.members.collect { case binding: Bnd => binding.name }, List("where"))
    }
  }

  test("malformed parameter syntax retains the following top-level declaration") {
    justParse("protocol Eq 'T = fn equal(x: 'T, ???): Bool; ;\nlet after = 1;").map { module =>
      assertEquals(error(module).message, "Invalid parameter list")
      assertEquals(module.members.collect { case b: Bnd => b.name }, List("after"))
    }
  }

  test("protocol signatures and parameters receive indexed identities") {
    val source = "protocol Eq 'T = fn equal(left: 'T, right: 'T): Bool; ;"
    parseNotFailed(source).map { module =>
      val initial     = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val assigned    = IdAssigner.rewriteModule(initial)
      val declaration = protocol(assigned.module)
      val signature   = declaration.signatures.head
      val index       = assigned.module.resolvables
      assertEquals(declaration.id.flatMap(index.lookup), Some(declaration))
      assertEquals(signature.id.flatMap(index.lookup), Some(signature))
      assertEquals(signature.params.head.id.flatMap(index.lookup), Some(signature.params.head))
      assertEquals(signature.params(1).id.flatMap(index.lookup), Some(signature.params(1)))
      assertEquals(ResolvablesIndexer.definitions(assigned.module).flatMap(_.id).distinct.size, 4)
      assertEquals(IdAssigner.rewriteModule(assigned).module, assigned.module)
    }
  }

  test("different instances keep distinct member and parameter identities") {
    val source = "instance Eq Int = fn equal(left, right) = true;; ;\n" +
      "instance Eq Float = fn equal(left, right) = true;; ;"
    parseNotFailed(source).map { module =>
      val initial   = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val assigned  = IdAssigner.rewriteModule(initial)
      val instances = assigned.module.members.collect { case i: ProtocolInstance => i }
      assertEquals(instances.flatMap(_.id).distinct.size, 2)
      assertEquals(instances.flatMap(_.members).flatMap(_.binding.id).distinct.size, 2)
      assertEquals(ResolvablesIndexer.definitions(assigned.module).flatMap(_.id).distinct.size, 6)
      val member = instances.head.members.head.binding
      assertEquals(member.id.flatMap(assigned.module.resolvables.lookup), Some(member))
      assertEquals(IdAssigner.rewriteModule(assigned).module, assigned.module)
    }
  }

  test("signature names and parameters are available to editor lookup") {
    val source = "protocol Eq 'T = fn equal(left: 'T, right: 'T): Bool; ;"
    parseNotFailed(source).map { module =>
      val initial   = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val assigned  = IdAssigner.rewriteModule(initial).module
      val signature = protocol(assigned).signatures.head
      val name      = SourceInfo(source).pointAt(source.indexOf("equal"))
      val parameter = SourceInfo(source).pointAt(source.indexOf("left"))
      assertEquals(AstLookup.findAt(assigned, name.line, name.col).flatMap(_.name), Some("equal"))
      assertEquals(
        AstLookup.findAt(assigned, parameter.line, parameter.col).flatMap(_.name),
        Some("left")
      )
      assertEquals(
        AstLookup.findDefinitionAt(assigned, name.line, name.col),
        signature.nameNode.spanOpt.toList
      )
      val symbols = AstLookup.collectSymbols(assigned, "file:///protocol.mml")
      assertEquals(
        symbols.map(s => s.name -> s.kind).toSet,
        Set("Eq" -> SymbolKind.Interface, "equal" -> SymbolKind.Function)
      )
    }
  }

  test("malformed types inside signatures reach compiler diagnostics") {
    val source = "protocol Eq 'T = fn equal(x: ???): Bool; ;"
    justParse(source).map { module =>
      val initial     = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val checked     = ParsingErrorChecker.checkModule(initial)
      val diagnostics = Diagnostics.fromCompilerState(checked)
      assertEquals(diagnostics.size, 1)
      assert(diagnostics.head.message.contains("Invalid type syntax"))
    }
  }

  test("a parameterized instance inside parentheses receives a focused diagnostic") {
    justParse("instance Eq (Option 'T) = fn equal(x, y) = true;; ;").map { module =>
      assertEquals(
        error(module).message,
        "Parameterized instances and instance constraints are not supported"
      )
    }
  }

  test("a required function cannot use a reserved word as its name") {
    justParse("protocol Bad 'T = fn if(x: 'T): 'T; ;").map { module =>
      assertEquals(error(module).message, "Invalid identifier 'if'")
      assertEquals(module.members.collect { case p: Protocol => p }, Nil)
    }
  }

  test("an instance function cannot use a reserved word as its name") {
    justParse("instance Bad Int = fn if(x) = x;; ;").map { module =>
      assertEquals(error(module).message, "Invalid identifier 'if'")
      assertEquals(module.members.collect { case i: ProtocolInstance => i }, Nil)
    }
  }

  test("a required function name may start with a keyword") {
    parseNotFailed("protocol Choose 'T = fn ifElse(x: 'T): 'T; ;").map { module =>
      assertEquals(protocol(module).signatures.map(_.name), List("ifElse"))
    }
  }

  test("an instance function name may start with a keyword") {
    parseNotFailed("instance Choose Int = fn ifElse(x) = x;; ;").map { module =>
      assertEquals(instance(module).members.map(_.binding.name), List("ifElse"))
    }
  }

  private def protocol(module: Module): Protocol = module.members
    .collectFirst { case declaration: Protocol =>
      declaration
    }
    .getOrElse(fail(s"Expected protocol, got ${module.members}"))

  private def instance(module: Module): ProtocolInstance = module.members
    .collectFirst { case declaration: ProtocolInstance =>
      declaration
    }
    .getOrElse(fail(s"Expected instance, got ${module.members}"))

  private def error(module: Module): ParsingMemberError = module.members
    .collectFirst { case error: ParsingMemberError =>
      error
    }
    .getOrElse(fail(s"Expected declaration error, got ${module.members}"))

  private def lambda(binding: Bnd): Lambda = binding.value.terms match
    case (lambda: Lambda) :: Nil => lambda
    case other => fail(s"Expected lambda, got $other")

  private def typeName(tpe: Type): String = tpe match
    case variable: TypeVariable => variable.name
    case ref:      TypeRef => ref.name
    case other => fail(s"Expected named type, got $other")

  private def written(node: FromSource, source: String): String =
    val span = node.spanOpt.getOrElse(fail("Expected source span"))
    source.substring(span.start.index, span.end.index)
