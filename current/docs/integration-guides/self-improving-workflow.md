# Tier 6: Self-Improving Workflow

**Prerequisites:** [Tier 1: Bare MCP](bare-mcp.md) complete · [Tier 3: Note Schemas](note-schemas.md) complete · Claude Code auto-memory enabled · Recommended: [Tier 5: Output Styles](output-styles.md) for full orchestration context

**Cross-references:** [Quick Start](../quick-start.md) · [API Reference](../api-reference.md) · [Workflow Guide](../workflow-guide.md) · [Output Styles](output-styles.md) · [Note Schemas](note-schemas.md)

---

## What You Get

- Inline analysis of every MCP interaction surfaces token waste, friction, and tier mismatches as they happen
- Tool-side issues are logged as persistent `agent-observation` MCP items
- Agent discipline issues are corrected via auto-memory — corrections apply in the next session
- Per-session structured outcome data is captured under gate enforcement on every implementation item
- A retrospective skill aggregates that data across sessions, tracks recurring patterns, and graduates them into concrete improvement proposals
- A continuous feedback loop: each session's learnings improve the next

---

## The Concept

Most agent workflows are open-loop: the agent uses tools, produces output, and forgets what worked. The self-improving workflow closes the loop with **three nested feedback paths** operating at different cadences:

```
Per turn        Inline analysis  → friction inline-flagged in response
                       ↓
Per detection   Observation log  → agent-observation MCP items (Channel 1)
                Self-correction  → auto-memory updates (Channel 2)
                       ↓
Per session     Retrospective    → aggregates session-tracking notes
                       ↓
Cross-session   Trend memory     → patterns graduate into proposals (3+ recurrences)
                       ↓
Next session    Loaded as context → behavior corrects automatically
```

The system rests on three substrates. **Schemas** drive data collection (gate-enforced notes per work item). **MCP items** persist tool-side issues that may need human triage. **Auto-memory** persists agent-side corrections that apply automatically in the next session.

---

## Foundation: Schema-Driven Data Collection

Before any of the loops can work, the data they consume must be collected reliably. Self-improvement is only as good as its inputs — and the inputs come from gate-enforced notes. A few specific note types do the load-bearing work:

### `session-tracking` — the per-item outcome record

Every implementation schema (`feature-implementation`, `feature-task`, `bug-fix`, `plugin-change`, `quick-fix`, and the catch-all `default`) requires a `session-tracking` note at work phase. The gate blocks the item from advancing to terminal until it is filled.

```yaml
work_item_schemas:
  feature-task:
    notes:
      - key: session-tracking
        role: work
        required: true
        description: "Session context — what was done, how it went, and anything the retrospective should know."
        guidance: |
          Record what happened during implementation. Structure:
          - **Outcome**: success | partial | failure
          - **Files changed**: list with brief rationale
          - **Deviations**: anything that differed from the plan
          - **Friction**: tool errors, roundtrips, workarounds, API confusion (type + description)
          - **Gate interactions**: note fill attempts, gate failures encountered
          - **Observations**: anything worth tracking for process improvement
          - **Test results**: pass/fail counts, new tests added
          Keep it factual and concise — this feeds the session retrospective.
```

The note is the **single source of truth** for what happened on each item. The retrospective reads these notes via `query_notes(role="work", key="session-tracking")` rather than re-deriving from the transcript. Gate enforcement guarantees the data exists.

> **Why on every implementation schema, not just one:** The retrospective aggregates across the full feature tree. Putting `session-tracking` on `feature-task`, `bug-fix`, `plugin-change`, etc. (rather than only the parent feature) lets distributed sub-agents each record their own outcomes without contention. The catch-all `default` schema picks up untagged items so nothing escapes tracking.

### `agent-observation` — the tool-side issue log

Standalone MCP items (no `parentId`) tagged `agent-observation` plus exactly one type tag. Each carries a single queue-phase note describing the issue:

