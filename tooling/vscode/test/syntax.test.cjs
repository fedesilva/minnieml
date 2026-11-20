const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const textmate = require('vscode-textmate');
const oniguruma = require('vscode-oniguruma');

test('constraint keywords retain context across headers, comments, and ordinary identifiers', async () => {
    await oniguruma.loadWASM(fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm')));
    const extensionPath = process.env.MML_VSCODE_EXTENSION || path.join(__dirname, '..');
    const grammarPath = path.join(extensionPath, 'syntaxes/mml.tmLanguage.json');
    const registry = new textmate.Registry({
        onigLib: Promise.resolve({
            createOnigScanner: patterns => new oniguruma.OnigScanner(patterns),
            createOnigString: value => new oniguruma.OnigString(value)
        }),
        loadGrammar: async () => textmate.parseRawGrammar(fs.readFileSync(grammarPath, 'utf8'), grammarPath)
    });
    try {
        const grammar = await registry.loadGrammar('source.mml');
        const fixtures = JSON.parse(fs.readFileSync(path.join(__dirname, '../../test/constraint-highlighting.json'), 'utf8'));
        let stack = textmate.INITIAL;
        for (const { text, words } of fixtures) {
            const result = grammar.tokenizeLine(text, stack);
            stack = result.ruleStack;
            for (const [word, expected] of Object.entries(words)) {
                const matches = [...text.matchAll(new RegExp(`\\b${word}\\b`, 'g'))];
                assert.equal(matches.length, expected.length);
                matches.forEach((match, i) => {
                    const token = result.tokens.find(t => t.startIndex <= match.index && t.endIndex > match.index);
                    assert.equal(token.scopes.some(s => s.startsWith('keyword.')), expected[i], `${text} at ${match.index}: ${token.scopes}`);
                });
            }
        }
        // Each malformed header starts a separate document; later declarations must recover.
        for (const broken of ['fn broken(x: Int = x;;', 'fn broken(x: (Int -> Int;', 'fn broken(x: Int']) {
            const lines = [broken, "fn fine(value: 'T): Int where 'T: Read = 1;;", 'let n = 2;'];
            let recoveryStack = textmate.INITIAL;
            for (const [line, text] of lines.entries()) {
                const result = grammar.tokenizeLine(text, recoveryStack);
                recoveryStack = result.ruleStack;
                if (line === 0) continue;
                for (const match of text.matchAll(/\b(fn|where|let|[12])\b/g)) {
                    const token = result.tokens.find(t => t.startIndex <= match.index && t.endIndex > match.index);
                    const scope = /^[12]$/.test(match[0]) ? 'constant.numeric.' : 'keyword.';
                    assert.ok(token.scopes.some(s => s.startsWith(scope)), `${broken} → ${text} at ${match.index}: ${token.scopes}`);
                }
            }
        }
    } finally {
        registry.dispose();
    }
});
