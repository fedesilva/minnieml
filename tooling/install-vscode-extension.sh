#!/bin/bash

set -e # Exit immediately if a command exits with a non-zero status.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
EXTENSION_DIR="$SCRIPT_DIR/vscode"
# Read name and version from package.json to build the vsix filename
EXTENSION_NAME=$(grep -o '"name": *"[^"]*"' "$EXTENSION_DIR/package.json" | cut -d'"' -f4)
EXTENSION_VERSION=$(grep -o '"version": *"[^"]*"' "$EXTENSION_DIR/package.json" | cut -d'"' -f4)

if [ -z "$EXTENSION_NAME" ] || [ -z "$EXTENSION_VERSION" ]; then
    echo "ERROR: Could not read extension name or version from $EXTENSION_DIR/package.json"
    exit 1
fi

VSIX_FILE="${EXTENSION_NAME}-${EXTENSION_VERSION}.vsix"

echo "--- Installing/Updating MinnieML VS Code Extension ($EXTENSION_NAME v$EXTENSION_VERSION) ---"

# 1. Ensure vsce is installed
echo "[1/5] Checking/Installing @vscode/vsce..."
if ! npm list -g --depth=0 | grep -q '@vscode/vsce'; then
    echo "   vsce not found globally, installing..."
    if ! npm install -g @vscode/vsce; then
        echo "ERROR: Failed to install @vscode/vsce globally. Please check npm permissions."
        exit 1
    fi
    echo "   vsce installed globally."
else
    echo "   vsce is already installed globally."
fi

# 2. Find npm global prefix and vsce path
echo "[2/5] Finding vsce path..."
NPM_GLOBAL_PREFIX=$(npm prefix -g)
if [ -z "$NPM_GLOBAL_PREFIX" ]; then
    echo "ERROR: Could not determine npm global prefix."
    exit 1
fi
VSCE_PATH="$NPM_GLOBAL_PREFIX/bin/vsce"

if [ ! -x "$VSCE_PATH" ]; then
    # Try common alternative location for nvm users
    ALT_VSCE_PATH="$HOME/.nvm/versions/node/$(node -v)/bin/vsce"
    if [ -x "$ALT_VSCE_PATH" ]; then
        VSCE_PATH="$ALT_VSCE_PATH"
    else
      echo "ERROR: vsce executable not found at expected path: $VSCE_PATH"
      echo "       (Also checked: $ALT_VSCE_PATH)"
      echo "       Please ensure npm global bin directory is correctly set up or add it to your PATH."
      exit 1
    fi
fi
echo "   Found vsce at: $VSCE_PATH"

# 3. Package the extension
echo "[3/5] Packaging extension in $EXTENSION_DIR..."
cd "$EXTENSION_DIR"

# Remove old package if it exists
rm -f "$VSIX_FILE"

# Package using full path and bypass prompts for repository/license
# Use --no-dependencies if you don't have node_modules in the extension folder
if ! "$VSCE_PATH" package --no-dependencies --allow-missing-repository -o "$VSIX_FILE"; then
    echo "ERROR: Failed to package the extension using vsce."
    cd "$SCRIPT_DIR" # Go back to original script dir before exiting
    exit 1
fi
echo "   Successfully packaged: $VSIX_FILE"

# 4. Check for 'code' command
echo "[4/5] Checking for 'code' command..."
if ! command -v code &> /dev/null; then
    echo "ERROR: 'code' command not found in PATH."
    echo "       Please install it from VS Code:"
    echo "       1. Open VS Code."
    echo "       2. Open Command Palette (Cmd+Shift+P)."
    echo "       3. Type 'Shell Command' and select 'Shell Command: Install 'code' command in PATH'."
    echo "       4. Restart your terminal and run this script again."
    cd "$SCRIPT_DIR" # Go back
    exit 1
fi
echo "   'code' command found."

# 5. Install the .vsix package
echo "[5/5] Installing extension package: $VSIX_FILE..."
# Uninstall first to ensure a clean install, ignore errors if not installed
code --uninstall-extension "$EXTENSION_NAME" > /dev/null 2>&1 || true
if ! code --install-extension "$VSIX_FILE"; then
    echo "ERROR: Failed to install extension using 'code' command."
    cd "$SCRIPT_DIR" # Go back
    exit 1
fi

# Go back to the original directory
cd "$SCRIPT_DIR"

echo ""
echo "--- MinnieML VS Code Extension installed/updated successfully! ---"
echo "Please fully restart VS Code (Cmd+Q and reopen) for changes to take effect."

exit 0