```yaml
work_item_schemas:
  agent-observation:
    lifecycle: auto
    notes:
      - key: observation-detail
        role: queue
        required: true
        description: "Expected vs actual behavior and suggested improvement."
        guidance: |
          Describe what you observed (tool call, parameters, result).
          State what you expected instead.
          Suggest a concrete improvement — API change, error message improvement, or new capability.
          Tag the observation type:
          - **optimization** (works but could be better)
          - **friction** (workflow impediment)
          - **bug** (incorrect behavior)
          - **missing-capability** (needed feature that doesn't exist)
```

Default `priority: low` keeps observations from competing with implementation items in `get_next_item`. Add `action-item` as a secondary tag for observations that block current work.

### `session-retrospective` — the aggregate artifact

A schema for the retrospective items the skill produces. Three queue-phase notes capture quantitative metrics, qualitative evaluation, and forward-looking signals:

```yaml
work_item_schemas:
  session-retrospective:
    lifecycle: auto
    notes:
      - key: session-metrics
        role: queue
        required: true
        description: "Quantitative session data — items, agents, schemas, token efficiency."
      - key: workflow-evaluation
        role: queue
        required: true
        description: "Qualitative workflow assessment across all evaluation dimensions."
      - key: improvement-signals
        role: queue
        required: true
        description: "Trends, proposals, and extension promotions."
```

### `delegation-metadata` (optional) — model alignment data

