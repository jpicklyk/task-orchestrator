# claude-plugins/ — Version Bump Requirement

**Any change to plugin content in this directory requires a version bump.**

Claude Code caches plugin content (skills, hooks, output styles, scripts) keyed by version number.
Without a version bump, Claude Code will continue serving the old cached copy — changes will not
take effect until the user removes and re-adds the marketplace.

## What Triggers a Bump

Changes to **any** file inside a plugin directory:
- `skills/` — skill markdown files
- `hooks/` — hook config JSON or referenced scripts
- `output-styles/` — output style markdown files
- `scripts/` — hook scripts (`.mjs`, `.sh`, etc.)
- `.claude-plugin/plugin.json` — plugin manifest

## What to Update

Two files must be kept in sync for each affected plugin:

| File | Field |
|------|-------|
| `.claude-plugin/marketplace.json` | `plugins[name="<plugin>"].version` |
| `claude-plugins/<plugin>/.claude-plugin/plugin.json` | `version` |

Both must carry the **same version string** after the bump.

## Versioning Convention

Use semantic versioning (`major.minor.patch`):
- **patch** — content fixes, wording, minor skill adjustments
- **minor** — new skill, new hook, new output style
- **major** — breaking changes to skill interface or hook behavior

## Current Plugin Versions

| Plugin | Directory | Current Version |
|--------|-----------|-----------------|
| `task-orchestrator` | `claude-plugins/task-orchestrator/` | `1.1.1` |

> Update this table when versions change.

## After Bumping

Re-add the marketplace in Claude Code to pull the updated cache:
1. Remove the marketplace (Claude Code settings → Plugins → Remove)
2. Re-add it pointing to this repo root
