# MinnieML Neovim Plugin

Syntax highlighting and LSP support for MinnieML (`.mml`) files in Neovim.

## Features

- **LSP support** (requires [nvim-lspconfig](https://github.com/neovim/nvim-lspconfig)):
  - Diagnostics (parse and semantic errors)
  - Hover (type information)
  - Go to definition
  - Find references
  - Semantic tokens (rich syntax highlighting)

- **Syntax highlighting** for all MML language constructs:
  - Keywords: `fn`, `let`, `op`, `type`, `module`, `if`/`then`/`else`
  - Comments: line comments (`#`) and documentation comments (`#- ... -#`)
  - Operators: symbolic (`+`, `-`, `==`, etc.) and alphanumeric (`and`, `or`, `not`)
  - Literals: strings, integers, floats, booleans
  - Types: uppercase identifiers (`Int`, `String`, `Bool`, etc.)
  - Native annotations: `@native`
  - Special constants: `()`, `???`, `_`

- **File type detection** for `.mml` files
- **No dependencies** - pure Vim script implementation
- **Compatible** with Neovim and Vim 7.0+

## Installation

### Method 1: Manual Installation

Copy the plugin files to your Neovim configuration directory:

```bash
# Determine your Neovim config directory
# Usually: ~/.config/nvim (Linux/macOS) or ~/AppData/Local/nvim (Windows)

# Create directories if they don't exist
mkdir -p ~/.config/nvim/ftdetect
mkdir -p ~/.config/nvim/syntax

# Copy the files
cp ftdetect/mml.vim ~/.config/nvim/ftdetect/
cp syntax/mml.vim ~/.config/nvim/syntax/
```

No restart required - Neovim will automatically load the syntax when you open a `.mml` file.

### Method 2: Using the Install Script

From the project root:

```bash
./tooling/install-nvim-syntax.sh
```

The script will:
- Detect your Neovim config directory
- Copy the plugin files to the correct locations
- Verify the installation
- Provide usage instructions

### Method 3: Plugin Manager (Recommended for Development)

If you use a plugin manager like [lazy.nvim](https://github.com/folke/lazy.nvim), [packer.nvim](https://github.com/wbthomason/packer.nvim), or [vim-plug](https://github.com/junegunn/vim-plug), you can add this as a local plugin:

#### lazy.nvim

```lua
{
  dir = "~/Workshop/mine/mml/mml/tooling/nvim-syntax",
  name = "mml-syntax",
  ft = "mml",  -- Load only for .mml files
}
```

#### packer.nvim

```lua
use {
  "~/Workshop/mine/mml/mml/tooling/nvim-syntax",
  ft = "mml",
}
```

#### vim-plug

```vim
Plug '~/Workshop/mine/mml/mml/tooling/nvim-syntax'
```

## Usage

Once installed, syntax highlighting will activate automatically when you open any `.mml` file.

### Verification

Open a `.mml` file in Neovim and run:

```vim
:set filetype?
```

You should see: `filetype=mml`

### Testing

Open one of the sample files to see the syntax highlighting in action:

```bash
nvim mml/samples/hello.mml
nvim mml/samples/factorial.mml
nvim mml/samples/cond1.mml
```

## Customization

### Adjusting Colors

The plugin uses standard Vim highlight groups, so it will respect your color scheme. If you want to customize MML-specific highlighting, add to your `init.vim` or `init.lua`:

**Vim script:**
```vim
" After your colorscheme is loaded
hi mmlKeyword guifg=#ff0000
hi mmlType guifg=#00ff00
hi mmlOperator guifg=#0000ff
```

**Lua:**
```lua
vim.cmd([[
  hi mmlKeyword guifg=#ff0000
  hi mmlType guifg=#00ff00
  hi mmlOperator guifg=#0000ff
]])
```

### Available Highlight Groups

The following MML-specific highlight groups are defined:

- `mmlComment` - line and block comments
- `mmlDocComment` - documentation comments
- `mmlKeyword` - language keywords
- `mmlConditional` - if/then/else
- `mmlBoolean` - true/false
- `mmlConstant` - (), ???, _
- `mmlOperator` - operators
- `mmlString` - string literals
- `mmlNumber` - numeric literals
- `mmlFloat` - float literals
- `mmlType` - type identifiers
- `mmlNative` - @native annotations
- `mmlIdentifier` - variable/function names
- `mmlFunction` - function definitions

## Troubleshooting

### Syntax highlighting not working

1. Verify the filetype is set:
   ```vim
   :set filetype?
   ```
   Should show `filetype=mml`. If not, manually set it:
   ```vim
   :set filetype=mml
   ```

2. Check if the syntax files are loaded:
   ```vim
   :scriptnames
   ```
   Look for `ftdetect/mml.vim` and `syntax/mml.vim` in the output.

3. Ensure files are in the correct location:
   ```bash
   ls ~/.config/nvim/ftdetect/mml.vim
   ls ~/.config/nvim/syntax/mml.vim
   ```

4. Verify syntax highlighting is enabled globally:
   ```vim
   :syntax on
   ```

### Colors look wrong

Make sure you have a color scheme loaded. Add to your config:

```vim
colorscheme default  " or your preferred scheme
```

## Technical Details

- **Implementation**: Pure Vim regex-based syntax
- **Syntax priority**: Keywords > Types > Identifiers > Operators
- **Comment support**: Line comments (`#`) and nested doc comments (`#- ... -#`)
- **Operator precedence**: Not implemented in syntax layer (handled by parser)

## Prerequisites

- **For LSP support**:
  - [nvim-lspconfig](https://github.com/neovim/nvim-lspconfig) must be installed
  - `mmlc` must be in your PATH (run `sbt mmlcPublishLocal` from the MML repo)

## Future Enhancements

Potential improvements for the future:

- **Tree-sitter grammar** for more advanced features (code folding, textobjects)
- **Indentation rules** (`indent/mml.vim`)
- **Code folding** based on function/module boundaries

## License

Part of the MinnieML project.
