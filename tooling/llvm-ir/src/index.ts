import { DocumentSymbol, Location, Position, Range, SymbolKind } from 'vscode-languageserver-types';
export type { DocumentSymbol, Range } from 'vscode-languageserver-types';

export type TextLine = { lineNumber: number; text: string };
export type TextDocument = { uri: string; lineCount: number; lineAt(line: number): TextLine };

/** Adapt source text to the line-oriented LLVM index. Columns use UTF-16 units. */
export function sourceDocument(uri: string, text: string): TextDocument {
    const lines = text.split(/\r?\n/);
    return { uri, lineCount: lines.length, lineAt: line => ({ lineNumber: line, text: lines[line] }) };
}

type LlvmSymbolKind =
    | 'attribute'
    | 'comdat'
    | 'function'
    | 'global'
    | 'label'
    | 'local'
    | 'metadata'
    | 'type';

type LlvmSymbol = {
    key: string;
    displayName: string;
    declaration: string;
    detail: string;
    kind: LlvmSymbolKind;
    line: number;
    range: Range;
    selectionRange: Range;
};

type FunctionScope = {
    endLine: number;
    functionSymbol: LlvmSymbol;
    locals: Map<string, LlvmSymbol>;
    outlineSymbol: DocumentSymbol;
    outlineChildren: DocumentSymbol[];
    startLine: number;
};

type LlvmIndex = {
    functionScopes: FunctionScope[];
    globals: Map<string, LlvmSymbol>;
    outlineSymbols: DocumentSymbol[];
};

type ResolvedSymbol = {
    declaration: LlvmSymbol;
    index: LlvmIndex;
    localScope: FunctionScope | undefined;
    token: string;
};

const TOKEN_RE =
    /%"(?:[^"\\]|\\.)+"|@\"(?:[^\"\\]|\\.)+\"|%[-$._A-Za-z0-9]+|@[-$._A-Za-z0-9]+|![-$._A-Za-z0-9]+|#\d+|\$[-$._A-Za-z0-9]+/g;

