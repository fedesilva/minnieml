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
progress() { printf "%s\n" "$1"; }

ASAN_OPTS="detect_leaks=1:halt_on_error=1:abort_on_error=1"

usage() {
  echo "Usage: $0 [all]"
  echo "  Runs memory tests in one ASan+LSan pass (single compile + single run per test)."
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

run_mem() {
  printf "\n${BOLD}=== ASan+LSan mode ===${RESET}\n\n"
  clean_build
  local start_ts
  start_ts=$(date +%s)

  local passed=0 failed=0 total=0 failures=""
  local tests
  tests="$(collect_tests)"
  total=$(printf "%s\n" "$tests" | wc -l | tr -d ' ')
  local current=0

  for src in $tests; do
    name=$(basename "$src" .mml)
    binary="$BUILD_DIR/$name"
    current=$((current + 1))
    compile_start=$(date +%s)
    progress "[mem] ($current/$total) compiling $name ..."

    # Compile once with ASan instrumentation; leak detection runs via ASAN_OPTIONS.
    if mmlc -s -b "$BUILD_DIR" -o "$binary" "$src" > /dev/null 2>&1; then
      compile_end=$(date +%s)
      compile_elapsed=$((compile_end - compile_start))
      progress "[mem] ($current/$total) compiled $name (${compile_elapsed}s)"
      run_start=$(date +%s)
      progress "[mem] ($current/$total) running $name ..."
    else
      compile_end=$(date +%s)
      compile_elapsed=$((compile_end - compile_start))
      fail "$name (compile)"
      progress "[mem] ($current/$total) compile failed $name (${compile_elapsed}s)"
      failed=$((failed + 1))
      failures="$failures $name"
      continue
    fi

    if ASAN_OPTIONS="$ASAN_OPTS" "$binary" > /dev/null 2>&1; then
      run_end=$(date +%s)
      run_elapsed=$((run_end - run_start))
      progress "[mem] ($current/$total) ran $name (${run_elapsed}s)"
      pass "$name"
      passed=$((passed + 1))
    else
      run_end=$(date +%s)
      run_elapsed=$((run_end - run_start))
      progress "[mem] ($current/$total) run failed $name (${run_elapsed}s)"
      fail "$name"
      failed=$((failed + 1))
      failures="$failures $name"
    fi
  done

  local end_ts elapsed
  end_ts=$(date +%s)
  elapsed=$((end_ts - start_ts))

  printf "\n${BOLD}ASan+LSan: %d/%d passed${RESET}" "$passed" "$total"
  if [ "$failed" -gt 0 ]; then
    printf " (${RED}%d failed:${RESET}%s)" "$failed" "$failures"
  fi
  printf " (${elapsed}s)\n"
  return "$failed"
}

# Main
[ $# -gt 1 ] && usage
[ $# -eq 1 ] && [ "$1" != "all" ] && usage

exit_code=0

run_mem || exit_code=1

clean_build
exit "$exit_code"
