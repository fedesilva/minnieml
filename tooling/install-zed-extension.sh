#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
bundle_dir="$repo_root/build/zed-minnieml"
export XDG_CACHE_HOME="$repo_root/build/zed-cache"

if ! command -v tree-sitter > /dev/null || ! command -v node > /dev/null ||
   ! command -v cargo > /dev/null || ! command -v rustup > /dev/null; then
  printf 'Install Tree-sitter CLI 0.27+, Node.js, and Rust via rustup, then run this script again.\n' >&2
  exit 1
fi

if [ -n "${ZED_DATA_DIR:-}" ]; then
  zed_data_dir="$ZED_DATA_DIR"
else
  case "$(uname -s)" in
    Darwin) zed_data_dir="$HOME/Library/Application Support/Zed" ;;
    Linux) zed_data_dir="${XDG_DATA_HOME:-$HOME/.local/share}/zed" ;;
    *) printf 'Set ZED_DATA_DIR to your Zed user-data directory.\n' >&2; exit 1 ;;
  esac
fi
install_dir="$zed_data_dir/extensions/installed/minnieml"

printf 'Building MinnieML grammar...\n'
mkdir -p "$bundle_dir/grammars"
(
  cd "$script_dir/tree-sitter-mml"
  tree-sitter generate
  tree-sitter build --wasm --output "$bundle_dir/grammars/mml.wasm"
)

printf 'Building MinnieML language-server adapter...\n'
rustup target add wasm32-wasip2
cargo build --manifest-path "$script_dir/zed/Cargo.toml" --locked --release \
  --target wasm32-wasip2 --target-dir "$repo_root/build/zed-target"
cp "$repo_root/build/zed-target/wasm32-wasip2/release/minnieml.wasm" "$bundle_dir/extension.wasm"

cp "$script_dir/zed/extension.toml" "$bundle_dir/extension.toml"
mkdir -p "$bundle_dir/languages/mml"
cp "$script_dir"/zed/languages/mml/* "$bundle_dir/languages/mml/"

# Replace a dev-extension link without writing through it into a source checkout.
if [ -L "$install_dir" ]; then
  unlink "$install_dir"
fi
mkdir -p "$install_dir"
cp -R "$bundle_dir/." "$install_dir/"

printf 'Installed MinnieML in %s\nRestart Zed and open an .mml file.\n' "$install_dir"
