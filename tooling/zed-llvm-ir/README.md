# LLVM IR Navigation for Zed

LLVM IR highlighting, bracket matching, indentation, outlines, definitions, hover,
and references for `.ll` files. Navigation is document-local: local values and labels
resolve inside their function; globals resolve across the open file. The language
server shares the indexer used by `../vscode-llvm-ir` and does not require LLVM or MML.

## Install

Requires Node.js and npm, Rust through rustup, Tree-sitter CLI 0.27+, curl, and tar.
From the repository root:

```sh
./tooling/install-zed-llvm-ir-extension.sh
```

The installer builds the grammar and Rust adapter to WebAssembly, bundles the Node.js
server, and copies the extension into Zed's installed extensions directory. It pins
the grammar revision in `extension.toml`; first use downloads that source and
Tree-sitter's WebAssembly build tools. Rust and npm use committed lockfiles.

The server launcher is installed as `~/.local/bin/mml-llvm-ir-lsp`. Put `~/.local/bin`
and Node.js on your shell's PATH, restart Zed, and open an `.ll` file. Use Zed's normal
Go to Definition, Find References, hover, and outline controls.

Set `LLVM_IR_BIN_DIR` to choose another launcher directory. Set `ZED_DATA_DIR` for a
custom Zed data directory. Defaults are `~/Library/Application Support/Zed` on macOS
and `$XDG_DATA_HOME/zed` (or `~/.local/share/zed`) on Linux. Run the installer again to
update. `--build-only` writes a bundle under `build/zed-llvm-ir` without installing it.

This is a precompiled installation. Use the script to rebuild it. Zed's **Install
Dev Extension** can build the extension sources, but does not install the Node.js server.
If another extension already owns `.ll`, disable that language provider before testing.

## Settings

If the launcher is not on PATH, configure its absolute path in Zed:

```json
{
  "lsp": {
    "llvm-ir": {
      "binary": {
        "path": "/absolute/path/to/mml-llvm-ir-lsp",
        "arguments": ["--stdio"]
      }
    }
  }
}
```

`arguments` replaces the default `--stdio`; `binary.env` overrides individual
shell environment variables. Use **editor: restart language server** after changes.
Use Zed's language-server logs for startup errors and **zed: open log** for grammar
or extension-loading errors.

## Verification

```sh
./tooling/zed-llvm-ir/test.sh
```

This builds the bundle, runs indexer and stdio protocol tests, compiles the VS Code
adapter, checks Rust formatting and Clippy, and parses the fixture with every Zed
query. It does not launch Zed. Check rendering, indentation, outlines, hover,
definitions, and references in the editor with `../llvm-ir/test/navigation.ll`.

## Scope and dependencies

The indexer is a lightweight scanner, not an LLVM validator. Function headers must
be on one line, and function definitions must put the opening brace on that line
and the closing brace on its own line. Navigation does not cover implicit unnamed
parameters or cross-file references. No diagnostics, completion, rename, or formatting
are advertised. Source changes are sent as full documents, so requests see unsaved edits.

Tree-sitter supplies Zed's outline (including functions, labels, types, globals,
COMDAT, attribute groups, and metadata). The language server also supplies document
symbols for clients that use them. Syntax parsing and navigation use separate
representations; syntax highlighting can recognize constructs outside the scanner's scope.

The grammar and adapted highlight queries come from
[tree-sitter-llvm](https://github.com/benwilliamgraham/tree-sitter-llvm), at the revision
in `extension.toml`. Its MIT license is included as `GRAMMAR-LICENSE`. The adapter uses
Zed extension API 0.7.0. See [Zed language extensions](https://zed.dev/docs/extensions/languages).
