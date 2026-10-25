"""Stream command output to the console and a retained log, with a group deadline."""

import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time


def run_command(command, log, timeout):
    Path(log).parent.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    with Path(log).open("ab") as output:
        def emit(data):
            output.write(data)
            output.flush()
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()

        emit(f"Command: {command!r}\n".encode())
        try:
            with subprocess.Popen(
                command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                start_new_session=True,
            ) as process:
                timed_out = threading.Event()

                # The deadline must remain active while reading a silent child's output.
                def expire():
                    timed_out.set()
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

                timer = threading.Timer(timeout, expire)
                timer.start()
                try:
                    while data := process.stdout.read1(65536):
                        emit(data)
                    code = process.wait()
                finally:
                    timer.cancel()
                    timer.join()
                if timed_out.is_set():
                    emit(f"Timed out after {timeout} seconds\n".encode())
                    code = 124
        except OSError as error:
            emit(f"Could not execute command: {error}\n".encode())
            code = 127
    return code, time.monotonic() - start
