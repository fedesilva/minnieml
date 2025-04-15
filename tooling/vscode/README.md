# MinnieML Language Extension for VS Code

This extension provides syntax highlighting support for the MinnieML language in Visual Studio Code.

## Features

- Syntax highlighting for MinnieML language constructs:
  - Module declarations
  - Function definitions
  - Operator definitions
  - Type annotations
  - Control flow statements (if/then/else)
  - Literals (strings, numbers, booleans, unit)
  - Comments and documentation comments
  - Built-in keywords and special symbols

## Installation

### Manual Installation

1. Clone this repository or download the extension files
2. Copy the contents of the `tooling/vscode` directory to a new folder in your VS Code extensions directory:
   - Windows: `%USERPROFILE%\.vscode\extensions\mml-language`
   - macOS: `~/.vscode/extensions/mml-language`
   - Linux: `~/.vscode/extensions/mml-language`
3. Make sure all files from `tooling/vscode` are directly inside the `mml-language` folder:
   ```
   .vscode/extensions/mml-language/
   ├── package.json
   ├── language-configuration.json
   ├── README.md
   ├── .vscodeignore
   └── syntaxes/
       └── mml.tmLanguage.json
   ```
4. Restart VS Code

### Building from Source and Installing

1. Navigate to the extension directory

```bash
cd tooling/vscode
```

2. Install the required tools (if not already installed)

```bash
npm install -g @vscode/vsce
```

3. Package the extension

```bash
vsce package
```

4. Install the extension from the generated .vsix file

```bash
code --install-extension mml-language-0.1.0.vsix
```

## Current Capabilities

The extension currently provides syntax highlighting for:

- Keywords: `module`, `fn`, `let`, `op`, `if`, `then`, `else`, etc.
- Operators: `=`, `;`, `:`, and custom operators
- Literals: strings, integers, floats, booleans, unit
- Comments: Line comments (`#`) and doc comments (`#-...-#`)
- Types: Type names starting with uppercase letters
- Identifiers: Variable and function names
- Native syntax: `@native` tags

## File Associations

The extension automatically recognizes files with the `.mml` extension.

## Future Improvements

Potential future enhancements:

- Code completion
- Go to definition
- Hover information
- Error checking
- Code folding
- Snippets for common MML patterns
