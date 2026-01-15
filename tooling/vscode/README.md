# MinnieML Language Extension for VS Code

Language support for MinnieML with syntax highlighting, diagnostics, and hover information.

## Features

- Syntax highlighting for MinnieML language constructs
- Real-time error diagnostics (powered by mmlc compiler)
- Hover to see type information

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

## How It Works

When you open a `.mml` file, the extension:
1. Spawns `mmlc dev <file> --lsp` for that file
2. The compiler watches the file and recompiles on changes
3. Diagnostics (errors) are pushed to VS Code
4. Hover requests return type information from the AST

## File Associations

The extension automatically recognizes files with the `.mml` extension.
