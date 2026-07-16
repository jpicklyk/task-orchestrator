---
name: quick-start
description: Interactive onboarding for the MCP Task Orchestrator. Detects empty or populated workspaces and walks through how plan mode, persistent tracking, and the MCP work together. Use when a user says "get started", "how do I use this", "quick start", "first time setup", "onboard me", "what can this MCP do", or "help me learn task orchestrator".
argument-hint: "[optional: describe what you want to track, e.g. 'a web app project']"
---

# Quick Start — MCP Task Orchestrator

Interactive onboarding that teaches by doing. Detects your workspace state and adapts.

## Step 1: Detect Workspace State

Resolve the project rootId first: check session context for a rootId injected by the SessionStart hook, or read `.taskorchestrator/config.yaml`'s top-level `project.rootId` (a file read, not an MCP call).

Call the health check to determine which path to follow:

```
get_context()
```

When a rootId is known, pass it to scope the check to this project: `get_context(ancestorId="<rootId>")`. When no rootId is known — the common case for a truly fresh workspace, or one that hasn't been bootstrapped yet — call unscoped exactly as shown.

**If no active or stalled items exist** — follow the **Fresh-Start Path** (Steps 2-8).
**If active items exist** — follow the **Orientation Path** (Steps A-C).

---

## Step 1.5: Project Anchor Bootstrap (if needed)

Before following either path, check whether this workspace has a project anchor yet:

- If `.taskorchestrator/config.yaml` does not exist at all, skip this step — that's the truly fresh workspace covered by the Fresh-Start Path below. Bootstrap can happen on a later run once a config file exists (e.g., after `/manage-schemas` creates one).
- If `.taskorchestrator/config.yaml` exists and already has a top-level `project:` block, read its `rootId` and use it for scoping throughout this session — no bootstrap needed.
- If `.taskorchestrator/config.yaml` exists but has **no** `project:` block, offer to create one via `AskUserQuestion`: *"This workspace doesn't have a project anchor yet — want me to create one? It lets `/work-summary`, `/create-item`, and other skills scope to just this project if multiple projects ever share the same database."*

If the user accepts:

1. Determine a project name — from `$ARGUMENTS`, conversation context, or by asking.
2. Create the anchor item at depth 0:
   ```
   manage_items(operation="create", items=[{title: "<project name>", type: "project", priority: "low"}])
   ```
3. Write the canonical block into `.taskorchestrator/config.yaml`:
   ```yaml
   project:
     rootId: "<created-item-uuid>"
     name: "<project name>"
   ```
4. Older servers may not expose it, so check the tool list before calling — if a `manage_project_config` tool is available, push the full current file text (not just the `project:` block — the server never reads that block itself; see `references/config-format.md` → Project Scoping) so per-root schema resolution picks it up immediately without waiting on a config reload:
   ```
   manage_project_config(operation="push", rootItemId="<created-item-uuid>", configYaml="<full current file text from step 3>")
   ```
   - Success → the returned `fingerprint` confirms the push landed; re-pushing identical content later returns the same fingerprint (idempotent).
   - `VALIDATION_ERROR` → surface the parse error to the user; the config.yaml write from step 3 is already saved locally, so nothing is lost — tell them to fix the file and retry the push (or run `/manage-schemas validate`).
   - `CONFLICT_ERROR` (superseded) → the local file is older than the server's stored config (rare during onboarding — usually means another checkout already synced a newer version). Fetch the server's copy with `manage_project_config(operation="get", ...)` and reconcile, or pass `force: true` if overwriting is intentional.
   - A `warning` field → relay it to the user (non-fatal).

   If the tool isn't available, note this and skip — the config.yaml write from step 3 is authoritative on its own; the server will pick it up on its normal config read path.

If the user declines, proceed unscoped — nothing else in this skill requires an anchor.

---

# Fresh-Start Path

## Step 2: Welcome — The Big Picture

Explain briefly:

- When you ask Claude to build something non-trivial, it enters **plan mode** — exploring the codebase and writing a plan saved as a **persistent markdown file**
- The MCP Task Orchestrator **complements** the plan file by tracking **execution state** — what's been started, what's blocked, what's done, and what's next
- Think of it this way: the **plan file** is your design document (the *what* and *how*), while the **MCP** is your project board (the *progress* and *status*)
- The MCP also helps **during planning** — Claude automatically checks for existing tracked work and schema requirements before planning, setting a **definition floor** so the plan accounts for documentation gates and doesn't duplicate what's already in progress
- Together, they give you full continuity across sessions — the plan tells you the approach, the MCP tells you where you left off

---

## Step 3: The Plan Mode Pipeline

Show how plan mode and the MCP work together. This is the workflow users will experience:

