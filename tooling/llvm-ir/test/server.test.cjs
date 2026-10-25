const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { readFileSync } = require('node:fs');
const { createMessageConnection, StreamMessageReader, StreamMessageWriter } = require('vscode-jsonrpc/node');

test('stdio server handles navigation, changes, close and shutdown', { timeout: 10000 }, async t => {
    const child = process.env.LLVM_IR_TEST_SERVER
        ? spawn(process.env.LLVM_IR_TEST_SERVER, ['--stdio'])
        : spawn(process.execPath, ['out/server.cjs', '--stdio']);
    const connection = createMessageConnection(new StreamMessageReader(child.stdout), new StreamMessageWriter(child.stdin));
    let stderr = '';
    child.stderr.on('data', data => { stderr += data; });
    t.after(() => { connection.dispose(); child.kill(); });
    connection.listen();
    const init = await connection.sendRequest('initialize', {
        processId: process.pid, rootUri: null, capabilities: {}
    });
    assert.equal(init.capabilities.definitionProvider, true);
    assert.equal(init.capabilities.textDocumentSync, 1);
    await connection.sendNotification('initialized', {});
    const uri = 'file:///test.ll';
    const textDocument = { uri };
    const text = readFileSync('test/navigation.ll', 'utf8');
    await connection.sendNotification('textDocument/didOpen', {
        textDocument: { uri, languageId: 'llvm-ir', version: 1, text }
    });
    const position = { line: 6, character: 20 };
    const definition = await connection.sendRequest('textDocument/definition', { textDocument, position });
    assert.deepEqual(definition.range.start, { line: 4, character: 22 });
    const hover = await connection.sendRequest('textDocument/hover', { textDocument, position });
    assert.match(hover.contents.value, /define i32 @first/);
    const refs = await connection.sendRequest('textDocument/references', {
        textDocument, position, context: { includeDeclaration: false }
    });
    assert.deepEqual(refs.map(ref => ref.range.start.line), [6]);
    const outline = await connection.sendRequest('textDocument/documentSymbol', { textDocument });
    assert.deepEqual(outline.find(symbol => symbol.name === '@first').children.map(s => s.name), ['%entry', '%done']);
    await connection.sendNotification('textDocument/didChange', {
        textDocument: { uri, version: 2 }, contentChanges: [{ text: '@new = global i32 7\n' }]
    });
    assert.equal(await connection.sendRequest('textDocument/definition', { textDocument, position }), null);
    assert.deepEqual((await connection.sendRequest('textDocument/documentSymbol', { textDocument })).map(s => s.name), ['@new']);
    await connection.sendNotification('textDocument/didClose', { textDocument });
    assert.deepEqual(await connection.sendRequest('textDocument/documentSymbol', { textDocument }), []);
    await connection.sendRequest('shutdown');
    const exit = new Promise(resolve => child.once('exit', resolve));
    await connection.sendNotification('exit');
    assert.equal(await exit, 0, stderr);
});
