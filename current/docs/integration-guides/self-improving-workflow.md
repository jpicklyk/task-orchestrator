# Tier 6: Self-Improving Workflow

**Prerequisites:** [Tier 1: Bare MCP](bare-mcp) complete · Claude Code auto-memory enabled · Recommended: [Tier 5: Output Styles](output-styles) for full orchestration context (not required)

**Cross-references:** [Quick Start](../quick-start) · [API Reference](../api-reference) · [Workflow Guide](../workflow-guide) · [Output Styles](output-styles)

---

## What You Get

- Claude monitors its own MCP tool usage in real time and flags inefficiencies
- Tool friction, bugs, and optimization opportunities are logged as persistent MCP items
- Agent discipline violations are corrected via auto-memory — corrections apply in the next session
- Analysis reports surface patterns after every response involving MCP calls
- A continuous feedback loop: each session's learnings improve the next

---

## The Concept

Most agent workflows are open-loop: the agent uses tools, produces output, and forgets what worked and what did not. The self-improving workflow closes the loop — the agent actively observes its own tool interactions, detects inefficiencies, and corrects its behavior for future sessions.

This creates two feedback channels:

```
Session N                                Session N+1
─────────                                ───────────
Tool calls → observations detected       Memory loaded → better tool calls
     ↓                                         ↑
MCP items (friction/bugs/optimization)   Read observations, apply corrections
     ↓                                         ↑
Memory writes (discipline corrections)  ──────────→
```

**Channel 1: Tool observations → MCP items.** When the agent detects a tool-side issue (API confusion, missing capability, optimization opportunity), it logs an MCP work item tagged `agent-observation`. These persist across sessions and can be reviewed, prioritized, and addressed as product improvements.

**Channel 2: Agent discipline → auto-memory.** When the agent detects a mistake in its own behavior (wrong query pattern, forgotten parameter, sequencing violation), it writes a correction to its persistent memory file. The next session loads this correction automatically.

---

## Channel 1: Observation Logging

### What Gets Logged

The agent watches for patterns during MCP interactions:

**Token waste patterns:**

| Pattern | Detection signal | Preferred alternative |
|---------|-----------------|----------------------|
| Over-fetching | `query_items(get)` for a status check | `query_items(overview)` — 85-90% fewer tokens |
| Missing batch | 3+ individual `manage_items` calls in one turn | Use the `items` array for bulk creates |
| Redundant queries | Same entity queried twice in a turn | Cache the first result |
| Unfiltered search | `query_items(search)` with no filters | Add `role`, `tags`, or `parentId` to narrow |
| Note body waste | `query_notes(includeBody=true)` when only keys/roles needed | Use `includeBody=false` for metadata-only reads |

**Friction patterns:**

| Category | What to watch |
|----------|--------------|
| Tool failures | MCP calls that return errors or unexpected empty results |
| Excessive round-trips | Workflows requiring 3+ sequential calls where 1-2 should suffice |
| API confusion | Parameter naming inconsistencies, unclear error messages |
| Silent failures | Operations that succeed but produce no useful effect |
| Missing capability | Gaps requiring workarounds |

### The Logging Protocol

When the agent detects an issue, it follows a three-step protocol:

**Step 1 — Dedup check.** Search for existing observations to avoid duplicates:

```
query_items(operation="search", tags="agent-observation", query="<topic keyword>")
```

**Step 2 — Create (only if no match found):**

```
manage_items(operation="create", items=[{
  title: "[optimization] Use overview instead of get for status dashboards",
  summary: "Detected query_items(get) used for a status check. query_items(overview) returns item summaries without note bodies — sufficient for dashboards and 85-90% cheaper.",
  tags: "agent-observation,optimization",
  priority: "low"
}])
```

No `parentId` — observations are standalone top-level items. Default `priority="low"` keeps them from competing with implementation items in `get_next_item`.

**Step 3 — Fill the observation note:**

```
manage_notes(operation="upsert", notes=[{
  itemId: "<new-item-uuid>",
  key: "observation-detail",
  role: "queue",
  body: "**Observed:** query_items(get) followed by query_notes(includeBody=true) to render a dashboard.\n**Expected:** query_items(overview) returns item summaries without note bodies — sufficient for dashboards.\n**Suggestion:** Use overview for any read that does not need note content."
}])
```

### Tagging Convention

Every observation gets `agent-observation` as primary tag plus exactly one type tag:

| Type tag | When |
|----------|------|
| `optimization` | Token waste, redundant queries, missed batch opportunities |
| `friction` | Confusing APIs, excessive round-trips, unclear errors |
| `bug` | Actual defects in tool behavior |
| `missing-capability` | Gaps requiring workarounds |

If an observation is immediately actionable — for example, a bug blocking current work — add `action-item` as an additional tag so it surfaces in dashboards.

### The `agent-observation` Schema

The `agent-observation` note schema (in `.taskorchestrator/config.yaml`) gives observations a lightweight gate:

```yaml
note_schemas:
  agent-observation:
    - key: observation-detail
      role: queue
      required: true
      description: "Expected vs actual behavior and suggested improvement."
      guidance: "Describe what you observed (tool call, parameters, result). State what you expected instead. Suggest a concrete improvement — API change, error message improvement, or new capability."
```

One note, queue phase only. Logging should be fast — two MCP calls after the dedup check. Observations never advance past queue; they are reference items, not implementation work.

---

## Channel 2: Self-Correction via Auto-Memory

### What Gets Corrected

When the agent detects a mistake in its own orchestration behavior — not a tool issue, but an agent discipline issue — it writes a correction to persistent memory.

