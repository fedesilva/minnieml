#!/bin/bash

handle_error() {
  echo -e "\033[0;31mError: $1\033[0m" >&2
  exit 1
}

# Default mode is binary.
mode="bin"

# Process flags (if any).
while [[ $# -gt 0 && "$1" == --* ]]; do
  case "$1" in
    --lib)
      if [[ "$mode" != "bin" ]]; then
        handle_error "Cannot specify both --lib and --bin."
      fi
      mode="lib"
      shift
      ;;
    --bin)
      if [[ "$mode" != "bin" ]]; then
        handle_error "Cannot specify both --lib and --bin."
      fi
      mode="bin"
      shift
      ;;
    *)
      handle_error "Unknown flag: $1"
      ;;
  esac
done

# Now there should be exactly one non-flag parameter: the input file.
if [ $# -ne 1 ]; then
  handle_error "Usage: $0 [--lib|--bin] <program.ll>"
fi

# Get input file and derive program name.
input_file="$1"
program_name="${input_file%.*}"  # Remove extension.
[[ ! -f "$input_file" ]] && handle_error "Input file ${input_file} not found"

# Detect OS and set the correct target triple.
OS="$(uname -s)"
case "$OS" in
  Darwin*) TARGET_TRIPLE="x86_64-apple-macosx" ;;  # macOS.
  Linux*)  TARGET_TRIPLE="x86_64-pc-linux-gnu" ;;    # Linux.
  *) handle_error "Unsupported OS: $OS" ;;
esac

echo "Using target triple: ${TARGET_TRIPLE}"
echo "Compilation mode: ${mode}"

# Create output directory if it doesn't exist.
mkdir -p out || handle_error "Failed to create output directory"

echo "Starting LLVM compilation pipeline for ${program_name}..."

# Step 1: IR → Bitcode.
echo "Converting IR to Bitcode..."
llvm-as "$input_file" -o "out/${program_name}.bc" || handle_error "Failed to convert IR to Bitcode"

# Step 2: Optimize IR.
echo "Optimizing IR..."
opt -O2 "out/${program_name}.bc" -o "out/${program_name}_opt.bc" || handle_error "Failed to optimize IR"

# Step 3: Generate optimized IR.
echo "Generating optimized IR..."
opt -O2 -S "out/${program_name}.bc" -o "out/${program_name}_opt.ll" || handle_error "Failed to generate optimized IR"

# Step 4: Bitcode → Assembly (force target triple).
echo "Converting Bitcode to Assembly..."
llc -mtriple="${TARGET_TRIPLE}" "out/${program_name}_opt.bc" -o "out/${program_name}.s" || handle_error "Failed to convert Bitcode to Assembly"

if [ "$mode" = "bin" ]; then
  # Step 5: Compile & Link.
  echo "Compiling and linking..."
  clang -target "${TARGET_TRIPLE}" "out/${program_name}.s" -o "out/${program_name}" || handle_error "Failed to compile and link"

  # Step 6: Run Executable.
  echo "Running executable..."
  pushd out > /dev/null || handle_error "Failed to change directory"
  ./"${program_name}"
  exit_code=$?
  popd > /dev/null
  echo "Program executed with exit code: ${exit_code}"
else
  # Library mode: compile to an object file and do not link.
  echo "Compiling as a library (object file only)..."
  clang -target "${TARGET_TRIPLE}" -c "out/${program_name}.s" -o "out/${program_name}.o" || handle_error "Failed to compile library object"
fi

# List generated files.
echo -e "\nGenerated files in ./out/:"
ls -l out/

if [ "$mode" = "bin" ]; then
  exit $exit_code
else
  exit 0
fi

