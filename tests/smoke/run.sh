#!/usr/bin/env bash
set -euo pipefail

TESTS_DIR="tests/smoke"
BUILD_DIR="build/test-smoke"

if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "all" ]; }; then
  echo "Usage: $0 [all]"
  exit 1
fi

if [ ! -d "$TESTS_DIR" ] || [ ! -f build.sbt ]; then
  echo "Error: run from the project root."
  exit 1
fi

mkdir -p "$BUILD_DIR/logs"
passed=0
failed=0

for src in "$TESTS_DIR"/*.mml; do
  name=$(basename "$src" .mml)
  log="$BUILD_DIR/logs/$name.log"

  # These samples exercise compilation without running renderers or returning a demo value.
  case "$name" in
    style-guide|lambda-factorial|raytracer3_p6)
      mode="compile"
      command="run -b $BUILD_DIR $src"
      ;;
    *)
      mode="compile and run"
      command="run run -b $BUILD_DIR $src"
      ;;
  esac

  printf '[smoke] %s (%s) ...\n' "$name" "$mode"
  # The thin client shares one server socket: keep all invocations sequential.
  if sbtn "$command" > "$log" 2>&1; then
    printf 'PASS %s\n' "$name"
    passed=$((passed + 1))
  else
    printf 'FAIL %s (log: %s)\n' "$name" "$log"
    tail -n 30 "$log"
    failed=$((failed + 1))
  fi
done

printf '\nSmoke: %s/%s passed\n' "$passed" "$((passed + failed))"
[ "$failed" -eq 0 ]