| Category | Example signal | Memory entry pattern |
|----------|---------------|---------------------|
| Data integrity | Used a truncated UUID | Always use full UUIDs from query responses |
| Delegation format | Subagent returned raw JSON instead of summary | Specify exact return format in delegation prompts |
| Sequencing | Dispatched agent before materializing items | Materialize all MCP items before dispatching agents |
| Missing parameter | Forgot `model` on Agent dispatch | Always set model explicitly: haiku / sonnet / opus |
| Prompt deficiency | Subagent failed due to missing context | Record what context was needed |

### Tool Issues vs Agent Issues

This distinction determines where the correction goes:

| Issue type | Persistence | Who fixes it |
|-----------|-------------|-------------|
| Tool friction, bug, missing capability | MCP observation item | Product development |
| Agent discipline, sequencing, format | Auto-memory file | The agent (next session) |

### The Correction Protocol

1. **Detect** the issue during the current session
2. **Check** existing memory for similar coverage to avoid duplicates
3. **Write** a concise correction: pattern + correct behavior
4. **Report** in the session output: `↳ [self-correction] Updated memory: always set model parameter on Agent dispatch`

---

## Analysis Reporting

The agent appends an analysis block to the end of every response that involved MCP tool calls or subagent activity.

### Lightweight Format (1-3 MCP calls, no subagents)

```
---
◆ **Analysis** — 2 MCP calls | clean
```

Or with an issue:

```
---
◆ **Analysis** — 3 MCP calls | over-fetch: used get+notes for status check
```

### Full Format (4+ MCP calls or subagent activity)

```
---
### ◆ Workflow Analysis

**MCP Call Efficiency**
↳ 12 calls, 2 batched, no redundant queries

**Friction Points** (0 this session)
↳ None detected

**Observations Logged** (1 new)
↳ [optimization] `a1b2c3d4` — batch dependency creation for linear chains

**Suggestions**
↳ Consider using query_items(overview) for the work-summary dashboard
```

Inline analysis uses the `↳ [analysis]` prefix for real-time visibility during the response body. The end-of-response block is the aggregate summary.

---

## Building Your Own

The self-improving workflow can be implemented at several depths:

### Option A: CLAUDE.md Instructions (lightest)

Add observation and self-correction instructions directly to your `CLAUDE.md`:

```markdown
## Self-Improvement Protocol

After every MCP interaction, check:
1. Did any call return more data than needed? Log as agent-observation optimization.
2. Did any call fail unexpectedly? Log as agent-observation friction.
3. Did I repeat a mistake from a previous session? Check and update memory.

When logging observations, always dedup-check first:
query_items(operation="search", tags="agent-observation", query="<topic>")
```

This approach adds the behavior without requiring a custom output style. The CLAUDE.md instructions are active in every session.

### Option B: Custom Output Style (deeper)

For deeper integration, create a custom output style with an analysis zone. Output styles have more behavioral influence than CLAUDE.md because they shape every response, not just tool usage.

Structure your output style in three zones:

1. **Zone 1 — Orchestration core:** delegation rules, planning, phase transitions (mirrors the Workflow Orchestrator)
2. **Zone 2 — Extended orchestration:** any workflow enhancements specific to your setup
3. **Zone 3 — Analysis layer:** monitoring patterns, observation logging protocol, self-correction rules, reporting format

Place your output style in `~/.claude/output-styles/` and activate via `.claude/settings.local.json`:

```json
{
  "outputStyle": "My Custom Orchestrator"
}
```

The `workflow-analyst.md` output style included with this project is an example of this pattern — Zone 1 mirrors the plugin's Workflow Orchestrator, Zone 2 adds parallel dispatch guidance, and Zone 3 contains the full analysis layer.

### Option C: Observation Schema Only (minimal)

The lightest approach: add the `agent-observation` schema to `.taskorchestrator/config.yaml` and instruct the agent (via CLAUDE.md or output style) to log observations when it detects issues. No analysis reporting, no memory corrections — just persistent tracking.

---

## Example: A Self-Correcting Session

**Turn 1.** Agent dispatches an implementation subagent without setting `model`:

```
Agent(prompt="Implement the search API...", isolation="worktree")
// model parameter missing — defaults to opus for sonnet-eligible work
```

**Turn 2.** Agent detects the issue when reviewing the subagent's token usage:

```
↳ [analysis] Delegation without model param — haiku-eligible work ran on opus
```

**Turn 3.** Agent checks memory — no existing correction for this pattern. Writes one:

```
Memory update: "Always set model parameter explicitly on Agent dispatch —
haiku for MCP bulk ops, sonnet for implementation, opus for architecture.
Omitting it wastes tokens by inheriting the orchestrator's model."
```

Agent logs an MCP observation:

```
manage_items(create, items=[{
  title: "[optimization] Set model parameter on all Agent dispatches",
  summary: "Omitting model causes sonnet-eligible work to run on opus. Always set model explicitly.",
  tags: "agent-observation,optimization",
  priority: "low"
}])
```

**Next session.** Memory loads the correction. Agent sets `model="sonnet"` on the first dispatch without being reminded.

---

## Reviewing Accumulated Observations

After several sessions, run:

```
query_items(operation="search", tags="agent-observation")
```

Group by type tag to see patterns:

- Multiple `optimization` observations pointing to the same tool → proposal for a new operation mode
- Multiple `friction` observations about the same parameter → documentation or error message improvement
- A `bug` observation that recurs → escalate priority

The `/session-retrospective` skill can aggregate these with session-tracking notes to produce a structured improvement report.
