# orchestration-mode

An opt-in Claude Code mod that puts Task Orchestrator on the session itself. With several desktop sessions open it is easy to lose track of which graph each one is driving and which ones run the board; this plugin shows both:

- **The band.** Above the prompt, bordered in amber, in every mode (a plain orchestrator session included): the graph this session drives, in plain words.
  - **The title row**: the title of the container, the root of the work being done, in bold amber, and on the right the **bar**, one cell per member (past 16, the cells no agent stands on are shares; a state with any member keeps a cell). Green `▰` is done, a red `⧄` cancelled (never counted as done), and each member an agent works on now takes an agent colour of its own (blue, violet, teal, pink, then repeating) and blinks. Red `▰` is a stuck agent, amber `▰` a member waiting on a decision, grey `▰` a member whose agent finished and that waits to be advanced, hollow `▱` not started (waiting on another item included). An item keeps its colour from stage to stage (planner, implementer, reviewer), and two members worked on at once never share one while a colour is free.
  - **The status row**: on the left what needs a look, first (`1 agent stuck` and `Coordinator not responding` in red; `1 decision for you`, `Run paused: run budget` and `All agents finished · advance the items` in amber), then what the agents do (`3 agents implementing`, `2 agents working` when they do different things, after `2 waves` when the session has several runs); on the right the members by phase in plain words, the zeros left out (`3 planned · 2 in progress · 1 in review · 1 on hold · 2 done · 1 cancelled`). With no run, the container's type and phase and `N waiting on another item`, the members by phase on the right, or for a lone item its own phases with the current one lit (`queue → ◉ work → review → done`). `Coordinator not responding` follows the director skill's rule: never while a Workflow runs the run (`hints.method` `A`, `hints.phase` `launched`), while any seat is live, or on a paused run.
  - **The agent rows**: one per member an agent stands on, the stuck ones first (red marker, `quiet 14m`), then the ones worked on, their marker `◉` in the member's colour and blinking with its bar cell: `◉ implementer   Show people by name…`, with the run's name after the seat when there are several runs. At most three; the last ends `+N more`. With no agent, the item this session last touched instead: `d8999287 · plugin-change · in-progress`, its title, and `blocked by <id>` in red when it waits.

  While anything blinks, one `Client` (`src/band.ts`) draws the whole band on the surface's own frame clock, so every blink turns on one timer, in step, with no board read; a surface with no `Client` (VS Code, the mobile app) draws the band still. No row ever wraps: in the terminal the mod cuts every row to the frame's cells itself (the right side stays whole; the left's cells go from the end, then its title ends in `…`; a frame too narrow for both drops the right side), and on the desktop app, whose font is proportional, each row goes over whole and the surface lays it out. On every surface the right side (the bar, the counts) never shrinks: a title too long for the room left gives way, never the bar (the desktop app wraps it onto a second line rather than cut it). The graph is this session's open or paused runs when there are any, else the **focus**. A session's runs are the run it coordinates and the runs it or one of its subagents opened or acted on through `runs_write`; a run of another session or another project is never drawn, in director mode too (the band is the session's own graph, not the board), even one scoped to the item the session has in focus: touching or reading an item claims no run. With a run shown, the title row names the run's container, its scope item (else its root). The focus is the top-most item of the work the last item this session touched belongs to (a write through `items_write`, `advance`, `notes_write` or `deps_write`, or an `items_read` naming an `itemId`): its ancestor just below the project root, or just below a **grouping item**, one that only groups work (typed `container` or `project`, or tagged `container`, such as a `Bugs` backlog bucket), so a task under a bucket shows its own feature, not the bucket's whole backlog. Its container is the focus when it has children, and a lone leaf below the root or a bucket is its own container (its row is then left out). A grouping item the session touched itself is the focus, counting its work and not itself. A short band drops rows from the bottom, never the title. With no run and nothing touched, no band is drawn. It refreshes every 30 seconds, after every turn, and when the focus moves. The cancelled count and the agents' item titles come from the run's frontier (`cancelled`, `inFlight[].title`, new in the server release after 4.0.0); an older server shows cancelled members as done and an agent row by the item's short id.
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
- **Focus.** The item a TO call names is walked up `parentId` (`items_read` `get`, at most six hops, once per item id) to the top-most item below the project root, stopping below a grouping item (typed `container` or `project`, or tagged `container`). A call naming the container the session is already in (an overview or context read of the feature while a child is in hand) keeps that child on line 3.
- **Runs.** A `runs_write` call makes the runs it opens (each ok `open` result) and the runs its ops name by `runId` this session's, whether the session made it or one of its subagents (a background coordinator, a Workflow, a seat). A read never does: the director reads every run's frontier to run the board.
- A call made inside a subagent (it carries an agent id) never moves the mode or the focus of the session that dispatched it; it only makes the runs it opens or acts on that session's.

The mod writes nothing but its own `$.state` values (`director`, `coordinator`, `focus`, `bandHidden`, `board`, `watchedRuns`); it keeps nothing in `$.store`.

## Desktop app notes

The desktop app's Code tab (as of 2.1.317) draws the band above the prompt and the footer label, the latter only as a tree returned from the `SessionMode` hook (a rewrite of its `modes` list reaches the terminal alone). It does not draw a plugin `Backdrop`; the backdrop is in place for the builds that do.

## Developing

```
claude plugin validate claude-plugins/orchestration-mode
node scripts/ci/check-task-orchestrator-mod.mjs claude-plugins/orchestration-mode
```

The second command is the CI gate: validate, the `=?` unresolved-matcher check, a source scan of `on(...)` matchers, then `claude plugin test`. The tests stand for the engine beneath the plugin (`http.fetch`, `ui.toast` and `tool.call` answered by the test), so they cover the footer at rest and for one and two modes, the bar's cells, colours and blink, the agent rows (the kit steps the `Client`'s clock), coordinator detection from an `open` result and its close, a subagent's calls being ignored, the focus walk, `/to-band`, the band's three lines, its collapses (a lone leaf, a run with nothing touched, a short band) and its hidden and off states, and the pure helpers in `src/model.ts`. The marker probe (`fs`, `env`, `session`) is covered live.

Rules that hold here as in every mod: matchers are literals in the registering file (`src/indicator.ts`), `$` never leaves that file, and the atoms are declared beside their reads and writes.
