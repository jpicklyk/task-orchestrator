---
name: quick-start
description: Interactive onboarding for the MCP Task Orchestrator. Detects empty or populated workspaces and walks through how plan mode, persistent tracking, and the MCP work together. Use when a user says "get started", "how do I use this", "quick start", "first time setup", "onboard me", "what can this MCP do", or "help me learn task orchestrator".
argument-hint: "[optional: describe what you want to track, e.g. 'a web app project']"
---

# Quick Start - MCP Task Orchestrator

Interactive onboarding that teaches by doing. Detects your workspace state and adapts. Calls are 4.0 tool calls; shapes live in [`../_generated/call-shapes.md`](../_generated/call-shapes.md). Every answer is the response envelope (`ok`, `data` or `error`).

## Step 0: Learn Your Tools First

Teach this before anything else. The server shows a client only the tools of its **profile**:

| Profile | Where | What it is for |
|---|---|---|
| `orchestration` (the default) | `/mcp`, and stdio unless `MCP_TOOL_PROFILE` says otherwise | every tool: read and write items, notes, dependencies, `advance`, `config`, runs |
| `director` | `/mcp/director`, or `MCP_TOOL_PROFILE=director` | oversight: reads and reports, no note or document bodies, a few run writes |
| `knowledge` | `/mcp/knowledge`, or `MCP_TOOL_PROFILE=knowledge` | items, notes, dependencies and documents only (no `advance`) |

`/task-orchestrator:init` sets up the registration for you. A client sees only its own profile's selects and ops; a hidden one looks absent.

Any tool answers `describe: true` with its own parameters and errors instead of running. When unsure about a field, ask the tool:

```json
{ "describe": true }
{ "select": "overview", "describe": true }
```

The first form works on any tool; the second asks a select tool about one select. The same data is rendered in [`../_generated/call-shapes.md`](../_generated/call-shapes.md).

## Step 1: Detect Workspace State

Resolve the scope first. Check the SessionStart context: a `## Project Scope` section carries a **project rootId** (it scopes reads and anchors new items); a `## Personal Scope` section carries a **personal root** (it only anchors new items; reads stay unscoped).

Without session context, ask the config locator, not a cwd-relative file read. Resolve `<plugin root>` as init Step 0 does, then:

```bash
node --input-type=module -e "const m = await import('file:///<plugin root>/hooks/config-locator.mjs'); const l = m.locateConfig(); console.log(JSON.stringify({scope: l.scope, path: l.path, rootId: l.rootId, name: l.name}))"
```

(forward slashes only; the drive-letter form on Windows). Scope `project` with a `rootId` is a project rootId; scope `user` with a `rootId` is the personal root.

Health check; pass `ancestorId` only when a **project** rootId is known (personal scope, or a truly fresh workspace, calls it unscoped):

```to-call
{"tool": "items_read", "select": "context", "args": {"ancestorId": "<rootId>"}}
```

**If `data.active` and `data.stalled` are both empty**, follow the **Fresh-Start Path** (Steps 2-8). **Otherwise** follow the **Orientation Path** (Steps A-C).

---

## Step 1.5: Project Setup (delegated to init)

Check whether this workspace has a project anchor yet: if either kind of root (project or personal) is known from Step 1, it is set up.

If no root is known, offer setup via `AskUserQuestion`: *"This workspace isn't set up for Task Orchestrator yet. Run `/task-orchestrator:init` to create a project anchor (or `/task-orchestrator:init --user` for a personal root that serves every unconfigured directory)?"* `/task-orchestrator:init` owns the anchor, the `config.yaml` `project:` block, the server push and the bundled-rule seeding; this skill does none of that. If the user declines, proceed unscoped; nothing else here requires an anchor.

---

# Fresh-Start Path

## Step 2: Welcome - The Big Picture

Explain briefly:

- For anything non-trivial Claude enters **plan mode**: it explores the codebase and writes a plan to a **persistent markdown file**.
- The MCP Task Orchestrator **complements** the plan by tracking **execution state**: what is started, blocked, done, and next.
- The **plan file** is your design document (the *what* and *how*); the **MCP** is your project board (*progress* and *status*).
- The MCP also helps **during planning**: Claude checks for existing tracked work and schema requirements first, setting a **definition floor** so the plan accounts for documentation gates and does not duplicate work in progress.
- Together they give continuity across sessions: the plan tells you the approach, the MCP tells you where you left off.

---

## Step 3: The Plan Mode Pipeline

