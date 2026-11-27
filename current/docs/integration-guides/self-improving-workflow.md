# Tier 6: Self-Improving Workflow

**Prerequisites:** [Tier 1: Bare MCP](bare-mcp.md) complete · [Tier 3: Note Schemas](note-schemas.md) complete · Claude Code auto-memory enabled · Recommended: [Tier 5: Orchestration Mode](orchestration-mode.md) for full orchestration context

**Cross-references:** [Quick Start](../quick-start.md) · [API Reference](../api-reference.md) · [Workflow Guide](../workflow-guide.md) · [Orchestration Mode](orchestration-mode.md) · [Note Schemas](note-schemas.md)

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
Cross-session   Trend memory     → patterns graduate into proposals (seen in 2+ sessions)
                       ↓
Next session    Loaded as context → behavior corrects automatically
```

The system rests on three substrates. **Schemas** drive data collection (gate-enforced notes per work item). **MCP items** persist tool-side issues that may need human triage. **Auto-memory** persists agent-side corrections that apply automatically in the next session.

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

Loop 2 fires continuously during work. Loop 3 fires at session boundaries, aggregating the distributed `session-tracking` notes into structured analysis and graduating recurring patterns into actionable proposals.

### When It Runs

A retrospective is owed **per completed scope** (boundary `scope`, the default) or **per closed run** without a `retro` mark (boundary `run`). Two trigger paths:

1. **Manual** — user invokes `/session-retrospective`, optionally with a root item UUID or `--run <runId>`. `--run` takes the run's members from its ended seats and frontier, and wins over a root UUID.
2. **Hook-driven** — closing a run (`runs_write` `close`) fires `retro-trigger`; `retro-backstop` runs the same check at `Stop`, so a run this session opened that a subagent closed is still found; only the session that opened or closed a run is told about it (exceptions at the scope boundary, all limited to groups that became due after the session started: in a dispatch-configured project any session takes a completed scope 60 minutes after it finished, and a dead claim on such a group is taken over after 20 minutes; an abandoned scope belongs to its owner alone, nudged or dispatched only there). At the default boundary `scope` one retrospective fires per completed scope (covering every run of it, fix-cycle runs included) and a low-signal scope is skipped by the hook at zero model tokens; at boundary `run` one fires per closed run still unmarked `retro`. The first `runs_write` `mark` wins, so two sessions never both dispatch. In the default `nudge` mode the orchestrator is told to suggest it:

```
Retrospective suggested — a run closed (run(s): <runId>, <runId>). Consider running `/session-retrospective --run <runId> --run <runId> --session-dir "<folder>"` to capture learnings before the session ends.
```

In `nudge` mode the suggestion names each closed, unmarked run and the user opts in. With `retrospective.mode: dispatch`, the hook instead wins the claim mark (`retro-claim` at the scope boundary, `retro` at the run boundary) and directs the orchestrator to launch the retrospective as a background agent at the next run boundary (at boundary `run`, once the run has at least `minItems` terminal members; below that it nudges) — see option 6b under [Setup](#setup) for the modes. Work that ran outside a run (an item advanced by hand) has no close to trigger on: run `/session-retrospective` yourself.

### What It Evaluates

The skill scores the run across **five dimensions**:

| Dimension | What it checks | Score type |
|-----------|---------------|-----------|
| Schema effectiveness | For each item: did required notes get filled? Do work notes say something the diff, log and CI do not (scored on derivability)? | Fraction filled appropriately |
| Delegation alignment | Cross-references `delegation-metadata` notes against the model table (haiku/sonnet/opus by task type) | Fraction matching expected model |
| Note effectiveness | Compares queue-phase specs to work-phase implementation notes for "deviated", "unexpected", "wrong" signals | Qualitative (effective / mixed / ineffective) |
| Plan-to-execution | Items created >1h after the root = ad-hoc additions; items still in queue under the root = skipped | Fraction reaching terminal |
| Friction synthesis | Groups friction entries by type (tool-error, excessive-roundtrips, workaround, api-confusion); identifies themes | Count + theme summary |

### Trend Memory

Trend memory lives in MCP, not in a file: each recurring pattern is a work item tagged `retrospective-trend` under a process-global `Retrospective Trends` container (created lazily, outside any project root — the same model as the `Session Retrospectives` and `Improvement Proposals` containers). A pattern specific to one project is kept apart: it sits under a per-project `Retrospective Trends` container beneath the project root, tagged `project-<root8>` (the first 8 hex characters of the root id), and matches only that project's trends, so one project's pattern never counts toward another's. Each finding is classified by kind (defect, process, agent-discipline, positive) and scope (product, global-process, project) before it is matched. The item's title carries a stable kebab-case key, its `summary` carries the distilled observation plus a session counter, and each recurrence adds one evidence note:

```
title:   trend: model-param-omitted — bulk MCP work dispatched without model param
summary: Bulk MCP work dispatched without model param defaults to opus (waste).
         Sessions: 7. Last seen: 2026-04-29.
