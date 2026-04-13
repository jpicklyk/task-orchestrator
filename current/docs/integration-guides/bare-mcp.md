# Tier 1: Bare MCP Integration

## What You Get

- 13 MCP tools for hierarchical work item management
- Persistent SQLite database across sessions (Docker volume)
- Role-based workflow: queue → work → review → terminal
- Dependency tracking with blocking, cascade, and unblock detection
- Works with any MCP-compatible client (Claude Code, VS Code, Cursor, etc.)

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) installed and running

## Setup

Reference the Quick Start for detailed installation steps:

> See [Quick Start](../quick-start.md) Steps 1-3 for Docker image pull, Claude Code registration, and verification.

For non-Claude-Code MCP clients, the server configuration follows the standard MCP format:

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

The `mcp-task-data` Docker volume persists the SQLite database across container restarts.

## Core Workflow

### Creating Work Items

Use `manage_items` to create individual items, or `create_work_tree` for hierarchy:

```
create_work_tree(
  root: { title: "Add user search", summary: "Full-text search for user profiles" },
  children: [
    { title: "Design search API", priority: "high" },
    { title: "Build search index", priority: "medium" },
    { title: "Add search UI", priority: "medium" }
  ],
  dependencyPattern: "linear"
)
```

This creates a root item with 3 children wired in sequence (each blocks the next).

### Advancing Through Roles

Items progress via named triggers — never update the role directly:

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

| Trigger | Effect |
|---------|--------|
| `start` | queue→work, work→review, review→terminal |
| `complete` | Any non-terminal → terminal |
| `block` / `hold` | Any → blocked (saves previous role) |
| `resume` | blocked → previous role |
| `cancel` | Any → terminal (statusLabel: "cancelled") |

See [Workflow Guide](../workflow-guide.md) Section 2 for the full trigger table.

### Checking Status

Use `get_context()` with no parameters for a health check of all active work:

```
get_context()  // Returns: active items, blocked items, stalled items
```

Use `get_context(itemId="<uuid>")` to inspect a specific item's gate status and expected notes.

### Finding What's Next

```
get_next_item()  // Returns top items ranked by priority then complexity
```

Use `get_next_item(parentId="<uuid>")` to scope recommendations to a specific subtree.

## Example Scenario: Multi-Step Feature

1. Create the feature with `create_work_tree` (root + 3 children, linear dependencies)
2. Start the first child: `advance_item(trigger="start")` → queue→work
3. Do the work, fill work-phase notes
4. Advance: `advance_item(trigger="start")` — moves to review (if schema has review-phase notes) or terminal (if not)
5. If in review: fill review notes, then `advance_item(trigger="start")` → terminal
6. The next child auto-unblocks — check with `get_blocked_items(parentId="<root-uuid>")`
7. Use `get_next_item(parentId="<root-uuid>")` to find what's ready
8. When all children complete, the parent auto-cascades to terminal

## Key Tool Patterns

| Pattern | Tools | Description |
|---------|-------|-------------|
| Create hierarchy | `create_work_tree` | Root + children + dependencies in one call |
| Check health | `get_context()` | Active, blocked, stalled items |
| Advance phase | `advance_item` | Trigger-based role transitions |
| Find next work | `get_next_item` | Priority-ranked ready items |
| Batch complete | `complete_tree` | Topological completion of a subtree |

See [API Reference](../api-reference.md) for full tool documentation.

## When to Level Up

**Signal:** You find yourself repeating the same workflow instructions to the agent every session — "use advance_item, not raw updates," "check get_context before starting," "fill notes before advancing."

**Next:** [CLAUDE.md-Driven Workflow](claude-md-driven.md) — embed these conventions so the agent follows them automatically.