```
You describe what you want
        |
        v
  EnterPlanMode              <- Claude explores the codebase
        |
  pre-plan hook fires        <- sets the definition floor: existing work, schemas, gates
        |
        v
  Plan written to disk       <- persistent markdown file: your design document
        |
  Plan approved (ExitPlanMode)
        |
  post-plan hook fires       <- tells Claude to materialize before implementing
        |
        v
  Materialize                <- Claude creates MCP items from the plan
        |                       items, dependencies, notes: execution tracking
        v
  Implement                  <- subagents work, each moving its own item
        |                       advance transition: start -> work -> complete
        v
  Health check               <- items_read context shows what completed and what did not
```

Reinforce: the **plan file** and **MCP items** are not duplicates, they serve different roles; MCP items track each unit of work through a lifecycle (who is on it, what is blocked, what is done); the plugin hooks inject this guidance automatically.

---

## Step 4: Hands-On - Create Your First Items

**Determine the topic:** use `$ARGUMENTS` if given; otherwise ask via `AskUserQuestion` with options like "A web app feature", "A bug fix workflow", "A documentation project", or Other.

**Read the declared types first.** The server rejects an undeclared `type` with `schema_violation` whenever it declares any, so ask which exist (pass `rootId` when a root is known):

```to-call
{"tool": "config", "op": "effective", "args": {"rootId": "<rootId>"}}
```

`data.types` lists the declared types and `data.schemas` their resolved schemas. Use the root `type: "container"` when it is declared (or any declared type, or any name when the server declares none); give the children a declared non-container type, shown below as `<task-type>`. If the list is long, ask the user which type fits a task.

Create the container, three children and two dependencies as ONE atomic unit. With a project or personal root known, add `parentId` so the tree hangs under it.

```to-call
{"tool": "items_write", "op": "create_tree", "args": {"root": {"title": "<Project Name> - Tutorial", "summary": "Quick-start tutorial project to learn MCP Task Orchestrator", "type": "container", "priority": "medium"}, "children": [{"ref": "design", "title": "Design <topic>", "summary": "Define requirements and approach", "type": "<task-type>", "priority": "high"}, {"ref": "implement", "title": "Implement <topic>", "summary": "Build the solution", "type": "<task-type>", "priority": "high"}, {"ref": "test", "title": "Test <topic>", "summary": "Verify the implementation", "type": "<task-type>", "priority": "medium"}], "deps": [{"from": "design", "to": "implement", "type": "blocks"}, {"from": "implement", "to": "test", "type": "blocks"}]}}
```

Read the envelope: `ok`, then `data.results[0].ok`; on success `data.results[0].items` is `[{ref, id, depth}]` (keep the ids) and on failure `error.code` (`schema_violation` carries `detail.availableTypes`).

**Explain:**
- `create_tree` creates everything atomically: the container, three children and two edges. In a real workflow **Claude does this after a plan is approved**.
- A `blocks` edge means the target cannot start until the source completes.
- `ref` names are local aliases used only within this call.

```
<Project Name> - Tutorial (container)
  |- Design <topic>          <- actionable (no blockers)
  |- Implement <topic>       <- blocked by Design
  '- Test <topic>            <- blocked by Implement
```

These items are **the project board side**; the plan file would hold the design decisions behind each.

**Fill the required notes (gate prerequisite).** `start` needs the current phase's required notes filled, and `complete` needs every required note across all phases of the *resolved* schema, including notes traits add. Read the exact list for the design item; `schema[]` shows each key with `required` and `filled`:

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "<design-id>"}}
```

Fill each missing required key (one `upsert` element per key; the first shown, other keys the same way):

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<design-id>", "key": "<note-key>", "role": "<queue|work|review>", "body": "Define requirements and approach for <topic>."}}
```

Check `ok`, `data.results[i].ok` and `error.code` for the whole call. Tell the user: in a real workflow subagents fill these notes as work happens, phase by phase; here we pre-fill them only so the `complete` call in Step 5 is not gate-blocked.

---

## Step 5: The Role Lifecycle

Items move queue, work, review, terminal through `advance` `transition` triggers.

**5a. Start the design task:**

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<design-id>", "trigger": "start"}}
```

Check `ok`, `data.results[0].newRole` and `error.code` (`gate_blocked` names the missing note). Point out `cascades`: the container cascades `queue` to `work` because its first child started. In a real workflow **each subagent calls this** when it begins its item.

**5b. Complete the design task:**

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<design-id>", "trigger": "complete"}}
```

Point out `unblocked`: implement is now unblocked; the container stays in `work` while siblings are active.

**5c. Confirm what is next:**

```to-call
{"tool": "items_read", "select": "next", "args": {"limit": 3}}
```

Implement is now recommended: it was unblocked when design completed. This is how the MCP answers "what should I work on next?" across sessions.

---

## Step 6: Cross-Session Continuity

- **If a session ends mid-work**, the next session can read `items_read` `context` or run `/work-summary` to see what is in progress, blocked and done.
- **The plan file** is still on disk, so Claude can re-read the approach.
- **The MCP items** show execution state, so nothing completed needs re-explaining.

