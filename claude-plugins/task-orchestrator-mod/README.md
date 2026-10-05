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

- The Task Orchestrator MCP server, registered under the name `mcp-task-orchestrator`. The name is fixed in the mod and matches the registration shown in the [root README](../../README.md).
- `.taskorchestrator/config.yaml` with `project.rootId`, read relative to the session working directory.
- Recommended: the task-orchestrator plugin, newer than 3.9.0 (the release that ships this mod). It supplies the early-exit side of the dedupe flags.
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

- With no argument it shows the active feature. Other arguments are `root` or an item id.
- A scope toggle switches between `This feature` and `Whole project`. The whole-project view is an overview of the root's children with role roll-ups, from a single call limited to 100 children; past that it shows "More children than shown."
- Items are drawn top-down as state-filled boxes. An amber dashed box has an open blocker. Plan badges come from the item's `properties.planLabel` (1 to 12 characters).
- Phase and seat text, a legend, and a `Show done steps` / `Hide done steps` toggle.
- A clickable breadcrumb. `Loading ...` shows while the scope switches.
- Clicking a box opens a detail panel with Copy UUID and an Open graph drill-down.
- The critical path (the longest open chain of two or more items) gets a purple stripe and heavy edges. It is not drawn on the overview.
- `stalled` marks an item whose last move was 30 minutes ago or more. `gate:` marks this session's gate-blocked advance and is kept for 10 minutes.
- A worker line shows `seat · model`. A star marks a recent change.
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

The mod offers the session retrospective itself, by spawning the retrospective agent, when the session is not headless, a config is readable (at `AGENT_CONFIG_DIR` or the working directory) and the orchestration mode is not off. Test-kit verified, not live-verified.

### Phase guard

An in-process version of the phase guard that blocks a sub-agent from stopping with its phase notes unfilled. Test-kit verified, not live-verified.

### Call-shape rewrites

Test-kit verified, not live-verified. The mod repairs two common call shapes before the server sees them, and logs one transcript line starting `call-shape:` for each rewrite.

- It injects `ancestorId=<project rootId>` into list-mode `query_items` search, `get_next_item`, `get_blocked_items` and non-item `get_context` calls. It applies only when all of these hold: `ancestorId` is absent, there is no `parentId`, depth is not 0, there is no `tags` or `type` filter, and a root id resolves.
- It copies a top-level `actor` into any `manage_notes` upsert element that lacks one, and keeps the top-level actor.

To opt out of the `ancestorId` injection for a call, pass `ancestorId: ""`. The server treats a blank value as absent.

## Dedupe with the task-orchestrator command hooks

The mod and the plugin's command hooks would otherwise act twice on the same event. The mod forwards a flag on the classic event, and the matching command hook exits early when it sees it.

| Area | Flag the mod sets | Command hooks that step aside |
|------|-------------------|-------------------------------|
| Retrospective | `to_mod_retro: true`, on `classic.PostToolUse` (Bash, `advance_item`, `complete_tree`) and `classic.Stop` | `retro-trigger` and `retro-backstop` |
| Phase guard | `to_mod_active: true`, after a clean run, on `classic.PostToolUse` and `classic.SubagentStop` | `phase-guard-record` and `phase-guard` |

If the phase guard hits an internal error it sends no flag, and the command hook acts instead.

Two command hooks are deliberately not ported: skill enforcement and actor-attribution enforcement. A flag on PreToolUse would land inside the tool input, so those command hooks stay authoritative.

Call-shape rewrites need no dedupe: the mod's tool-call hook runs before the classic PreToolUse, so the command hooks see the repaired input.

Version: the early exits exist only in a task-orchestrator plugin newer than 3.9.0. With 3.9.0 or older plus this mod, the retrospective is offered twice (a spawned agent plus the command-hook directive), and both phase guards block.

## Live updates

Live updates need the server's REST API (`API_ENABLED=true`) and `curl` on `PATH`.

The API URL is resolved in this order:

1. The `TASK_ORCHESTRATOR_API_URL` environment variable.
2. `.taskorchestrator/client.json` in the working directory (a bare loopback origin only).
3. The user-level `client.json` under `TASK_ORCHESTRATOR_HOME`, `HOME` or `USERPROFILE`.

A bearer token from `TASK_ORCHESTRATOR_API_TOKEN` is passed to `curl` on stdin, never on the command line. The mod streams `GET /api/v1/events` for the project root.

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
- Three task-orchestrator plugin skills assume global results from calls the mod narrows to the project (pass `ancestorId: ""` to opt out):
  - `work-summary`, Scoped Mode `get_context()`: a process-global scope such as Retrospective Trends, Observations or Proposals loses its active, blocked and stalled items.
  - `dependency-manager`, Path B `get_blocked_items(includeDetails=true)`: the broad view is narrowed to the project, so blocked process-global items vanish.
  - `ralph`, troubleshooting `query_items(operation="search", claimStatus="claimed")`: claims outside the root are hidden.

  The skills are not changed by the mod; the skill-side fixes are tracked separately.

## For maintainers

Authoring and testing rules for this plugin are in [CLAUDE.md](CLAUDE.md).
