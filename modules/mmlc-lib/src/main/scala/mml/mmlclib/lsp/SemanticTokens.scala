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
    module.members.flatMap(collectFromMember(_, module))

  /** Collect tokens from a member. */
  private def collectFromMember(member: Member, module: Module): List[RawToken] =
    member match
      case bnd: Bnd =>
        collectFromBnd(bnd, module)
      case td: TypeDef =>
        collectFromTypeDef(td)
      case ta: TypeAlias =>
        collectFromTypeAlias(ta)
      case ts: TypeStruct =>
        collectFromTypeStruct(ts)
      case _: DuplicateMember | _: InvalidMember | _: ParsingMemberError | _: ParsingIdError =>
        Nil

  /** Collect tokens from a binding (let, fn, op). */
  private def collectFromBnd(bnd: Bnd, module: Module): List[RawToken] =
    if !bnd.source.isFromSource then return Nil

    val (keywordLen, nameTokenType) = bnd.meta match
      case Some(meta) =>
        meta.origin match
          case BindingOrigin.Function | BindingOrigin.Constructor | BindingOrigin.Destructor =>
            (2, TokenType.Function) // "fn"
          case BindingOrigin.Operator => (2, TokenType.Operator) // "op"
      case None =>
        val tokenType =
          if hasCallableType(bnd.typeSpec.orElse(bnd.typeAsc), module) then TokenType.Function
          else TokenType.Variable
        (3, tokenType) // "let"

    val nameSpanOpt = bnd.nameNode.source.spanOpt
    val keywordToken = nameSpanOpt.flatMap { nameSpan =>
      val keywordCol = nameSpan.start.col - 1 - keywordLen
      tokenAtPos(nameSpan.start.line, keywordCol, keywordLen, TokenType.Keyword)
    }

    val nameToken = nameSpanOpt.flatMap { nameSpan =>
      tokenAt(
        nameSpan,
        nameTokenType,
        modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
      )
    }

    val bodyTokens = collectFromExpr(bnd.value, module)

    keywordToken.toList ++ nameToken.toList ++ bodyTokens

  /** Collect tokens from a type definition. */
  private def collectFromTypeDef(td: TypeDef): List[RawToken] =
    val keyword = td.spanOpt.flatMap(span => keywordAt(span.start, 4)) // "type"
    val name = td.nameNode.source.spanOpt.flatMap { span =>
      tokenAt(
        span,
        TokenType.Type,
        modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
      )
    }
    keyword.toList ++ name.toList ++ td.typeSpec.toList.flatMap(collectFromType)

  /** Collect tokens from a type alias. */
  private def collectFromTypeAlias(ta: TypeAlias): List[RawToken] =
    val keyword = ta.spanOpt.flatMap(span => keywordAt(span.start, 4)) // "type"
    val name = ta.nameNode.source.spanOpt.flatMap { span =>
      tokenAt(
        span,
        TokenType.Type,
        modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
      )
    }
    keyword.toList ++ name.toList ++ collectFromType(ta.typeRef)

  /** Collect tokens from a struct type. */
  private def collectFromTypeStruct(ts: TypeStruct): List[RawToken] =
    val keyword = ts.spanOpt.flatMap(span => keywordAt(span.start, 6)) // "struct"
    val name = ts.nameNode.source.spanOpt.flatMap { span =>
      tokenAt(
        span,
        TokenType.Type,
        modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
      )
    }
    val fields = ts.fields.toList.flatMap(f => collectFromType(f.typeSpec))
    keyword.toList ++ name.toList ++ fields

  /** Collect tokens from an expression. */
  private def collectFromExpr(expr: Expr, module: Module): List[RawToken] =
    expr.terms.flatMap(collectFromTerm(_, module))

  /** Collect tokens from a term. */
  private def collectFromTerm(term: Term, module: Module): List[RawToken] =
    term match
      case ref: Ref =>
        collectFromRef(ref, module)

      case app: App =>
        collectFromApp(app, module)

      case lambda: Lambda =>
        collectFromLambda(lambda, module)

      case cond: Cond =>
        collectFromCond(cond, module)

      case lit: LiteralInt =>
        lit.spanOpt.flatMap(tokenAt(_, TokenType.Number)).toList

      case lit: LiteralFloat =>
        lit.spanOpt.flatMap(tokenAt(_, TokenType.Number)).toList

      case lit: LiteralString =>
        lit.spanOpt.flatMap(tokenAt(_, TokenType.String)).toList

      case lit: LiteralBool =>
        // true/false are keywords
        lit.spanOpt.flatMap(tokenAt(_, TokenType.Keyword)).toList

      case _: LiteralUnit =>
        Nil // () is just punctuation

      case group: TermGroup =>
        collectFromExpr(group.inner, module)

      case tuple: Tuple =>
        tuple.elements.toList.flatMap(collectFromExpr(_, module))

      case expr: Expr =>
        collectFromExpr(expr, module)

      case _: Placeholder | _: Hole | _: DataConstructor | _: DataDestructor | _: NativeImpl |
          _: TermError | _: InvalidExpression =>
        Nil

  /** Collect tokens from a reference. */
  private def collectFromRef(ref: Ref, module: Module): List[RawToken] =
    if !ref.source.isFromSource then return Nil
    val resolvables = module.resolvables
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
        val tokenType       = refTokenType(ref, resolved, module)
        val qualifierTokens = ref.qualifier.toList.flatMap(collectFromTerm(_, module))
        val nameToken       = refToken(ref, tokenType).toList
        qualifierTokens ++ nameToken

  /** Collect tokens from a function application. */
  private def collectFromApp(app: App, module: Module): List[RawToken] =
    val fnTokens = app.fn match
      case ref:    Ref => collectFromRef(ref, module)
      case nested: App => collectFromApp(nested, module)
      case lambda: Lambda => collectFromLambda(lambda, module)
    fnTokens ++ collectFromExpr(app.arg, module)

  /** Collect tokens from a lambda. */
  private def collectFromLambda(lambda: Lambda, module: Module): List[RawToken] =
    val paramTokens = lambda.params.flatMap { param =>
      if param.source.isFromSource then
        val tokenType =
          if hasCallableType(param.typeSpec.orElse(param.typeAsc), module) then TokenType.Function
          else TokenType.Parameter
        val nameToken = param.nameNode.source.spanOpt.flatMap { span =>
          tokenAt(
            span,
            tokenType,
            modifiers = Set(TokenModifier.Declaration, TokenModifier.Readonly)
          )
        }
        val typeTokens = param.typeAsc.toList.flatMap(collectFromType)
        nameToken.toList ++ typeTokens
      else Nil
    }
    paramTokens ++ collectFromExpr(lambda.body, module)

  /** Collect tokens from a conditional. */
  private def collectFromCond(cond: Cond, module: Module): List[RawToken] =
    // "if" at start of cond span
    val ifKeyword =
      cond.spanOpt.flatMap { span =>
        if isElifLoweredCond(cond) then None else keywordAt(span.start, 2)
      }

    // "then" is between cond.cond.end and cond.ifTrue.start
    val thenKeyword = for
      condSpan <- cond.cond.spanOpt
      ifTrueSpan <- cond.ifTrue.spanOpt
      kw <- keywordBetween(condSpan.end, ifTrueSpan.start, 4)
    yield kw

    // "else" or "elif" is between cond.ifTrue.end and cond.ifFalse.start
    val elseKeyword = for
      ifTrueSpan <- cond.ifTrue.spanOpt
      ifFalseSpan <- cond.ifFalse.spanOpt
      kw <- keywordBetween(ifTrueSpan.end, ifFalseSpan.start, 4)
    yield kw

    val condTokens    = collectFromExpr(cond.cond, module)
    val ifTrueTokens  = collectFromExpr(cond.ifTrue, module)
    val ifFalseTokens = collectFromExpr(cond.ifFalse, module)

    ifKeyword.toList ++
      thenKeyword.toList ++
      elseKeyword.toList ++
      condTokens ++
      ifTrueTokens ++
      ifFalseTokens

  private def isElifLoweredCond(cond: Cond): Boolean =
    (for
      condSpan <- cond.spanOpt
      condExprSpan <- cond.cond.spanOpt
    yield condSpan.start.line == condExprSpan.start.line &&
      condSpan.start.col == condExprSpan.start.col &&
      condSpan.start.index == condExprSpan.start.index).getOrElse(false)

  /** Collect tokens from a type reference. */
  private def collectFromType(typ: Type): List[RawToken] =
    typ match
      case tr: TypeRef =>
        tr.spanOpt
          .flatMap(tokenAt(_, TokenType.Type, lengthOverride = Some(tr.name.length)))
          .toList
      case tf: TypeFn =>
        tf.paramTypes.toList.flatMap(collectFromType) ++ collectFromType(tf.returnType)
      case tt: TypeTuple =>
        tt.elements.flatMap(collectFromType)
      case ta: TypeApplication =>
        collectFromType(ta.base) ++ ta.args.flatMap(collectFromType)
      case tv: TypeVariable =>
        tv.spanOpt
          .flatMap(tokenAt(_, TokenType.Type, lengthOverride = Some(tv.name.length)))
          .toList
      case ts: TypeScheme =>
        collectFromType(ts.bodyType)
      case tor: TypeOpenRecord =>
        tor.fields.flatMap((_, t) => collectFromType(t))
      case _: NativePrimitive | _: NativePointer | _: NativeStruct | _: TypeUnit | _: TypeGroup |
          _: Union | _: Intersection | _: TypeRefinement | _: TypeStruct | _: InvalidType =>
        Nil

  private def refTokenType(ref: Ref, resolved: Resolvable, module: Module): TokenType =
    resolved match
      case _: TypeDef | _: TypeAlias | _: TypeStruct =>
        TokenType.Type
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Operator) =>
        TokenType.Operator
      case _ if refHasCallableType(ref, resolved, module) =>
        TokenType.Function
      case _: FnParam =>
        TokenType.Parameter
      case bnd: Bnd =>
        bnd.meta match
          case Some(meta) =>
            meta.origin match
              case BindingOrigin.Function | BindingOrigin.Constructor | BindingOrigin.Destructor =>
                TokenType.Function
              case BindingOrigin.Operator =>
                TokenType.Operator
          case None =>
            TokenType.Variable
      case _: Field =>
        TokenType.Variable

  private def refHasCallableType(ref: Ref, resolved: Resolvable, module: Module): Boolean =
    val typeSpec =
      ref.typeSpec.orElse {
        resolved match
          case param: FnParam =>
            param.typeSpec.orElse(param.typeAsc)
          case decl: Decl =>
            decl.typeSpec.orElse(decl.typeAsc)
          case field: Field =>
            Some(field.typeSpec)
          case _ =>
            None
      }
    hasCallableType(typeSpec, module)

  private def hasCallableType(typeSpec: Option[Type], module: Module): Boolean =
    typeSpec.flatMap(resolveCallableType(_, module)).isDefined

  private def resolveCallableType(typeSpec: Type, module: Module): Option[TypeFn] =
    typeSpec match
      case tf: TypeFn =>
        Some(tf)
      case TypeGroup(_, types) if types.size == 1 =>
        resolveCallableType(types.head, module)
      case TypeScheme(_, _, bodyType) =>
        resolveCallableType(bodyType, module)
      case TypeRef(_, name, resolvedId, _) =>
        val resolved = resolvedId
          .flatMap(module.resolvables.lookupType)
          .orElse(module.members.collectFirst {
            case ta: TypeAlias if ta.name == name => ta
          })
        resolved match
          case Some(ta: TypeAlias) =>
            ta.typeSpec
              .flatMap(resolveCallableType(_, module))
              .orElse(resolveCallableType(ta.typeRef, module))
          case _ =>
            None
      case _ =>
        None

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
      ref.spanOpt.flatMap { span =>
        ref.qualifier match
          case Some(_) =>
            val end      = span.end
            val startCol = end.col - length
            tokenAtPos(end.line, startCol, length, tokenType)
          case None =>
            tokenAtPos(span.start.line, span.start.col, length, tokenType)
      }

  private def tokenAtPos(
    line:      Int,
    col:       Int,
    length:    Int,
    tokenType: TokenType,
    modifiers: Set[TokenModifier] = Set.empty
  ): Option[RawToken] =
    if line <= 0 || col <= 0 || length <= 0 then None
    else Some(RawToken(line, col, length, tokenType, modifiers))

  /** Delta encode tokens for LSP protocol. Each token is: [deltaLine, deltaCol, length, tokenType,
    * tokenModifiers]
    *
    * LSP uses 0-based line/column. Our RawToken uses 1-based (from source spans).
    */
  private def deltaEncode(tokens: List[RawToken]): Array[Int] =
    val (_, _, builder) =
      tokens.foldLeft((1, 1, Array.newBuilder[Int])) { case ((prevLine, prevCol, result), token) =>
        val deltaLine = token.line - prevLine
        val deltaCol =
          if deltaLine == 0 then token.col - prevCol
          else token.col - 1
        val modifierBits = token.modifiers.foldLeft(0) { (acc, mod) =>
          acc | (1 << mod.ordinal)
        }

        result += deltaLine
        result += deltaCol
        result += token.length
        result += token.tokenType.ordinal
        result += modifierBits

        (token.line, token.col, result)
      }

    builder.result()