*"Read the plan to remember the approach. Check the MCP to see where you left off."* The plan does not change as work progresses; the MCP does.

---

## Step 7: Note Schemas (Optional Power Feature)

MCP items can have **required notes** that act as documentation gates:

- `.taskorchestrator/config.yaml` defines schemas under `work_item_schemas:`: which notes must be filled before an item advances. `type` is required on every item and selects the schema (`type: "feature-implementation"` activates that schema's notes and gates).
- Example: a `feature-implementation` schema can require a `feature-summary` note before work starts and a `review-checklist` note before completion.
- Each schema sets a **lifecycle**: `auto`, `manual`, `permanent` or `none` (lifecycle-free: no roles, no transitions).
- Notes can carry `guidance` (authoring hints) and `skill` (an evaluation framework to run before filling).
- **Composable traits** add note requirements per item. `traits` is always an array: `"traits": ["needs-security-review"]` adds a `security-assessment` note at the review phase.
- Run `/manage-schemas` to set one up interactively.

---

## Step 8: What's Next

| Want to... | Skill | What it does |
|---|---|---|
| Track a feature with documentation gates | `/manage-schemas` | Create schemas with lifecycle gates, then use companion skills |
| Create items from conversation context | `/create-item` | Infers type, priority, and container placement |
| Build custom workflow schemas | `/manage-schemas` | Create, view, edit, delete, and validate note schemas |
| See project health dashboard | `/work-summary` | Active work, blockers, next actions at a glance |
| Advance an item through gates | `/status-progression` | Shows current role, gate status, correct trigger |
| Change how the server runs (HTTP, REST API, config-sync) | `/configure-server` | Transport, REST API mode, port publishing, config mount |

**Offer cleanup.** Ask via `AskUserQuestion` whether to keep the tutorial items or delete them. To delete, use the container id from `create_tree`:

```to-call
{"tool": "items_write", "op": "delete", "args": {"id": "<container-id>", "recursive": true}}
```

Check `ok`, `data.results[0].ok` (`data.results[0].deleted` counts the removed subtree) and `error.code`.

---

# Orientation Path

For users with an existing populated workspace.

## Step A: Health Check Dashboard

Run three reads in parallel; add `ancestorId` / `anchorId` only when a **project** rootId is known (Step 1), which keeps the dashboard scoped to this project:

```to-call
{"tool": "items_read", "select": "overview", "args": {"excludeTerminal": true, "anchorId": "<rootId>"}}
```

```to-call
{"tool": "items_read", "select": "context", "args": {"ancestorId": "<rootId>"}}
```

```to-call
{"tool": "items_read", "select": "next", "args": {"limit": 3, "ancestorId": "<rootId>"}}
```

Present a condensed dashboard:

- **Active Work** (`data.active`): items in `work` or `review`: title, role, ancestor path.
- **Blocked / Stalled** (`data.blocked`, `data.stalled`): explicit blocks, items waiting on a dependency, and items missing required notes.
- **Containers**: the overview's root items with `childCounts` by role.
- **Recommendations**: the `next` result.

Symbols: `◉` in progress, `⊘` blocked, `○` pending, `✓` completed.

---

## Step B: Explain What You're Seeing

- **Active items** are in `work` or `review`: being worked on now.
- **Blocked items** are explicit blocks (`blockType: "explicit"`, with a `reason`) or open items with an unsatisfied dependency (`blockType: "dependency"`).
- **Stalled items** have required notes not yet filled: read `items_read` `context` with the `itemId`, then fill the missing keys with `notes_write` `upsert`.
- **Containers at depth 0** organize work hierarchically; items nest to any depth.

If blocked items exist: *"Run `/status-progression` on a blocked item to see exactly what is needed to unblock it."*

**Plan mode connection:** these items are the **execution tracking side** of your work. In plan mode Claude writes a plan file; when it is approved the plugin hooks tell Claude to create MCP items like these. The plan captures *what and how*, the MCP tracks *progress and status*.

---

## Step C: Suggested Next Action

Recommend one concrete action:

| Situation | Recommendation |
|---|---|
| Stalled items with missing notes | Fill the required notes: show the exact `notes_write` call |
| Blocked items with satisfied deps | Advance with `advance` `transition`, `trigger: "start"` |
| No active work, queue items exist | Start the highest-priority queue item |
| Empty workspace | Switch to the Fresh-Start path (Step 2) |
| Everything terminal | Suggest creating new work with `/create-item` |

End with: *"Run `/work-summary` anytime to see this dashboard. When you are ready to build something, just describe it: Claude will enter plan mode, write a plan file, and create MCP items to track the work automatically."*
