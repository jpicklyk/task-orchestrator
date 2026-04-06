---
name: api-compat-review
description: Backward compatibility assessment for MCP tool API changes. Evaluates parameter additions, response shape changes, and migration paths. Invoked via skillPointer when filling api-compatibility notes.
user-invocable: false
---

# API Compatibility Review Framework

Evaluate whether MCP tool API changes maintain backward compatibility for existing callers (Claude Code agents, orchestrators, skills, hooks).

## Step 1: Parameter Changes

Read the changed tool definition and check:
- [ ] **No new required parameters** on existing operations — adding a required param breaks all existing callers
- [ ] **New optional parameters** have sensible defaults that preserve existing behavior
- [ ] **Renamed parameters** — does the old name still work? If not, is there a migration path?
- [ ] **Removed parameters** — are any previously-accepted params silently ignored? Document the break.

## Step 2: Response Shape Changes

Compare before/after response JSON:
- [ ] **Additive only** — new fields added, no fields removed or renamed
- [ ] **Null-omission consistency** — new nullable fields follow the existing pattern (omit when null, not `"field": null`)
- [ ] **Type stability** — existing fields retain their type (don't change string to array, int to string, etc.)
- [ ] **Array/object structure** — existing nested structures unchanged; new nesting is additive

## Step 3: Behavioral Changes

- [ ] **Default behavior preserved** — calling with the same params as before produces the same result
- [ ] **Error codes unchanged** — existing error responses use the same codes and structure
- [ ] **Side effects** — does the change add new side effects (cascades, note creation, state changes) that existing callers don't expect?

## Step 4: Documentation

- [ ] **`api-reference.md` updated** — new parameters, response fields, and behavioral changes documented
- [ ] **Tool description text** — the inline description in the tool definition accurately reflects new behavior
- [ ] **Examples** — JSON examples in docs reflect the current response shape

## Step 5: Consumer Impact

Check which consumers reference the changed tool:
- [ ] **Plugin skills** — grep skill files for the tool name
- [ ] **Plugin hooks** — grep hook scripts for the tool name
- [ ] **Output styles** — grep for tool name references
- [ ] **CLAUDE.md** — any hardcoded response expectations

## Output

Compose the `api-compatibility` note with findings from each step. Flag any breaking changes with migration paths.
