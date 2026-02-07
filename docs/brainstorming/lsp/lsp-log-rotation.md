# LSP log rotation

## Context

The LSP server logs to `build/lsp/server.log` via a custom `Logger[IO]` in `LspLogging.scala`. The file is opened in append mode with no size limit, rotation, or cleanup. Over time it grows unbounded.

## Design: size-based rotation on startup

On `LspLogging.create()`, before opening the writer:

1. Check if `server.log` exists and its size exceeds a threshold (e.g. 5 MB)
2. If so, rotate:
   - Delete `server.log.{maxFiles}` if it exists
   - Rename `server.log.N` → `server.log.{N+1}` for N from `maxFiles-1` down to `1`
   - Rename `server.log` → `server.log.1`
3. Then open `server.log` fresh (or append to the now-smaller file)

Constants: `maxSize = 5 MB`, `maxFiles = 3` (keep `server.log.1`, `.2`, `.3`)

### Why on startup (not per-write)

- The LSP server restarts frequently (editor open/close, crashes, reconnects)
- Checking file size on every log line adds unnecessary I/O overhead
- Startup rotation is simple, predictable, and sufficient

### File modified

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspLogging.scala`
  - Add `rotate(logDir, maxSize, maxFiles)` private method
  - Call it from `create()` before opening the writer

### Verification

- Manually create an oversized `build/lsp/server.log`, start LSP, confirm rotation happened
- Confirm rotated files are named `server.log.1`, `server.log.2`, etc.
- Confirm files beyond `maxFiles` are deleted
