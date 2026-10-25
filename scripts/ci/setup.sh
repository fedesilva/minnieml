#!/usr/bin/env bash
set -euo pipefail

sbt_version=$(sed -n 's/^sbt.version=//p' project/build.properties)
install_dir="$RUNNER_TEMP/mml-tools"
mkdir -p "$install_dir"
curl --fail --location --retry 3 \
  "https://github.com/sbt/sbt/releases/download/v$sbt_version/sbt-$sbt_version.tgz" \
  -o "$install_dir/sbt.tgz"
tar -xzf "$install_dir/sbt.tgz" -C "$install_dir"

if [ "$(uname -s)" = Darwin ]; then
  brew install llvm lld hyperfine coreutils
  llvm_prefix=$(brew --prefix llvm)
  lld_prefix=$(brew --prefix lld)
  if [[ "$("$llvm_prefix/bin/llvm-config" --version)" != 23.* ]] || \
     [[ "$("$lld_prefix/bin/ld.lld" --version)" != *"LLD 23."* ]]; then
    echo 'macOS verification requires LLVM and lld 23.' >&2
    exit 1
  fi
  echo "$(brew --prefix coreutils)/libexec/gnubin" >> "$GITHUB_PATH"
  echo "$llvm_prefix/bin" >> "$GITHUB_PATH"
  echo "$lld_prefix/bin" >> "$GITHUB_PATH"
  PATH="$llvm_prefix/bin:$lld_prefix/bin:$PATH" \
    bash scripts/ci/select-macos-sdk.sh "$llvm_prefix/etc/clang"
else
  curl --fail --location --retry 3 https://apt.llvm.org/llvm.sh -o "$install_dir/llvm.sh"
  sudo bash "$install_dir/llvm.sh" 20
  sudo apt-get install -y llvm-20 lld-20 libclang-rt-20-dev hyperfine
  echo /usr/lib/llvm-20/bin >> "$GITHUB_PATH"
fi

mkdir -p "$HOME/bin"
test -x "$install_dir/sbt/bin/sbt"
echo "$install_dir/sbt/bin" >> "$GITHUB_PATH"
echo "$HOME/bin" >> "$GITHUB_PATH"
