import {
    createConnection, ProposedFeatures, TextDocuments, TextDocumentSyncKind
} from 'vscode-languageserver/node';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { buildIndex, collectReferenceLocations, resolveSymbol, sameRange, sourceDocument } from './index';

const connection = createConnection(ProposedFeatures.all);
const documents = new TextDocuments(TextDocument);

connection.onInitialize(() => ({
    capabilities: {
        positionEncoding: 'utf-16',
        textDocumentSync: TextDocumentSyncKind.Full,
        definitionProvider: true,
        hoverProvider: true,
        referencesProvider: true,
        documentSymbolProvider: true
    }
}));

function source(uri: string) {
    const document = documents.get(uri);
    return document && sourceDocument(uri, document.getText());
}

connection.onDefinition(({ textDocument, position }) => {
    const document = source(textDocument.uri);
    const symbol = document && resolveSymbol(document, position);
    return symbol ? { uri: textDocument.uri, range: symbol.declaration.selectionRange } : null;
});

connection.onHover(({ textDocument, position }) => {
    const document = source(textDocument.uri);
    const symbol = document && resolveSymbol(document, position);
    return symbol ? { contents: { language: 'llvm', value: symbol.declaration.declaration } } : null;
});

connection.onReferences(({ textDocument, position, context }) => {
    const document = source(textDocument.uri);
    const symbol = document && resolveSymbol(document, position);
    return document && symbol ? collectReferenceLocations(document, symbol).filter(location =>
        context.includeDeclaration || !sameRange(location.range, symbol.declaration.selectionRange)
    ) : [];
});

connection.onDocumentSymbol(({ textDocument }) => {
    const document = source(textDocument.uri);
    return document ? buildIndex(document).outlineSymbols : [];
});

documents.listen(connection);
connection.listen();
