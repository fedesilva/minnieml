import * as vscode from 'vscode';

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
    range: vscode.Range;
    selectionRange: vscode.Range;
};

type FunctionScope = {
    endLine: number;
    functionSymbol: LlvmSymbol;
    locals: Map<string, LlvmSymbol>;
    outlineSymbol: vscode.DocumentSymbol;
    outlineChildren: vscode.DocumentSymbol[];
    startLine: number;
};

type LlvmIndex = {
    functionScopes: FunctionScope[];
    globals: Map<string, LlvmSymbol>;
    outlineSymbols: vscode.DocumentSymbol[];
};

type ResolvedSymbol = {
    declaration: LlvmSymbol;
    index: LlvmIndex;
    localScope: FunctionScope | undefined;
    token: string;
};

const TOKEN_RE =
    /%"(?:[^"\\]|\\.)+"|@\"(?:[^\"\\]|\\.)+\"|%[-$._A-Za-z0-9]+|@[-$._A-Za-z0-9]+|![-$._A-Za-z0-9]+|#\d+|\$[-$._A-Za-z0-9]+/g;

const DECLARATION_RE =
    /^\s*(%"(?:[^"\\]|\\.)+"|@\"(?:[^\"\\]|\\.)+\"|%[-$._A-Za-z0-9]+|@[-$._A-Za-z0-9]+|![-$._A-Za-z0-9]+|\$[-$._A-Za-z0-9]+)\s*=\s*(.*)$/;

const FUNCTION_RE =
    /^\s*(?:define|declare)\b.*?(@\"(?:[^\"\\]|\\.)+\"|@[-$._A-Za-z0-9]+)\s*\(/;

const ATTRIBUTE_RE = /^\s*attributes\s+(#\d+)\s*=/;

const LABEL_RE = /^\s*(?:"((?:[^"\\]|\\.)+)"|([-$.A-Za-z0-9]+)):\s*(?:;.*)?$/;