/** Ignore comments and string contents while retaining quoted LLVM identifiers. */
function symbolTokens(text: string): RegExpMatchArray[] {
    const lexical = new RegExp(`;.*$|${TOKEN_RE.source}|"(?:[^"\\\\]|\\\\.)*"`, 'g');
    return Array.from(text.matchAll(lexical)).filter(match => /^[%@!#$]/.test(match[0]));
}

const DECLARATION_RE =
    /^\s*(%"(?:[^"\\]|\\.)+"|@\"(?:[^\"\\]|\\.)+\"|%[-$._A-Za-z0-9]+|@[-$._A-Za-z0-9]+|![-$._A-Za-z0-9]+|\$[-$._A-Za-z0-9]+)\s*=\s*(.*)$/;

const FUNCTION_RE =
    /^\s*(?:define|declare)\b.*?(@\"(?:[^\"\\]|\\.)+\"|@[-$._A-Za-z0-9]+)\s*\(/;

const ATTRIBUTE_RE = /^\s*attributes\s+(#\d+)\s*=/;

const LABEL_RE = /^\s*(?:"((?:[^"\\]|\\.)+)"|([-$.A-Za-z0-9]+)):\s*(?:;.*)?$/;

export function resolveSymbol(
    document: TextDocument,
    position: Position
): ResolvedSymbol | undefined {
    const token = symbolAtPosition(document, position);
    if (!token) {
        return undefined;
    }

    const index = buildIndex(document);
    const localScope = index.functionScopes.find((scope) =>
        scope.startLine <= position.line && position.line <= scope.endLine
    );

    const localDeclaration = token.startsWith('%')
        ? localScope?.locals.get(token)
        : undefined;

    const declaration = localDeclaration ?? index.globals.get(token);
    if (!declaration) {
        return undefined;
    }

    return {
        declaration,
        index,
        localScope,
        token
    };
}

export function buildIndex(document: TextDocument): LlvmIndex {
    const globals = new Map<string, LlvmSymbol>();
    const functionScopes: FunctionScope[] = [];
    const outlineSymbols: DocumentSymbol[] = [];
    const allLines = Array.from({ length: document.lineCount }, (_, line) => document.lineAt(line));

    // A single stateful pass keeps local `%` values and labels scoped to the current function.
    let activeScope: FunctionScope | undefined;

    for (const line of allLines) {
        if (activeScope && isFunctionEnd(line.text)) {
            finalizeFunctionOutline(activeScope, line.lineNumber, line.text.length);
            functionScopes.push(activeScope);
            activeScope = undefined;
            continue;
        }

        if (!activeScope) {
            const functionSymbol = matchFunctionDeclaration(line);
            if (functionSymbol) {
                globals.set(functionSymbol.key, functionSymbol);
                const outlineSymbol = createOutlineSymbol(
                    functionSymbol,
                    functionSymbol.range
                );

                if (/^\s*define\b/.test(line.text) && line.text.includes('{')) {
                    activeScope = {
                        endLine: line.lineNumber,
                        functionSymbol: functionSymbol,
                        locals: new Map(
                            extractFunctionParams(line).map((symbol) => [symbol.key, symbol])
                        ),
                        outlineSymbol: outlineSymbol,
                        outlineChildren: [],
                        startLine: line.lineNumber
                    };
                    outlineSymbols.push(outlineSymbol);
                    continue;
                }

                outlineSymbols.push(outlineSymbol);
                continue;
            }

            const topLevel = matchTopLevelDeclaration(line);
            if (!topLevel) {
                continue;
            }

            globals.set(topLevel.key, topLevel);
            outlineSymbols.push(createOutlineSymbol(topLevel, topLevel.range));
            continue;
        }

        const localDeclaration = matchLocalDeclaration(line);
        if (!localDeclaration) {
            continue;
        }

        activeScope.locals.set(localDeclaration.key, localDeclaration);
        if (localDeclaration.kind === 'label') {
            activeScope.outlineChildren.push(
                createOutlineSymbol(localDeclaration, localDeclaration.range)
            );
        }
    }

    if (activeScope) {
        finalizeFunctionOutline(
            activeScope,
            Math.max(document.lineCount - 1, activeScope.startLine),
            document.lineAt(Math.max(document.lineCount - 1, activeScope.startLine)).text.length
        );
        functionScopes.push(activeScope);
    }

    return { functionScopes, globals, outlineSymbols };
}

function matchTopLevelDeclaration(line: TextLine): LlvmSymbol | undefined {
    const attribute = matchAttributeDeclaration(line);
    if (attribute) {
        return attribute;
    }

    const declaration = matchAssignmentDeclaration(line, false);
    if (!declaration) {
        return undefined;
    }

    return declaration;
}

function matchLocalDeclaration(line: TextLine): LlvmSymbol | undefined {
    const label = matchLabelDeclaration(line);
    if (label) {
        return label;
    }

    return matchAssignmentDeclaration(line, true);
}

function matchAssignmentDeclaration(
    line: TextLine,
    localScope: boolean
): LlvmSymbol | undefined {
    const match = line.text.match(DECLARATION_RE);
    if (!match) {
        return undefined;
    }

    const key = match[1];
    const rhs = match[2].trim();
    const symbolKind = classifyAssignment(key, rhs, localScope);
    if (!symbolKind) {
        return undefined;
    }

    return createSymbolFromMatch(line, key, key, symbolKind);
}

function matchFunctionDeclaration(line: TextLine): LlvmSymbol | undefined {
    const match = line.text.match(FUNCTION_RE);
    if (!match) {
        return undefined;
    }

    const key = match[1];
    return createSymbolFromMatch(line, key, key, 'function');
}

function matchAttributeDeclaration(line: TextLine): LlvmSymbol | undefined {
    const match = line.text.match(ATTRIBUTE_RE);
    if (!match) {
        return undefined;
    }

    const key = match[1];
    return createSymbolFromMatch(line, key, key, 'attribute');
}

function matchLabelDeclaration(line: TextLine): LlvmSymbol | undefined {
    const match = line.text.match(LABEL_RE);
    if (!match) {
        return undefined;
    }

    const rawName = match[1] ?? match[2];
    if (!rawName) {
        return undefined;
    }

    const displayName = match[1] ? `"${rawName}"` : rawName;
    return createSymbolFromMatch(line, `%${displayName}`, displayName, 'label');
}

function extractFunctionParams(line: TextLine): LlvmSymbol[] {
    const header = line.text.match(FUNCTION_RE);
    const openParen = header ? header[0].length - 1 : -1;
    const closeParen = closingParameterParen(line.text, openParen);
    if (openParen < 0 || closeParen <= openParen) {
        return [];
    }

    const parameterList = line.text.slice(openParen + 1, closeParen);
    return splitTopLevelSegments(parameterList).flatMap((segment) => {
        const matches = Array.from(symbolTokens(segment.text))
            .map((match) => ({
                token: match[0],
                start: match.index ?? 0
            }))
            .filter((match) => match.token.startsWith('%'))
            .filter((match) => isTerminalSymbolToken(segment.text, match));

        const param = matches.at(-1);
        if (!param) {
            return [];
        }

        return [
            createSymbolAt(
                line, param.token, param.token, 'local',
                openParen + 1 + segment.offset + param.start
            )
        ];
    });
}

function closingParameterParen(text: string, openParen: number): number {
    // Scan balanced delimiters at the text boundary; quoted names are atomic tokens.
    let depth = 1;
    for (const match of text.slice(openParen + 1).matchAll(/"(?:[^"\\]|\\.)*"|[()]/g)) {
        if (match[0] === '(') depth += 1;
        if (match[0] === ')') depth -= 1;
        if (depth === 0) return openParen + 1 + (match.index ?? 0);
    }
    return -1;
}

