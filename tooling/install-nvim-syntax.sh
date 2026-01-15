#!/bin/bash

set -e # Exit immediately if a command exits with a non-zero status.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PLUGIN_DIR="$SCRIPT_DIR/nvim-syntax"

echo "--- Installing MinnieML Neovim Syntax Plugin ---"

# 1. Determine Neovim config directory
echo "[1/4] Locating Neovim config directory..."

# Check for XDG_CONFIG_HOME first, then fall back to ~/.config
if [ -n "$XDG_CONFIG_HOME" ]; then
    NVIM_CONFIG="$XDG_CONFIG_HOME/nvim"
else
    NVIM_CONFIG="$HOME/.config/nvim"
fi

# Check if directory exists
if [ ! -d "$NVIM_CONFIG" ]; then
    echo "   Neovim config directory not found at: $NVIM_CONFIG"
    echo "   Creating directory..."
    mkdir -p "$NVIM_CONFIG"
fi

echo "   Using Neovim config directory: $NVIM_CONFIG"

# 2. Check if nvim is installed (just a warning, not blocking)
echo "[2/4] Checking for Neovim installation..."
if command -v nvim &> /dev/null; then
    NVIM_VERSION=$(nvim --version | head -n1)
    echo "   Found: $NVIM_VERSION"
else
    echo "   WARNING: 'nvim' command not found in PATH."
    echo "   The plugin will be installed, but please ensure Neovim is installed to use it."
fi

# 3. Copy plugin files
echo "[3/4] Installing plugin files..."

# Create target directories
mkdir -p "$NVIM_CONFIG/ftdetect"
mkdir -p "$NVIM_CONFIG/syntax"
mkdir -p "$NVIM_CONFIG/after/plugin"

# Copy ftdetect file
echo "   Copying ftdetect/mml.vim..."
cp "$PLUGIN_DIR/ftdetect/mml.vim" "$NVIM_CONFIG/ftdetect/mml.vim"

# Copy syntax file
echo "   Copying syntax/mml.vim..."
cp "$PLUGIN_DIR/syntax/mml.vim" "$NVIM_CONFIG/syntax/mml.vim"

# Copy LSP config
echo "   Copying after/plugin/mml_lsp.lua..."
cp "$PLUGIN_DIR/after/plugin/mml_lsp.lua" "$NVIM_CONFIG/after/plugin/mml_lsp.lua"

echo "   Files installed successfully."

# 4. Verify installation
echo "[4/4] Verifying installation..."

if [ -f "$NVIM_CONFIG/ftdetect/mml.vim" ] && \
   [ -f "$NVIM_CONFIG/syntax/mml.vim" ] && \
   [ -f "$NVIM_CONFIG/after/plugin/mml_lsp.lua" ]; then
    echo "   ✓ ftdetect/mml.vim installed"
    echo "   ✓ syntax/mml.vim installed"
    echo "   ✓ after/plugin/mml_lsp.lua installed"
else
    echo "   ERROR: Installation verification failed."
    echo "   Some files may not have been copied correctly."
    exit 1
fi

echo ""
echo "--- MinnieML Neovim Plugin installed successfully! ---"
echo ""
echo "Installation location: $NVIM_CONFIG"
echo ""
echo "Features:"
echo "  - Syntax highlighting for .mml files"
echo "  - LSP support (requires nvim-lspconfig and mmlc in PATH)"
echo ""
echo "To verify:"
echo "  1. Open any .mml file in Neovim"
echo "  2. Run :set filetype? (should show 'filetype=mml')"
echo "  3. Run :LspInfo (should show 'mml' client attached)"
echo ""
echo "If you use a plugin manager (lazy.nvim, packer, etc.), you may want to"
echo "add this as a local plugin instead. See README.md for details."
echo ""

exit 0