```
You describe what you want
        │
        ▼
  EnterPlanMode              ← Claude explores the codebase
        │
  pre-plan hook fires        ← Plugin sets the definition floor: existing work, schemas, gate requirements
        │
        ▼
  Plan written to disk       ← Persistent markdown file — your design document
        │
  Plan approved (ExitPlanMode)
        │
  post-plan hook fires       ← Plugin tells Claude to materialize before implementing
        │
        ▼
  Materialize                ← Claude creates MCP items from the plan
        │                       Items, dependencies, notes — execution tracking
        ▼
  Implement                  ← Subagents work, each transitioning their MCP item
        │                       advance_item(start) → work → advance_item(complete)
        ▼
  Health check               ← get_context() shows what completed and what didn't
```

**Reinforce to the user:**
- The **plan file** and **MCP items** are not duplicates — they serve different roles
- MCP items track individual units of work through a lifecycle: who's working on what, what's blocked, and what's done
- The plugin hooks inject guidance automatically so Claude follows this pipeline — you don't need to ask for it

---

## Step 4: Hands-On — Create Your First Items

Now let's create some MCP items to see how the execution tracking works.

**Determine the project topic:**
- If `$ARGUMENTS` is provided, use it as the project topic
- Otherwise, ask via `AskUserQuestion` with options like "A web app feature", "A bug fix workflow", "A documentation project", or Other

Create a container with child items and dependencies in one atomic call:

```
create_work_tree(
  root: {
    title: "<Project Name> — Tutorial",
    summary: "Quick-start tutorial project to learn MCP Task Orchestrator",
    type: "container",
    priority: "medium"
  },
  children: [
    { ref: "design", title: "Design <topic>", summary: "Define requirements and approach", type: "feature-task", priority: "high" },
    { ref: "implement", title: "Implement <topic>", summary: "Build the solution", type: "feature-task", priority: "high" },
    { ref: "test", title: "Test <topic>", summary: "Verify the implementation", type: "feature-task", priority: "medium" }
  ],
  deps: [
    { from: "design", to: "implement", type: "BLOCKS" },
    { from: "implement", to: "test", type: "BLOCKS" }
  ]
)
```

**Explain to the user:**
- `create_work_tree` creates everything atomically — the container, three child items, and two dependency edges
- In a real workflow, **Claude creates these automatically** after a plan is approved — the post-plan hook triggers this
- The `BLOCKS` dependency means: implement cannot start until design completes, test cannot start until implement completes
- The `ref` names ("design", "implement", "test") are local aliases used only within this call

Show the structure:

```
<Project Name> — Tutorial (container)
  ├── Design <topic>          ← actionable (no blockers)
  ├── Implement <topic>       ← blocked by Design
  └── Test <topic>            ← blocked by Implement
```

This is **the project board side** — these items track progress. The plan file (if this were a real feature) would contain the *design decisions* behind each of these tasks.

**Fill required notes (gate prerequisite):** `feature-task` items require a `task-scope` note (queue, required) before `advance_item(trigger="start")` will succeed, and a `complete` trigger checks ALL required notes across every phase (queue + work + review) — not just the current one. `create_work_tree` above created the children with no notes — its `createNotes` option only auto-fills blank bodies, which don't count as "filled" for gate purposes — so fill all four required notes on the design item now, before Step 5a, so both the `start` and `complete` calls succeed:

```
manage_notes(
  operation="upsert",
  notes=[
    { itemId: "<design-UUID>", key: "task-scope", role: "queue", body: "Define requirements and approach for <topic>." },
    { itemId: "<design-UUID>", key: "implementation-notes", role: "work", body: "Design work completed for <topic>." },
    { itemId: "<design-UUID>", key: "session-tracking", role: "work", body: "Design phase completed this session." },
    { itemId: "<design-UUID>", key: "review-checklist", role: "review", body: "Design reviewed and approved." }
  ]
)
```

**Explain to the user:** in a real workflow, subagents fill these notes as work actually happens, phase by phase. Here we're pre-filling all of them up front purely so the tutorial's Step 5b `complete` call isn't gate-blocked — note bodies don't need to match the item's current role to be saved, only to satisfy the gate check at advance time.

---

## Step 5: The Role Lifecycle

Items move queue → work → review → terminal via `advance_item` triggers — see the `advance_item` tool description for full trigger semantics.

**5a. Start the design task:**

```
advance_item(transitions=[{ itemId: "<design-UUID>", trigger: "start" }])
```

Point out in the response: `cascadeEvents` shows the container cascading `queue` → `work` (first child started). In a real workflow, **each subagent calls this** when it begins its assigned item.

**5b. Complete the design task:**

```
advance_item(transitions=[{ itemId: "<design-UUID>", trigger: "complete" }])
```

Point out in the response: `unblockedItems` shows implement is now unblocked; the container stays in `work` because siblings are still active.

**5c. Confirm what's next:**

```
get_next_item(limit=3, includeDetails=true)
```

Point out: the implement task is now recommended — it was unblocked when design completed. This is how the MCP answers "what should I work on next?" across sessions.

---

## Step 6: Cross-Session Continuity

This is where the plan file and MCP complement each other most visibly. Explain:

