---
name: plugin-impact-review
description: Assessment of plugin skill and hook changes needed after MCP or config changes. Evaluates skill references, hook context, config-format docs, and output style references. Invoked via skillPointer when filling plugin-impact notes.
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

## Step 4: Output Style References

Check output styles for stale references:
- [ ] `workflow-orchestrator.md` — any references to changed tools, fields, or workflows
- [ ] Zone 1 (shared core) vs Zone 2/3 (extensions) — changes in Zone 1 must be synced to `workflow-analyst.md`

## Step 5: Plugin Caching

- [ ] Are hook scripts changed? If yes, `/plugin marketplace remove` + re-add required (content cached)
- [ ] Are skill files changed? `/reload-plugins` sufficient (read at invocation)
- [ ] Are output styles changed? `/reload-plugins` sufficient

## Output

Compose the `plugin-impact` note listing specific files and sections that need changes. Note `/mcp reconnect` requirements.