let outputChannel: vscode.OutputChannel | undefined;

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel('LLVM IR');
    context.subscriptions.push(outputChannel);

    const selector: vscode.DocumentSelector = [
        { scheme: 'file', language: 'llvm-ir' },
        { scheme: 'untitled', language: 'llvm-ir' },
        { scheme: 'file', pattern: '**/*.ll' }
    ];

    logOutput('extension activated');

    context.subscriptions.push(
        vscode.languages.registerDefinitionProvider(selector, {
            provideDefinition(document, position) {
                const resolved = resolveSymbol(document, position);
                if (!resolved) {
                    return undefined;
                }
                logOutput(`definition: ${resolved.token} @ ${document.uri.fsPath}:${position.line + 1}`);
                return new vscode.Location(document.uri, resolved.declaration.selectionRange);
            }
        })
    );

    context.subscriptions.push(
        vscode.languages.registerHoverProvider(selector, {
            provideHover(document, position) {
                const resolved = resolveSymbol(document, position);
                if (!resolved) {
                    return undefined;
                }

                const content = new vscode.MarkdownString();
                content.appendMarkdown(
                    `**${symbolLabel(resolved.declaration.kind)}** \`${resolved.declaration.key}\`\n\n`
                );
                content.appendCodeblock(resolved.declaration.declaration, 'llvm');
                return new vscode.Hover(content, resolved.declaration.selectionRange);
            }
        })
    );

    context.subscriptions.push(
        vscode.languages.registerReferenceProvider(selector, {
            provideReferences(document, position, context) {
                const resolved = resolveSymbol(document, position);
                if (!resolved) {
                    return [];
                }
                const locations = collectReferenceLocations(document, resolved);
                return context.includeDeclaration
                    ? locations
                    : locations.filter(
                          (location) => !sameRange(location.range, resolved.declaration.selectionRange)
                      );
            }
        })
    );

    context.subscriptions.push(
        vscode.languages.registerDocumentSymbolProvider(selector, {
            provideDocumentSymbols(document) {
                return buildIndex(document).outlineSymbols;
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('llvmIr.showOutput', () => {
            outputChannel?.show(true);
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('llvmIr.goToDeclaration', async () => {
            const editor = vscode.window.activeTextEditor;
            if (!editor) {
                return;
            }
            const resolved = resolveSymbol(editor.document, editor.selection.active);
            if (!resolved) {
                logOutput('go to declaration: no symbol under cursor');
                return;
            }
            outputChannel?.show(true);
            logOutput(`go to declaration: ${resolved.token}`);
            const targetEditor = await vscode.window.showTextDocument(editor.document);
            targetEditor.selection = new vscode.Selection(
                resolved.declaration.selectionRange.start,
                resolved.declaration.selectionRange.end
            );
            targetEditor.revealRange(
                resolved.declaration.selectionRange,
                vscode.TextEditorRevealType.InCenter
            );
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('llvmIr.findReferences', async () => {
            const editor = vscode.window.activeTextEditor;
            if (!editor) {
                return;
            }
            const resolved = resolveSymbol(editor.document, editor.selection.active);
            if (!resolved) {
                logOutput('find references: no symbol under cursor');
                return;
            }
            const locations = collectReferenceLocations(editor.document, resolved);
            outputChannel?.show(true);
            logOutput(`find references: ${resolved.token} (${locations.length} matches)`);
            await vscode.commands.executeCommand(
                'editor.action.showReferences',
                editor.document.uri,
                editor.selection.active,
                locations
            );
        })
    );

    const activeEditor = vscode.window.activeTextEditor;
    if (activeEditor?.document.fileName.endsWith('.ll')) {
        outputChannel.show(true);
        logOutput(`active LLVM IR file: ${activeEditor.document.fileName}`);
    }
}

export function deactivate(): undefined {
    logOutput('extension deactivated');
    return undefined;
}

function resolveSymbol(
    document: vscode.TextDocument,
    position: vscode.Position
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

function buildIndex(document: vscode.TextDocument): LlvmIndex {
    const globals = new Map<string, LlvmSymbol>();
    const functionScopes: FunctionScope[] = [];
    const outlineSymbols: vscode.DocumentSymbol[] = [];
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

                if (line.text.includes('{')) {
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

function matchTopLevelDeclaration(line: vscode.TextLine): LlvmSymbol | undefined {
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

function matchLocalDeclaration(line: vscode.TextLine): LlvmSymbol | undefined {
    const label = matchLabelDeclaration(line);
    if (label) {
        return label;
    }

    return matchAssignmentDeclaration(line, true);
}

function matchAssignmentDeclaration(
    line: vscode.TextLine,
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

function matchFunctionDeclaration(line: vscode.TextLine): LlvmSymbol | undefined {
    const match = line.text.match(FUNCTION_RE);
    if (!match) {
        return undefined;
    }

    const key = match[1];
    return createSymbolFromMatch(line, key, key, 'function');
}

function matchAttributeDeclaration(line: vscode.TextLine): LlvmSymbol | undefined {
    const match = line.text.match(ATTRIBUTE_RE);
    if (!match) {
        return undefined;
    }

    const key = match[1];
    return createSymbolFromMatch(line, key, key, 'attribute');
}

function matchLabelDeclaration(line: vscode.TextLine): LlvmSymbol | undefined {
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

function extractFunctionParams(line: vscode.TextLine): LlvmSymbol[] {
    const openParen = line.text.indexOf('(');
    const closeParen = line.text.lastIndexOf(')');
    if (openParen < 0 || closeParen <= openParen) {
        return [];
    }

    const parameterList = line.text.slice(openParen + 1, closeParen);
    return splitTopLevelSegments(parameterList).flatMap((segment) => {
        const matches = Array.from(segment.text.matchAll(TOKEN_RE))
            .map((match) => ({
                token: match[0],
                start: segment.offset + (match.index ?? 0)
            }))
            .filter((match) => match.token.startsWith('%'))
            .filter((match) => isTerminalSymbolToken(segment.text, match));

        const param = matches.at(-1);
        if (!param) {
            return [];
        }

        return [
            createSymbolAt(line, param.token, param.token, 'local', param.start)
        ];
    });
}

function createSymbolFromMatch(
    line: vscode.TextLine,
    key: string,
    displayName: string,
    kind: LlvmSymbolKind
): LlvmSymbol {
    const start = line.text.indexOf(displayName);
    return createSymbolAt(line, key, displayName, kind, start >= 0 ? start : 0);
}

function createSymbolAt(
    line: vscode.TextLine,
    key: string,
    displayName: string,
    kind: LlvmSymbolKind,
    start: number
): LlvmSymbol {
    const end = start + displayName.length;
    const range = new vscode.Range(line.lineNumber, 0, line.lineNumber, line.text.length);
    const selectionRange = new vscode.Range(line.lineNumber, start, line.lineNumber, end);

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

    for (const [index, char] of Array.from(text).entries()) {
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
    const localStart = segment.indexOf(match.token);
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
    document: vscode.TextDocument,
    position: vscode.Position
): string | undefined {
    const line = document.lineAt(position.line);

    for (const match of line.text.matchAll(TOKEN_RE)) {
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

function collectReferenceLocations(
    document: vscode.TextDocument,
    resolved: ResolvedSymbol
): vscode.Location[] {
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
        Array.from(line.text.matchAll(TOKEN_RE))
            .filter((match) => match[0] === resolved.token)
            .map((match) => {
                const start = match.index ?? 0;
                const end = start + resolved.token.length;
                return new vscode.Location(
                    document.uri,
                    new vscode.Range(line.lineNumber, start, line.lineNumber, end)
                );
            })
    );

    if (resolved.declaration.kind !== 'label') {
        return scanned;
    }

    return [
        new vscode.Location(document.uri, resolved.declaration.selectionRange),
        ...scanned
    ].filter(
        (location, index, locations) =>
            locations.findIndex((candidate) => sameRange(candidate.range, location.range)) === index
    );
}

function sameRange(left: vscode.Range, right: vscode.Range): boolean {
    return (
        left.start.line === right.start.line &&
        left.start.character === right.start.character &&
        left.end.line === right.end.line &&
        left.end.character === right.end.character
    );
}

function logOutput(message: string): void {
    outputChannel?.appendLine(`[llvm-ir] ${message}`);
}

function finalizeFunctionOutline(
    scope: FunctionScope,
    endLine: number,
    endCharacter: number
): void {
    scope.endLine = endLine;
    scope.outlineSymbol.range = new vscode.Range(
        scope.startLine,
        0,
        endLine,
        endCharacter
    );
    scope.outlineSymbol.children = scope.outlineChildren;
}

function createOutlineSymbol(symbol: LlvmSymbol, range: vscode.Range): vscode.DocumentSymbol {
    return new vscode.DocumentSymbol(
        symbol.key,
        symbol.detail,
        outlineKind(symbol.kind),
        range,
        symbol.selectionRange
    );
}

function outlineKind(kind: LlvmSymbolKind): vscode.SymbolKind {
    switch (kind) {
        case 'attribute':
            return vscode.SymbolKind.Property;
        case 'comdat':
            return vscode.SymbolKind.Object;
        case 'function':
            return vscode.SymbolKind.Function;
        case 'global':
            return vscode.SymbolKind.Variable;
        case 'label':
            return vscode.SymbolKind.Namespace;
        case 'local':
            return vscode.SymbolKind.Variable;
        case 'metadata':
            return vscode.SymbolKind.Object;
        case 'type':
            return vscode.SymbolKind.Struct;
    }
}

function symbolLabel(kind: LlvmSymbolKind): string {
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
