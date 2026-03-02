# Quick Start: MCP Task Orchestrator

MCP Task Orchestrator gives AI agents persistent, structured task tracking that survives across sessions. Instead of loading your entire project state into context on every prompt, agents read and write a shared graph of `WorkItem` entities — keeping context lean and work visible.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) installed and running
- [Claude Code CLI](https://claude.ai/code) installed (`claude --version` should work)

---

## Step 1: Pull the Docker image

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

The image is published to GitHub Container Registry. The `latest` tag always points to the most recent release from the main branch.

---

## Step 2: Add to Claude Code

### Option A: CLI (recommended)

Run this once in your terminal:

```bash
claude mcp add-json mcp-task-orchestrator '{
  "command": "docker",
  "args": [
    "run", "--rm", "-i",
    "-v", "mcp-task-data:/app/data",
    "ghcr.io/jpicklyk/task-orchestrator:latest"
  ]
}'
```

This registers the server at the user level. The `mcp-task-data` Docker volume persists the SQLite database across container restarts.

### Option B: Project `.mcp.json`

Add to `.mcp.json` in your project root (checked into source control so teammates get it automatically):

```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

The server auto-initializes its schema on first run — no additional setup required.

### Option C: Other MCP clients

Configure your client using the same JSON structure as Option B above. STDIO transport works with any MCP-compatible client.

---

## Step 3: Verify the connection

Restart Claude Code (close and reopen), then run:

```
/mcp
```

You should see `mcp-task-orchestrator` listed as connected, with tools including `manage_items`, `query_items`, `advance_item`, `manage_notes`, `query_notes`, `manage_dependencies`, `query_dependencies`, `get_next_item`, `get_blocked_items`, `get_next_status`, `get_context`, `create_work_tree`, and `complete_tree`.

If the server shows as disconnected, check that Docker is running and that the image pulled successfully:

```bash
docker images ghcr.io/jpicklyk/task-orchestrator
```

---

## Step 4: Install the plugin

The plugin adds workflow skills, automation hooks, and an orchestrator output style to Claude Code. It is the recommended experience layer for Claude Code users — the sections below describe a workflow shaped by what the plugin provides.

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

After installing, restart Claude Code and run `/plugin list` to confirm `task-orchestrator` is enabled.

> The plugin is optional if you are using a non-Claude-Code MCP client, but for Claude Code it provides the guided workflow experience described below.

### Skills

Skills are invoked as slash commands in any Claude Code session:

| Command | Description |
|---------|-------------|
| `/task-orchestrator:work-summary` | Insight-driven dashboard: active work, blockers, and next actions |
| `/task-orchestrator:create-item` | Create a tracked work item from the current conversation context |
| `/task-orchestrator:quick-start` | Interactive onboarding — teaches by doing, adapts to empty or populated workspaces |
| `/task-orchestrator:manage-schemas` | Create, view, edit, delete, and validate note schemas in config |
| `/task-orchestrator:status-progression` | Navigate role transitions; shows current gate status and the correct trigger |
| `/task-orchestrator:dependency-manager` | Visualize, create, and diagnose dependencies between work items |
| `/task-orchestrator:batch-complete` | Complete or cancel multiple items at once — close out features or workstreams |

### Hooks

Hooks run automatically — no invocation required:

- **Session start** — injects current work context at the beginning of each Claude Code session so you never have to re-orient
- **Plan mode** — after plan approval, prompts Claude to create MCP items so persistent tracking stays in sync with the conversation
- **Subagent start** — passes task context into spawned subagents so they start with full awareness of the current item

### Output style

The plugin includes a **Workflow Analyst** output style. When active, Claude Code acts as a project management orchestrator — it plans, delegates implementation to subagents, and tracks progress in the WorkItem graph without writing code directly. Useful for complex multi-step features. Select it from the output style menu (`/output-style`) after installing the plugin.

---

## Step 5: How it works — the plan-mode pipeline

When you describe a feature or task, the plugin hooks automate a structured pipeline that keeps your design document and your project board in sync:

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

The plan file and the MCP items are not duplicates — they serve different roles:

- **Plan file** = design document. It captures the what and how: decisions, rationale, scope. It is a readable artifact you can review and share.
- **MCP items** = project board. They track progress and status: what is in flight, what is blocked, what is done. They survive across sessions without any re-explaining.

The MCP also shapes the plan itself. When Claude enters plan mode, the pre-plan hook tells it to check for existing tracked work and note schema requirements — setting a **definition floor**. This means the plan is written with awareness of what documentation gates must be satisfied and what items are already in progress, rather than starting from scratch.

The plugin hooks automate the handoff between these two artifacts. You describe what you want, approve the plan, and the hooks prompt Claude to materialize MCP items before implementation begins. From there, subagents self-report their progress through role transitions.

---

## Step 6: Your first work item

The easiest way to get started is to just tell Claude what you want to build.

**Creating structured work:**

```
You: "I want to build user authentication with a database schema,
      API endpoints, and a login UI."

Claude: → Calls create_work_tree to create a root item and three child items
        → Wires dependency edges so the UI and API must complete before integration tests
        → Shows the structure and which items are immediately actionable
```

**Navigating what to do next:**

```
You: "What should I work on next?"

Claude: → Calls get_next_item() to find the highest-priority unblocked item
        → Reports: "Database schema is ready — no blockers, high priority"
        → Starts working on it, filling notes as it goes
        → Calls advance_item(trigger="complete") when done
```

**Checking overall status:**

```
You: "Where do we stand on the authentication work?"

Claude: → Calls get_context() for a health snapshot
        → Reports: 2 items complete, 1 in progress, 1 blocked on the in-progress item
```

**Under the hood**

The conversational examples above translate to these tool calls:

```
# Build a work tree with children and dependencies in one call
create_work_tree(
  root={title: "Authentication system", priority: "high"},
  children=[
    {ref: "schema", title: "Database schema"},
    {ref: "api",    title: "Auth API endpoints"},
    {ref: "ui",     title: "Login UI"},
    {ref: "tests",  title: "Integration tests"}
  ],
  deps=[
    {from: "tests", to: "api"},
    {from: "tests", to: "ui"}
  ]
)

# Find the next thing to work on
get_next_item()

# Transition an item through its lifecycle
advance_item(transitions=[{itemId: "<uuid>", trigger: "start"}])
advance_item(transitions=[{itemId: "<uuid>", trigger: "complete"}])

# Health snapshot across all active work
get_context()
```

See [api-reference.md](api-reference.md) for full parameter documentation on all 13 tools.

---

## Step 7: Session resume

When you start a new Claude Code session, the plugin's session-start hook fires automatically. It injects your current work context so Claude knows what is in flight before you say anything.

```
[New session starts]

Session-start hook fires → injects active items, blockers, and recent transitions

You: "Let's keep going."

Claude: → Already knows: 2 items in progress, 1 blocked, last completed 4 hours ago
        → Picks up exactly where the previous session left off
        → No re-explaining required
```

You can also trigger the dashboard manually at any time:

```
/task-orchestrator:work-summary
```

This calls `get_context()` and `get_blocked_items()` and presents a structured view of active work, blockers, and recommended next actions — useful at the start of a session or after a long implementation run.

---

## Step 8: Note schemas

> **Schemas vs notes:** Schemas are user-configured rules in `.taskorchestrator/config.yaml` — they define what documentation agents must provide at each workflow phase. Notes are the actual content agents write as they work on items. Schemas live in your project config; notes live in the MCP database. Schemas define the gates; agents fill the notes to pass them.

Note schemas enforce per-phase documentation requirements. When an item's `tags` match a schema, `advance_item` gates progression until the required notes are filled.

Create `.taskorchestrator/config.yaml` in your project root:

```yaml
note_schemas:
  task-implementation:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Testable acceptance criteria for this task."
    - key: done-criteria
      role: work
      required: true
      description: "What does done look like? How was it verified?"
```

Items tagged `task-implementation` will now require an `acceptance-criteria` note before `advance_item(trigger="start")` advances them to work, and a `done-criteria` note before `advance_item(trigger="complete")` closes them.

The interactive way to build schemas is the `/task-orchestrator:manage-schemas` skill — it walks you through creating, viewing, editing, and validating schemas without editing YAML directly.

After adding or editing this file, reconnect the MCP server:

```
/mcp  (disconnect and reconnect mcp-task-orchestrator)
```

> **Docker:** To read this config file, mount the `.taskorchestrator/` folder into the container. Add this to your project-level `.mcp.json` (not the global CLI registration — a globally-registered server should not have its schema config vary per project):
> ```json
> {
>   "mcpServers": {
>     "mcp-task-orchestrator": {
>       "command": "docker",
>       "args": [
>         "run", "--rm", "-i",
>         "-v", "mcp-task-data:/app/data",
>         "-v", "${workspaceFolder}/.taskorchestrator:/project/.taskorchestrator:ro",
>         "-e", "AGENT_CONFIG_DIR=/project",
>         "ghcr.io/jpicklyk/task-orchestrator:latest"
>       ]
>     }
>   }
> }
> ```
> Only the `.taskorchestrator/` folder is exposed — the server has no access to the rest of your project.

---

## Key concepts

| Concept | Description |
|---------|-------------|
| `WorkItem` | The core entity. Has a `role` (queue/work/review/terminal/blocked), `priority`, `tags`, `depth` (0-3), and optional `parentId`. |
| `Note` | Key-value text attached to an item. Has a `role` indicating which workflow phase it belongs to. |
| `Dependency` | Directed edge between items: `BLOCKS`, `IS_BLOCKED_BY`, or `RELATES_TO`. |
| Role progression | Items advance via triggers: `start` (queue→work, work→review), `complete` (any→terminal), `block`/`hold` (any→blocked), `resume` (blocked→previous). |
| Note schema gating | When enabled, `advance_item` checks required notes exist and are non-empty before allowing phase transitions. |

---

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_PATH` | `data/current-tasks.db` | SQLite file path inside the container |
| `USE_FLYWAY` | `true` | Apply database migrations on startup |
| `AGENT_CONFIG_DIR` | _(unset)_ | Parent directory of `.taskorchestrator/`; set when mounting a config folder into the container |
| `LOG_LEVEL` | `INFO` | Verbosity: `DEBUG`, `INFO`, `WARN`, `ERROR` |

---

## What's next

- Run `/task-orchestrator:quick-start` for an interactive hands-on tutorial
- [api-reference.md](api-reference.md) — full reference for all 13 MCP tools, parameters, and response shapes
- [workflow-guide.md](workflow-guide.md) — note schemas, phase gates, dependency patterns, and lifecycle examples
