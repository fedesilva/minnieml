
import subprocess
import sys
import json
import time
import os
import threading

def reader(pipe, name):
    try:
        for line in iter(pipe.readline, b''):
            line_str = line.decode('utf-8').strip()
            print(f"[{name}] {line_str}")
    except Exception as e:
        print(f"Reader {name} error: {e}")

def run():
    mmlc_path = os.path.expanduser("~/bin/mmlc")
    cmd = [mmlc_path, "lsp"]
    
    print(f"Starting: {cmd}")
    process = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, # We will read this manually for JSON-RPC
        stderr=subprocess.PIPE
    )

    # Start stderr reader in background
    t = threading.Thread(target=reader, args=(process.stderr, "STDERR"))
    t.daemon = True
    t.start()

    def send(msg):
        content = json.dumps(msg)
        l = len(content.encode('utf-8'))
        # Try sending Content-Type first to test header parsing robustness
        h = f"Content-Type: application/vscode-jsonrpc; charset=utf-8\r\nContent-Length: {l}\r\n\r\n"
        process.stdin.write(h.encode('utf-8'))
        process.stdin.write(content.encode('utf-8'))
        process.stdin.flush()
        print(f"Sent: {msg.get('method')}")

    try:
        send({"jsonrpc": "2.0", "method": "initialize", "params": {}, "id": 1})
        send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
        
        with open("mml/samples/astar.mml", "r") as f:
            txt = f.read()
            
        send({
            "jsonrpc": "2.0", 
            "method": "textDocument/didOpen", 
            "params": {
                "textDocument": {
                    "uri": "file:///tmp/astar.mml", 
                    "languageId": "mml", 
                    "version": 1, 
                    "text": txt
                }
            }
        })
        
        # Semantic tokens
        send({
             "jsonrpc": "2.0",
             "method": "textDocument/semanticTokens/full",
             "params": {"textDocument": {"uri": "file:///tmp/astar.mml"}},
             "id": 2
        })

        # Document Symbols
        send({
             "jsonrpc": "2.0",
             "method": "textDocument/documentSymbol",
             "params": {"textDocument": {"uri": "file:///tmp/astar.mml"}},
             "id": 3
        })
        print("Sent documentSymbol")

        # Definition at a known location (unsafe_ar_int_set call)
        # Line 131: unsafe_ar_int_set g_score i inf;
        send({
             "jsonrpc": "2.0",
             "method": "textDocument/definition",
             "params": {
                 "textDocument": {"uri": "file:///tmp/astar.mml"},
                 "position": {"line": 130, "character": 6} # 0-indexed line 130 = line 131
             },
             "id": 4
        })
        print("Sent definition")

        # Read loop for stdout (JSON-RPC)
        while True:
            line = process.stdout.readline()
            if not line: break
            line = line.decode('utf-8')
            if line.startswith("Content-Length:"):
                cl = int(line.split(':')[1].strip())
                process.stdout.readline() # \r\n
                body = process.stdout.read(cl).decode('utf-8')
                msg = json.loads(body)
                if 'error' in msg:
                    print(f"RECEIVED ERROR: {msg}")
                elif 'method' in msg:
                    print(f"RECEIVED NOTIF: {msg['method']}")
                else:
                    print(f"RECEIVED RESP: ID={msg.get('id')}")

    except Exception as e:
        print(f"Error: {e}")
    finally:
        process.terminate()

if __name__ == "__main__":
    run()
