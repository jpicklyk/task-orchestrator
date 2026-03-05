# claude-plugins/ — Plugin Versioning

Plugin versions are bumped by the `/prepare-release` skill — either alongside a project release
or as a standalone plugin-only PR. They are **not** bumped per individual change during development.

During development, plugin changes (skills, hooks, output styles, scripts) are picked up by
removing and re-adding the marketplace in Claude Code. No version bump is needed for local iteration.

## Current Plugin Versions

| Plugin | Directory | Current Version |
|--------|-----------|-----------------|
| `task-orchestrator` | `claude-plugins/task-orchestrator/` | `2.4.1` |

> Updated automatically by `/prepare-release`. Do not bump manually.

## Version Files

Two files must stay in sync for each plugin:

| File | Field |
|------|-------|
| `.claude-plugin/marketplace.json` | `plugins[name="<plugin>"].version` |
| `claude-plugins/<plugin>/.claude-plugin/plugin.json` | `version` |

## Delegation Warning

When multiple subagents edit plugin files in parallel, subagents must NOT modify version files.
Version bumps are handled by the orchestrator or by `/prepare-release` at release time.
