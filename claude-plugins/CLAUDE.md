# claude-plugins/ — Plugin Versioning

Plugin versions are bumped by the `/prepare-release` skill — either alongside a project release
or as a standalone plugin-only PR. They are **not** bumped per individual change during development.

During development, plugin changes (skills, hooks, scripts) are picked up only after a
forced cache refresh — see "Plugin Discovery and Cache Refresh" below. No version bump is needed for local iteration.

## Current Plugin Versions

| Plugin | Directory | Current Version |
|--------|-----------|-----------------|
| `task-orchestrator` | `claude-plugins/task-orchestrator/` | `3.9.0` |
| `task-orchestrator-mod` | `claude-plugins/task-orchestrator-mod/` | `0.1.0` |

> Updated automatically by `/prepare-release`. Do not bump manually.
>
> `/prepare-release` step 8b (`.claude/skills/prepare-release/SKILL.md`) bumps only `task-orchestrator` today, so the `task-orchestrator-mod` version is held by hand, in both version files, until the skill learns about it.

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

## Mod plugin: loading and refresh

`task-orchestrator-mod` is an opt-in plugin of in-process function hooks (see its [README](task-orchestrator-mod/README.md) and [CLAUDE.md](task-orchestrator-mod/CLAUDE.md)). It has no skills, only the runtime commands `/to-graph` and `/to-band`, so it has no skill-trigger conflicts.

- A marketplace install uses the same version-keyed cache and the same `rm -rf` + `marketplace update` refresh as above, with the same lazy re-extract.
- For development, skip the cache: `--plugin-dir` or `CLAUDE_CODE_PLUGIN_DIRS` watch the folder, and a save reloads the module. A reload re-runs `register` and `session.start` and resets module variables; `$.state` and `$.store` survive.
- A plugin-authoring dev-mods folder is a COPY, not a link. Re-sync it after edits and check it with `diff -rq`. The "Enable hot reloading?" answer is the switch.
- Never load two copies at once (marketplace plus dev).
