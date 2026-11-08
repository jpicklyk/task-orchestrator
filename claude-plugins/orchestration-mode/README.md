# orchestration-mode

An opt-in Claude Code mod that shows which Task Orchestrator **orchestration mode** a session is in, on the session itself. Today there is one mode, **director** (`/task-orchestrator:director`): with several desktop sessions open it is easy to forget which ones are running the board, and this plugin makes it unmistakable:

- **Footer label.** `Orchestration Mode director` joins the labels at the right of the prompt footer, drawn in the engine's own mode slot (where `focus` and `memory paused` appear) and styled like the `Fable 5.1 Medium` pair beside it, on the terminal and the desktop app.
- **Run board.** A band above the prompt, bordered in amber: the title, a bar of members done over members in all across the open and paused runs, and the totals (`runs 1 open · decisions 0 · blocked 3`), then one row per run (at most three): `done/total`, the run's name, its phase pipeline (`q2 → w1 → r0 → ✓1`, with `blocked n` when any member is blocked), its seats (`in flight 1 (1 stalled) · ready 1 · waiting 1`) and a chip when it needs a look (`COORD STALLED`, or `PAUSED run-budget`). Red marks a stalled seat or coordinator. Token spend joins each row above 120 columns. The board refreshes every 30 seconds and after every turn.
- **Desktop backdrop.** The desktop app's backdrop turns a warm amber with a faint `DIRECTOR` watermark while the mode is on, so the window reads as "director" at a glance even from a thumbnail. Turn it off in the plugin's settings (`backdrop: false`) to keep only the label, board and toasts.
- **Toasts.** `Director mode on since 14:02` when the mode starts, `Director mode off` when it ends.

It is a separate plugin from `task-orchestrator-mod` and shares nothing with it.

## Install

```
/plugin install orchestration-mode@task-orchestrator-marketplace
```

It needs the `task-orchestrator` plugin, whose `director-guard` hook keeps the marker this plugin reads, and a 4.x server reachable over its REST API for the board (see `apiUrl` below). It relies on the early-access Claude Code function-hooks API and runs on Claude Code 2.1.317 or later (the `Backdrop` component arrived with that build; the label, board and toasts also work on 2.1.286 and later).

## Settings

| Key | Default | Meaning |
|-----|---------|---------|
| `backdrop` | `true` | Tint the desktop app's backdrop while the mode is on. |
| `apiUrl` | `http://localhost:3003` | Base URL of the server's REST API; the board reads `POST <apiUrl>/api/v1/ops/director/<tool>` (the REST twin of the director profile's `runs_read` and `items_read`). Leave it empty to read over the `mcp-task-orchestrator-director` MCP connection instead (a stdio server has no REST API). |

The board goes over REST by default because the reads are the mod's own, on a timer and at turn end, not the model's: the desktop app's auto permission mode has no request to evaluate such a call against and refuses a plugin-originated `$.mcp.call` outright, while `$.http.fetch` goes through the host with no permission step. Both paths use the director profile, which has no note-body read, so the board can never breach the altitude rule.

## How it decides

The `task-orchestrator` plugin's `director-guard` hook writes a per-session marker, `<tmp>/task-orchestrator/<session_id>/director.json`, when the director skill is invoked and removes it on `/task-orchestrator:director off`. That file is the single source of truth here: the mod checks it at session start, after every `Skill` tool call that names `task-orchestrator:director`, and at the end of every turn. It never guesses from its own memory of a call, so a resume, a hot reload or an `off` from any path all agree with the guard.

The mod writes nothing but its own `$.state` values (`director`: `{ since } | null`; `board`: the last board read, or null while off).

## Desktop app notes

The desktop app's Code tab (as of 2.1.317) draws the band above the prompt and the footer label, the latter only as a tree returned from the `SessionMode` hook (a rewrite of its `modes` list reaches the terminal alone). It does not draw a plugin `Backdrop`; the backdrop is in place for the builds that do.

## Developing

```
claude plugin validate claude-plugins/orchestration-mode
node scripts/ci/check-task-orchestrator-mod.mjs claude-plugins/orchestration-mode
```

The second command is the CI gate: validate, the `=?` unresolved-matcher check, a source scan of `on(...)` matchers, then `claude plugin test`. The test kit has no `fs`, `env`, `http` or `session` nouns, so the marker probe and the board reads are covered live rather than in the kit; the tests cover the footer rewrite and the pure helpers in `src/model.ts` (the envelope and board parsing, the cells and the totals).

Rules that hold here as in every mod: matchers are literals in the registering file (`src/indicator.ts`), `$` never leaves that file, and the atoms are declared beside their reads and writes.