When the orchestrator dispatches a subagent, it can record the model used and isolation mode as a `delegation-metadata` note on the work item:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<task-uuid>",
  key: "delegation-metadata",
  role: "work",
  body: "model: sonnet | isolation: worktree | rationale: implementation work, file edits required"
}])
```

The retrospective uses these notes to score delegation alignment (haiku for bulk MCP, sonnet for implementation, opus for architecture). Optional — when absent, the delegation-alignment dimension is skipped rather than failed.

---

## Loop 1: Inline Analysis & Observation Logging

The first loop runs in real time as the agent uses MCP tools. It surfaces issues immediately in response output and persists tool-side issues to MCP.

### What Gets Watched

**Token waste patterns:**

| Pattern | Detection signal | Preferred alternative |
|---------|-----------------|----------------------|
| Over-fetching | `query_items(get)` for a status check | `query_items(overview)` — 85-90% fewer tokens |
| Missing batch | 3+ individual `manage_items`/`manage_dependencies`/`advance_item` calls in one turn | Use `items`/`dependencies`/`transitions` arrays for bulk operations |
| Note body waste | `query_notes(includeBody=true)` when only keys/roles needed | Use `includeBody=false` for metadata-only reads |
| Redundant queries | Same entity queried twice in a turn | Cache the first result or combine into one call |
| Unfiltered search | `query_items(search)` with no filters | Add `role`, `tags`, `priority`, or `parentId` to narrow |
| Multi-status role query | Listing specific statuses when a role would suffice | Use `role="work"` — resolves to all work-phase statuses |
| Full notes for partial read | `query_notes(includeBody=true)` for all notes when one is needed | Scope with `query_notes(role=...)` |

**Friction patterns:**

| Category | What to watch |
|----------|--------------|
| Tool failures | MCP calls that return errors or unexpected empty results |
| Excessive round-trips | Workflows requiring 3+ sequential calls where 1-2 should suffice |
| Workarounds | Cases where the agent must work around a missing capability |
| API confusion | Parameter naming inconsistencies, unclear error messages |
| Tool misuse | Wrong operation chosen, missing required params, skipping `advance_item` |
| Silent failures | Operations that succeed but produce no useful effect |

**Return-payload waste** (often the biggest token drain in delegated runs):

| Pattern | Detection signal | Suggestion |
|---------|-----------------|------------|
| Verbose subagent returns | Subagent returns full MCP JSON when only UUIDs/status were needed | Specify exact return format: "Return only: [fields]" |
| Unrequested context | Subagent returns file contents or exploration findings beyond scope | Tighten prompt: "Do not include [X], only report [Y]" |
| Echo-back waste | Subagent restates the full task before answering | Add: "Do not restate the task — begin with results" |
| Full JSON dumps | Raw MCP response objects instead of extracted values | Request structured summaries: "Return a markdown table of [columns]" |
| Team message bloat | Routine status DMs exceed 3-4 lines | Set norms in team prompt: "Status messages: 1-2 lines max" |
| Broadcast overuse | `broadcast` used for information relevant to 1-2 teammates | Use targeted `message`; reserve `broadcast` for blocking issues |

**Tier-classification monitoring** — flag when process is mismatched to the work:

| Pattern | Signal | Correction |
|---------|--------|------------|
| Over-process on small work | Plan mode + subagent + separate review for a 1-2 file known fix | Should be Direct tier — implement inline, skip plan mode |
| Under-process on complex work | No planning, no review, no worktree for 10+ files with deps | Should be Parallel tier — use plan mode, worktree agents, separate review |
| Missing session-tracking | Item reaches terminal without a `session-tracking` note | Fill before advancing — the retrospective depends on it |

### The Logging Protocol

When the agent detects an issue worth persisting, it follows a three-step protocol:

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

**Step 3 — Fill the observation note:**

```
manage_notes(operation="upsert", notes=[{
  itemId: "<new-item-uuid>",
  key: "observation-detail",
  role: "queue",
  body: "**Observed:** ...\n**Expected:** ...\n**Suggestion:** ..."
}])
```

### Tagging Convention

Every observation gets `agent-observation` plus exactly one type tag:

| Type tag | When |
|----------|------|
| `optimization` | Token waste, redundant queries, missed batch opportunities |
| `friction` | Confusing APIs, excessive round-trips, unclear errors |
| `bug` | Actual defects in tool behavior |
| `missing-capability` | Gaps requiring workarounds |

---

## Loop 2: Self-Correction via Auto-Memory

When the agent detects a mistake in its own orchestration behavior — not a tool issue, but an agent discipline issue — it writes a correction to persistent memory rather than to MCP.

### What Gets Corrected

| Category | Example signal | Memory entry pattern |
|----------|---------------|---------------------|
| Data integrity | Used a truncated UUID | Always use full UUIDs from query responses |
| Delegation format | Subagent returned raw JSON instead of summary | Specify exact return format in delegation prompts |
| Sequencing | Dispatched agent before materializing items | Materialize all MCP items before dispatching agents |
| Missing parameter | Forgot `model` on Agent dispatch | Always set model explicitly: haiku / sonnet / opus |
| Prompt deficiency | Subagent failed due to missing context | Record what context was needed |
| Mock/API mismatch | Subagent tests failed due to wrong API assumptions | Record correct API signature |

### Tool Issues vs Agent Issues

This distinction determines where the correction goes:

| Issue type | Persistence | Who fixes it |
|-----------|-------------|-------------|
| Tool friction, bug, missing capability | MCP observation item | Product development |
| Agent discipline, sequencing, format | Auto-memory file | The agent (next session, automatically) |

### The Correction Protocol

1. **Detect** the issue during the current session
2. **Check** existing memory for similar coverage to avoid duplicates
3. **Write** a concise correction: pattern + correct behavior + brief reason
4. **Report** in the session output: `↳ [self-correction] Updated memory: always set model parameter on Agent dispatch`

Do not log agent-discipline issues as MCP observation tasks — those are for tool improvements only. Auto-memory is the self-correction substrate.

---

## Loop 3: Session Retrospective & Trend Graduation

Loops 1 and 2 fire continuously during work. Loop 3 fires at session boundaries, aggregating the distributed `session-tracking` notes into structured analysis and graduating recurring patterns into actionable proposals.

### When It Runs

Two trigger paths:

1. **Manual** — user invokes `/session-retrospective` (optionally with a root item UUID)
2. **Nudge** — when work items reach terminal during an `/implement` run, the orchestrator output style suggests running it:

```
↳ Implementation run complete. Consider running `/session-retrospective` to capture learnings.
```

The nudge appears at most once per implementation run and never auto-invokes the skill — the user always opts in.

### What It Evaluates

The skill scores the run across **five dimensions**:

| Dimension | What it checks | Score type |
|-----------|---------------|-----------|
| Schema effectiveness | For each item: did required notes get filled? Are they sized appropriately (50-500 tokens for status notes)? | Fraction filled appropriately |
| Delegation alignment | Cross-references `delegation-metadata` notes against the model table (haiku/sonnet/opus by task type) | Fraction matching expected model |
| Note effectiveness | Compares queue-phase specs to work-phase implementation notes for "deviated", "unexpected", "wrong" signals | Qualitative (effective / mixed / ineffective) |
| Plan-to-execution | Items created >1h after the root = ad-hoc additions; items still in queue under the root = skipped | Fraction reaching terminal |
| Friction synthesis | Groups friction entries by type (tool-error, excessive-roundtrips, workaround, api-confusion); identifies themes | Count + theme summary |

### Trend Memory File

The skill maintains `memory/retrospectives.md` in the auto-memory directory. Each finding is recorded with a session counter:

```markdown
## Schema Effectiveness
- session-tracking: implementation agents fill it briefly (<50 tokens) when delegating in parallel.
  Sessions: 3. Last seen: 2026-04-25