tags:    retrospective-trend,delegation
notes:   evidence-2026-04-29-<retro-short-id>  (role: work — one per recurrence)
```

The dimension tag is one of `schema-effectiveness`, `delegation`, `note-quality`, `plan-to-execution`, `friction`, or `extension-candidate` (plus `positive` for a positive pattern). Findings that match an existing trend increment `Sessions: N` and add an evidence note; new findings create a trend item of type `retrospective-trend` at `Sessions: 1` (the server's global config must declare that type; the shipped global floor does). A trend that is addressed or obsolete is retired with `advance` `transition` `cancel`, which drops it out of the active listing. `/task-orchestrator:review-retrospectives --deep` hands the matching to the `retro-analysis` workflow in the main session, and stashes the findings as the plan document `retro/<runId>` while it runs.

> **Legacy layout.** Earlier plugin versions kept trends in `memory/retrospectives.md` in the auto-memory directory. If that file still holds trend entries and the Trends container is absent or empty, the skill follows `skills/session-retrospective/references/legacy-trend-migration.md` to move them into MCP items once.

### Proposal Graduation

When a trend reaches **`Sessions >= 2`**, the skill creates an `improvement-proposal` MCP item containing a concrete change:

- **Schema gap** → exact YAML to add or modify
- **Skill regression** → the section reference and the change to make
- **Orchestration context / orchestrate skill miss** → the section and content to add (or, for a personal output style, the zone)
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
2. New or updated `retrospective-trend` MCP items under the global or per-project `Retrospective Trends` container (new trends, incremented counters, evidence notes)
3. Zero or more `improvement-proposal` MCP items under an `Improvement Proposals` container (one per graduated trend)
4. Bug observations for defects found (an `agent-observation` tagged `bug` and `retro-reported`, or evidence on an open one), created on first sighting with no session threshold; an agent-discipline slip creates no item and returns as a `memory:` line to the session that dispatched the retrospective
5. A dashboard rendered to the user with dimension scores, trends, and any proposals created

### Settings

The hooks read `extensions.claude-code.retrospective` from `.taskorchestrator/config.yaml`; the skills read `github_feedback` from the same block:

```yaml
extensions:
  claude-code:
    retrospective:
      mode: nudge            # nudge (default) | dispatch | off
      minItems: 3            # triage size signal (scope) / terminal members a closed run needs (run)
      boundary: scope        # scope (default, one per completed scope) | run (one per closed run)
      github_feedback:
        enabled: false
        repo: jpicklyk/task-orchestrator
```

A top-level `retrospective:` block is ignored, and a 4.0 server rejects it on push. Edits take effect on the next hook trigger, with no `/mcp` reconnect. Full field reference: [config-format.md → Retrospective](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#retrospective).

### Dry-Run Mode

```
/session-retrospective --dry-run
```

Performs all evaluation steps and renders the report but skips persistence — no MCP items created, no memory updated. Use when previewing what a real run would surface.

---

## Setup

Three implementation depths. Each builds on the previous — start at the depth that fits your need.

### Option A: Observation Schema Only (minimal)

The lightest path: persistent tracking of tool issues, no analysis reporting, no retrospectives.

**1.** Add the `agent-observation` schema to `.taskorchestrator/config.yaml` (the schema is in the [example schemas](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/example-schemas.md#agent-observation--process-global-schema-global-floor-not-per-project)).

**2.** Add a short prompt in `CLAUDE.md` instructing the agent to log observations:

```markdown
## Observation Logging

When you detect tool friction, bugs, or optimization opportunities during MCP use:

