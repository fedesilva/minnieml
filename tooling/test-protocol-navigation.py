#!/usr/bin/env python3
"""Check the shared navigation server contract used by VS Code, Neovim, and Zed."""

import argparse
import asyncio
import itertools
import json
from pathlib import Path


NAMED_SOURCE = """protocol Inspect 'T =
  fn inspect(value: 'T): Int;
  fn rank(value: 'T): Int;
;
type Count = Int;
instance Inspect Count =
  fn inspect(value) = value;;
  fn rank(value) = value;;
;
instance Inspect Bool =
  fn inspect(value) = 0;;
  fn rank(value) = 1;;
;
let integer = inspect 1;
let boolean = inspect true;
let anotherInteger = inspect 2;
let integerRank = rank 3;
let booleanRank = rank false;
fn ordinary(value: Int): Int = value;;
let ordinaryUse = ordinary 4;
"""

OPERATOR_SOURCE = """protocol Combine 'T =
  op %%(left: 'T, right: 'T): 'T 60 left;
  op %%(value: 'T): 'T 95 right;
;
instance Combine Int =
  op %%(left, right) = left;;
  op %%(value) = value;;
;
instance Combine Bool =
  op %%(left, right) = right;;
  op %%(value) = value;;
;
let integer = 1 %% 2;
let boolean = true %% false;
let unary = %% 3;
"""


def position(line, column):
    return {"line": line - 1, "character": column - 1}


def location(uri, line, column, length):
    return {
        "uri": uri,
        "range": {
            "start": position(line, column),
            "end": position(line, column + length),
        },
    }


class Server:
    """One stdio connection; requests await their response and inspect notifications."""

    def __init__(self, process):
        self.process = process
        self.ids = itertools.count(1)

    async def send(self, method, params, request_id=None):
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if request_id is not None:
            message["id"] = request_id
        content = json.dumps(message).encode("utf-8")
        self.process.stdin.write(f"Content-Length: {len(content)}\r\n\r\n".encode() + content)
        await self.process.stdin.drain()

    async def request(self, method, params):
        request_id = next(self.ids)
        await self.send(method, params, request_id)
        while True:
            headers = await self.process.stdout.readuntil(b"\r\n\r\n")
            fields = dict(line.split(":", 1) for line in headers.decode().strip().split("\r\n"))
            content = await self.process.stdout.readexactly(int(fields["Content-Length"]))
            response = json.loads(content)
            assert "error" not in response, response
            if response.get("method") == "textDocument/publishDiagnostics":
                errors = [d for d in response["params"]["diagnostics"] if d.get("severity") == 1]
                assert not errors, errors
            if response.get("id") == request_id:
                return response.get("result")

    async def check(self, uri, method, line, column, expected, include_declaration=False):
        params = {"textDocument": {"uri": uri}, "position": position(line, column)}
        if method == "references":
            params["context"] = {"includeDeclaration": include_declaration}
        actual = await self.request(f"textDocument/{method}", params)
        expected_response = [location(uri, *span) for span in expected]
        if not expected_response and method in ("definition", "implementation"):
            expected_response = None
        assert actual == expected_response, (
            f"{method} at {line}:{column}:\nexpected {expected_response}\nreceived {actual}"
        )
        return actual

    async def check_selection_end(self, method, selected_location, expected):
        end = selected_location["range"]["end"]
        return await self.check(
            selected_location["uri"], method, end["line"] + 1, end["character"] + 1, expected
        )


async def check_navigation_chain(server, uri, line, column, definition, implementations, references):
    """Zed selects destination names and sends their ending position for the next action."""
    destinations = await server.check(uri, "definition", line, column, [definition])
    members = await server.check_selection_end("implementation", destinations[0], implementations)
    for member, expected_references in zip(members, references, strict=True):
        await server.check_selection_end("references", member, expected_references)


