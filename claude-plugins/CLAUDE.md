# claude-plugins/ — Plugin Versioning

Plugin versions are bumped by the `/prepare-release` skill — either alongside a project release
or as a standalone plugin-only PR. They are **not** bumped per individual change during development.

During development, plugin changes (skills, hooks, scripts) are picked up only after a
forced cache refresh — see "Plugin Discovery and Cache Refresh" below. No version bump is needed for local iteration.

## Current Plugin Versions

| Plugin | Directory | Current Version |
|--------|-----------|-----------------|
| `task-orchestrator` | `claude-plugins/task-orchestrator/` | `4.0.0` |
| `task-orchestrator-mod` | `claude-plugins/task-orchestrator-mod/` | `0.2.0` (not listed in the marketplace; dev-only via `--plugin-dir`) |
| `orchestration-mode` | `claude-plugins/orchestration-mode/` | `0.1.0` |

> Updated automatically by `/prepare-release`. Do not bump manually.

## Compatibility with the server

The plugin and the server are versioned together by major version. The 4.x plugin speaks the 4.0 tool surface (`items_read`, `advance`, `runs_write` and the rest of the registry) and needs a server whose major version is 4 and whose `GET /.well-known/mcp-task-orchestrator.json` lists the `seats` feature (`hooks/lib/server-compat.mjs`, `assessServer`).

| Plugin | Server | Result |
|--------|--------|--------|
| 4.x | 4.x with `seats` | Supported. |
| 4.x | 3.x, or 4.x without `seats` | `session-start` shows one warning line (when a REST API URL resolves and the server answers); `run-wave` `plan` refuses (exit 3, `server incompatible: ...`); a tool result that still has the 3.x shape shows one warning per session from `phase-guard-record` and `dispatch-hint`, which record and hint nothing. The other hooks fail open. |
| 3.x | 3.x | Supported (the `release/3.x` line). |
| 3.x | 4.x | Not supported: every 3.x call shape is gone, so the hooks fail open and the skills call tools that do not exist. |

To keep a 3.x server on the 3.x plugin, install the plugin from the `release/3.x` line: clone the repository and check out `release/3.x`; remove the 4.x marketplace first (`/plugin marketplace remove task-orchestrator-marketplace`, since both lines use that name); then run `/plugin marketplace add <checkout>` with the repository root, which holds `.claude-plugin/marketplace.json` and against which its `source` paths resolve. The marketplace serves the checkout's working tree, so keep that checkout on `release/3.x`.

## Version Files

Two files must stay in sync for each plugin:

| File | Field |
|------|-------|
| `.claude-plugin/marketplace.json` | `plugins[name="<plugin>"].version` |
| `claude-plugins/<plugin>/.claude-plugin/plugin.json` | `version` |

## Delegation Warning

When multiple subagents edit plugin files in parallel, subagents must NOT modify version files.
Version bumps are handled by the orchestrator or by `/prepare-release` at release time.

## Plugin Discovery and Cache Refresh

Two skill systems — do not confuse them: **project-level skills** in `.claude/skills/` are auto-discovered with no config; plugin skills are activated as below.

**Plugin skills** (`claude-plugins/task-orchestrator/skills/`) — require activation via `.claude/settings.json`:
```json
{ "enabledPlugins": { "task-orchestrator@task-orchestrator-marketplace": true } }
```
- Marketplace name: `task-orchestrator-marketplace` (from `.claude-plugin/marketplace.json` → `name`)
- If plugin stops loading: `/plugin marketplace add .claude-plugin` then `/plugin enable task-orchestrator@task-orchestrator-marketplace`
- After editing plugin files: the plugin cache is **version-keyed** — while the plugin version is
  unchanged, `claude plugin marketplace update` AND full remove/re-add both silently reuse the stale
  cached copy at `~/.claude/plugins/cache/<marketplace>/<plugin>/<version>/`. Force a refresh:
  `rm -rf ~/.claude/plugins/cache/task-orchestrator-marketplace`, then
  `claude plugin marketplace update task-orchestrator-marketplace`. Re-extraction is **lazy** (next
  session start) — an empty cache dir right after the update is normal, not broken. Verify after the
  next session starts by grepping the cached files for your change; for `run-wave` also run
  `node <cache>/scripts/run-planner.mjs probe` (it reports `pluginVersion` and `phase0Hooks`), confirm
  `task-orchestrator:implement-wave` is listed in `/workflows`, and confirm the skill's `${CLAUDE_PLUGIN_ROOT}`
  path resolved (no literal in the probe command). A session that amends plugin
  content must treat same-session invocations of those skills/hooks as stale (diff loaded content
  against disk before following it). The marketplace serves the working **tree** — confirm the
  checkout is on the branch you intend to install before refreshing.
