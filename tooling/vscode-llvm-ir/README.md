# LLVM IR Navigation Extension for VS Code

Editor-side LLVM IR support for VS Code with document-local symbol indexing, go-to-definition,
and hover information.

## Features

- Index LLVM IR declarations in the current file
- Go to definition for local and global symbols such as `%str.0`, `@puts`, `%MyType`, and labels
- Hover to inspect the declaration behind a symbol reference
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