## Delegation Patterns
- Bulk MCP work dispatched without model param defaults to opus (waste).
  Sessions: 7. Last seen: 2026-04-29

## Note Quality
- review-checklist filled by implementing agent rather than separate reviewer.
  Sessions: 2. Last seen: 2026-04-22
```

This file is the cross-session memory of recurring patterns. Findings that match an existing trend increment the counter; new findings start at 1.

### Proposal Graduation

When a trend reaches **`Sessions >= 2`**, the skill creates an `improvement-proposal` MCP item containing a concrete change:

- **Schema gap** → exact YAML to add or modify
- **Skill regression** → the section reference and the change to make
- **Output style miss** → the zone and content to add
- **Hook addition** → the event, matcher, and purpose

The proposal is the deliverable — not a vague "this is a problem" item but a ready-to-apply patch. The user reviews and either accepts (apply) or rejects (close).

### Meta-Evaluation

After 3+ retrospectives exist, the skill performs a meta-evaluation:

- **Trend durability** — did previously identified trends get addressed? Check whether improvement proposals from prior trends reached terminal.
- **Proposal staleness** — proposals created 3+ retrospectives ago with no movement.
- **Self-quality** — are retrospective notes converging on useful patterns or repeating without resolution?

Meta-findings append to the current retrospective's `improvement-signals` note.

### Output Artifacts

A retrospective run produces:

1. One `session-retrospective` MCP item under a `Session Retrospectives` container (with three queue-phase notes filled)
2. Updated `memory/retrospectives.md` (new trends, incremented counters)
3. Zero or more `improvement-proposal` MCP items under an `Improvement Proposals` container (one per graduated trend)
4. A dashboard rendered to the user with dimension scores, trends, and any proposals created

### Dry-Run Mode

```
/session-retrospective --dry-run
```

Performs all evaluation steps and renders the report but skips persistence — no MCP items created, no memory updated. Use when previewing what a real run would surface.

---

## Analysis Reporting

Loops 1 and 2 surface findings inline. The agent appends an analysis block to the end of every response that involved MCP tool calls or subagent activity.

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

**Return Payload Efficiency**
↳ Subagents dispatched: 2 | Returns reviewed: 2
↳ Clean — both returns matched requested format

**Friction Points** (0 this session)
↳ None detected

**Observations Logged** (1 new, 0 existing matched)
↳ [optimization] `a1b2c3d4` — batch dependency creation for linear chains

**Suggestions**
↳ Consider using query_items(overview) for the work-summary dashboard
```

Inline analysis uses the `↳ [analysis]` prefix for real-time visibility during the response body. The end-of-response block is the aggregate summary.

### When to Omit

Omit the analysis block only when the response involved **zero** MCP calls and **zero** subagent activity (pure conversation, answering from memory, discussing a plan before any tool use).

---

## Setup

