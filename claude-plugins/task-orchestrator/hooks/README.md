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

## Mode gating

Plugin keys live under `extensions.claude-code` in `.taskorchestrator/config.yaml` (for example
`extensions.claude-code.orchestration.mode`). Top-level 3.x keys such as `orchestration:` are ignored; a 4.0 server
rejects them on push, and `session-start` prints a `## Config Shape` line when it finds one.

`execution-mode.suppressionReason(hookInput, env)` is the shared gate for the four injecting context hooks. It returns
`headless` (`TASK_ORCHESTRATOR_MODE=headless-iteration`), then `subagent` (a non-empty `agent_id`), then `director` (a
file exists at `directorMarkerPath(session_id)`; nothing writes it until director mode lands), or `null`.

The table lists one row per script registered in `hooks-config.json` and what it does at this version. "Not gated" means
the hook has no check for that column; "non-injecting" means it adds no context.

| Script | Headless iteration | Subagent (`agent_id`) | `orchestration.mode` | Director session |
|--------|--------------------|-----------------------|----------------------|------------------|
| `session-start.mjs` | Guidance omitted; diagnostics kept | Guidance omitted; diagnostics kept | Not read | Guidance omitted; diagnostics kept |
| `orchestration-context.mjs` | `{}` | `{}` | `off` gives `{}`; `workflow` and `schema` pick the variant | `{}` |
| `config-sync.mjs` | Not gated; non-injecting | Not gated; non-injecting | Not read | Not gated; non-injecting |
| `pre-plan.mjs` | `{}` | `{}` | `off` gives `{}` | `{}` |
| `post-plan.mjs` | `{}` | `{}` | `off` gives `{}` | `{}` |
| `plan-capture.mjs` | Not gated; non-injecting | Not gated; non-injecting | Not read | Not gated; non-injecting |
| `skill-enforcement.mjs` | Not gated | Not gated | Not read | Not gated |
| `enforce-actor-attribution.mjs` | Not gated | Not gated | Not read | Not gated |
| `dispatch-model-guard.mjs` | `{}` | Not gated (guards the dispatch) | `off` gives `{}`; `schema` changes the deny reason | Not gated (still guarded) |
| `dispatch-hint.mjs` | `{}` | `{}` | `off` gives `{}`; `schema` changes the model token | Not gated |
| `retro-trigger.mjs` | `{}` | Parent-completion nudge suppressed | Not read (`retrospective.mode` `off` gives `{}`) | Not gated |
| `phase-guard-record.mjs` | `{}` | Acts only inside a subagent | Not read | Not gated |
| `phase-guard.mjs` | `{}` | Acts on phase-owner agent types | Not read | Not gated |
| `subagent-start.mjs` | Exits silently | Acts on phase-owner agent types | Not read | Not gated |
| `retro-backstop.mjs` | `{}` | Not gated | Not read (`retrospective.mode` `off` gives `{}`) | Not gated |

The `retro-*`, `phase-guard*`, `dispatch-hint`, `subagent-start`, `skill-enforcement` and `enforce-actor-attribution`
rows describe current behaviour; the items that rewrite those hooks update their rows.

Run the tests by glob: `node --test "claude-plugins/task-orchestrator/hooks/tests/*.test.mjs"`.
