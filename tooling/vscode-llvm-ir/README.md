# LLVM IR Navigation Extension for VS Code

Editor-side LLVM IR support for VS Code with document-local symbol indexing, go-to-definition,
and hover information.

## Features

- Index LLVM IR declarations in the current file
- Go to definition for local and global symbols such as `%str.0`, `@puts`, `%MyType`, and labels
- Hover to inspect the declaration behind a symbol reference
- Find references within the current file or function
- Outline support for top-level declarations and function labels

## Scope

- Purely in-editor implementation
- No backend, compiler integration, or language server

## Installation

Use the dedicated installer:

```bash
./tooling/install-vscode-llvm-ir-extension.sh
```

## Notes

- The extension targets `.ll` files.
- Symbol lookup is document-local. Local `%` values and labels resolve within their containing
  function; global declarations resolve across the file.

## Development

The editor adapter uses the shared indexer in `../llvm-ir`. Install both sets of
build dependencies before compiling from a fresh checkout:

```sh
npm ci --prefix tooling/llvm-ir
npm ci --prefix tooling/vscode-llvm-ir
npm run compile --prefix tooling/vscode-llvm-ir
npm run build --prefix tooling/vscode-llvm-ir
```

`compile` and `watch` type-check; `build` produces the bundled extension. The installer
performs dependency installation automatically. See the [shared indexer](../llvm-ir/README.md)
for tests and the [Zed port](../zed-llvm-ir/README.md) for scanner limitations.
