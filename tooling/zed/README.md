# MinnieML for Zed

Syntax highlighting, bracket matching, outline entries, and indentation rules for
`.mml` files. Language-server integration is the next implementation chunk.

## Verify

Install the Tree-sitter CLI (tested with 0.27.0), Node.js, and a C compiler. From the
repository root:

```sh
bash tooling/zed/test.sh
```

The script regenerates the parser, checks the syntax and recovery corpus, parses
reference programs, and compiles/runs every Zed query. It uses the repository's
ignored `build/` directory for the native parser cache. It does not launch Zed;
indentation and rendering still require an editor check.

## Local installation

With Tree-sitter CLI 0.27+ and Node.js installed, run from the repository root:

```sh
./tooling/install-zed-extension.sh
```

The script generates the parser from the current working files, compiles its C
sources to WebAssembly, and copies the plugin into Zed's installed extensions
directory. Restart Zed and open an `.mml` file. No Git operations or Python are
involved. Rerun the same command after edits to update the installed plugin.

Tree-sitter downloads its WebAssembly build tools on first use and caches them
under the repository's ignored `build/zed-cache` directory. The compiled bundle
is also kept under `build/zed-minnieml`. Installation defaults to
`~/Library/Application Support/Zed` on macOS and `$XDG_DATA_HOME/zed` (or
`~/.local/share/zed`) on Linux. Set `ZED_DATA_DIR` for a custom Zed data directory.

The plugin is installed precompiled; do not use **Install Dev Extension** or Zed's
dev-extension rebuild command on its source folder. The empty grammar source
fields in the manifest are unused when loading the supplied WebAssembly file.
Use **zed: open log** to investigate loading errors.

## Grammar scope

The reusable grammar lives in `../tree-sitter-mml`. Its generated C parser and
headers are included because Zed compiles them directly. `src/scanner.c` is
handwritten and handles nested documentation comments. Regenerate the other
`src/` files with `tree-sitter generate` from the grammar directory.

Expressions retain a flat sequence of terms, matching the compiler's parsing
strategy. The editor grammar does not assign precedence to user-defined operators
or decide which identifiers are calls. LSP semantic tokens can supply that detail
when server integration is added.

This is an editing grammar, not a replacement validator: it accepts block comments
throughout source, generic native attribute values, and syntactically complete
bodies that may fail compiler checks. The compiler remains responsible for
declaration restrictions, types, ownership, and native attribute validation.

References: [Zed language extensions](https://zed.dev/docs/extensions/languages),
[Zed extension development](https://zed.dev/docs/extensions/developing-extensions),
and the [MML parser](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser).
