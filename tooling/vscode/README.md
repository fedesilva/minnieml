# MinnieML Language Extension for VS Code

Language support for MinnieML with syntax highlighting, diagnostics, hover, and navigation.

## Features

- Syntax highlighting for MinnieML language constructs
- Real-time error diagnostics (powered by mmlc compiler)
- Hover to see type information
- Go to Definition, Go to Implementations, and Find All References for functions and operators

## Protocol navigation

Use VS Code's native **Go to Definition**, **Go to Implementations**, and
**Find All References** actions from the editor context menu or command palette.
The language client registers these actions from the compiler's advertised capabilities.

- Definition on a protocol-member call opens its signature in the protocol.
- Implementations on a protocol name lists its concrete instances. On a signature,
  it lists that member's implementations.
- References on an instance header finds uses of that instance's members. On an
  individual member, it finds only that implementation's uses. References on the
  protocol signature includes uses across its instances.

The same actions work for named functions and operators. Navigation sends pending
editor changes before its request, including during `mml.didChangeDebounceMs`.
Restart the language server after installing an updated compiler.



## Requirements

- `mmlc` compiler must be installed and available in your PATH (or configured via settings)

## Installation

### Development Installation

1. Build the extension:
   ```bash
   cd tooling/vscode
   npm install
   npm run compile
   ```

2. Open the `tooling/vscode` folder in VS Code

3. Press F5 to launch the Extension Development Host

### Manual Installation

1. Copy the `tooling/vscode` directory to your VS Code extensions folder:
   - Windows: `%USERPROFILE%\.vscode\extensions\mml-language`
   - macOS: `~/.vscode/extensions/mml-language`
   - Linux: `~/.vscode/extensions/mml-language`

2. Make sure to include the compiled `out/` directory

3. Restart VS Code

## Configuration

- `mml.mmlcPath`: Path to the mmlc compiler executable (default: `mmlc`)
- `mml.didChangeDebounceMs`: Delay before sending edits (default: `250`; navigation flushes edits)

## Verification

Run `npm test` and `npm run build` from `tooling/vscode`. The tests activate the
extension with editor/client test doubles and check navigation forwarding, pending-edit
ordering, and empty results. They do not launch the VS Code extension host.

After publishing the compiler, run the shared server smoke check from the repository root:

```sh
python3 tooling/test-protocol-navigation.py
```

Use `--compiler /path/to/mmlc` to match `mml.mmlcPath`. It starts `mmlc lsp`, checks
the advertised capabilities and exact navigation destinations, and shuts down the server.

## How It Works

When you open a `.mml` file, the extension:
1. Starts `mmlc lsp`.
2. Sends open documents and edits to the compiler.
3. Receives diagnostics and semantic tokens.
4. Uses standard language-server requests for hover and navigation.

## File Associations

The extension automatically recognizes files with the `.mml` extension.
