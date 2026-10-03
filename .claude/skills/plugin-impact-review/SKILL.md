---
name: plugin-impact-review
description: Assessment of plugin skill and hook changes needed after MCP or config changes. Evaluates skill references, hook context, config-format docs, and orchestration context references. Invoked via skillPointer when filling plugin-impact notes.
user-invocable: false
---

# Plugin Impact Review Framework

Evaluate which plugin components need updating after changes to MCP tools, config format, or response shapes.

## Step 1: Skill Impact

Search all skill files for references to the changed behavior:

```
grep -r "<tool-name>\|<config-key>\|<changed-concept>" claude-plugins/task-orchestrator/skills/
```

For each match:
- [ ] Does the skill reference the old behavior, field name, or response shape?
- [ ] Does the skill need updated instructions or examples?
- [ ] Are trigger phrases still accurate?

## Step 2: Hook Impact

Search hook scripts for references:

```
grep -r "<tool-name>\|<changed-concept>" claude-plugins/task-orchestrator/hooks/
```

For each match:
- [ ] Does the hook inject context that references the old behavior?
- [ ] Does the hook read tool input/output fields that changed?
- [ ] Does the hook matcher still target the correct tool name?

## Step 3: Config Format Documentation

Check `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md`:
- [ ] YAML structure example reflects current format
- [ ] Field reference table has all current fields
- [ ] Descriptions match current behavior

## Step 4: Orchestration Context References

Check the orchestration context for stale references:
- [ ] `hooks/orchestration-context.mjs` — any references to changed tools, fields, or workflows in the injected core (both the `workflow` and `schema` variants)
- [ ] `skills/orchestrate/SKILL.md` — tier table, delegation table, and phase-owner dispatch rules

## Step 5: Plugin Caching

- [ ] Are hook scripts changed? If yes, `/plugin marketplace remove` + re-add required (content cached)
- [ ] Are skill files changed? `/reload-plugins` sufficient (read at invocation)
- [ ] Is the orchestration-context hook content changed? Needs a session restart (or the forced cache refresh in `claude-plugins/CLAUDE.md`), not `/reload-plugins`; the `orchestrate` skill is read at invocation

## Output

Compose the `plugin-impact` note listing specific files and sections that need changes. Note `/mcp reconnect` requirements.

Before asserting any skill/hook/doc file is missing, verify with a direct existence check
(Read the exact expected path, or an exact-path Glob) — skills live in BOTH
`claude-plugins/task-orchestrator/skills/` and project-level `.claude/skills/`; checking
only one directory has produced a false "missing skill" finding before.