async def check_document(server, root, name, source, cases, chains=()):
    uri = (root / "build" / f"navigation-smoke-{name}.mml").as_uri()
    await server.send("textDocument/didOpen", {
        "textDocument": {"uri": uri, "languageId": "mml", "version": 1, "text": source}
    })
    for case in cases:
        await server.check(uri, *case)
    for chain in chains:
        await check_navigation_chain(server, uri, *chain)
    await server.send("textDocument/didClose", {"textDocument": {"uri": uri}})
    print(f"{name}: {len(cases)} exact navigation responses and {len(chains)} selection chains passed")


async def run(compiler):
    root = Path(__file__).resolve().parent.parent
    process = await asyncio.create_subprocess_exec(
        compiler, "lsp", cwd=root,
        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
    )
    server = Server(process)
    try:
        initialized = await server.request("initialize", {
            "processId": None, "rootUri": root.as_uri(), "capabilities": {}
        })
        for capability in ("definitionProvider", "implementationProvider", "referencesProvider"):
            assert initialized["capabilities"].get(capability) is True, capability
        await server.send("initialized", {})

        await check_document(server, root, "named", NAMED_SOURCE, [
            ("definition", 14, 15, [(2, 6, 7)]),
            ("definition", 15, 15, [(2, 6, 7)]),
            ("implementation", 1, 10, [(6, 10, 7), (10, 10, 7)]),
            ("implementation", 2, 6, [(7, 6, 7), (11, 6, 7)]),
            ("implementation", 3, 6, [(8, 6, 4), (12, 6, 4)]),
            ("references", 6, 10, [(14, 15, 7), (16, 22, 7), (17, 19, 4)]),
            ("references", 10, 10, [(15, 15, 7), (18, 19, 4)]),
            ("references", 7, 6, [(14, 15, 7), (16, 22, 7)]),
            ("references", 2, 6, [(14, 15, 7), (15, 15, 7), (16, 22, 7)]),
            ("references", 7, 6, [(7, 6, 7), (14, 15, 7), (16, 22, 7)], True),
            ("definition", 20, 19, [(19, 4, 8)]),
            ("implementation", 19, 4, []),
            ("references", 19, 4, [(20, 19, 8)]),
        ], chains=[
            (14, 15, (2, 6, 7), [(7, 6, 7), (11, 6, 7)], [
                [(14, 15, 7), (16, 22, 7)],
                [(15, 15, 7)],
            ]),
            (6, 10, (1, 10, 7), [(6, 10, 7), (10, 10, 7)], [
                [(14, 15, 7), (16, 22, 7), (17, 19, 4)],
                [(15, 15, 7), (18, 19, 4)],
            ]),
        ])
        await check_document(server, root, "operators", OPERATOR_SOURCE, [
            ("definition", 13, 17, [(2, 6, 2)]),
            ("definition", 14, 20, [(2, 6, 2)]),
            ("definition", 15, 13, [(3, 6, 2)]),
            ("implementation", 1, 10, [(5, 10, 7), (9, 10, 7)]),
            ("implementation", 2, 6, [(6, 6, 2), (10, 6, 2)]),
            ("implementation", 3, 6, [(7, 6, 2), (11, 6, 2)]),
            ("references", 5, 10, [(13, 17, 2), (15, 13, 2)]),
            ("references", 6, 6, [(13, 17, 2)]),
            ("references", 2, 6, [(13, 17, 2), (14, 20, 2)]),
        ], chains=[
            (13, 17, (2, 6, 2), [(6, 6, 2), (10, 6, 2)], [
                [(13, 17, 2)],
                [(14, 20, 2)],
            ]),
            (15, 13, (3, 6, 2), [(7, 6, 2), (11, 6, 2)], [
                [(15, 13, 2)],
                [],
            ]),
            (5, 10, (1, 10, 7), [(5, 10, 7), (9, 10, 7)], [
                [(13, 17, 2), (15, 13, 2)],
                [(14, 20, 2)],
            ]),
        ])
        await server.request("shutdown", None)
        assert await asyncio.wait_for(process.wait(), 10) == 0
        print("VS Code / Neovim / Zed shared mmlc lsp navigation contract passed.")
    finally:
        if process.returncode is None:
            process.kill()
            await process.wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", default="mmlc", help="Compiler executable used by the editor")
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(run(args.compiler), timeout=60))