- **If a session ends mid-work**, the next session can call `get_context()` or `/work-summary` to see exactly which items are in progress, which are blocked, and which are done
- **The plan file** is still on disk — Claude can re-read it to recall the design approach
- **The MCP items** show execution state — no need to re-explain what's been completed
- Together: *"Read the plan to remember the approach. Check the MCP to see where you left off."*

This is the difference between having a plan document alone vs. having a plan document **plus** a live project board. The plan doesn't change as work progresses — the MCP does.

---

## Step 7: Note Schemas (Optional Power Feature)

Briefly mention that MCP items can have **required notes** that act as documentation gates:

- A `.taskorchestrator/config.yaml` file defines schemas under `work_item_schemas:` — which notes must be filled before an item can advance
- Items match schemas via their `type` field (e.g., `type: "feature-implementation"` activates that schema's notes and gates)
- Example: the `feature-implementation` schema requires a `feature-summary` note before work can start, and a `review-checklist` note before completion
- Each schema can set a **lifecycle mode** (auto, manual, auto-reopen, permanent) controlling cascade behavior
- Notes can carry a `guidance` field (authoring hints) and a `skill` field (structured evaluation framework to invoke before filling)
- **Composable traits** add additional note requirements per-item — e.g., `traits: "needs-security-review"` adds a `security-assessment` note at the review phase
- Run `/manage-schemas` to set one up interactively — it can also generate a companion lifecycle skill for your schema

---

## Step 8: What's Next

Present this capabilities table:

| Want to... | Skill | What it does |
|---|---|---|
| Track a feature with documentation gates | `/manage-schemas` | Create schemas with lifecycle gates, then use companion skills |
| Create items from conversation context | `/create-item` | Infers type, priority, and container placement |
| Build custom workflow schemas | `/manage-schemas` | Create, view, edit, delete, and validate note schemas |
| See project health dashboard | `/work-summary` | Active work, blockers, next actions at a glance |
| Advance an item through gates | `/status-progression` | Shows current role, gate status, correct trigger |
| Change how the server runs (HTTP, REST API, config-sync) | `/configure-server` | Transport, REST API mode, port publishing, config mount |

**Offer cleanup:** Ask via `AskUserQuestion` whether to keep the tutorial items for reference or delete them. If delete, use the container UUID returned in Step 4 above:

```
manage_items(operation="delete", ids=["<container-UUID>"], recursive=true)
```

---

# Orientation Path

For users with an existing populated workspace.

## Step A: Health Check Dashboard

Run two calls in parallel:

```
get_context()
query_items(operation="overview", includeChildren=true)
```

Add `ancestorId="<rootId>"` to both when a rootId is known (resolved in Step 1) — this keeps the orientation dashboard scoped to the current project in multi-project workspaces. Call unscoped exactly as shown when no rootId is known.

Present a condensed dashboard with these sections:

- **Active Work** (role=work or review): items currently in progress — show title, role, and ancestor path
- **Blocked / Stalled**: items that cannot advance — either dependency-blocked or missing required notes
- **Containers**: root items with child counts by role
- **Recommendations**: from `get_next_item(limit=3, includeDetails=true)` — add `ancestorId="<rootId>"` when known

Use status symbols: `◉` in-progress, `⊘` blocked, `○` pending, `✓` completed

---

## Step B: Explain What You're Seeing

For each section of the dashboard, add a brief annotation:

- **Active items** are in `work` or `review` role — these are things being worked on right now
- **Blocked items** have unsatisfied dependencies (another item must complete first) or are missing required notes that gate advancement
- **Stalled items** have required notes that haven't been filled — use `get_context(itemId=...)` to see which notes are missing, then `manage_notes(upsert)` to fill them
- **Containers at depth 0** organize your work hierarchically — items can nest up to depth 3

If blocked items exist, explain: *"Run `/status-progression` on a blocked item to see exactly what's needed to unblock it."*

**Explain the plan mode connection:** These MCP items are the **execution tracking side** of your work. When Claude enters plan mode, it writes a persistent plan file (your design document). When the plan is approved, the plugin hooks tell Claude to create MCP items like these to track implementation progress. The plan file and MCP items are complementary — the plan captures *what and how*, the MCP tracks *progress and status*.

---

## Step C: Suggested Next Action

Based on the dashboard, recommend one concrete action:

| Situation | Recommendation |
|---|---|
| Stalled items with missing notes | Fill the required notes — show the exact `manage_notes` call |
| Blocked items with satisfied deps | Advance with `advance_item(trigger="start")` |
| No active work, queue items exist | Start the highest-priority queue item |
| Empty workspace | Switch to the Fresh-Start path (Step 2) |
| Everything terminal | Suggest creating new work with `/create-item` |

End with: *"Run `/work-summary` anytime to see this dashboard. When you're ready to build something, just describe it — Claude will enter plan mode, write a plan file, and create MCP items to track the work automatically."*
