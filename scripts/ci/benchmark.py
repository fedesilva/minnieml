"""Measure the binaries selected by the benchmark Makefile, with bounded executions."""

import json
import os
from pathlib import Path
import shlex
import sys

from process import run_command


def measure(output_dir, binaries):
    output_dir.mkdir(parents=True, exist_ok=True)
    failures = []
    for binary in binaries:
        name = Path(binary).name
        arguments = []
        if name.startswith("sieve-"):
            arguments = ["100000" if name.endswith("-no-tco") else "10000000"]
        command = shlex.join(["timeout", "--signal=KILL", "300", binary, *arguments])
        if not Path(binary).is_file() or not os.access(binary, os.X_OK):
            message = f"Missing or non-executable benchmark binary: {binary}"
            print(message, flush=True)
            (output_dir / f"{name}.hyperfine.log").write_text(message + "\n")
            code = 127
        else:
            code, _ = run_command([
                "hyperfine", "-N", "--warmup", "2", "--runs", "10",
                "--command-name", shlex.join([name, *arguments]),
                "--output", str(output_dir / f"{name}.output.log"),
                "--export-json", str(output_dir / f"{name}.json"),
                "--export-csv", str(output_dir / f"{name}.csv"), command,
            ], output_dir / f"{name}.hyperfine.log", 3700)
        failures.append({"binary": name, "arguments": arguments, "exit_code": code})
        (output_dir / "status.json").write_text(json.dumps(failures, indent=2) + "\n")
        print(f"{name}: {'pass' if code == 0 else 'FAIL'}", flush=True)
    return int(any(result["exit_code"] for result in failures))


if __name__ == "__main__":
    sys.exit(measure(Path(sys.argv[1]).resolve(), sys.argv[2:]))
