# Quick Start: MCP Task Orchestrator v3

MCP Task Orchestrator gives AI agents persistent, structured task tracking that survives across sessions. Instead of loading your entire project state into context on every prompt, agents read and write a shared graph of `WorkItem` entities — keeping context lean and work visible. v3 is a ground-up rewrite built around a unified `WorkItem` graph with `Note` attachments and `Dependency` edges.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) installed and running
- [Claude Code CLI](https://claude.ai/code) installed (`claude --version` should work)

---

## Step 1: Pull the Docker image

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

The image is built from the `current` module (`runtime-current` target) and published to GitHub Container Registry. The `latest` tag always points to the most recent release from the main branch.

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

### Option B: Project settings file

Add this to `.claude/settings.json` in your project root (checked into source control so teammates get it automatically):

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

## Step 4: Your first work item

Once connected, you can use the tools directly in a Claude Code session. Here is a minimal workflow:

```
# Create a top-level work item
manage_items(operation="create", items=[{title: "Build login feature", priority: "high", tags: "feature"}])

# View the item tree
query_items(operation="overview")

# Advance the item from queue to work phase
advance_item(transitions=[{itemId: "<uuid from create response>", trigger: "start"}])
```

The `manage_items` create response includes the item's `id` (full UUID). Use that UUID in subsequent calls.

### Adding context with notes

Notes attach phase-specific documentation to items:

```
# Add a requirements note (queue phase)
manage_notes(operation="upsert", notes=[{
  itemId: "<uuid>",
  key: "requirements",
  role: "queue",
  body: "User must be able to log in with email and password. Session persists for 30 days."
}])

# Read notes for an item
query_notes(operation="list", itemId: "<uuid>")
```

### Building a subtree

For structured work with dependencies, `create_work_tree` creates a root item, children, and dependency edges in one call:

```
create_work_tree(
  root={title: "Authentication system", priority: "high"},
  children=[
    {ref: "api", title: "Auth API endpoints"},
    {ref: "ui", title: "Login UI"},
    {ref: "tests", title: "Integration tests"}
  ],
  deps=[
    {from: "tests", to: "api"},
    {from: "tests", to: "ui"}
  ]
)
```

This creates `api` and `ui` as siblings that must complete before `tests` can be started.

### Checking what to work on next

```
# Get the highest-priority unblocked item
get_next_item()

# See everything active and blocked
get_context()
```

---

## Note schemas (optional)

Note schemas let you enforce per-phase documentation requirements. When an item's `tags` match a schema, `advance_item` will gate progression until the required notes are filled.

Create `.taskorchestrator/config.yaml` in your project root:

```yaml
note_schemas:
  - tags: "task-implementation"
    notes:
      - key: "requirements"
        role: "queue"
        required: true
        description: "Testable acceptance criteria before starting"
      - key: "done-criteria"
        role: "work"
        required: true
        description: "Conditions that must be true before marking complete"
```

After adding or editing this file, reconnect the MCP server:

```
/mcp  (disconnect and reconnect mcp-task-orchestrator)
```

Items tagged `task-implementation` will now require a `requirements` note before `advance_item(trigger="start")` advances them to work, and a `done-criteria` note before `advance_item(trigger="complete")` closes them.

> **Docker:** To read this config file, mount only the `.taskorchestrator/` folder into the container. Add this to your project-level `.mcp.json` (not the global CLI registration):
> ```json
> "-v", "${workspaceFolder}/.taskorchestrator:/project/.taskorchestrator:ro",
> "-e", "AGENT_CONFIG_DIR=/project"
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

- [api-reference.md](api-reference.md) — full reference for all 13 MCP tools, parameters, and response shapes
- [workflow-guide.md](workflow-guide.md) — note schemas, phase gates, dependency patterns, and lifecycle examples
