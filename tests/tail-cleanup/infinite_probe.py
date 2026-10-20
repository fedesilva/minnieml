#!/usr/bin/env python3
"""Measure a live infinite sample on macOS, then terminate it explicitly."""

import argparse
import json
import platform
import re
import resource
import signal
import subprocess
import threading
import time
from pathlib import Path


def size_bytes(text):
    match = re.fullmatch(r"([\d.]+)([KMGT]?)", text)
    if not match:
        raise ValueError(f"Unexpected vmmap size: {text}")
    return int(float(match[1]) * 1024 ** " KMGT".index(match[2] or " "))


def memory_sample(pid):
    report = subprocess.run(
        ["/usr/bin/vmmap", "-summary", str(pid)],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    ).stdout
    stack = re.search(r"^Stack\s+(\S+)\s+(\S+)\s+(\S+)", report, re.MULTILINE)
    zones = report.split("MALLOC ZONE", 1)
    if not stack or len(zones) != 2:
        raise RuntimeError("vmmap did not report stack and allocator usage")
    rows = [
        line.split()
        for line in zones[1].splitlines()
        if re.match(r"^\S+_0x[0-9a-fA-F]+\s", line)
    ]
    if not rows:
        raise RuntimeError("vmmap did not report allocator zones")
    return {
        "stack_resident_bytes": size_bytes(stack[2]),
        "stack_dirty_bytes": size_bytes(stack[3]),
        "heap_allocations": sum(int(row[5]) for row in rows),
        "heap_allocated_bytes": sum(size_bytes(row[6]) for row in rows),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("--warmup", type=float, default=2)
    parser.add_argument("--samples", type=int, default=8)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--stack-kib", type=int, default=256)
    args = parser.parse_args()
    if platform.system() != "Darwin":
        parser.error("This probe requires macOS vmmap")
    if args.samples < 3 or args.interval <= 0 or args.warmup < 0:
        parser.error("Use at least three samples and a positive interval")

    def limit_stack():
        resource.setrlimit(resource.RLIMIT_STACK, (args.stack_kib * 1024,) * 2)

    process = subprocess.Popen(
        [str(args.binary.resolve())],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        preexec_fn=limit_stack,
    )
    # Pipe readers mutate counters at the process-I/O boundary while vmmap samples the child.
    lock = threading.Lock()
    output = {"lines": 0, "bytes": 0, "stderr": b""}

    def drain(stream, stdout):
        while chunk := stream.read(65536):
            with lock:
                if stdout:
                    output["lines"] += chunk.count(b"\n")
                    output["bytes"] += len(chunk)
                else:
                    output["stderr"] = (output["stderr"] + chunk)[-4096:]

    readers = [
        threading.Thread(target=drain, args=(process.stdout, True), daemon=True),
        threading.Thread(target=drain, args=(process.stderr, False), daemon=True),
    ]
    for reader in readers:
        reader.start()
    samples = []
    started = time.monotonic()
    try:
        time.sleep(args.warmup)
        for _ in range(args.samples):
            if process.poll() is not None:
                raise RuntimeError(f"Sample exited early with status {process.returncode}")
            sample = memory_sample(process.pid)
            with lock:
                sample["lines"] = output["lines"]
            sample["elapsed_seconds"] = round(time.monotonic() - started, 3)
            samples.append(sample)
            print(json.dumps(sample), flush=True)
            time.sleep(args.interval)
        if process.poll() is not None:
            raise RuntimeError(f"Sample exited early with status {process.returncode}")
        if samples[-1]["lines"] - samples[0]["lines"] < 100000:
            raise RuntimeError("Insufficient progress after warmup")
        if any(b["lines"] <= a["lines"] for a, b in zip(samples, samples[1:])):
            raise RuntimeError("Output did not advance between measurements")
        limits = {
            "stack_resident_bytes": 16384,
            "stack_dirty_bytes": 16384,
            "heap_allocations": 64,
            "heap_allocated_bytes": 131072,
        }
        for key, tolerance in limits.items():
            values = [sample[key] for sample in samples]
            if max(values) - min(values) > tolerance:
                raise RuntimeError(f"Unbounded {key}: {values}")
        if max(sample["stack_dirty_bytes"] for sample in samples) > 131072:
            raise RuntimeError("Stack usage exceeds the bounded-loop threshold")
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        for reader in readers:
            reader.join(timeout=5)
        process.stdout.close()
        process.stderr.close()
    with lock:
        if output["stderr"]:
            raise RuntimeError(output["stderr"].decode(errors="replace"))
    if process.returncode != -signal.SIGTERM:
        raise RuntimeError(f"Expected explicit SIGTERM, got {process.returncode}")
    print(json.dumps({
        "result": "pass",
        "terminated_by": "SIGTERM",
        "stack_limit_kib": args.stack_kib,
        "lines_drained": output["lines"],
        "bytes_drained": output["bytes"],
    }))


if __name__ == "__main__":
    main()