1. Dedup-check: `items_read(select="search", query="<topic>", tags=["agent-observation"])`
2. If no match, create with `type: "agent-observation"` and `tags: ["agent-observation", "<type>"]` where type is one of: optimization, friction, bug, missing-capability
3. Fill the `observation-detail` note describing observed/expected/suggested
```

**3.** Apply the config: when a REST API URL resolves and the file carries `project.rootId`, `config-sync` pushes the saved file and no reconnect is needed; otherwise, for a stdio server that reads the file, reconnect with `/mcp` (or restart Claude Code).

You now have persistent tool-issue tracking. Periodically run `items_read(select="list", tags=["agent-observation"])` to triage.

### Option B: CLAUDE.md Driven (lightweight active analysis)

Adds inline analysis and self-correction without requiring a personal output style.

**1.** Complete Option A.

**2.** Add to `CLAUDE.md`:

```markdown
## Self-Improvement Protocol

Watch every Task Orchestrator tool call (other MCP servers do not count):
1. Did any call return more data than needed? Log as `optimization`.
2. Did any call fail unexpectedly or require a workaround? Log as `friction` or `missing-capability`.
3. Did I make an agent-side mistake (forgot model param, used short UUID, dispatched before materializing)? Update auto-memory with the correction.

Report a finding as one inline `↳ [analysis]` line where it happens; say nothing on a clean response.
Emit one summary block (MCP efficiency, return-payload efficiency, friction, observations) only at a
boundary: a worktree's PR opened or merged, a run closed, a feature reaching terminal, or when asked.
Hold it while background agents from that work are still running, and never emit it twice for one boundary.
```

This buys you continuous monitoring without changing how you operate.

### Option C: Full Pipeline (personal output style + retrospective)

The complete setup, including session-level aggregation and trend graduation.

> **What the plugin ships vs. what you assemble.** Enabling the TO plugin gives you the orchestration core only — the `orchestration-context` SessionStart hook, the `orchestrate` skill, and the other orchestration skills (planning, materialization, advance, work-summary, etc.). The analysis layer described below is **not** packaged in the plugin marketplace and is **not installed when you enable the plugin**. The TO project repo carries reference implementations of these pieces: the `.taskorchestrator/config.yaml` schemas and traits, and the process-schema floor under `deploy/global-config/.taskorchestrator/`; the retrospective skill and hooks ship in the plugin (described below). Adopters copy and adapt those files into their own projects. This keeps the self-improvement layer optional and per-project customizable rather than imposing one shape on every TO user.

**1.** Complete Option A (observation schema).

**2.** Add the `session-tracking` note to every implementation schema in your config. It arrives through the `session-tracked` trait, shown under [Traits Example](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/example-schemas.md#traits-example); apply it to:

- `feature-implementation`
- `feature-task`
- `bug-fix`
- `plugin-change` (if you use one)
- `quick-fix` (if you use one)
- `default` (catch-all for untagged items)

This is the load-bearing change — without distributed `session-tracking` notes, the retrospective has nothing to aggregate.

**3.** Add the `session-retrospective` schema (three queue-phase notes; it ships in the server's global config, see the [example schemas](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/example-schemas.md#agent-observation--process-global-schema-global-floor-not-per-project)).

**4.** Create a personal output style with two zones. The plugin already delivers the orchestration core (delegation rules, tier classification, phase transitions) through its SessionStart hook, so the style holds no Zone 1 and mirrors nothing from the plugin:

- **Zone 2 — Extended orchestration:** enhancements specific to your setup (parallel dispatch rules, retrospective nudge, etc.)
- **Zone 3 - Workflow analysis layer:** detection of friction and tier mismatches, self-correction protocol from [Loop 2](#loop-2-self-correction-via-auto-memory)

Place the file in `~/.claude/output-styles/` (a personal style is still a valid Claude Code feature) and activate via `.claude/settings.local.json`:

```json
{
  "outputStyle": "Your Custom Orchestrator"
}
```

> **Note on the analyst output style:** The plugin ships no output style. It delivers the orchestration core through the `orchestration-context` SessionStart hook and the `orchestrate` skill. The TO project repo does not ship a layered "analyst" variant — users assemble their own personal output style holding Zones 2 and 3 only, which layers on top of the plugin's core. This keeps the analysis layer customizable per project rather than imposing a one-size shape.

**5.** Enable the retrospective skill. It ships with the plugin at `claude-plugins/task-orchestrator/skills/session-retrospective/SKILL.md` (invoked as `/task-orchestrator:session-retrospective`), so plugin users get it automatically. It implements the pipeline documented in [Loop 3](#loop-3-session-retrospective--trend-graduation):

- Gather scope (root item or recently terminal items)
- Collect distributed `session-tracking` notes
- Aggregate and evaluate across five dimensions
- Compare against the active trend items under the `Retrospective Trends` container
- Persist a `session-retrospective` MCP item with three queue-phase notes
- Create or update trend items
- Create `improvement-proposal` items for trends that hit `Sessions >= 2`
- Run meta-evaluation if 3+ prior retrospectives exist
- Render a dashboard

> **Reference implementation:** The plugin carries this skill at `claude-plugins/task-orchestrator/skills/session-retrospective/SKILL.md`. If you prefer a project-local variant (no plugin activation required), copy that file into your own project's `.claude/skills/session-retrospective/SKILL.md` and adapt as needed — schema names, trend container and tag names, dimension definitions, and graduation thresholds may differ in your setup. After a plugin update the version-keyed plugin cache can stay stale; refresh it as "Plugin Discovery and Cache Refresh" in [claude-plugins/CLAUDE.md](../../../claude-plugins/CLAUDE.md#plugin-discovery-and-cache-refresh) describes.

**6.** Wire a retrospective nudge so the agent suggests `/session-retrospective` after implementation runs end. Two layered options — pick one or use both:

**Option 6a — Output-style prose (lightweight).** Add to Zone 2 of your personal output style:

```
## Retrospective Nudge

