package mml.mmlclib.lsp

import mml.mmlclib.ast.*

/** Semantic token types for MML. Order matters - index is used in LSP protocol. */
enum TokenType(val name: String) derives CanEqual:
  case Function  extends TokenType("function")
  case Operator  extends TokenType("operator")
  case Parameter extends TokenType("parameter")
  case Variable  extends TokenType("variable")
  case Type      extends TokenType("type")
  case String    extends TokenType("string")
  case Number    extends TokenType("number")
  case Keyword   extends TokenType("keyword")

/** Semantic token modifiers for MML. Bit flags - index determines bit position. */
enum TokenModifier(val name: String) derives CanEqual:
  case Declaration extends TokenModifier("declaration")
  case Readonly    extends TokenModifier("readonly")

/** A raw token before delta encoding. */
case class RawToken(
  line:      Int,
  col:       Int,
  length:    Int,
  tokenType: TokenType,
  modifiers: Set[TokenModifier]
)

object SemanticTokens:

  /** The legend advertised to the client. */
  val legend: SemanticTokensLegend = SemanticTokensLegend(
    tokenTypes     = TokenType.values.toList.map(_.name),
    tokenModifiers = TokenModifier.values.toList.map(_.name)
  )

  /** Compute semantic tokens for a module. */
  def compute(module: Module): SemanticTokensResult =
    val tokens = collectTokens(module)
    val sorted = tokens.sortBy(t => (t.line, t.col))
    SemanticTokensResult(deltaEncode(sorted))

  /** Collect all tokens from the module. */
  private def collectTokens(module: Module): List[RawToken] =
    module.members.flatMap(collectFromMember(_, module.resolvables))

  /** Collect tokens from a member. */
  private def collectFromMember(member: Member, resolvables: ResolvablesIndex): List[RawToken] =
    member match
      case bnd: Bnd =>
        collectFromBnd(bnd, resolvables)
      case td: TypeDef =>
        collectFromTypeDef(td)
      case ta: TypeAlias =>
        collectFromTypeAlias(ta)
      case ts: TypeStruct =>
        collectFromTypeStruct(ts)
      case _: DuplicateMember | _: InvalidMember | _: ParsingMemberError | _: ParsingIdError =>
        Nil

  /** Collect tokens from a binding (let, fn, op). Note: bnd.span.start points to the NAME, not the
    * keyword.
    */
  private def collectFromBnd(bnd: Bnd, resolvables: ResolvablesIndex): List[RawToken] =
    val (keywordLen, nameTokenType) = bnd.meta match
      case Some(meta) =>
        meta.origin match
          case BindingOrigin.Function | BindingOrigin.Constructor | BindingOrigin.Destructor =>
            (2, TokenType.Function) // "fn"
          case BindingOrigin.Operator => (2, TokenType.Operator) // "op"
      case None =>
        (3, TokenType.Variable) // "let"

    // Keyword is BEFORE the name: col = name_col - 1 (space) - keyword_len
    val keywordCol = bnd.span.start.col - 1 - keywordLen
    val keywordToken =
      tokenAtPos(bnd.span.start.line, keywordCol, keywordLen, TokenType.Keyword)

    // Name token: span.start is already at the name position
    val nameLen = bnd.meta match
      case Some(meta) if meta.origin == BindingOrigin.Operator => meta.originalName.length
      case _ => bnd.name.length

    val nameToken =
      tokenAtPos(
        line      = bnd.span.start.line,
        col       = bnd.span.start.col,
        length    = nameLen,
        tokenType = nameTokenType,
        modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
      )

    val bodyTokens = collectFromExpr(bnd.value, resolvables)

    keywordToken.toList ++ nameToken.toList ++ bodyTokens

  /** Collect tokens from a type definition. */
  private def collectFromTypeDef(td: TypeDef): List[RawToken] =
    val keyword = keywordAt(td.span.start, 4) // "type"
    val name    = declarationToken(td.span, td.name, TokenType.Type)
    keyword.toList ++ name.toList ++ td.typeSpec.toList.flatMap(collectFromType)

  /** Collect tokens from a type alias. */
  private def collectFromTypeAlias(ta: TypeAlias): List[RawToken] =
    val keyword = keywordAt(ta.span.start, 4) // "type"
    val name    = declarationToken(ta.span, ta.name, TokenType.Type)
    keyword.toList ++ name.toList ++ collectFromType(ta.typeRef)

  /** Collect tokens from a struct type. */
  private def collectFromTypeStruct(ts: TypeStruct): List[RawToken] =
    val keyword = keywordAt(ts.span.start, 6) // "struct"
    val name = tokenAtPos(
      line      = ts.span.start.line,
      col       = ts.span.start.col + 7, // 6 ("struct") + 1 (space)
      length    = ts.name.length,
      tokenType = TokenType.Type,
      modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
    )
    val fields = ts.fields.toList.flatMap(f => collectFromType(f.typeSpec))
    keyword.toList ++ name.toList ++ fields

  /** Collect tokens from an expression. */
  private def collectFromExpr(expr: Expr, resolvables: ResolvablesIndex): List[RawToken] =
    expr.terms.flatMap(collectFromTerm(_, resolvables))

  /** Collect tokens from a term. */
  private def collectFromTerm(term: Term, resolvables: ResolvablesIndex): List[RawToken] =
    term match
      case ref: Ref =>
        collectFromRef(ref, resolvables)

      case app: App =>
        collectFromApp(app, resolvables)

      case lambda: Lambda =>
        collectFromLambda(lambda, resolvables)

      case cond: Cond =>
        collectFromCond(cond, resolvables)

      case lit: LiteralInt =>
        tokenAt(lit.span, TokenType.Number).toList

      case lit: LiteralFloat =>
        tokenAt(lit.span, TokenType.Number).toList

      case lit: LiteralString =>
        tokenAt(lit.span, TokenType.String).toList

      case lit: LiteralBool =>
        // true/false are keywords
        tokenAt(lit.span, TokenType.Keyword).toList

      case _: LiteralUnit =>
        Nil // () is just punctuation

      case group: TermGroup =>
        collectFromExpr(group.inner, resolvables)

      case tuple: Tuple =>
        tuple.elements.toList.flatMap(collectFromExpr(_, resolvables))

      case expr: Expr =>
        collectFromExpr(expr, resolvables)

      case _: Placeholder | _: Hole | _: DataConstructor | _: DataDestructor | _: NativeImpl |
          _: TermError | _: InvalidExpression =>
        Nil

  /** Collect tokens from a reference. */
  private def collectFromRef(ref: Ref, resolvables: ResolvablesIndex): List[RawToken] =
    val resolvedOpt =
      ref.resolvedId
        .flatMap(resolvables.lookup)
        .orElse {
          val candidates = ref.candidateIds.flatMap(resolvables.lookup)
          if candidates.size == 1 then candidates.headOption else None
        }
    resolvedOpt match
      case None => Nil
      case Some(resolved) =>
        val tokenType = resolved match
          case _:   FnParam => TokenType.Parameter
          case bnd: Bnd =>
            bnd.meta match
              case Some(meta) =>
                meta.origin match
                  case BindingOrigin.Function | BindingOrigin.Constructor |
                      BindingOrigin.Destructor =>
                    TokenType.Function
                  case BindingOrigin.Operator => TokenType.Operator
              case None => TokenType.Variable
          case _: TypeDef | _: TypeAlias | _: TypeStruct => TokenType.Type
        val qualifierTokens = ref.qualifier.toList.flatMap(collectFromTerm(_, resolvables))
        val nameToken       = refToken(ref, tokenType).toList
        qualifierTokens ++ nameToken

  /** Collect tokens from a function application. */
  private def collectFromApp(app: App, resolvables: ResolvablesIndex): List[RawToken] =
    val fnTokens = app.fn match
      case ref:    Ref => collectFromRef(ref, resolvables)
      case nested: App => collectFromApp(nested, resolvables)
      case lambda: Lambda => collectFromLambda(lambda, resolvables)
    fnTokens ++ collectFromExpr(app.arg, resolvables)

  /** Collect tokens from a lambda. */
  private def collectFromLambda(lambda: Lambda, resolvables: ResolvablesIndex): List[RawToken] =
    val paramTokens = lambda.params.flatMap { param =>
      // Skip params with invalid/empty spans or synthetic names (e.g., statement chain params)
      if param.span.start.line > 0 && param.span.start.col > 0 && param.source.isFromSource then
        val nameToken = RawToken(
          line      = param.span.start.line,
          col       = param.span.start.col,
          length    = param.name.length,
          tokenType = TokenType.Parameter,
          modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
        )
        val typeTokens = param.typeAsc.toList.flatMap(collectFromType)
        nameToken :: typeTokens
      else Nil
    }
    paramTokens ++ collectFromExpr(lambda.body, resolvables)

  /** Collect tokens from a conditional. */
  private def collectFromCond(cond: Cond, resolvables: ResolvablesIndex): List[RawToken] =
    // "if" at start of cond span
    val ifKeyword = keywordAt(cond.span.start, 2)

    // "then" is between cond.cond.end and cond.ifTrue.start
    val thenKeyword = keywordBetween(cond.cond.span.end, cond.ifTrue.span.start, 4)

    // "else" or "elif" is between cond.ifTrue.end and cond.ifFalse.start
    val elseKeyword = keywordBetween(cond.ifTrue.span.end, cond.ifFalse.span.start, 4)

    // "end" is at the end of the cond span (if present)
    val endKeyword = keywordAtEnd(cond.span, cond.ifFalse.span, 3)

    val condTokens    = collectFromExpr(cond.cond, resolvables)
    val ifTrueTokens  = collectFromExpr(cond.ifTrue, resolvables)
    val ifFalseTokens = collectFromExpr(cond.ifFalse, resolvables)

    ifKeyword.toList ++
      thenKeyword.toList ++
      elseKeyword.toList ++
      endKeyword.toList ++
      condTokens ++
      ifTrueTokens ++
      ifFalseTokens

  /** Collect tokens from a type reference. */
  private def collectFromType(typ: Type): List[RawToken] =
    typ match
      case tr: TypeRef =>
        tokenAt(tr.span, TokenType.Type, lengthOverride = Some(tr.name.length)).toList
      case tf: TypeFn =>
        tf.paramTypes.flatMap(collectFromType) ++ collectFromType(tf.returnType)
      case tt: TypeTuple =>
        tt.elements.flatMap(collectFromType)
      case ta: TypeApplication =>
        collectFromType(ta.base) ++ ta.args.flatMap(collectFromType)
      case tv: TypeVariable =>
        tokenAt(tv.span, TokenType.Type, lengthOverride = Some(tv.name.length)).toList
      case ts: TypeScheme =>
        collectFromType(ts.bodyType)
      case tor: TypeOpenRecord =>
        tor.fields.flatMap((_, t) => collectFromType(t))
      case _: NativePrimitive | _: NativePointer | _: NativeStruct | _: TypeUnit | _: TypeGroup |
          _: Union | _: Intersection | _: TypeRefinement | _: TypeStruct | _: InvalidType =>
        Nil

  // --- Helper functions ---

  /** Create a keyword token at a source point. */
  private def keywordAt(point: SrcPoint, length: Int): Option[RawToken] =
    tokenAtPos(point.line, point.col, length, TokenType.Keyword)

  /** Find a keyword between two spans. */
  private def keywordBetween(
    after:  SrcPoint,
    before: SrcPoint,
    length: Int
  ): Option[RawToken] =
    if after.line != before.line then None
    else
      val col = after.col + 1
      if before.col >= col + length && col > 0 then
        tokenAtPos(after.line, col, length, TokenType.Keyword)
      else None

  /** Find "end" keyword at the end of a cond span. */
  private def keywordAtEnd(
    condSpan:     SrcSpan,
    lastExprSpan: SrcSpan,
    length:       Int
  ): Option[RawToken] =
    // "end" should be at the very end of condSpan
    val endLine = condSpan.end.line
    val endCol  = condSpan.end.col - length + 1
    if endCol > 0 && (endLine > lastExprSpan.end.line ||
        (endLine == lastExprSpan.end.line && endCol > lastExprSpan.end.col))
    then tokenAtPos(endLine, endCol, length, TokenType.Keyword)
    else None

  /** Create a token at a span. */
  private def tokenAt(
    span:           SrcSpan,
    tokenType:      TokenType,
    modifiers:      Set[TokenModifier] = Set.empty,
    lengthOverride: Option[Int]        = None
  ): Option[RawToken] =
    val length =
      lengthOverride.getOrElse {
        if span.start.line == span.end.line then span.end.col - span.start.col
        else 0
      }
    tokenAtPos(span.start.line, span.start.col, length, tokenType, modifiers)

  private def refToken(ref: Ref, tokenType: TokenType): Option[RawToken] =
    val length = ref.name.length
    if length <= 0 then None
    else
      ref.qualifier match
        case Some(_) =>
          val end      = ref.span.end
          val startCol = end.col - length
          tokenAtPos(end.line, startCol, length, tokenType)
        case None =>
          tokenAtPos(ref.span.start.line, ref.span.start.col, length, tokenType)

  private def tokenAtPos(
    line:      Int,
    col:       Int,
    length:    Int,
    tokenType: TokenType,
    modifiers: Set[TokenModifier] = Set.empty
  ): Option[RawToken] =
    if line <= 0 || col <= 0 || length <= 0 then None
    else Some(RawToken(line, col, length, tokenType, modifiers))

  /** Create a declaration token, trying to find the name after the keyword. */
  private def declarationToken(
    span:      SrcSpan,
    name:      String,
    tokenType: TokenType
  ): Option[RawToken] =
    // Assume name follows keyword + whitespace
    // For "type Foo", "let x", "fn bar", etc.
    // This is an approximation - real implementation would need source text
    tokenAtPos(
      line      = span.start.line,
      col       = span.start.col + keywordLengthFor(tokenType) + 1,
      length    = name.length,
      tokenType = tokenType,
      modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
    )

  private def keywordLengthFor(tokenType: TokenType): Int =
    tokenType match
      case TokenType.Type => 4 // "type"
      case TokenType.Function => 2 // "fn"
      case TokenType.Operator => 2 // "op"
      case TokenType.Variable => 3 // "let"
      case _ => 0

  /** Delta encode tokens for LSP protocol. Each token is: [deltaLine, deltaCol, length, tokenType,
    * tokenModifiers]
    *
    * LSP uses 0-based line/column. Our RawToken uses 1-based (from source spans).
    */
  private def deltaEncode(tokens: List[RawToken]): Array[Int] =
    val result   = Array.newBuilder[Int]
    var prevLine = 1 // 1-based
    var prevCol  = 1 // 1-based

    for token <- tokens do
      // Convert to deltas (result is 0-based)
      val deltaLine = token.line - prevLine
      val deltaCol =
        if deltaLine == 0 then token.col - prevCol
        else token.col - 1 // Absolute position for new lines (0-based)

      val modifierBits = token.modifiers.foldLeft(0) { (acc, mod) =>
        acc | (1 << mod.ordinal)
      }

      result += deltaLine
      result += deltaCol
      result += token.length
      result += token.tokenType.ordinal
      result += modifierBits

      prevLine = token.line
      prevCol  = token.col

    result.result()
