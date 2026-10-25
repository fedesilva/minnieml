# Shared LLVM IR navigation

`src/index.ts` indexes LLVM declarations and function-local symbols. Both the VS Code
extension and the stdio language server use it. `src/server.ts` exposes definitions,
hover, references, and document symbols through LSP, using UTF-16 positions and full
text synchronization. No compiler process is launched.

```sh
npm ci --prefix tooling/llvm-ir
npm test --prefix tooling/llvm-ir
```

`npm run build --prefix tooling/llvm-ir` bundles the server as `out/server.cjs`.
Run it with `node tooling/llvm-ir/out/server.cjs --stdio`. The bundle includes its
runtime dependencies. Node.js is required on the editor's shell PATH.

See [the Zed extension](../zed-llvm-ir/README.md) for installation and scanner limits.
The tests check scope separation, declaration ranges, labels, quoted identifiers,
outline hierarchy, comments, incomplete documents, and the stdio document lifecycle.