When a run closes, or a retrospective nudge or dispatch directive arrives from the plugin's
hooks, suggest one `/session-retrospective --run <runId> --run <runId> --session-dir "<folder>"`
invocation naming every run of the scope, once per scope:

  ↳ Run closed. Consider running `/session-retrospective --run <runId> --run <runId> --session-dir "<folder>"` to capture learnings.

Do not auto-invoke. Show at most once per scope.
```

This relies on the agent noticing the run close and remembering to nudge. Soft signal, no infrastructure.

**Option 6b — Plugin-native hooks (reliable, no setup required).** The `task-orchestrator` plugin ships deterministic retrospective hooks out of the box:

| Hook | Fires on | Role |
|------|----------|------|
| `retro-trigger.mjs` | PostToolUse on `runs_write` | On a successful `close`, records it and nudges or (in `dispatch` mode) wins the claim mark and directs a background retrospective |
| `retro-backstop.mjs` | Stop | End-of-turn backstop: finds completed scopes (or closed, unmarked runs) so a close is never silently missed; retries a failed dispatch |
| `retro-subagent-stop.mjs` | SubagentStop | Marks a dispatched retrospective that stopped without the `retro` mark as failed, so the next Stop retries it |

All three are registered automatically via the plugin's `hooks-config.json` — there is nothing to copy into `.claude/hooks/` and no `.claude/settings.json` wiring to add. Enabling the plugin (see "Plugin Discovery and Cache Refresh" in [claude-plugins/CLAUDE.md](../../../claude-plugins/CLAUDE.md#plugin-discovery-and-cache-refresh)) is enough.

Behavior is configured per workspace via `retrospective.mode` under `extensions.claude-code` in `.taskorchestrator/config.yaml`: `nudge` (default, prints the suggestion) | `dispatch` (directs a background `/session-retrospective` automatically) | `off` (disabled). See [config-format.md → Retrospective](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#retrospective) for the full field reference; edits to `retrospective.mode` take effect on the next hook trigger, no `/mcp` reconnect needed.

Hooks fire deterministically on every matching tool call regardless of agent attention, so the nudge cannot be silently skipped. The TO repo uses both 6a and 6b together — the hook injects the nudge (or dispatch directive, in `dispatch` mode), the personal output-style prose tells the agent how to act on it.

**7.** Reload Claude Code so the schemas, skill, hooks, and personal output style are picked up. If you only edited `.taskorchestrator/config.yaml`, no reload is needed when `config-sync` pushes it (a REST API URL resolves and the file carries `project.rootId`); otherwise a stdio server that reads the file needs a `/mcp` reconnect.

### Verifying the Loop Works

After the first implementation run with the full pipeline:

1. Confirm `session-tracking` notes were filled on each item: `notes_read(select="list", itemId="<uuid>", role="work")`
2. Run `/session-retrospective --run <runId>` and check the dashboard renders dimension scores; when a REST API URL resolves, `runs_read(select="get", runId="<runId>")` shows the run's `retro` mark afterwards
3. List the trend items — `items_read(select="list", type="retrospective-trend", role="queue")` — the first trend entries should now exist under a `Retrospective Trends` container
4. After a second similar run, check whether any trends graduated into `improvement-proposal` items

If `session-tracking` notes are missing, the gate enforcement is not configured — re-check Step 2.

---

## Example: A Multi-Session Improvement Cycle

This shows how a pattern moves through the loops over four sessions.

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

It also logs an `agent-observation` MCP item tagged `optimization`. The implementation item's `session-tracking` note records the friction entry: `friction: api-confusion - model param defaulted unexpectedly`.

`/session-retrospective` runs at end of session. A new trend item is created, with this summary:

```
Bulk delegations dispatched without model param. Sessions: 1. Last seen: 2026-04-22.
```

Below threshold — no proposal yet.

**Session 2.** Memory loaded the correction. The orchestrator sets `model="sonnet"` on the first dispatch automatically. But on a second dispatch later in the session, the model param is omitted again — under different conditions the memory entry didn't catch.

Retrospective runs. The trend item's summary is updated and a second evidence note is added:

```
Bulk delegations dispatched without model param. Sessions: 2. Last seen: 2026-04-25.
```

**Threshold reached.** The skill creates an `improvement-proposal` MCP item:

```
title: Proposal: Strengthen model-param requirement in the orchestrate skill
summary: Pattern recurred across 2 sessions. Proposed change to the
         orchestrate skill's Delegation section (or your personal output style's Zone 2): "**always set `model` explicitly** on every Agent dispatch.
         Omitting it causes sonnet-eligible work to run on opus."