function createSymbolFromMatch(
    line: TextLine,
    key: string,
    displayName: string,
    kind: LlvmSymbolKind
): LlvmSymbol {
    const start = line.text.indexOf(displayName);
    return createSymbolAt(line, key, displayName, kind, start >= 0 ? start : 0);
}

function createSymbolAt(
    line: TextLine,
    key: string,
    displayName: string,
    kind: LlvmSymbolKind,
    start: number
): LlvmSymbol {
    const end = start + displayName.length;
    const range = Range.create(line.lineNumber, 0, line.lineNumber, line.text.length);
    const selectionRange = Range.create(line.lineNumber, start, line.lineNumber, end);

    return {
        key,
        displayName,
        declaration: line.text.trim(),
        detail: symbolLabel(kind),
        kind,
        line: line.lineNumber,
        range,
        selectionRange
    };
}

function splitTopLevelSegments(text: string): Array<{ offset: number; text: string }> {
    // Parameter chunks can contain nested function types, so commas only split at depth 0.
    const segments: Array<{ offset: number; text: string }> = [];
    let depth = 0;
    let current = '';
    let offset = 0;

    for (const match of text.matchAll(/"(?:[^"\\]|\\.)*"|./g)) {
        const char = match[0];
        const index = match.index ?? 0;
        if (char === ',' && depth === 0) {
            const trimmed = current.trim();
            const leading = current.length - current.trimStart().length;
            if (trimmed.length > 0) {
                segments.push({ offset: offset + leading, text: trimmed });
            }
            current = '';
            offset = index + 1;
            continue;
        }

        if (char === '(' || char === '[' || char === '{') {
            depth += 1;
        } else if (char === ')' || char === ']' || char === '}') {
            depth = Math.max(0, depth - 1);
        }

        current += char;
    }

    const trimmed = current.trim();
    const leading = current.length - current.trimStart().length;
    if (trimmed.length > 0) {
        segments.push({ offset: offset + leading, text: trimmed });
    }

    return segments;
}

function isTerminalSymbolToken(
    segment: string,
    match: { token: string; start: number }
): boolean {
    const localStart = match.start;
    if (localStart < 0) {
        return false;
    }

    const tail = segment.slice(localStart + match.token.length).trim();
    return tail.length === 0;
}