Three implementation depths. Each builds on the previous — start at the depth that fits your need.

### Option A: Observation Schema Only (minimal)

The lightest path: persistent tracking of tool issues, no analysis reporting, no retrospectives.

**1.** Add the `agent-observation` schema to `.taskorchestrator/config.yaml` (see [Foundation](#foundation-schema-driven-data-collection) for the exact YAML).

**2.** Add a short prompt in `CLAUDE.md` instructing the agent to log observations:

```markdown
## Observation Logging

When you detect tool friction, bugs, or optimization opportunities during MCP use:

1. Dedup-check: `query_items(operation="search", tags="agent-observation", query="<topic>")`
2. If no match, create with `tags: "agent-observation,<type>"` where type is one of: optimization, friction, bug, missing-capability
3. Fill the `observation-detail` note describing observed/expected/suggested
```

**3.** Reload the MCP config: `/mcp` (or restart Claude Code).

You now have persistent tool-issue tracking. Periodically run `query_items(operation="search", tags="agent-observation")` to triage.

### Option B: CLAUDE.md Driven (lightweight active analysis)

Adds inline analysis and self-correction without requiring a custom output style.

**1.** Complete Option A.

**2.** Add to `CLAUDE.md`:

```markdown
## Self-Improvement Protocol

After every response involving MCP tool calls:
1. Did any call return more data than needed? Log as `optimization`.
2. Did any call fail unexpectedly or require a workaround? Log as `friction` or `missing-capability`.
3. Did I make an agent-side mistake (forgot model param, used short UUID, dispatched before materializing)? Update auto-memory with the correction.

Append an analysis block at end of response:
- Lightweight (1-3 calls): `◆ Analysis — N MCP calls | clean | <issue if any>`
- Full (4+ calls or subagents): structured block covering MCP efficiency, return-payload efficiency, friction, observations
```

This buys you continuous monitoring without changing how you operate. Most of Loop 1 + all of Loop 2.

### Option C: Full Pipeline (output style + retrospective)

The complete setup — all three loops including session-level aggregation and trend graduation.

> **What the plugin ships vs. what you assemble.** Enabling the TO plugin gives you the orchestration core only — the `Workflow Orchestrator` output style and the orchestration skills (planning, materialization, advance, work-summary, etc.). The analysis layer described below is **not** packaged in the plugin marketplace and is **not installed when you enable the plugin**. The TO project repo carries reference implementations of these pieces as project-local source files (`.claude/skills/`, `.claude/hooks/`, `.taskorchestrator/config.yaml`). Adopters copy and adapt those files into their own projects. This keeps the self-improvement layer optional and per-project customizable rather than imposing one shape on every TO user.

**1.** Complete Option A (observation schema).

**2.** Add the `session-tracking` note to every implementation schema in your config. The exact YAML is in [Foundation](#foundation-schema-driven-data-collection); apply it to:

- `feature-implementation`
- `feature-task`
- `bug-fix`
- `plugin-change` (if you use one)
- `quick-fix` (if you use one)
- `default` (catch-all for untagged items)

This is the load-bearing change — without distributed `session-tracking` notes, the retrospective has nothing to aggregate.

**3.** Add the `session-retrospective` schema (three queue-phase notes — see [Foundation](#foundation-schema-driven-data-collection)).

**4.** Create a custom output style with three zones:

- **Zone 1 — Orchestration core:** delegation rules, tier classification, phase transitions (mirror the `Workflow Orchestrator` output style shipped by the plugin)
- **Zone 2 — Extended orchestration:** enhancements specific to your setup (parallel dispatch rules, retrospective nudge, etc.)
- **Zone 3 — Workflow analysis layer:** detection patterns from [Loop 1](#loop-1-inline-analysis--observation-logging), self-correction protocol from [Loop 2](#loop-2-self-correction-via-auto-memory), analysis reporting format

Place the file in `~/.claude/output-styles/` (personal, gitignored) and activate via `.claude/settings.local.json`:

```json
{
  "outputStyle": "Your Custom Orchestrator"
}
```

> **Note on the analyst output style:** The plugin ships only the orchestration core (`workflow-orchestrator`) at `claude-plugins/task-orchestrator/output-styles/workflow-orchestrator.md`. The TO project repo does not ship a layered "analyst" variant — users assemble their own by copying the orchestration core into Zone 1 of a personal output style and layering Zones 2 and 3 on top. This keeps the analysis layer customizable per project rather than imposing a one-size shape.

**5.** Add a retrospective skill at `.claude/skills/session-retrospective/SKILL.md` in **your project repo**. It implements the pipeline documented in [Loop 3](#loop-3-session-retrospective--trend-graduation):

- Gather scope (root item or recently terminal items)
- Collect distributed `session-tracking` notes
- Aggregate and evaluate across five dimensions
- Compare against `memory/retrospectives.md` trend file
- Persist a `session-retrospective` MCP item with three queue-phase notes
- Update the trend file
- Create `improvement-proposal` items for trends that hit `Sessions >= 2`
- Run meta-evaluation if 3+ prior retrospectives exist
- Render a dashboard

> **Reference implementation:** The TO project repo carries this skill at `.claude/skills/session-retrospective/SKILL.md`. Copy that file into your own project's `.claude/skills/session-retrospective/SKILL.md` and adapt as needed. Project-local skills are auto-discovered by Claude Code — no plugin activation required. Adapt freely: schema names, trend file paths, dimension definitions, and graduation thresholds may differ in your setup.

**6.** Wire a retrospective nudge so the agent suggests `/session-retrospective` after implementation runs end. Two layered options — pick one or use both:

**Option 6a — Output-style prose (lightweight).** Add to Zone 2 of your output style:

```
## Retrospective Nudge

When work items reach terminal during an implementation run — via `advance_item`,
`complete_tree`, or auto-cascade — suggest `/session-retrospective` once per run:

  ↳ Implementation run complete. Consider running `/session-retrospective` to capture learnings.

Do not auto-invoke. Show at most once per implementation run.
```

This relies on the agent noticing the terminal transition and remembering to nudge. Soft signal, no infrastructure.

**Option 6b — PostToolUse hooks (reliable).** Add two Node hook scripts and wire them in `.claude/settings.json`. The TO project repo carries reference implementations:

| Hook | Path in TO repo | Triggers on |
|------|-----------------|-------------|
| `post-advance-retro-nudge.mjs` | `.claude/hooks/post-advance-retro-nudge.mjs` | `advance_item` calls that produce `"newRole":"terminal"` |
| `post-complete-tree-retro-nudge.mjs` | `.claude/hooks/post-complete-tree-retro-nudge.mjs` | every `complete_tree` call |

Each script writes a `hookSpecificOutput.additionalContext` block that injects a one-line retrospective nudge into the agent's next turn. Copy both scripts into your project's `.claude/hooks/` and register them in `.claude/settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__mcp-task-orchestrator__advance_item",
        "hooks": [
          { "type": "command", "command": "node .claude/hooks/post-advance-retro-nudge.mjs", "timeout": 5 }
        ]
      },
      {
        "matcher": "mcp__mcp-task-orchestrator__complete_tree",
        "hooks": [
          { "type": "command", "command": "node .claude/hooks/post-complete-tree-retro-nudge.mjs", "timeout": 5 }
        ]
      }
    ]
  }
}
```

Hooks fire deterministically on every matching tool call regardless of agent attention, so the nudge cannot be silently skipped. The TO repo uses both 6a and 6b together — the hook injects the nudge, the output-style prose tells the agent how to act on it.

**7.** Reload Claude Code so the schemas, skill, hooks, and output style are picked up. If you only edited `.taskorchestrator/config.yaml`, run `/mcp` to reconnect.

### Verifying the Loop Works

After the first implementation run with the full pipeline:

1. Confirm `session-tracking` notes were filled on each item: `query_notes(itemId="<uuid>", role="work")`
2. Run `/session-retrospective` and check the dashboard renders dimension scores
3. Inspect `memory/retrospectives.md` — it should now exist with the first trend entries
4. After a second similar run, check whether any trends graduated into `improvement-proposal` items

If `session-tracking` notes are missing, the gate enforcement is not configured — re-check Step 2.

---

## Example: A Multi-Session Improvement Cycle

This shows how a pattern moves through all three loops over four sessions.

**Session 1.** Orchestrator dispatches an implementation subagent without setting `model`:

```
Agent(prompt="Implement the search API...", isolation="worktree")
// model parameter missing — defaults to opus for sonnet-eligible work
```

Inline analysis flags it:

```
↳ [analysis] Delegation without model param — sonnet-eligible work ran on opus
```

The orchestrator updates auto-memory (Loop 2):

```
Memory update: "Always set model parameter explicitly on Agent dispatch —
haiku for MCP bulk ops, sonnet for implementation, opus for architecture."
```

It also logs an `agent-observation` MCP item (Loop 1) tagged `optimization`. The implementation item's `session-tracking` note records the friction entry: `friction: api-confusion — model param defaulted unexpectedly`.

`/session-retrospective` runs at end of session. The trend memory file gets a new entry:

```
- Bulk delegations dispatched without model param. Sessions: 1. Last seen: 2026-04-22
```

Below threshold — no proposal yet.

**Session 2.** Memory loaded the correction. The orchestrator sets `model="sonnet"` on the first dispatch automatically. But on a second dispatch later in the session, the model param is omitted again — under different conditions the memory entry didn't catch.

Retrospective runs. Trend file updates:

```
- Bulk delegations dispatched without model param. Sessions: 2. Last seen: 2026-04-25
```

**Threshold reached.** The skill creates an `improvement-proposal` MCP item:

```
title: Proposal: Strengthen model-param requirement in output style
summary: Pattern recurred across 2 sessions. Proposed change to Zone 1 of the
         output style: "**always set `model` explicitly** on every Agent dispatch.
         Omitting it causes sonnet-eligible work to run on opus."
tags: improvement-proposal
```

**Session 3.** The user reviews the proposal, applies the suggested edit to their output style, and closes the proposal item.

**Session 4.** The strengthened output style instruction prevents the omission entirely. The retrospective sees no new friction entries for this pattern. After 3 more sessions without recurrence, the meta-evaluation flags it as `addressed`.

The pattern moved from `inline detection → memory correction → MCP observation → trend tracking → graduated proposal → applied fix → addressed`. No human had to remember to track or escalate it — the system did.

---

## Reviewing Trends and Improvement Proposals

After several sessions, three places hold the accumulated learning:

**MCP — observations and proposals:**

```
query_items(operation="search", tags="agent-observation")
query_items(operation="search", tags="improvement-proposal")
query_items(operation="search", tags="session-retrospective")
```

Group observations by type tag to see patterns:

- Multiple `optimization` observations on the same tool → proposal for a new operation mode
- Multiple `friction` observations about the same parameter → docs or error message improvement
- A recurring `bug` observation → escalate priority (add `action-item` tag)

**Auto-memory — trend file:**

```
~/.claude/projects/<project-key>/memory/retrospectives.md
```

Lists current trends with session counts. Trends with `Sessions >= 2` should already have proposals. Trends with high session counts but no proposals indicate the graduation step missed (skill bug or schema gap).

**Auto-memory — self-corrections:**

```
~/.claude/projects/<project-key>/memory/MEMORY.md
```

The agent's discipline corrections. Reviewable and editable; entries that are no longer relevant can be removed.

### Triage Cadence

A reasonable rhythm:

- **Per session:** retrospective runs at end of implementation work
- **Weekly or per release:** review accumulated `improvement-proposal` items, accept/reject
- **Monthly:** scan `agent-observation` items for recurring `friction` or `bug` reports that should escalate to product work
- **Quarterly:** review the trend file for patterns that graduated but never had proposals applied — indicates either the proposal was wrong or the user wasn't reviewing them

The system is designed to surface, not enforce. Human judgment decides which signals turn into product changes.