- `workflows/` and `agents/` ride the same version-keyed cache as skills and hooks — a workflow or
  agent edit needs the same forced refresh above. Verify with `/workflows` listing
  `task-orchestrator:implement-wave`, `task-orchestrator:audit`, `task-orchestrator:retro-analysis`,
  and `task-orchestrator:review-wave`, and `/agents` listing the new agent definitions.

## Regenerate call shapes

The plugin's view of the 4.0 tool surface is generated, never hand-edited: `skills/_generated/call-shapes.md`
(per select/op fields, profiles, capability, errors, with `<a id="<tool>-<name>">` anchors),
`hooks/lib/tool-catalog.json`, `hooks/lib/tool-names.mjs`, and the `TOOL_NAMES` region (between
`// @generated:tool-names-begin` and `// @generated:tool-names-end`) of `workflows/implement-wave.js` and
`workflows/review-wave.js`. After the server's tool goldens or `current/docs/api-reference.md` change, run
`node claude-plugins/task-orchestrator/scripts/gen-call-shapes.mjs` and commit the result; CI runs the same command with
`--check` and fails on a stale file (exit 1) or on a golden/API disagreement (exit 2). Workflow scripts run as function
bodies and cannot import, which is why they carry the generated region instead of importing `tool-names.mjs`.

Contract tests (`hooks/tests/contract-*.test.mjs`, library `scripts/lib/contract-checks.mjs`) pin the hook matchers
to real tool names and reject a hard-coded retired 3.x tool name. The baseline `scripts/lib/contract-names-baseline.json` is now empty (`pendingPort: []`, `proseExceptions: []`): no file waits for a port, so a new
file that names a retired tool fails the test. The ratchet is two-way, so a row added for a file that no longer needs it also fails. Documented examples are ```to-call
blocks (`{tool, select|op, args, profile?}`) validated against the catalog.

## Mod plugin: loading and refresh

`task-orchestrator-mod` is an in-repo experiment of in-process function hooks (see its [README](task-orchestrator-mod/README.md) and [CLAUDE.md](task-orchestrator-mod/CLAUDE.md)). It is NOT listed in `.claude-plugin/marketplace.json` and has no public install path: it loads only from a checkout, dev-only, via `--plugin-dir` or `CLAUDE_CODE_PLUGIN_DIRS`. It has no skills, only the runtime commands `/to-graph` and `/to-band`, so it has no skill-trigger conflicts.

- There is no marketplace copy to refresh, so the version-keyed cache and `marketplace update` steps above do not apply to it.
- Loading, from a checkout: `--plugin-dir` or `CLAUDE_CODE_PLUGIN_DIRS` watch the folder, and a save reloads the module. A reload re-runs `register` and `session.start` and resets module variables; `$.state` and `$.store` survive.
- A plugin-authoring dev-mods folder is a COPY, not a link. Re-sync it after edits and check it with `diff -rq`. The "Enable hot reloading?" answer is the switch.
- Never load two copies at once (two dev folders, or a dev folder plus a stale cached copy).

`orchestration-mode` ([README](orchestration-mode/README.md)) is a second, standalone mod with the same loading and refresh rules. Its band shows the graph the session drives (open runs, else the focus item its own TO calls touched) in every mode, toggled by userConfig `band` and its own `/to-band` command, and its footer always names the mode (`orchestrator` at rest, else the roster modes a user sees in a session of their own: `director`, `coordinator`); do not load it beside `task-orchestrator-mod`, which registers a `/to-band` of its own. It shares no code or state with `task-orchestrator-mod`; keep it that way (a feature for one never lands in the other). Its CI is `.github/workflows/orchestration-mode.yml`, running the same gate script on its folder with a CLI pinned at 2.1.317 (the `Backdrop` component). Its plugin name is also its footer prefix (the engine draws every status line as `<plugin name> <text>`), which is why it is named for the concept and not for one mode.