function classifyAssignment(
    key: string,
    rhs: string,
    localScope: boolean
): LlvmSymbolKind | undefined {
    if (key.startsWith('%') && rhs.startsWith('type')) {
        return 'type';
    }
    if (key.startsWith('!')) {
        return 'metadata';
    }
    if (key.startsWith('$')) {
        return 'comdat';
    }
    if (localScope && key.startsWith('%')) {
        return 'local';
    }
    if (key.startsWith('@')) {
        return 'global';
    }
    if (!localScope && key.startsWith('%')) {
        return 'type';
    }
    return undefined;
}

function symbolAtPosition(
    document: TextDocument,
    position: Position
): string | undefined {
    if (position.line < 0 || position.line >= document.lineCount) {
        return undefined;
    }
    const line = document.lineAt(position.line);

    for (const match of symbolTokens(line.text)) {
        const token = match[0];
        const start = match.index ?? 0;
        const end = start + token.length;
        if (start <= position.character && position.character < end) {
            return token;
        }
    }

    const label = matchLabelDeclaration(line);
    if (!label) {
        return undefined;
    }

    const start = label.selectionRange.start.character;
    const end = label.selectionRange.end.character;
    if (start <= position.character && position.character < end) {
        return label.key;
    }

    return undefined;
}

export function collectReferenceLocations(
    document: TextDocument,
    resolved: ResolvedSymbol
): Location[] {
    const scope =
        resolved.declaration.kind === 'local' || resolved.declaration.kind === 'label'
            ? resolved.localScope
            : undefined;

    const startLine = scope?.startLine ?? 0;
    const endLine = scope?.endLine ?? Math.max(0, document.lineCount - 1);
    const scanned = Array.from(
        { length: endLine - startLine + 1 },
        (_, offset) => document.lineAt(startLine + offset)
    ).flatMap((line) =>
        Array.from(symbolTokens(line.text))
            .filter((match) => match[0] === resolved.token)
            .map((match) => {
                const start = match.index ?? 0;
                const end = start + resolved.token.length;
                return Location.create(
                    document.uri,
                    Range.create(line.lineNumber, start, line.lineNumber, end)
                );
            })
    );

    if (resolved.declaration.kind !== 'label') {
        return scanned;
    }

    return [
        Location.create(document.uri, resolved.declaration.selectionRange),
        ...scanned
    ].filter(
        (location, index, locations) =>
            locations.findIndex((candidate) => sameRange(candidate.range, location.range)) === index
    );
}

export function sameRange(left: Range, right: Range): boolean {
    return (
        left.start.line === right.start.line &&
        left.start.character === right.start.character &&
        left.end.line === right.end.line &&
        left.end.character === right.end.character
    );
}

function finalizeFunctionOutline(
    scope: FunctionScope,
    endLine: number,
    endCharacter: number
): void {
    scope.endLine = endLine;
    scope.outlineSymbol.range = Range.create(
        scope.startLine,
        0,
        endLine,
        endCharacter
    );
    scope.outlineSymbol.children = scope.outlineChildren;
}

function createOutlineSymbol(symbol: LlvmSymbol, range: Range): DocumentSymbol {
    return DocumentSymbol.create(
        symbol.key,
        symbol.detail,
        outlineKind(symbol.kind),
        range,
        symbol.selectionRange
    );
}

function outlineKind(kind: LlvmSymbolKind): SymbolKind {
    switch (kind) {
        case 'attribute':
            return SymbolKind.Property;
        case 'comdat':
            return SymbolKind.Object;
        case 'function':
            return SymbolKind.Function;
        case 'global':
            return SymbolKind.Variable;
        case 'label':
            return SymbolKind.Namespace;
        case 'local':
            return SymbolKind.Variable;
        case 'metadata':
            return SymbolKind.Object;
        case 'type':
            return SymbolKind.Struct;
    }
}

export function symbolLabel(kind: LlvmSymbolKind): string {
    switch (kind) {
        case 'attribute':
            return 'Attribute Group';
        case 'comdat':
            return 'COMDAT';
        case 'function':
            return 'Function';
        case 'global':
            return 'Global';
        case 'label':
            return 'Label';
        case 'local':
            return 'Local Value';
        case 'metadata':
            return 'Metadata';
        case 'type':
            return 'Type';
    }
}

function isFunctionEnd(line: string): boolean {
    return /^\s*}\s*(?:;.*)?$/.test(line);
}
