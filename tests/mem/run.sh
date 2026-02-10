#!/usr/bin/env bash
set -euo pipefail

BUILD_DIR="build/test-mem"
TESTS_DIR="tests/mem"

# Color output (if terminal)
if [ -t 1 ]; then
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  BOLD='\033[1m'
  RESET='\033[0m'
else
  GREEN=''
  RED=''
  BOLD=''
  RESET=''
fi

pass() { printf "${GREEN}PASS${RESET} %s\n" "$1"; }
fail() { printf "${RED}FAIL${RESET} %s\n" "$1"; }

usage() {
  echo "Usage: $0 {asan|leaks|all}"
  echo "  asan   Compile+run each test with AddressSanitizer"
  echo "  leaks  Compile each test, check with leaks --atExit"
  echo "  all    Run both asan and leaks"
  exit 1
}

# Must run from project root
if [ ! -d "$TESTS_DIR" ]; then
  echo "Error: must run from the project root (could not find $TESTS_DIR/)"
  exit 1
fi

collect_tests() {
  find "$TESTS_DIR" -maxdepth 1 -name '*.mml' | sort
}

clean_build() {
  mmlc clean -b "$BUILD_DIR" > /dev/null 2>&1 || true
  rm -rf "$BUILD_DIR"
}

run_asan() {
  printf "\n${BOLD}=== ASan mode ===${RESET}\n\n"
  clean_build

  local passed=0 failed=0 total=0 failures=""

  for src in $(collect_tests); do
    name=$(basename "$src" .mml)
    binary="$BUILD_DIR/${name}-asan"
    total=$((total + 1))

    # Compile with ASan, then run the binary directly
    if mmlc -s -b "$BUILD_DIR" -o "$binary" "$src" > /dev/null 2>&1 \
       && "$binary" > /dev/null 2>&1; then
      pass "$name"
      passed=$((passed + 1))
    else
      fail "$name"
      failed=$((failed + 1))
      failures="$failures $name"
    fi
  done

  printf "\n${BOLD}ASan: %d/%d passed${RESET}" "$passed" "$total"
  if [ "$failed" -gt 0 ]; then
    printf " (${RED}%d failed:${RESET}%s)" "$failed" "$failures"
  fi
  printf "\n"
  return "$failed"
}

run_leaks() {
  printf "\n${BOLD}=== Leaks mode ===${RESET}\n\n"
  clean_build

  # Compile all first
  local compile_failures=""
  for src in $(collect_tests); do
    name=$(basename "$src" .mml)
    if ! mmlc -b "$BUILD_DIR" -o "$BUILD_DIR/$name" "$src" > /dev/null 2>&1; then
      compile_failures="$compile_failures $name"
    fi
  done

  if [ -n "$compile_failures" ]; then
    printf "${RED}Compile failures:${RESET}%s\n" "$compile_failures"
  fi

  # Check leaks on compiled binaries
  local passed=0 failed=0 total=0 failures=""

  for src in $(collect_tests); do
    name=$(basename "$src" .mml)
    binary="$BUILD_DIR/$name"

    if [ ! -x "$binary" ]; then
      fail "$name (not compiled)"
      failed=$((failed + 1))
      total=$((total + 1))
      failures="$failures $name"
      continue
    fi

    total=$((total + 1))
    leaks_output=$(leaks --atExit -- "$binary" 2>&1) || true

    if echo "$leaks_output" | grep -q "0 leaks for 0 total leaked bytes"; then
      pass "$name"
      passed=$((passed + 1))
    else
      fail "$name"
      failed=$((failed + 1))
      failures="$failures $name"
      # Show the leak summary line
      echo "$leaks_output" | grep -E "leaks for .* total leaked bytes" || true
    fi
  done

  printf "\n${BOLD}Leaks: %d/%d passed${RESET}" "$passed" "$total"
  if [ "$failed" -gt 0 ]; then
    printf " (${RED}%d failed:${RESET}%s)" "$failed" "$failures"
  fi
  printf "\n"
  return "$failed"
}

# Main
[ $# -lt 1 ] && usage

mode="$1"
exit_code=0

case "$mode" in
  asan)
    run_asan || exit_code=1
    ;;
  leaks)
    run_leaks || exit_code=1
    ;;
  all)
    run_asan || exit_code=1
    run_leaks || exit_code=1
    ;;
  *)
    usage
    ;;
esac

clean_build
exit "$exit_code"
