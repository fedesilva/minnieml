#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
extension_dir="$script_dir/zed-llvm-ir"
bundle_dir="$repo_root/build/zed-llvm-ir"
export XDG_CACHE_HOME="$repo_root/build/zed-cache"

case "${1:-}" in
  ""|--build-only) ;;
  *) printf 'Usage: %s [--build-only]\n' "$0" >&2; exit 1 ;;
esac
for tool in node npm cargo rustup tree-sitter curl tar; do
  command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done

revision="$(sed -n 's/^rev = "\([^"]*\)"/\1/p' "$extension_dir/extension.toml")"
grammar_dir="$repo_root/build/tree-sitter-llvm-$revision"
if [ ! -f "$grammar_dir/src/parser.c" ]; then
  mkdir -p "$grammar_dir"
  curl --fail --location --retry 3 \
    "https://api.github.com/repos/benwilliamgraham/tree-sitter-llvm/tarball/$revision" \
    --output "$grammar_dir/source.tar.gz"
  tar -xzf "$grammar_dir/source.tar.gz" --strip-components=1 -C "$grammar_dir"
fi

mkdir -p "$bundle_dir/grammars" "$bundle_dir/languages/llvm"
(
  cd "$grammar_dir"
  tree-sitter build --wasm --output "$bundle_dir/grammars/llvm.wasm"
)
npm ci --prefix "$script_dir/llvm-ir"
npm run build --prefix "$script_dir/llvm-ir"
rustup target add wasm32-wasip2
cargo build --manifest-path "$extension_dir/Cargo.toml" --locked --release \
  --target wasm32-wasip2 --target-dir "$repo_root/build/zed-ir-target"
cp "$repo_root/build/zed-ir-target/wasm32-wasip2/release/llvm_ir_navigation.wasm" \
  "$bundle_dir/extension.wasm"
cp "$extension_dir/extension.toml" "$extension_dir/GRAMMAR-LICENSE" "$bundle_dir/"
cp "$extension_dir"/languages/llvm/* "$bundle_dir/languages/llvm/"
cp "$script_dir/llvm-ir/out/server.cjs" "$bundle_dir/server.cjs"

if [ "${1:-}" = --build-only ]; then
  printf 'Built LLVM IR extension in %s\n' "$bundle_dir"
  exit 0
fi

if [ -n "${ZED_DATA_DIR:-}" ]; then
  zed_data_dir="$ZED_DATA_DIR"
else
  case "$(uname -s)" in
    Darwin) zed_data_dir="$HOME/Library/Application Support/Zed" ;;
    Linux) zed_data_dir="${XDG_DATA_HOME:-$HOME/.local/share}/zed" ;;
    *) printf 'Set ZED_DATA_DIR to your Zed data directory.\n' >&2; exit 1 ;;
  esac
fi
install_dir="$zed_data_dir/extensions/installed/mml-llvm-ir"
bin_dir="${LLVM_IR_BIN_DIR:-$HOME/.local/bin}"
if [ -L "$install_dir" ]; then
  unlink "$install_dir"
fi
mkdir -p "$install_dir" "$bin_dir"
cp -R "$bundle_dir/." "$install_dir/"
cp "$bundle_dir/server.cjs" "$bin_dir/mml-llvm-ir-server.cjs"
cat > "$bin_dir/mml-llvm-ir-lsp" <<'LAUNCHER'
#!/usr/bin/env sh
exec node "$(dirname "$0")/mml-llvm-ir-server.cjs" "$@"
LAUNCHER
chmod +x "$bin_dir/mml-llvm-ir-lsp"
printf 'Installed LLVM IR in %s\nServer: %s/mml-llvm-ir-lsp\n' "$install_dir" "$bin_dir"
printf 'Put the server directory and Node.js on PATH, restart Zed, and open an .ll file.\n'
