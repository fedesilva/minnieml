#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
export XDG_CACHE_HOME="$repo_root/build/zed-cache"
cd "$repo_root/tooling/tree-sitter-mml"

tree-sitter generate
tree-sitter test

samples=(
  "$repo_root/benchmark/nqueens.mml"
  "$repo_root/benchmark/mat-mul.mml"
  "$repo_root/mml/samples/astar2.mml"
  "$repo_root/mml/samples/raytracer.mml"
  "$repo_root/mml/samples/lambda-factorial.mml"
  "$repo_root/mml/samples/link-zlib.mml"
)
tree-sitter parse --quiet "${samples[@]}"

for query in "$repo_root"/tooling/zed/languages/mml/*.scm; do
  tree-sitter query --quiet "$query" "${samples[@]}" > /dev/null
done

printf 'MinnieML grammar, reference programs, and Zed queries passed.\n'
