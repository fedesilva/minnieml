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
  local start_ts
  start_ts=$(date +%s)

  local passed=0 failed=0 total=0 failures=""
  local tests
  tests="$(collect_tests)"
  total=$(printf "%s\n" "$tests" | wc -l | tr -d ' ')
  local current=0

  for src in $tests; do
    name=$(basename "$src" .mml)
    binary="$BUILD_DIR/${name}-asan"
    current=$((current + 1))
    compile_start=$(date +%s)
    progress "[asan] ($current/$total) compiling $name ..."

    # Compile with ASan, then run the binary directly
    if mmlc -s -b "$BUILD_DIR" -o "$binary" "$src" > /dev/null 2>&1; then
      compile_end=$(date +%s)
      compile_elapsed=$((compile_end - compile_start))
      progress "[asan] ($current/$total) compiled $name (${compile_elapsed}s)"
      run_start=$(date +%s)
      progress "[asan] ($current/$total) running $name ..."
    else
      compile_end=$(date +%s)
      compile_elapsed=$((compile_end - compile_start))
      fail "$name (compile)"
      progress "[asan] ($current/$total) compile failed $name (${compile_elapsed}s)"
      failed=$((failed + 1))
      failures="$failures $name"
      continue
    fi

    if "$binary" > /dev/null 2>&1; then
      run_end=$(date +%s)
      run_elapsed=$((run_end - run_start))
      progress "[asan] ($current/$total) ran $name (${run_elapsed}s)"
      pass "$name"
      passed=$((passed + 1))
    else
      run_end=$(date +%s)
      run_elapsed=$((run_end - run_start))
      progress "[asan] ($current/$total) run failed $name (${run_elapsed}s)"
      fail "$name"
      failed=$((failed + 1))
      failures="$failures $name"
    fi
  done

  local end_ts elapsed
  end_ts=$(date +%s)
  elapsed=$((end_ts - start_ts))

  printf "\n${BOLD}ASan: %d/%d passed${RESET}" "$passed" "$total"
  if [ "$failed" -gt 0 ]; then
    printf " (${RED}%d failed:${RESET}%s)" "$failed" "$failures"
  fi
  printf " (${elapsed}s)\n"
  return "$failed"
}

run_leaks() {
  printf "\n${BOLD}=== Leaks mode ===${RESET}\n\n"
  clean_build
  local start_ts
  start_ts=$(date +%s)
  local tests
  tests="$(collect_tests)"
  local total
  total=$(printf "%s\n" "$tests" | wc -l | tr -d ' ')

  local passed=0 failed=0 failures=""
  local current=0

  for src in $tests; do
    name=$(basename "$src" .mml)
    binary="$BUILD_DIR/$name"
    current=$((current + 1))

    compile_start=$(date +%s)
    progress "[leaks] ($current/$total) compiling $name ..."
    if ! mmlc -b "$BUILD_DIR" -o "$binary" "$src" > /dev/null 2>&1; then
      compile_end=$(date +%s)
      compile_elapsed=$((compile_end - compile_start))
      progress "[leaks] ($current/$total) compile failed $name (${compile_elapsed}s)"
      fail "$name (compile)"
      failed=$((failed + 1))
      failures="$failures $name"
      continue
    else
      compile_end=$(date +%s)
      compile_elapsed=$((compile_end - compile_start))
      progress "[leaks] ($current/$total) compiled $name (${compile_elapsed}s)"
    fi

    check_start=$(date +%s)
    progress "[leaks] ($current/$total) checking leaks $name ..."
    leaks_output=$(leaks --atExit -- "$binary" 2>&1) || true

    if echo "$leaks_output" | grep -q "0 leaks for 0 total leaked bytes"; then
      check_end=$(date +%s)
      check_elapsed=$((check_end - check_start))
      progress "[leaks] ($current/$total) checked $name (${check_elapsed}s)"
      pass "$name"
      passed=$((passed + 1))
    else
      check_end=$(date +%s)
      check_elapsed=$((check_end - check_start))
      progress "[leaks] ($current/$total) leak check failed $name (${check_elapsed}s)"
      fail "$name"
      failed=$((failed + 1))
      failures="$failures $name"
      # Show the leak summary line
      echo "$leaks_output" | grep -E "leaks for .* total leaked bytes" || true
    fi
  done

  local end_ts elapsed
  end_ts=$(date +%s)
  elapsed=$((end_ts - start_ts))

  printf "\n${BOLD}Leaks: %d/%d passed${RESET}" "$passed" "$total"
  if [ "$failed" -gt 0 ]; then
    printf " (${RED}%d failed:${RESET}%s)" "$failed" "$failures"
  fi
  printf " (${elapsed}s)\n"
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
