"""Execute sequential compiler checks and render retained results for GitHub Actions."""

import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys

from process import run_command

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build/ci"
CHECKS = ["sanitizers", "style", "compile", "smoke", "tests", "publish", "memory", "tail-O0", "tail-O3",
          "tail-stderr-control", "benchmark-build", "benchmark-run"]


def capture(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        return (result.stdout + result.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as error:
        return str(error)


def save(checks):
    (OUTPUT / "checks.json").write_text(json.dumps(checks, indent=2) + "\n")


def verify():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    metadata = {
        "commit": capture(["git", "rev-parse", "HEAD"]),
        "OS": platform.platform(), "architecture": platform.machine(),
        "runner": os.environ.get("RUNNER_NAME", "local"),
        "image": os.environ.get("ImageVersion", "local"),
        "SDKROOT": os.environ.get("SDKROOT", "not selected"),
        "CPU": capture(["sysctl", "-n", "machdep.cpu.brand_string"])
        if sys.platform == "darwin" else capture(["lscpu"]),
    }
    for tool in ["java", "clang", "opt", "llc", "ld.lld", "hyperfine", "python3", "timeout"]:
        metadata[tool] = capture([tool, "--version"])
    metadata["sbt"] = (ROOT / "project/build.properties").read_text().strip()
    (OUTPUT / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    checks = {name: {"status": "pending"} for name in CHECKS}
    save(checks)

    def step(name, commands, dependencies=(), timeout=1800):
        if any(checks[dependency]["status"] != "pass" for dependency in dependencies):
            checks[name] = {"status": "blocked", "reason": ", ".join(dependencies)}
            save(checks)
            return
        checks[name] = {"status": "running"}
        save(checks)
        elapsed = 0
        code = 0
        for command in commands:
            code, duration = run_command(command, OUTPUT / f"{name}.log", timeout)
            elapsed += duration
            if code:
                break
        checks[name] = {"status": "pass" if code == 0 else "fail",
                        "seconds": round(elapsed, 3), "exit_code": code}
        save(checks)
        print(f"{name}: {checks[name]['status']} ({elapsed:.1f}s)", flush=True)

    step("sanitizers", [[sys.executable, "scripts/ci/sanitizer_probe.py", str(OUTPUT)]],
         timeout=300)
    step("style", [["sbt", "--server", "--batch", "scalafmtCheckAll; scalafixAll --check"]])
    step("compile", [["sbt", "--server", "--batch", "compile"]])
    step("smoke", [["bash", "tests/smoke/run.sh", "all"]], ["compile"])
    step("tests", [["sbt", "--server", "--batch", "testFull"]], ["compile", "sanitizers"])
    step("publish", [["sbt", "--server", "--batch", "mmlcPublishLocal"]], ["smoke", "tests"])
    step("memory", [["bash", "tests/mem/run.sh", "all"]], ["publish"])

    if sys.platform == "darwin":
        for level in ["O0", "O3"]:
            directory = f"build/tail-cleanup-{level.lower()}"
            binary = f"{directory}/probe"
            step(f"tail-{level}", [
                ["sbt", "--server", "--batch", f"run -{level} -b {directory} -o {binary} "
                 "tests/tail-cleanup/closure-heap-capture-fail.mml"],
                ["python3", "tests/tail-cleanup/infinite_probe.py", binary],
            ], ["compile"])
        step("tail-stderr-control", [
            ["clang", "-O0", "tests/tail-cleanup/stderr_probe.c", "-o",
             "build/ci/stderr-probe"],
            [sys.executable, __file__, "stderr-control"],
        ], ["compile"])
    else:
        for name in ["tail-O0", "tail-O3", "tail-stderr-control"]:
            checks[name] = {"status": "not applicable", "reason": "macOS vmmap only"}
        save(checks)

    step("benchmark-build", [["make", "-C", "benchmark", "clean"],
                             ["make", "-C", "benchmark", "mml"]], ["publish"])
    step("benchmark-run", [["make", "-C", "benchmark", "bench-mml",
                            f"RESULTSDIR={OUTPUT / 'benchmarks'}"]],
         ["benchmark-build"], timeout=5400)
    return int(any(check["status"] not in ["pass", "not applicable"]
                   for check in checks.values()))


def stderr_control():
    log = OUTPUT / "stderr-control-output.log"
    code, _ = run_command([
        "python3", "tests/tail-cleanup/infinite_probe.py", "build/ci/stderr-probe",
        "--warmup", "0.2", "--samples", "3", "--interval", "0.2",
    ], log, 60)
    output = log.read_text()
    return int(not (code not in [0, 124, 127] and "diagnostic-before-loop" in output
                    and '"result": "pass"' not in output))


def benchmark_statistics(document):
    results = document["results"]
    if len(results) != 1:
        return None
    result = results[0]
    version = document.get("schema_version", 1)
    if version == 1:
        times = result["times"]
        codes = result["exit_codes"]
    elif version == 2:
        samples = result["measurements"]
        if any(sample["time_wall_clock"]["unit"] != "second" for sample in samples):
            return None
        times = [sample["time_wall_clock"]["value"] for sample in samples]
        codes = [sample["exit_code"] for sample in samples]
    else:
        return None
    if len(times) != 10 or len(codes) != 10 or any(code != 0 for code in codes):
        return None
    if any(not math.isfinite(value) or value < 0 for value in times):
        return None
    return len(times), statistics.median(times), min(times), max(times), statistics.stdev(times)


def report():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    checks_file = OUTPUT / "checks.json"
    checks = json.loads(checks_file.read_text()) if checks_file.exists() else {}
    lines = ["## Compiler verification", "", "| Check | Status | Seconds |",
             "|---|---|---:|"]
    for name in CHECKS:
        check = checks.get(name, {"status": "unavailable"})
        status = check["status"]
        if "reason" in check:
            status += f" ({check['reason']})"
        lines.append(f"| {name} | {status} | {check.get('seconds', '—')} |")
    lines += ["", "Unavailable, pending, or running checks are not passing evidence.",
              "", "## MML benchmark execution", "",
              "Two warmups; ten measured runs; 300-second execution limit per invocation.",
              "Measurements include process startup, the GNU timeout wrapper, and output capture.",
              "Compilation time is reported separately as benchmark-build above.", "",
              "| Binary (arguments) | Runs | Median (s) | Min (s) | Max (s) | Stddev (s) |",
              "|---|---:|---:|---:|---:|---:|"]
    status_file = OUTPUT / "benchmarks/status.json"
    results = json.loads(status_file.read_text()) if status_file.exists() else []
    incomplete = False
    for result in results:
        name = result["binary"]
        label = " ".join([name, *result.get("arguments", [])])
        path = OUTPUT / f"benchmarks/{name}.json"
        if result["exit_code"] or not path.exists():
            lines.append(f"| {label} | FAILED / missing measurements | — | — | — | — |")
            incomplete = True
            continue
        try:
            measurements = benchmark_statistics(json.loads(path.read_text()))
        except (OSError, ValueError, KeyError, TypeError) as error:
            print(f"Invalid benchmark measurements for {label}: {error}", file=sys.stderr)
            measurements = None
        if measurements is None:
            lines.append(f"| {label} | FAILED / invalid measurements | — | — | — | — |")
            incomplete = True
            continue
        count, *measurements = measurements
        lines.append(f"| {label} | {count} | " + " | ".join(
            f"{measurement:.6f}" for measurement in measurements
        ) + " |")
    if not results:
        lines += ["", "No benchmark measurements available."]
        incomplete = True
    lines += ["", "Timings are informational. Successful execution does not independently "
              "prove benchmark output correctness."]
    environment = OUTPUT / "environment.json"
    if environment.exists():
        lines += ["", "## Runner and toolchain", "", "```json", environment.read_text(), "```"]
    else:
        lines += ["", "Runner metadata unavailable; inspect setup logs."]
    markdown = "\n".join(lines) + "\n"
    (OUTPUT / "summary.md").write_text(markdown)
    if "GITHUB_STEP_SUMMARY" in os.environ:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(markdown)
    else:
        print(markdown)
    return int(incomplete or not checks or any(
        check["status"] not in ["pass", "not applicable"] for check in checks.values()
    ))


if __name__ == "__main__":
    os.chdir(ROOT)
    sys.exit({"run": verify, "report": report, "stderr-control": stderr_control}[sys.argv[1]]())
