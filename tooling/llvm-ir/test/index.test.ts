import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildIndex, collectReferenceLocations, resolveSymbol, sourceDocument } from '../src/index';

const fixture = readFileSync('test/navigation.ll', 'utf8');
const document = sourceDocument('file:///navigation.ll', fixture);
const at = (line: number, token: string) => ({ line, character: document.lineAt(line).text.indexOf(token) });

test('definitions and references stay within their function', () => {
    const first = resolveSymbol(document, at(9, '%value'))!;
    const second = resolveSymbol(document, at(15, '%value'))!;
    assert.equal(first.declaration.selectionRange.start.line, 6);
    assert.equal(second.declaration.selectionRange.start.line, 14);
    assert.deepEqual(collectReferenceLocations(document, first).map(l => l.range.start.line), [6, 9]);
});

test('parameter declaration has an absolute UTF-16 column', () => {
    const param = resolveSymbol(document, at(6, '%input'))!;
    assert.deepEqual(param.declaration.selectionRange.start, at(4, '%input'));
    assert.deepEqual(collectReferenceLocations(document, param).map(l => l.range.start.line), [4, 6]);
});

test('labels resolve and include their bare declaration exactly once', () => {
    const label = resolveSymbol(document, at(7, '%done'))!;
    assert.deepEqual(label.declaration.selectionRange.start, at(8, 'done'));
    assert.deepEqual(collectReferenceLocations(document, label).map(l => l.range.start.line), [8, 7]);
    assert.equal(resolveSymbol(document, at(8, 'done'))!.declaration.key, '%done');
});

test('globals, types, metadata, COMDAT and attribute groups are indexed', () => {
    assert.deepEqual([...buildIndex(document).globals.keys()],
        ['%Pair', '@counter', '$group', '@external', '@first', '@second', '#0', '!0']);
    assert.equal(resolveSymbol(document, at(9, '@external'))!.declaration.selectionRange.start.line, 3);
    assert.equal(resolveSymbol(document, at(4, '#0'))!.declaration.selectionRange.start.line, 17);
});

test('outline nests labels and spans the function body', () => {
    const first = buildIndex(document).outlineSymbols.find(s => s.name === '@first')!;
    assert.deepEqual(first.children!.map(s => s.name), ['%entry', '%done']);
    assert.equal(first.range.end.line, 11);
});

test('quoted names work, comments and string contents are not references', () => {
    const doc = sourceDocument('file:///quoted.ll', [
        '@"quoted name" = global i32 0',
        '@str = private constant [15 x i8] c"@quoted name\\00"',
        '; @"quoted name"',
        'define ptr @read() {',
        '  ret ptr @"quoted name"',
        '}'
    ].join('\n'));
    const symbol = resolveSymbol(doc, { line: 4, character: 12 })!;
    assert.equal(symbol.declaration.key, '@"quoted name"');
    assert.deepEqual(collectReferenceLocations(doc, symbol).map(l => l.range.start.line), [0, 4]);
    assert.equal(resolveSymbol(doc, { line: 2, character: 4 }), undefined);
});

test('aggregate parameters do not turn declarations into function bodies', () => {
    const doc = sourceDocument('file:///aggregate.ll', 'declare void @f({ i32, i32 })\n@g = global i32 0');
    assert.equal(buildIndex(doc).functionScopes.length, 0);
    assert.ok(buildIndex(doc).globals.has('@g'));
});

test('empty and incomplete documents are usable', () => {
    assert.deepEqual(buildIndex(sourceDocument('file:///empty.ll', '')).outlineSymbols, []);
    const doc = sourceDocument('file:///partial.ll', 'define i32 @f(i32 %x) {\n ret i32 %x');
    assert.equal(resolveSymbol(doc, { line: 1, character: 10 })!.declaration.key, '%x');
    assert.equal(resolveSymbol(doc, { line: 99, character: 0 }), undefined);
});

test('quoted parameter punctuation and trailing attributes preserve declaration positions', () => {
    const header = 'define i32 @"f(😀)"(i32 %"x,y", i32 %second) allocsize(0) {';
    const doc = sourceDocument('file:///params.ll', header + '\n ret i32 %second\n}');
    const param = resolveSymbol(doc, { line: 1, character: 10 })!;
    assert.equal(param.declaration.selectionRange.start.character, header.indexOf('%second'));
    const quoted = resolveSymbol(doc, { line: 0, character: header.indexOf('%"x,y"') })!;
    assert.equal(quoted.declaration.key, '%"x,y"');
    assert.equal(quoted.declaration.selectionRange.start.character, header.indexOf('%"x,y"'));
});
