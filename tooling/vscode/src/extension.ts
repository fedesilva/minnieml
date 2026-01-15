import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    ExecuteCommandRequest,
    ErrorAction,
    CloseAction,
    ErrorHandler,
    Message
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel;
let isRestarting = false;

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
                    return { action: CloseAction.DoNotRestart };
                }
                outputChannel.appendLine('[Info] Server connection closed');
                return { action: CloseAction.DoNotRestart };
            }
        };

        const clientOptions: LanguageClientOptions = {
            documentSelector: [{ scheme: 'file', language: 'mml' }],
            outputChannel: outputChannel,
            synchronize: {
                fileEvents: vscode.workspace.createFileSystemWatcher('**/*.mml')
            },
            errorHandler: errorHandler
        };

        client = new LanguageClient(
            'mmlLanguageServer',
            'MinnieML Language Server',
            serverOptions,
            clientOptions
        );

        client.start().then(() => {
            outputChannel.appendLine('LSP client started successfully');
        }).catch((error) => {
            outputChannel.appendLine(`Failed to start LSP client: ${error}`);
            client = undefined;
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
                const response = await client.sendRequest(ExecuteCommandRequest.type, {
                    command: 'mml.server.restart',
                    arguments: []
                });
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
        vscode.window.showWarningMessage('MML: Language server not running');
        return;
    }

    const uri = editor.document.uri.toString();
    outputChannel.appendLine(`${message} ${uri}`);

    try {
        const result = await client.sendRequest(ExecuteCommandRequest.type, {
            command: serverCommand,
            arguments: [uri]
        }) as { success: boolean; message?: string };

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
    if (client) {
        return client.stop();
    }
    return undefined;
}
