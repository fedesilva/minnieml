import * as vscode from 'vscode';
import {
    buildIndex as indexSource, resolveSymbol as resolveSource,
    collectReferenceLocations as sourceReferences, sameRange, symbolLabel, sourceDocument
} from '../../llvm-ir/src/index';
import type { DocumentSymbol as SourceSymbol, Range as SourceRange } from '../../llvm-ir/src/index';

function documentSource(document: vscode.TextDocument) {
    return sourceDocument(document.uri.toString(), document.getText());
}

function editorRange(range: SourceRange): vscode.Range {
    return new vscode.Range(
        range.start.line, range.start.character, range.end.line, range.end.character
    );
}

function resolveSymbol(document: vscode.TextDocument, position: vscode.Position) {
    const resolved = resolveSource(documentSource(document), position);
    return resolved && {
        ...resolved,
        declaration: {
            ...resolved.declaration,
            selectionRange: editorRange(resolved.declaration.selectionRange)
        }
    };
}

function collectReferenceLocations(
    document: vscode.TextDocument,
    resolved: NonNullable<ReturnType<typeof resolveSymbol>>
) {
    return sourceReferences(documentSource(document), resolved).map(location =>
        new vscode.Location(document.uri, editorRange(location.range))
    );
}

function editorSymbol(symbol: SourceSymbol): vscode.DocumentSymbol {
    // LSP symbol kinds are one-based; VS Code uses the same ordering from zero.
    const result = new vscode.DocumentSymbol(
        symbol.name, symbol.detail ?? '', symbol.kind - 1,
        editorRange(symbol.range), editorRange(symbol.selectionRange)
    );
    result.children = (symbol.children ?? []).map(editorSymbol);
    return result;
}

function buildIndex(document: vscode.TextDocument) {
    return {
        outlineSymbols: indexSource(documentSource(document)).outlineSymbols.map(editorSymbol)
    };
}

function logOutput(message: string): void {
    outputChannel?.appendLine(`[llvm-ir] ${message}`);
}

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
                return new vscode.Hover(content);
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
