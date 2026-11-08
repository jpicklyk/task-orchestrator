# orchestration-mode

An opt-in Claude Code mod that puts Task Orchestrator on the session itself. With several desktop sessions open it is easy to lose track of which graph each one is driving and which ones run the board; this plugin shows both:

- **The band.** Above the prompt, bordered in amber, in every mode (a plain orchestrator session included): the progression of the graph this session drives, one line per layer and never more than three. **Line 1, the container**: the title of the root of the work being done, alone, in bold amber; with a run open the run facts follow it (`run wave-8 open`, or `2 runs open` and the first run, a chip when it needs a look, `COORD STALLED` or `PAUSED run-budget`, its seats `in flight 2 (1 stalled) · ready 1 · waiting 1` and, from 120 columns, its tokens `1.3M tok`). **Line 2, the progress**: the container's type and phase, dim, then, for a graph with members (a run, or a focus with children), a bar of members done over members in all (red when a seat or coordinator is stalled), `3/8` and the pipeline `q2 → w1 → r1 → ✓3`, or, for an item with no children, its own phases with the current one lit (`plugin-change · queue → ◉ work → review → done`); then only the counts that are not zero (`blocked 1`, `decisions 2 (1 overdue)`). **Line 3, the current item**: the item this session last touched, `d8999287 · plugin-change · in-progress`, its title, and `blocked by <id>` in red when it waits. No line ever wraps: in the terminal the mod cuts every line to the frame's cells itself (cells go from the end first, then the title ends in `…`; on line 3 the title gives way before the blocker), and on the desktop app, whose font is proportional, each line goes over whole and the surface cuts it at the frame's edge. The graph is the open or paused runs when there are any, else the **focus**: the top-most item below the project root of the last item this session touched (a write through `items_write`, `advance`, `notes_write` or `deps_write`, or an `items_read` naming an `itemId`); its container is the focus when it has children, and a lone leaf below the root is its own container (line 3 is then left out, its facts already on lines 1 and 2). A run with nothing touched shows lines 1 and 2; a short band drops line 3, then line 2, never line 1. With no run and nothing touched, no band is drawn. It refreshes every 30 seconds, after every turn, and when the focus moves.
- **Footer label.** Always drawn, dedicated to the mode: `TO orchestrator` at rest, else the modes below that are on (`director`, `coordinator`, `director & coordinator`). It joins the labels at the right of the prompt footer, drawn in the engine's own mode slot (where `focus` and `memory paused` appear) and styled like the `Fable 5.1 Medium` pair beside it.
- **Desktop backdrop.** The desktop app's backdrop takes the glass of the first mode that is on: warm amber with a faint `DIRECTOR` watermark for director, a cool blue for coordinator. Turn it off in the plugin's settings (`backdrop: false`).
- **Toasts.** `Director mode on since 14:02` / `Director mode off`, `Coordinator of run wave-8` / `Run wave-8 closed`, and `Band hidden` / `Band shown`.

It is a separate plugin from `task-orchestrator-mod` and shares nothing with it.

## The modes it names

The footer names only the modes a user sees in a session of their own:

| Mode | Where it runs | Footer |
|------|---------------|--------|
| Orchestrator (default) | your own session | `orchestrator` |
| Director | your session after `/task-orchestrator:director`, or `claude --agent director` | `director` |
| Coordinator | a session that opened a run (tier 1: a desktop session the director started) | `coordinator` |
| Seats, ralph, workflow coordinators | background agents and headless processes | never (never a visible session) |

Both can be on at once (`TO director & coordinator`).

## Install

```
/plugin install orchestration-mode@task-orchestrator-marketplace
```

It needs the `task-orchestrator` plugin, whose `director-guard` hook keeps the marker the director label reads, and a 4.x server reachable over its REST API for the band (see `apiUrl` below). It relies on the early-access Claude Code function-hooks API and runs on Claude Code 2.1.317 or later (the `Backdrop` component arrived with that build; the label, band and toasts also work on 2.1.286 and later).

## Settings

| Key | Default | Meaning |
|-----|---------|---------|
| `band` | `true` | Draw the band above the prompt; this is the default for every session. `/to-band` hides or shows the band for this session (the choice survives a reload); a fresh session starts from `band` again. The footer label is not affected. |
| `backdrop` | `true` | Tint the desktop app's backdrop while director or coordinator mode is on. |
| `apiUrl` | `http://localhost:3003` | Base URL of the server's REST API; the band reads `POST <apiUrl>/api/v1/ops/director/<tool>` (the REST twin of the director profile's `runs_read` and `items_read`). Leave it empty to read over the `mcp-task-orchestrator-director` MCP connection instead (a stdio server has no REST API). |

The band goes over REST by default because the reads are the mod's own, on a timer and at turn end, not the model's: the desktop app's auto permission mode has no request to evaluate such a call against and refuses a plugin-originated `$.mcp.call` outright, while `$.http.fetch` goes through the host with no permission step. Both paths use the director profile, which has no note-body read, so the band can never breach the altitude rule. The director profile has every read the band needs (`items_read` `get`, `overview` and `blocked`, and `runs_read`).

## How it decides

- **Director.** The `task-orchestrator` plugin's `director-guard` hook writes a per-session marker, `<tmp>/task-orchestrator/<session_id>/director.json`, when the director skill is invoked and removes it on `/task-orchestrator:director off`. That file is the single source of truth: the mod checks it at session start, after every `Skill` tool call that names `task-orchestrator:director`, and at the end of every turn. It never guesses from its own memory of a call, so a resume, a hot reload or an `off` from any path all agree with the guard.
- **Coordinator.** A `runs_write` `open` this session makes (on either TO MCP server) whose result carries a `coordinatorToken` makes it the run's coordinator; a `close` of that run, or a board read that shows it closed, ends it. The token is never kept. A `coordinator.json` marker beside `director.json` is honoured too, for a run opened through the plugin's scripts.
- **Focus.** The item a TO call names is walked up `parentId` (`items_read` `get`, at most six hops, once per item id) to the top-most item below the project root. A call naming the container the session is already in (an overview or context read of the feature while a child is in hand) keeps that child on line 3.
- A call made inside a subagent (it carries an agent id) is ignored, so a seat's calls never move the mode or the focus of the session that dispatched it.

The mod writes nothing but its own `$.state` values (`director`, `coordinator`, `focus`, `bandHidden`, `board`); it keeps nothing in `$.store`.

## Desktop app notes

The desktop app's Code tab (as of 2.1.317) draws the band above the prompt and the footer label, the latter only as a tree returned from the `SessionMode` hook (a rewrite of its `modes` list reaches the terminal alone). It does not draw a plugin `Backdrop`; the backdrop is in place for the builds that do.

## Developing

```
claude plugin validate claude-plugins/orchestration-mode
node scripts/ci/check-task-orchestrator-mod.mjs claude-plugins/orchestration-mode
```

The second command is the CI gate: validate, the `=?` unresolved-matcher check, a source scan of `on(...)` matchers, then `claude plugin test`. The tests stand for the engine beneath the plugin (`http.fetch`, `ui.toast` and `tool.call` answered by the test), so they cover the footer at rest and for one and two modes, coordinator detection from an `open` result and its close, a subagent's calls being ignored, the focus walk, `/to-band`, the band's three lines, its collapses (a lone leaf, a run with nothing touched, a short band) and its hidden and off states, and the pure helpers in `src/model.ts`. The marker probe (`fs`, `env`, `session`) is covered live.

Rules that hold here as in every mod: matchers are literals in the registering file (`src/indicator.ts`), `$` never leaves that file, and the atoms are declared beside their reads and writes.
