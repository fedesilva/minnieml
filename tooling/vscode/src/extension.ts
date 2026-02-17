import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    ExecuteCommandRequest,
    ErrorAction,
    CloseAction,
    ErrorHandler,
    Message,
    Trace
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel;
let isRestarting = false;
let isDeactivating = false;
let autoRestartTimer: NodeJS.Timeout | undefined;
let autoRestartAttempts = 0;
let verboseLspClientLogging = false;
let requestSequence = 0;
let startLspFn: (() => void) | undefined;

export function activate(context: vscode.ExtensionContext) {
    outputChannel = vscode.window.createOutputChannel('MinnieML');
    outputChannel.appendLine('MinnieML extension activating...');

    // Start LSP when a .mml file is opened
    const startLsp = () => {
        if (client) {
            outputChannel.appendLine('LSP client already running');
            return;
        }

        outputChannel.appendLine('Starting LSP...');

        const config = vscode.workspace.getConfiguration('mml');
        const mmlcPath = config.get<string>('mmlcPath', 'mmlc');
        verboseLspClientLogging = config.get<boolean>('verboseLspClientLogging', false);

        const serverOptions: ServerOptions = {
            command: mmlcPath,
            args: ['lsp']
        };

        const errorHandler: ErrorHandler = {
            error: (error: Error, message: Message | undefined, count: number | undefined) => {
                if (isRestarting) {
                    return { action: ErrorAction.Shutdown };
                }
                outputChannel.appendLine(`[Error] ${error.message}`);
                return { action: ErrorAction.Continue };
            },
            closed: () => {
                if (isRestarting) {
                    client = undefined;
                    return { action: CloseAction.DoNotRestart };
                }
                outputChannel.appendLine('[Info] Server connection closed');
                client = undefined;
                scheduleAutoRestart('server connection closed');
                return { action: CloseAction.DoNotRestart };
            }
        };

        const clientOptions: LanguageClientOptions = {
            documentSelector: [{ scheme: 'file', language: 'mml' }],
            outputChannel: outputChannel,
            synchronize: {
                fileEvents: vscode.workspace.createFileSystemWatcher('**/*.mml')
            },
            errorHandler: errorHandler,
            middleware: {
                provideDefinition: async (document, position, token, next) => {
                    const reqId = nextRequestId();
                    const symbol = symbolAt(document, position);
                    logLsp(`(invoked) textDocument/definition (${reqId}) ${symbol}`);
                    const result = await next(document, position, token);
                    if (isEmptyDefinition(result)) {
                        logLsp(`(received) textDocument/definition (${reqId}) not-found`);
                        vscode.window.showWarningMessage(
                            `definition not found for: ${symbol}`
                        );
                        return result;
                    }
                    logLsp(`(received) textDocument/definition (${reqId}) found`);
                    return result;
                }
            }
        };

        client = new LanguageClient(
            'mmlLanguageServer',
            'MinnieML Language Server',
            serverOptions,
            clientOptions
        );

        client.start().then(() => {
            outputChannel.appendLine('LSP client started successfully');
            autoRestartAttempts = 0;
            if (verboseLspClientLogging && client) {
                client.setTrace(Trace.Messages);
                logLsp('(info) client request/response tracing enabled');
            }
        }).catch((error) => {
            outputChannel.appendLine(`Failed to start LSP client: ${error}`);
            client = undefined;
            scheduleAutoRestart(`startup failure: ${error}`);
        });

        context.subscriptions.push({
            dispose: () => {
                if (client) {
                    client.stop();
                    client = undefined;
                }
            }
        });
    };
    startLspFn = startLsp;

    // Register commands that call server-side commands
    context.subscriptions.push(
        vscode.commands.registerCommand('mml.restart', async () => {
            if (!client) {
                vscode.window.showWarningMessage('MML: Language server not running');
                return;
            }
            outputChannel.appendLine('Restarting language server...');
            isRestarting = true;
            try {
                // Server will ack, then quit
                outputChannel.appendLine('Sending restart request...');
                const response = await sendServerExecuteCommand('mml.server.restart', []);
                outputChannel.appendLine(`Restart response: ${JSON.stringify(response)}`);
            } catch (e) {
                // Expected - server may close before response is fully received
            }
            try {
                await client.stop();
            } catch (e) {
                // Expected - server already exited
            }
            client = undefined;
            startLsp();
            isRestarting = false;
            vscode.window.showInformationMessage('MML: Language server restarted');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('mml.compileBin', async () => {
            await executeCompileCommand('mml.server.compileBin', 'Compiling to binary...');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('mml.compileLib', async () => {
            await executeCompileCommand('mml.server.compileLib', 'Compiling as library...');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('mml.clean', async () => {
            await executeCompileCommand('mml.server.clean', 'Cleaning build directory...');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('mml.ast', async () => {
            await executeCompileCommand('mml.server.ast', 'Generating AST...');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('mml.ir', async () => {
            await executeCompileCommand('mml.server.ir', 'Generating IR...');
        })
    );

    // Check if there's already an active .mml file
    const activeEditor = vscode.window.activeTextEditor;
    if (activeEditor && activeEditor.document.languageId === 'mml') {
        startLsp();
    }

    // Listen for .mml files being opened
    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument((document) => {
            if (document.languageId === 'mml' && !client) {
                startLsp();
            }
        })
    );

    // Start LSP when switching to a .mml file
    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor((editor) => {
            if (editor && editor.document.languageId === 'mml' && !client) {
                startLsp();
            }
        })
    );

    outputChannel.appendLine('MinnieML extension activated');
}

async function executeCompileCommand(serverCommand: string, message: string): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'mml') {
        vscode.window.showWarningMessage('MML: No .mml file active');
        return;
    }
    if (!client) {
        outputChannel.appendLine('[Info] Language server not running, attempting start...');
        startLspFn?.();
        vscode.window.showWarningMessage('MML: Language server not running. Retry in a moment.');
        return;
    }

    const uri = editor.document.uri.toString();
    outputChannel.appendLine(`${message} ${uri}`);

    try {
        const result = await sendServerExecuteCommand(serverCommand, [uri]) as {
            success: boolean;
            message?: string;
        };

        if (result.success) {
            vscode.window.showInformationMessage('MML: Compilation successful');
        } else {
            const msg = result.message || 'Unknown error';
            outputChannel.appendLine(`Compilation failed: ${msg}`);
            vscode.window.showErrorMessage(`MML: ${msg}`);
        }
    } catch (error) {
        outputChannel.appendLine(`Compile error: ${error}`);
        vscode.window.showErrorMessage(`MML: Compilation error: ${error}`);
    }
}

export function deactivate(): Thenable<void> | undefined {
    isDeactivating = true;
    if (autoRestartTimer) {
        clearTimeout(autoRestartTimer);
        autoRestartTimer = undefined;
    }
    if (client) {
        return client.stop();
    }
    return undefined;
}

function scheduleAutoRestart(reason: string): void {
    if (isRestarting || isDeactivating || autoRestartTimer) {
        return;
    }
    if (autoRestartAttempts >= 5) {
        outputChannel.appendLine('[Warn] Auto-restart disabled after repeated failures');
        return;
    }
    autoRestartAttempts += 1;
    const delayMs = Math.min(1000 * autoRestartAttempts, 5000);
    outputChannel.appendLine(
        `[Info] Scheduling auto-restart in ${delayMs}ms (reason: ${reason})`
    );
    autoRestartTimer = setTimeout(() => {
        autoRestartTimer = undefined;
        if (!client) {
            startLspFn?.();
        }
    }, delayMs);
}

function symbolAt(document: vscode.TextDocument, position: vscode.Position): string {
    const range = document.getWordRangeAtPosition(position);
    if (!range) {
        return '<unknown>';
    }
    const text = document.getText(range).trim();
    return text.length > 0 ? text : '<unknown>';
}

function isEmptyDefinition(
    result: vscode.Location | vscode.Location[] | vscode.DefinitionLink[] | null | undefined
): boolean {
    if (!result) {
        return true;
    }
    if (Array.isArray(result)) {
        return result.length === 0;
    }
    return false;
}

function nextRequestId(): string {
    requestSequence += 1;
    return `mml-${requestSequence}`;
}

async function sendServerExecuteCommand(
    command: string,
    args: unknown[]
): Promise<unknown> {
    if (!client) {
        throw new Error('Language server not running');
    }
    const reqId = nextRequestId();
    logLsp(`(invoked) workspace/executeCommand (${reqId}) ${command}`);
    const response = await client.sendRequest(ExecuteCommandRequest.type, {
        command: command,
        arguments: args
    });
    logLsp(`(received) workspace/executeCommand (${reqId}) ${command}`);
    logLsp(`Sending response (${reqId}) ${command}`);
    return response;
}

function logLsp(message: string): void {
    if (verboseLspClientLogging) {
        outputChannel.appendLine(`[LSP] ${message}`);
    }
}
