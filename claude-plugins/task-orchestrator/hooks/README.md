# Plugin hooks

Hook entrypoints live in this directory and are wired by `hooks-config.json`. Shared plumbing lives in `lib/`;
every module is pure at import (no top-level I/O) and never throws.

| Module | Role |
|--------|------|
| `lib/stdin.mjs` | `readHookInput()` / `parseHookInput(text)`: hook stdin as a plain object, or `null` when unreadable. |
| `lib/emit.mjs` | Output builders (`contextOutput`, `denyOutput`, `blockOutput`, `systemMessageOutput`) and the matching one-write stdout writers (`emit`, `emitEmpty`, `emitContext`, `emitDeny`, `emitBlock`, `emitSystemMessage`). |
| `lib/markers.mjs` | Temp-dir marker files: `sessionMarkerPath` (per session), `sharedMarkerPath` (cross-session), `readMarker`, atomic `writeMarker`, `createMarkerOnce`. |
| `lib/envelope.mjs` | `parseToolResult` reads the 4.0 result envelope (`kind` and `retryAfterMs` as the server sends them); `isLegacyShape` flags a 3.x payload. |
| `lib/ops.mjs` | `opsOf`, `selectOf`, `opItemIds`, `resultItemIds`: helpers over the `ops` array of a tool input and its result. |
| `lib/api-client.mjs` | URL and token discovery, `fetchWithTimeout`, `callOp(tool, body)` over `POST /api/v1/ops/{tool}`, and `wellKnown()`. |
| `lib/server-compat.mjs` | `assessServer` / `checkServer` (major 4 with the `seats` feature) and `legacyShapeWarningOnce`. |
| `lib/tool-names.mjs` | `TOOLS`, the 4.0 tool names (hand list until `scripts/gen-call-shapes.mjs` generates it). |

`api-client.mjs` in this directory is a re-export shim of `lib/api-client.mjs`; it is removed once the remaining
callers move to `callOp`.

Run the tests by glob: `node --test "claude-plugins/task-orchestrator/hooks/tests/*.test.mjs"`.
