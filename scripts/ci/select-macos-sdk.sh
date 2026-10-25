#!/usr/bin/env bash
set -euo pipefail

config_dir=$1
shift
mkdir -p build/ci
probe_dir=$(mktemp -d "$RUNNER_TEMP/mml-sdk.XXXXXX")
trap 'rm -rf "$probe_dir"' EXIT
printf '#include <stdio.h>\nint main(void) { return puts("SDK probe passed") < 0; }\n' > "$probe_dir/probe.c"

if [ "$#" -eq 0 ]; then
  set -- /Applications/Xcode_*.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk
fi
for sdk in "$@"; do
  [ -d "$sdk" ] || continue
  echo "Testing SDK: $sdk" | tee -a build/ci/sdk-probe.log
  if clang -isysroot "$sdk" -target arm64-apple-macosx -fuse-ld=lld \
      "$probe_dir/probe.c" -o "$probe_dir/probe" >> build/ci/sdk-probe.log 2>&1 && \
      "$probe_dir/probe" >> build/ci/sdk-probe.log 2>&1; then
    # Homebrew target configs supply -isysroot even when SDKROOT is set.
    python3 - "$config_dir" "$sdk" <<'PY'
from pathlib import Path
import re
import sys

configs = list(Path(sys.argv[1]).glob("*apple*.cfg"))
if not configs:
    sys.exit("No Homebrew Apple target configs found")
for config in configs:
    text = config.read_text()
    text = re.sub(r"(?m)^-isysroot .*$", "", text)
    config.write_text(text.rstrip() + f'\n-isysroot "{sys.argv[2]}"\n')
PY
    # Check the implicit selection used by compiler subprocesses, including resolved clang paths.
    clang -### -target arm64-apple-macosx -fuse-ld=lld "$probe_dir/probe.c" \
      -o "$probe_dir/probe" 2> build/ci/sdk-driver.log
    if ! grep -F -- "\"-syslibroot\" \"$sdk\"" build/ci/sdk-driver.log; then
      cat build/ci/sdk-driver.log
      echo 'Clang did not forward the selected SDK to the linker.' >&2
      exit 1
    fi
    clang -target arm64-apple-macosx -fuse-ld=lld "$probe_dir/probe.c" \
      -o "$probe_dir/probe" >> build/ci/sdk-probe.log 2>&1
    "$probe_dir/probe" >> build/ci/sdk-probe.log 2>&1
    echo "SDKROOT=$sdk" >> "$GITHUB_ENV"
    echo "Selected SDK: $sdk" | tee -a build/ci/sdk-probe.log
    exit 0
  fi
done

cat build/ci/sdk-probe.log
echo 'No installed Xcode SDK passed the selected LLVM compile/link/run probe.' >&2
exit 1
