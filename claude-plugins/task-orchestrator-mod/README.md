# task-orchestrator-mod

An opt-in Claude Code mod for [MCP Task Orchestrator](../../README.md): a live work-graph pane, an in-flight band and status line, and in-process orchestration hooks. It ships as a separate plugin in the same marketplace as the task-orchestrator plugin and does not replace it.

A mod is a plugin of in-process TypeScript function hooks (`hooks/hooks.json` points at `./register.ts`). It is additive: the server stays the gate authority, and the task-orchestrator plugin's command hooks remain the baseline for clients that do not load the mod.

## Early access

The function-hooks API this mod uses is early access. A Claude Code update can break the mod or switch it off. It was built and tested against Claude Code 2.1.286, and the per-build types file shipped with your Claude Code is the authority on the API.

If the mod misbehaves, disable it. The task-orchestrator command hooks resume by themselves, because the dedupe flags (see [Dedupe with the command hooks](#dedupe-with-the-task-orchestrator-command-hooks)) are only set when the mod handled the event.

Verification status:

- Live-verified on the desktop Code tab: the graph data, the `/to-graph` pane and the band.
- Test-kit verified, not live-verified: the in-process retrospective, the phase guard and the call-shape rewrites. Their live acceptance checks are still open.
- The terminal edge raster is also kit-tested only. See [Surfaces](#surfaces).

## Requirements

- A Task Orchestrator 4.0 MCP server, registered under the name `mcp-task-orchestrator`. The name is fixed in the mod and matches the registration shown in the [root README](../../README.md). The mod speaks the 4.0 tools (`items_read`, `notes_read`, `deps_read`, `runs_read`, `advance`, `items_write`, `notes_write`, `deps_write`, `runs_write`, `config`) and reads every result from its envelope (`structuredContent`); a 3.x server is not supported.
- `.taskorchestrator/config.yaml` with `project.rootId`, read relative to the session working directory.
- Recommended: the task-orchestrator plugin 4.0.0. It supplies the early-exit side of the dedupe flags and the 4.0 command hooks the mod's hooks stand in for.
- Optional, for live updates: the server's REST API enabled, and `curl` on `PATH`. See [Live updates](#live-updates).

## Opt in

Marketplace install, after the existing `marketplace add` from the [root README](../../README.md):

```
/plugin install task-orchestrator-mod@task-orchestrator-marketplace
```

To disable it:

```
/plugin disable task-orchestrator-mod@task-orchestrator-marketplace
```

From a checkout on disk:

```
claude --plugin-dir <repo>/claude-plugins/task-orchestrator-mod
```

On the desktop app, where no flag can be passed, set `CLAUDE_CODE_PLUGIN_DIRS` in the process environment or in the `env` block of `~/.claude/settings.json`. Do not put it in project settings. Both forms are watched and hot-reload on save.

Headless `claude -p` and `claude plugin test` need `CLAUDE_CODE_ENABLE_FUNCTION_HOOKS=1`.

Do not load the marketplace copy and a disk copy at the same time.

The marketplace install path has not yet been confirmed end to end. If your build needs an extra enable step, it is not described here.

## Surfaces

The graph pane draws boxes and edges differently per surface. The terminal falls back to a raster; every other surface uses an SVG edge layer.

| Surface | Rendering | Status |
|---------|-----------|--------|
| Terminal | Boxes with a box-drawing edge raster | Kit-tested, not live-verified |
| Desktop Code tab | Boxes with an SVG edge layer; cell size comes from `userConfig` | Live-verified |
| VS Code and mobile | Take the SVG path | Unverified |

On all surfaces:

- The band, the status line and toasts are available.
- The band hides below 40 columns, or while a survey is showing.
- Copy UUID falls back to the host machine's clipboard tool (`clip`, `pbcopy`, `wl-copy` or `xclip`). On mobile that is the host machine's clipboard, which is unverified.

## What it does

### Work graph pane: `/to-graph`

- With no argument it shows the scope of the latest transition under the project: the nearest feature, or the item itself for work under a container such as Bugs. It falls back to the most recently modified feature in work when that read fails. Other arguments are `root` or an item id.
- The scope is auto or pinned. A bare `/to-graph`, or the band opening the pane, starts in auto: while the pane is open, a TO call or a spawned agent that names an item outside the graph moves the pane to that item's owning scope. An id argument, `root`, `This feature`, `Whole project`, a breadcrumb, `Open graph` or a click on a box pins the scope, and a pinned scope never moves; the `Follow` button (shown only while pinned) or a bare `/to-graph` returns to auto. Besides this session's own activity, an item transition made by another session in this project (an `item.advanced` event outside this session's echo window) moves an auto pane after a 3 second pause, when this session has been quiet for 30 seconds.
- A scope toggle switches between `This feature` and `Whole project`. The whole-project view is an overview of the root's children with role roll-ups, from a single call limited to 100 children; past that it shows "More children than shown."
- Items are drawn top-down as state-filled boxes. An amber dashed box has an open blocker. Plan badges come from the item's `properties.planLabel` (1 to 12 characters).
- Phase and seat text, a legend, and a `Show done steps` / `Hide done steps` toggle.
- A clickable breadcrumb. `Loading ...` shows while the scope switches.
- Clicking a box opens a detail panel with Copy UUID and an Open graph drill-down.
- The critical path (the longest open chain of two or more items) gets a purple stripe and heavy edges. It is not drawn on the overview.
- `stalled` marks an item whose last move was 30 minutes ago or more. `gate:` marks this session's gate-blocked advance and is kept for 10 minutes.
- A worker line shows `seat · model`. A star marks a recent change. A `?` marks an item with an open decision (from `decision.*` events on the live stream; an answered or withdrawn decision clears it).
- The header shows `runs: <n> open, <m> paused` while the project has open or paused runs (from `run.*` events on the live stream).
- Graph items are keyed by their 8-character short ids, as the 4.0 compact views return them; Copy UUID copies the full id from the item's `get` view.
- Keyboard: one focus stop per box; Enter opens the detail.
- Size limits: a scope with more children than the draw limit shows "Too large to draw" with the count and asks you to open a smaller scope. Below a card-count threshold the whole box is clickable; above it, each box gets one button. A subtree snapshot keeps only the shallowest items. `Reconnect` shows only when live updates are degraded.

### Band and status line

- On a scoped graph the band reads `item-id · done/total done · ready: ... · waiting: ...`. Otherwise it shows the in-flight item and its gate.
- Pressing the band opens the pane. `/to-band` hides or shows it.
- The status line shows `TO`, the item id prefix and a finished/remaining count.
- The band subscribes once per session, so the live source (one `curl` stream, or a 15 second poll) runs for the whole session.

### Toasts

Opt-in through `graphToasts`. A toast appears when an item completes, becomes ready, is sent back from review to work, or one of this session's advances is gate-blocked. At most three toasts are shown per snapshot.

### Cross-session change marks

A change made by another session, seen over the live stream outside a short window after your own writes, is marked in the graph. Poll mode never marks changes.

### In-process retrospective

The mod offers the session retrospective itself, by spawning the retrospective agent, when the session is not headless, a config is readable (at `AGENT_CONFIG_DIR` or the working directory) and `extensions.claude-code.retrospective.mode` is not `off`. It follows run marks, like the command hooks: a retrospective is owed per closed run on the project root that is not marked `retro`. On a successful `runs_write` `close` (and again at Stop) it lists the root's closed, unmarked runs of the last 24 hours, reads each run's terminal count, and in `dispatch` mode at or above `dispatchThreshold` tries to win the run's `retro` mark (first write wins, so two sessions never both dispatch) before spawning one agent for `/session-retrospective --run <runId>`; otherwise it nudges. In a subagent, or without a project `rootId`, the close is recorded and the Stop nudges once. Test-kit verified, not live-verified.

Known gap: the server serves the `runs_write` `mark` op over REST only, so the mod's `mark` call (made over the MCP connection) fails and every dispatch-eligible run is nudged instead of dispatched until that is resolved.

### Phase guard

An in-process version of the phase guard that blocks a sub-agent from stopping with its phase notes unfilled. It records the items an `advance` call entered (a transition, or a `gate_blocked` element), reads each item's gate with `items_read` `context`, and never records a call made under a run `seatToken` or blocks a workflow subagent. Test-kit verified, not live-verified.

### Call-shape rewrites

Test-kit verified, not live-verified. The mod repairs common call shapes before the server sees them, and logs one transcript line `call-shape: <tool> <rule>` for each rule it applied. An input already in the 4.0 shape is passed through unchanged.

- R1: it injects `ancestorId=<project rootId>` into `items_read` `list`, `next`, `blocked` and item-less `context` calls. It applies only when all of these hold: `ancestorId` is absent, there is no `parentId`, a `list` has depth other than 0, there is no `tags`, `type` or `traits` filter, and a root id resolves.
- R2: `ancestorId: ""` is the opt-out: the mod removes it and injects nothing.
- W1: a write call with a top-level `op` and no `ops` is wrapped into `ops[0]` (`atomic` and `seatToken` stay at the top).
- W2: a 3.x `transitions[]` on `advance` or `notes[]` on `notes_write` becomes `ops[]`, each element given `op: "transition"` or `op: "upsert"` when it has none.
- W3: a top-level `actor` and an `actor` on any `ops` element are removed (4.0 takes the caller from the transport and refuses a body `actor`).
- W4: on `items_write` `create` and `update`, `properties.traits` moves to the op's `traits` (dropped when the op already has `traits`).

The mod never guesses a missing `type`: the server's `schema_violation` lists the available types.

## Dedupe with the task-orchestrator command hooks

The mod and the plugin's command hooks would otherwise act twice on the same event. The mod forwards a flag on the classic event, and the matching command hook exits early when it sees it.

| Area | Flag the mod sets | Command hooks that step aside |
|------|-------------------|-------------------------------|
| Retrospective | `to_mod_retro: true`, on `classic.PostToolUse` (`runs_write`) and `classic.Stop` | `retro-trigger` and `retro-backstop` |
| Phase guard | `to_mod_active: true`, after a clean run, on `classic.PostToolUse` (`advance`) and `classic.SubagentStop` | `phase-guard-record` and `phase-guard` |

If the phase guard hits an internal error it sends no flag, and the command hook acts instead.

Skill enforcement is deliberately not ported: a flag on PreToolUse would land inside the tool input, so the command hook stays authoritative. There is no actor-attribution hook: 4.0 takes the caller from the transport.

Call-shape rewrites need no dedupe: the mod's tool-call hook runs before the classic PreToolUse, so the command hooks see the repaired input.

Version: pair the mod with the task-orchestrator plugin 4.0.0. The early exits exist only in a task-orchestrator plugin newer than 3.9.0; with an older plugin, the retrospective is offered twice (a spawned agent plus the command-hook directive), and both phase guards block.

## Live updates

Live updates need the server's REST API (`API_ENABLED=true`) and `curl` on `PATH`.

The API URL is resolved in this order:

1. The `TASK_ORCHESTRATOR_API_URL` environment variable.
2. `.taskorchestrator/client.json` in the working directory (a bare loopback origin only).
3. The user-level `client.json` under `TASK_ORCHESTRATOR_HOME`, `HOME` or `USERPROFILE`.

A bearer token from `TASK_ORCHESTRATOR_API_TOKEN` is passed to `curl` on stdin, never on the command line. The mod streams `GET /api/v1/events` for the project root, subscribed to the 4.0 item, note, dependency, scope, run, seat and decision events. An event that arrives while one of this session's writes is in flight, or within 5 seconds after the last one, is this session's own echo; every other event came from another session. The event's `actor` is not used: in 4.0 it is the transport principal, which every session on one credential shares.

A reconnect resumes the stream: the mod sends the last event id it saw as `Last-Event-ID` (kept as text, since event ids are above 10^12). When the server cannot replay from it, the first frame is `sync.lost`; the graph re-reads at once and the stream resumes from the id the `sync.lost` frame carries. A restarted live source (Reconnect, or the pane and band all closed) starts without a resume id.

With no URL, or after three failed connects, a 15 second poll takes over. `Reconnect` in the pane restarts the stream.

## Configuration

Set these from the plugin's config menu rows, or in settings under `pluginConfigs["task-orchestrator-mod"].options`.

| Option | Default | Meaning |
|--------|---------|---------|
| `cellWidthPx` | `8.4` | Width of one character cell in the desktop pane; clamped to 4-32 |
| `cellHeightPx` | `18` | Height of one character cell in the desktop pane; clamped to 8-64 |
| `graphToasts` | `false` | Show graph toasts |

## Known limitations

- Config and `client.json` are resolved from the session working directory only. There is no walk-up, no main-checkout lookup from a linked worktree, and no user-level config, unlike the task-orchestrator hooks. A session in a linked worktree falls back to polling unless the API URL is set through the environment or a user-level `client.json`.
- The MCP server name is fixed to `mcp-task-orchestrator`.
- `/to-graph` and `/to-band` are registered lazily, on the first band draw or snapshot.
- Write actions (acting on items from the pane) are not available.
- Known issues you may hit: `/to-graph <prefix>` of the root id walks the subtree instead of showing the overview; Copy UUID on Linux may report unavailable; a critical path can drop the SVG layer; and a teardown error can replace a tool result.
- A task-orchestrator plugin skill that expects global results from a call the mod narrows to the project loses what lies outside it (pass `ancestorId: ""` to opt out):
  - an item-less `items_read` `context` (the health view): process-global scopes such as Retrospective Trends, Observations or Proposals lose their active, blocked and stalled items.
  - `items_read` `blocked`: blocked process-global items vanish from the broad view.
  - `items_read` `list` with `claimStatus: "claimed"`: claims outside the root are hidden.
  - `items_read` `next`: work outside the root is not offered.

  The skills are not changed by the mod.
- Open decisions and runs are known only from the events this session's live stream has seen since it started; there is no snapshot read behind them (`runs_read` `decisions` is director-only).
- `/to-graph <prefix>` needs at least 8 hex characters on a 4.0 server.

## For maintainers

Authoring and testing rules for this plugin are in [CLAUDE.md](CLAUDE.md).
