const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function activateExtension() {
    const document = {
        languageId: 'mml',
        uri: { toString: () => 'file:///navigation.mml' },
        getWordRangeAtPosition: () => undefined
    };
    const warnings = [];
    const disposable = { dispose() {} };
    let clientOptions;
    let serverOptions;

    const vscode = {
        window: {
            activeTextEditor: { document },
            createOutputChannel: () => ({ appendLine() {} }),
            onDidChangeActiveTextEditor: () => disposable,
            showWarningMessage: message => warnings.push(message)
        },
        workspace: {
            getConfiguration: () => ({ get: (_key, fallback) => fallback }),
            createFileSystemWatcher: () => disposable,
            onDidOpenTextDocument: () => disposable,
            onDidChangeConfiguration: () => disposable
        },
        commands: { registerCommand: () => disposable }
    };
    const languageClient = {
        LanguageClient: class {
            constructor(_id, _name, server, options) {
                serverOptions = server;
                clientOptions = options;
            }
            start() { return Promise.resolve(); }
            stop() { return Promise.resolve(); }
        }
    };
    const exports = {};
    const context = vm.createContext({
        exports,
        require: name => {
            if (name === 'vscode') return vscode;
            if (name === 'vscode-languageclient/node') return languageClient;
            assert.fail(`Unexpected dependency: ${name}`);
        },
        setTimeout,
        clearTimeout
    });
    const extensionPath = path.join(__dirname, '../out/extension.js');
    vm.runInContext(fs.readFileSync(extensionPath, 'utf8'), context);
    exports.activate({ subscriptions: [] });

    return {
        document,
        warnings,
        serverOptions,
        middleware: clientOptions.middleware,
        deactivate: exports.deactivate
    };
}

for (const provider of ['provideDefinition', 'provideImplementation', 'provideReferences']) {
    test(`${provider} sends pending edits before navigation and preserves locations`, async () => {
        const extension = activateExtension();
        try {
            assert.equal(extension.serverOptions.command, 'mmlc');
            assert.deepEqual(Array.from(extension.serverOptions.args), ['lsp']);
            const events = [];
            const position = { line: 4, character: 8 };
            const token = { isCancellationRequested: false };
            const options = { includeDeclaration: false };
            const locations = [
                { uri: 'file:///navigation.mml', range: { start: { line: 1, character: 5 } } },
                { uri: 'file:///navigation.mml', range: { start: { line: 7, character: 5 } } }
            ];
            await extension.middleware.didChange({ document: extension.document }, async () => {
                await Promise.resolve();
                events.push('change');
            });
            assert.deepEqual(events, []);

            const args = [extension.document, position];
            if (provider === 'provideReferences') args.push(options);
            args.push(token);
            const result = await extension.middleware[provider](...args, async (...received) => {
                assert.deepEqual(received, args);
                events.push('request');
                return locations;
            });

            assert.deepEqual(events, ['change', 'request']);
            assert.equal(result, locations);
            assert.deepEqual(extension.warnings, []);
        } finally {
            await extension.deactivate();
        }
    });
}

test('implementation navigation preserves an empty result without a definition warning', async () => {
    const extension = activateExtension();
    try {
        const result = await extension.middleware.provideImplementation(
            extension.document, { line: 0, character: 0 }, {}, async () => []
        );
        assert.deepEqual(result, []);
        assert.deepEqual(extension.warnings, []);
    } finally {
        await extension.deactivate();
    }
});

test('navigation flushes only the requested document and sends its latest edit once', async () => {
    const extension = activateExtension();
    try {
        const events = [];
        const otherDocument = { uri: { toString: () => 'file:///other.mml' } };
        await extension.middleware.didChange({ document: otherDocument }, async () => {
            events.push('other');
        });
        await extension.middleware.didChange({ document: extension.document }, async () => {
            events.push('older');
        });
        await extension.middleware.didChange({ document: extension.document }, async () => {
            events.push('latest');
        });
        await extension.middleware.provideImplementation(
            extension.document, {}, {}, async () => []
        );
        await extension.middleware.provideReferences(
            extension.document, {}, { includeDeclaration: true }, {}, async () => []
        );
        assert.deepEqual(events, ['latest']);
    } finally {
        await extension.deactivate();
    }
});
