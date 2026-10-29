package mml.mmlclib.test

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.{CompilerApi, FrontEndApi, ParserApi}
import mml.mmlclib.ast.{
  BindOwnership,
  Bnd,
  CallableArity,
  Error,
  Lambda,
  Member,
  Module,
  Protocol,
  ProtocolSignature,
  ResolvablesIndex,
  SourceOrigin,
  TermTraversal,
  TypeRef
}
import mml.mmlclib.codegen.{CompilationMode, LlvmToolchain}
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.*
import mml.mmlclib.test.llvm.LlvmAssertions
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.sys.process.{Process, ProcessLogger}

/** Base trait for effectful tests; adds common MML specific assertions. */
trait BaseEffFunSuite extends CatsEffectSuite with LlvmAssertions:

  /** Assign identities and prepare references for grouping; duplicate checks precede this list. */
  protected val phasesBeforeExpressionRewriting: List[CompilerState => CompilerState] = List(
    IdAssigner.rewriteModule,
    TypeResolver.rewriteModule,
    ConstructorGenerator.rewriteModule,
    MemoryFunctionGenerator.rewriteModule,
    ProtocolChecker.rewriteModule,
    RefResolver.rewriteModule,
    OperatorGrouping.checkModule
  )

  /** Retain each phase boundary through grouping, expression rewriting, and capture analysis. */
  protected val phasesBeforeTypeChecking: List[CompilerState => CompilerState] =
    phasesBeforeExpressionRewriting ++ List(
      ExpressionRewriter.rewriteModule,
      Simplifier.rewriteModule,
      CaptureAnalyzer.rewriteModule
    )

  /** Prepare source fixtures at the type-checker boundary while retaining unresolved candidates. */
  protected def prepareForTypeChecking(
    source:                String,
    parsed:                Module,
    withStandardOperators: Boolean = true
  ): CompilerState =
    val withTypes = injectBasicTypes(parsed)
    val withOperators =
      if withStandardOperators then injectStandardOperators(withTypes) else withTypes
    val module  = injectCommonFunctions(withOperators)
    val initial = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
    val checked = DuplicateNameChecker.rewriteModule(initial)
    phasesBeforeTypeChecking.foldLeft(checked) { (state, phase) =>
      ResolvablesIndexer.rewriteModule(phase(state))
    }

  /** Select a fixture signature without depending on module declaration order. */
  protected def protocolSignature(
    module:       Module,
    protocolName: String,
    memberName:   String,
    arity:        Option[CallableArity] = None
  ): ProtocolSignature =
    val protocols = module.members.collect {
      case protocol: Protocol if protocol.name == protocolName => protocol
    }
    assertEquals(protocols.size, 1, s"Expected one fixture protocol named $protocolName")
    val signatures = protocols.head.signatures.filter { signature =>
      signature.meta.originalName == memberName && arity.forall(_ == signature.meta.arity)
    }
    assertEquals(signatures.size, 1, s"Expected one fixture signature named $memberName")
    signatures.head

  /** Follow the registry from a source signature to its concrete executable binding. */
  protected def protocolImplementation(
    state:        CompilerState,
    protocolName: String,
    typeName:     String,
    memberName:   String,
    arity:        Option[CallableArity] = None
  ): Bnd =
    val signature = protocolSignature(state.module, protocolName, memberName, arity)
    val index     = state.module.resolvables
    val protocol = state.module.members
      .collectFirst {
        case protocol: Protocol if protocol.signatures.exists(_.id == signature.id) => protocol
      }
      .getOrElse(fail(s"Expected owner of fixture signature $memberName"))
    val instance = state.protocols
      .instance(protocol.id.get, typeReference(typeName, index), index)
      .fold(error => fail(s"Expected registered fixture instance: $error"), identity)
    state.protocols
      .memberBinding(instance, signature.id.get, index)
      .fold(error => fail(s"Expected registered fixture member: $error"), identity)

  /** Select a uniquely named fixture declaration and preserve its resolved identity. */
  protected def typeReference(name: String, index: ResolvablesIndex): TypeRef =
    val declarations = index.resolvableTypes.values.filter(_.name == name).toList
    assertEquals(declarations.size, 1, s"Expected one fixture type named $name")
    val declaration = declarations.head
    assert(declaration.id.nonEmpty)
    TypeRef(SourceOrigin.Synth, declaration.name, declaration.id)

  /** Runs a test with a temporary source file and an initially absent output directory. */
  protected def withSourceFixture[A](
    source:   String,
    fileName: String
  )(use: (Path, CompilerConfig) => IO[A]): IO[A] =

    IO.blocking(Files.createTempDirectory("mml-source-fixture"))
      .bracket { directory =>
        val path   = directory.resolve(fileName)
        val config = CompilerConfig.default.copy(outputDir = directory.resolve("output"))
        IO.blocking(Files.writeString(path, source)).flatMap(_ => use(path, config))
      } { directory =>
        IO.blocking {
          val paths = Files.walk(directory)
          try
            paths
              .sorted(Comparator.reverseOrder[Path]())
              .forEach(path => Files.deleteIfExists(path))
          finally paths.close()
        }
      }

  /** Compile a fixture with ASan and assert a successful leak-checked run with exact stdout. */
  protected def runSanitizedProgram(
    source:         String,
    name:           String,
    optLevel:       Int,
    expectedOutput: List[String]
  ): IO[Unit] =

    withSourceFixture(source, s"$name.mml") { (path, initialConfig) =>
      val directory = path.getParent
      val binary    = directory.resolve("program")
      val config = initialConfig.copy(
        mode       = CompilationMode.Exe,
        outputName = binary.toString.some,
        optLevel   = optLevel,
        asan       = true
      )

      for
        compiled <- CompilerApi.compileNativeQuiet(path, config)
        _ = compiled.fold(error => fail(error), state => assertEquals(state.errors, Vector.empty))
        _ <- programExits(
          List("env", "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1", binary.toString),
          directory,
          expectedCode   = 0,
          expectedOutput = expectedOutput.some
        )
      yield ()
    }

  /** Assert success through the compiler's shared toolchain command execution. */
  protected def commandNotFailed(command: List[String], workingDir: Path): IO[Unit] =
    LlvmToolchain
      .executeCommand(command, "Fixture command failed", workingDir, verbose = false)
      .map(_.fold(error => fail(error.message), code => assertEquals(code, 0)))

  /** Assert a fixture's exit code, optional stdout, and diagnostic text on stderr. */
  protected def programExits(
    command:        List[String],
    workingDir:     Path,
    expectedCode:   Int,
    expectedOutput: Option[List[String]] = None,
    expectedError:  Option[String]       = None
  ): IO[Unit] =
    IO.blocking {
      // The child's stdout and stderr callbacks can run concurrently.
      val stdout = new StringBuffer
      val stderr = new StringBuffer
      val logger = ProcessLogger(
        line =>
          stdout.append(s"$line\n")
          ()
        ,
        line =>
          stderr.append(s"$line\n")
          ()
      )
      val code       = Process(command, workingDir.toFile).!(logger)
      val diagnostic = s"${command.mkString(" ")}\n$stdout$stderr"
      assertEquals(code, expectedCode, diagnostic)
      expectedOutput.foreach(expected =>
        assertEquals(stdout.toString.linesIterator.toList, expected, diagnostic)
      )
      expectedError.foreach(expected => assert(stderr.toString.contains(expected), diagnostic))
    }

  final case class SemanticResult(module: Module, errors: List[SemanticError])

  /** Returns the unique resolved binding named within a module member. */
  protected def resolvedBindingId(module: Module, owner: String, name: String): String =

    val member = module.members
      .collectFirst { case binding: Bnd if binding.name == owner => binding }
      .getOrElse(fail(s"Expected member $owner"))
    val ids = TermTraversal
      .collect(member.value) {
        case lambda:  Lambda => lambda.params.filter(_.name == name).flatMap(_.id)
        case binding: BindOwnership if binding.valueParam.name == name =>
          binding.valueParam.id.toList
      }
      .flatten
      .distinct

    assertEquals(ids.size, 1, s"Expected one resolved binding for $owner.$name")
    ids.head

  private def containsErrorNode(module: Module): Boolean = {
    def checkMembers(members: List[Member]): Boolean =
      members.exists {
        case _: Error => true
        case _ => false
      }

    checkMembers(module.members)
  }

  private def collectErrors(module: Module): List[Error] = {
    module.members.collect { case error: Error =>
      error
    }
  }

  def parseNotFailed(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[Module] = {

    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        assert(
          !containsErrorNode(module),
          msg.getOrElse(
            s"Failed: found Error nodes:\n ${prettyPrintAst(module)}"
          )
        )
        module
      case Left(error) =>
        fail(msg.getOrElse("Parser Failed: .") + s"\n$error")
    }
  }

  def parseFailed(
    source: String,
    name:   String         = "TestFail",
    msg:    Option[String] = None
  ): IO[Unit] = {
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        assert(
          containsErrorNode(module),
          msg.getOrElse(s"Expected Error nodes but found none. ${prettyPrintAst(module)} ")
        )
      case Left(_) =>
      // pass
    }
  }

  def parseFailedWithErrors(
    source: String,
    name:   String         = "TestErrors",
    msg:    Option[String] = None
  ): IO[List[Error]] = {
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) =>
        val errors = collectErrors(module)
        if errors.isEmpty then
          import mml.mmlclib.util.prettyprint.ast.*
          prettyPrintAst(module)
        assert(
          errors.nonEmpty,
          msg.getOrElse(s"Expected Error nodes but found none. ${prettyPrintAst(module)}")
        )
        errors // Return just the errors for inspection
      case Left(error) =>
        fail(
          msg.getOrElse(
            "Parser should not fail completely, but should create error nodes."
          ) + s"\n$error"
        )
    }
  }

  def semNotFailed(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[Module] =
    semStateNotFailed(source, name, msg).map(_.module)

  def semStateNotFailed(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[CompilerState] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) =>
        if state.errors.nonEmpty then
          fail(msg.getOrElse("Semantic Failed: .") + s"\n${state.errors}")
        else
          val module = state.module
          assert(
            !containsErrorNode(module),
            msg.getOrElse(
              s"Failed: found Error nodes:\n ${prettyPrintAst(module)}"
            )
          )
          state
      case Left(error) =>
        fail(msg.getOrElse("Semantic Failed: .") + s"\n${error}")
    }

  def semFailed(
    source: String,
    name:   String         = "TestFail",
    msg:    Option[String] = None
  ): IO[Unit] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) =>
        assert(
          state.errors.nonEmpty || containsErrorNode(state.module),
          msg.getOrElse(s"Expected errors but found none. ${prettyPrintAst(state.module)} ")
        )
      case Left(_) =>
      // pass
    }

  def semState(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[SemanticResult] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) => SemanticResult(state.module, state.semanticErrors.toList)
      case Left(error) =>
        fail(
          msg.getOrElse("Semantic pipeline failed before producing a state.") + s"\n$error"
        )
    }

  /** Semantic results with the same diagnostic selection used by CLI and editor presentation. */
  def semVisibleState(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[SemanticResult] =
    FrontEndApi.compile(source, name).value.map {
      case Right(state) =>
        SemanticResult(
          state.module,
          state.visibleErrors.collect { case e: SemanticError => e }.toList
        )
      case Left(error) =>
        fail(msg.getOrElse("Semantic pipeline failed before producing a state.") + s"\n$error")
    }

  /** Parse source code without asserting on MemberErrors. Useful for testing phases that
    * specifically deal with MemberErrors
    */
  def justParse(
    source: String,
    name:   String         = "Test",
    msg:    Option[String] = None
  ): IO[Module] =
    ParserApi.parseModuleString(source, name).value.map {
      case Right(module) => module
      case Left(error) => fail(msg.getOrElse("Parser Failed: ") + s"\n$error")
    }

  protected def compileAndGenerate(
    source: String,
    name:   String         = "Test",
    config: CompilerConfig = CompilerConfig.default
  ): IO[String] =
    FrontEndApi.compile(source, name, config).value.flatMap {
      case Right(state) =>
        if state.errors.nonEmpty then fail(s"Compilation failed: ${state.errors}")
        else
          val validated = CodegenStage.validate(state)
          if validated.hasErrors then fail(s"Validation failed: ${validated.errors}")
          else
            CodegenStage.emitIrOnly(validated).flatMap { codegenState =>
              codegenState.llvmIr match
                case Some(ir) => IO.pure(ir)
                case None => fail(s"CodeGen failed: ${codegenState.errors}")
            }
      case Left(error) =>
        fail(s"Compilation failed: $error")
    }