tags: improvement-proposal
```

**Session 3.** The user reviews the proposal, applies the suggested edit to the orchestrate skill or their personal output style, and closes the proposal item.

**Session 4.** The strengthened instruction prevents the omission entirely. The retrospective sees no new friction entries for this pattern. After 3 more sessions without recurrence, the meta-evaluation flags it as `addressed`.

The pattern moved from `inline detection → memory correction → MCP observation → trend tracking → graduated proposal → applied fix → addressed`. No human had to remember to track or escalate it — the system did.

---

## Reviewing Trends and Improvement Proposals

After several sessions, three places hold the accumulated learning:

**MCP — observations, proposals, and retrospectives:**

```
items_read(select="list", tags=["agent-observation"])
items_read(select="list", tags=["improvement-proposal"])
items_read(select="list", tags=["session-retrospective"])
```

Group observations by type tag to see patterns:

- Multiple `optimization` observations on the same tool → proposal for a new operation mode
- Multiple `friction` observations about the same parameter → docs or error message improvement
- A recurring `bug` observation → escalate priority (add `action-item` tag)

**MCP — trend items:**

```
items_read(select="list", type="retrospective-trend", role="queue", limit=100)
```

Lists the active trends (id, title, tags); fetch one with `items_read(select="get", itemId="<trend-uuid>")` to read its summary and session count, and `notes_read(select="list", itemId="<trend-uuid>")` for its per-session evidence. Trends with `Sessions >= 2` should already have proposals — their summary carries a `GRADUATED -> proposal <short-id>` pointer. Trends with high session counts but no pointer indicate the graduation step missed (skill bug or schema gap).

**Auto-memory — self-corrections:**

```
~/.claude/projects/<project-key>/memory/MEMORY.md
```

The agent's discipline corrections. Reviewable and editable; entries that are no longer relevant can be removed.

### Accepting proposals

`/task-orchestrator:review-retrospectives` is the front door: it summarises the retrospective record, suggests ranked actions and offers to triage pending proposals. `/task-orchestrator:review-proposals` (user-invoked only) walks the pending `improvement-proposal` items and records an accept, reject or defer for each. A project-scoped acceptance has its exact YAML applied to `.taskorchestrator/config.yaml` and pushed to the project's root with the `config` tool: `validate` first, then `get` for the head version, then `push` with that version as `expectedVersion` (a concurrent edit comes back as `version_conflict` instead of being overwritten). An accepted item then dwells in `review` by design, until a later `/session-retrospective` run fills its `outcome-verification` note. A global acceptance is tracked as a GitHub issue when `retrospective.github_feedback.enabled` is on and `gh` is available; otherwise the maintainer path applies (`skills/review-proposals/SKILL.md`).

### Triage Cadence

A reasonable rhythm:

- **Per session:** retrospective runs at end of implementation work
- **Weekly or per release:** review accumulated `improvement-proposal` items, accept/reject
- **Monthly:** scan `agent-observation` items for recurring `friction` or `bug` reports that should escalate to product work
- **Quarterly:** review the trend items for patterns that graduated but never had proposals applied — indicates either the proposal was wrong or the user wasn't reviewing them

The system is designed to surface, not enforce. Human judgment decides which signals turn into product changes.
