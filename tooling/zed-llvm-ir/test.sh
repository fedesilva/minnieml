#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"
export XDG_CACHE_HOME="$repo_root/build/zed-cache"

./tooling/install-zed-llvm-ir-extension.sh --build-only
npm test --prefix tooling/llvm-ir
npm ci --prefix tooling/vscode-llvm-ir
npm run compile --prefix tooling/vscode-llvm-ir
npm run build --prefix tooling/vscode-llvm-ir
cargo fmt --manifest-path tooling/zed-llvm-ir/Cargo.toml --check
cargo clippy --manifest-path tooling/zed-llvm-ir/Cargo.toml --locked \
  --target wasm32-wasip2 --target-dir build/zed-ir-target -- -D warnings

revision="$(sed -n 's/^rev = "\([^"]*\)"/\1/p' tooling/zed-llvm-ir/extension.toml)"
config="$repo_root/build/zed-llvm-ir-tree-sitter.json"
node -e 'console.log(JSON.stringify({"parser-directories": [process.argv[1]]}))' \
  "$repo_root/build" > "$config"
cd "$repo_root/build/tree-sitter-llvm-$revision"
fixture="$repo_root/tooling/llvm-ir/test/navigation.ll"
tree-sitter parse --config-path "$config" --grammar-path "$PWD" --quiet "$fixture"
for query in "$repo_root"/tooling/zed-llvm-ir/languages/llvm/*.scm; do
  tree-sitter query --config-path "$config" --grammar-path "$PWD" --quiet "$query" "$fixture" >/dev/null
done
printf 'LLVM IR server, editor adapters, grammar fixture, and Zed queries passed.\n'
