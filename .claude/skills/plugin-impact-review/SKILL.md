---
name: plugin-impact-review
description: Assessment of plugin skill and hook changes needed after MCP or config changes. Evaluates skill references, hook context, config-format docs, and orchestration context references. Invoked via skillPointer when filling plugin-impact notes.
user-invocable: false
---

# Plugin Impact Review Framework

Evaluate which plugin components need updating after changes to MCP tools, config format, or response shapes.

## Steps 1-4: Where to Look

The plugin-impact guidance (`items_read` `schema`, `fields` `["guidance"]`; checks (1)-(4)) lists what to
check. This skill adds only where to look in this repo:

1. Skills: both `claude-plugins/task-orchestrator/skills/` and `.claude/skills/`.
2. Hooks: `claude-plugins/task-orchestrator/hooks/`.
3. Config format: `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md`.
4. Orchestration context: `hooks/orchestration-context.mjs` (the `workflow` and `schema` variants) and `skills/orchestrate/SKILL.md`.

## Step 5: Generated Artifacts and Contracts

- [ ] Regenerate call shapes: `node claude-plugins/task-orchestrator/scripts/gen-call-shapes.mjs` (CI runs it with `--check`)
- [ ] Re-render the ralph prompts and the tier-classification fragments: `node claude-plugins/task-orchestrator/scripts/render-ralph-prompts.mjs --check` and `node claude-plugins/task-orchestrator/_fragments/generate.mjs` (`TierClassificationConsistencyTest` fails on drift)
- [ ] Contract tests pass: `claude-plugins/task-orchestrator/hooks/tests/contract-*.test.mjs`, and the `to-call` blocks in skills still match the tool shapes
- [ ] Other plugin consumers of the changed behavior: `agents/*.md`, `workflows/`, `bundled-rules/`, `.taskorchestrator/rules/`

## Step 6: Plugin Caching

- [ ] Is any plugin file changed (skills, hooks, workflows, agents)? The cache is version-keyed and re-extracts lazily, so a plain marketplace update does not pick it up: run the forced refresh in `claude-plugins/CLAUDE.md` (rm -rf the cache, then marketplace update)
- [ ] Is the orchestration-context hook content changed? It also needs a session restart; the `orchestrate` skill is read at invocation

## Output

Compose the `plugin-impact` note listing specific files and sections that need changes. Note the cache refresh and session restart the change needs (Step 6).

Before asserting any skill/hook/doc file is missing, verify with a direct existence check
(Read the exact expected path, or an exact-path Glob) — skills live in BOTH
`claude-plugins/task-orchestrator/skills/` and project-level `.claude/skills/`; checking
only one directory has produced a false "missing skill" finding before.
