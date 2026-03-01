---
name: quick-start
description: Interactive onboarding for the MCP Task Orchestrator. Detects empty or populated workspaces and walks through how plan mode, persistent tracking, and the MCP work together. Use when a user says "get started", "how do I use this", "quick start", "first time setup", "onboard me", "what can this MCP do", or "help me learn task orchestrator".
argument-hint: "[optional: describe what you want to track, e.g. 'a web app project']"
---

# Quick Start — MCP Task Orchestrator

Interactive onboarding that teaches by doing. Detects your workspace state and adapts.

## Step 1: Detect Workspace State

Call the health check to determine which path to follow:

```
get_context()
```

**If no active or stalled items exist** — follow the **Fresh-Start Path** (Steps 2-8).
**If active items exist** — follow the **Orientation Path** (Steps A-C).

---

# Fresh-Start Path

## Step 2: Welcome — The Big Picture

Explain the core concept (4 sentences max):

- When you ask Claude to build something non-trivial, it enters **plan mode** — exploring the codebase and writing a plan saved as a **persistent markdown file**
- The MCP Task Orchestrator **complements** the plan file by tracking **execution state** — what's been started, what's blocked, what's done, and what's next
- Think of it this way: the **plan file** is your design document (the *what* and *how*), while the **MCP** is your project board (the *progress* and *status*)
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
  pre-plan hook fires        ← Plugin tells Claude to check MCP for existing work
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

**Explain to the user:**
- The **plan file** and **MCP items** are not duplicates — they serve different roles
- The plan file captures your design decisions, architectural reasoning, and approach — it's a reference document
- MCP items track individual units of work through a lifecycle: who's working on what, what's blocked, and what's done
- The plugin hooks (`pre-plan`, `post-plan`) inject guidance automatically so Claude follows this pipeline — you don't need to ask for it

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
    priority: "medium"
  },
  children: [
    { ref: "design", title: "Design <topic>", summary: "Define requirements and approach", priority: "high" },
    { ref: "implement", title: "Implement <topic>", summary: "Build the solution", priority: "high" },
    { ref: "test", title: "Test <topic>", summary: "Verify the implementation", priority: "medium" }
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

---

## Step 5: The Role Lifecycle

Every MCP item moves through roles: **queue** (planned) → **work** (active) → **review** (verifying) → **terminal** (done). This is how the MCP knows what's in progress and what's finished.

**5a. Start the design task:**

```
advance_item(transitions=[{ itemId: "<design-UUID>", trigger: "start" }])
```

Point out in the response:
- Design moved from `queue` → `work`
- Check `cascadeEvents` — the container likely cascaded from `queue` → `work` automatically (first child started)
- In a real workflow, **each subagent calls this** when it begins working on its assigned item

**5b. Complete the design task:**

```
advance_item(transitions=[{ itemId: "<design-UUID>", trigger: "complete" }])
```

Point out in the response:
- Design moved from `work` → `terminal`
- Check `unblockedItems` — implement should now be unblocked
- The container stays in `work` because siblings are still active

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

- A `.taskorchestrator/config.yaml` file defines note schemas — which notes must be filled before an item can advance
- Example: a `feature-implementation` schema might require `requirements` and `design` notes before work can start
- This enforces documentation discipline — Claude must fill the notes before `advance_item` allows progression
- Run `/schema-builder` to set one up interactively, or `/feature-implementation` to see it in action

---

## Step 8: What's Next

Present this capabilities table:

| Want to... | Skill | What it does |
|---|---|---|
| Track a feature with documentation gates | `/feature-implementation` | Full lifecycle with required notes at each phase |
| Create items from conversation context | `/create-item` | Infers type, priority, and container placement |
| Build custom workflow schemas | `/schema-builder` | Define note requirements for your own item types |
| See project health dashboard | `/work-summary` | Active work, blockers, next actions at a glance |
| Advance an item through gates | `/status-progression` | Shows current role, gate status, correct trigger |

**Offer cleanup:** Ask via `AskUserQuestion` whether to keep the tutorial items for reference or delete them. If delete, use:

```
manage_items(operation="delete", ids=["<container-UUID>"], recursive=true)
```

---

# Orientation Path

For users with an existing populated workspace.

## Step A: Health Check Dashboard

Run two calls in parallel:

```
get_context(includeAncestors=true)
query_items(operation="overview", includeChildren=true)
```

Present a condensed dashboard with these sections:

- **Active Work** (role=work or review): items currently in progress — show title, role, and ancestor path
- **Blocked / Stalled**: items that cannot advance — either dependency-blocked or missing required notes
- **Containers**: root items with child counts by role
- **Recommendations**: from `get_next_item(limit=3, includeDetails=true)`

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
