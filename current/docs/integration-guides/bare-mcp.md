# Tier 1: Bare MCP Integration

## What You Get

- MCP tools for hierarchical work item management
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

For a persistent HTTP server shared across multiple projects (instead of this per-session `--rm -i`
container), see `/task-orchestrator:configure-server`.

## How Calls Look

The server serves its tools per **tool profile**; this guide uses the default `orchestration` profile (stdio's default; HTTP `/mcp`). A client sees only the selects and ops of its profile, and any tool answers `{"describe": true}` with its own contract, so a client never has to guess a field. Every example below is the JSON request body of one tool call: a read tool takes a `select`, a write tool takes an `ops` array whose elements name an `op`, and every answer is the same `{ok, data | error, meta}` envelope. The profile table, `describe` and the envelope are explained in [Calling the tools](../workflow-guide.md#calling-the-tools).

## Core Workflow

### Creating Work Items

Use `items_write` `create` for an individual item, or `create_tree` for a hierarchy. `type` is required on every item and must be a type your config declares:

```json
{
  "ops": [
    {
      "op": "create_tree",
      "root": { "title": "Add user search", "summary": "Full-text search for user profiles", "type": "feature" },
      "children": [
        { "ref": "design", "title": "Design search API", "type": "task", "priority": "high" },
        { "ref": "index",  "title": "Build search index", "type": "task", "priority": "medium" },
        { "ref": "ui",     "title": "Add search UI", "type": "task", "priority": "medium" }
      ],
      "deps": [
        { "from": "design", "to": "index" },
        { "from": "index",  "to": "ui" }
      ]
    }
  ]
}
```

This creates a root item with 3 children wired in sequence (each blocks the next). The answer carries the root `id` and `version`, plus `items` (each `{ref, id, depth}`) and `deps`. To wire an existing set of items instead, `deps_write` has a `linear` op: `{"ops": [{"op": "linear", "ids": ["a1b2c3d4", "b2c3d4e5", "c3d4e5f6"]}]}`.

### Advancing Through Roles

Items progress via named triggers — never update the role directly:

```json
{ "ops": [ { "op": "transition", "itemId": "a1b2c3d4", "trigger": "start" } ] }
```

| Trigger | Effect |
|---------|--------|
| `start` | queue→work, work→review, review→terminal |
| `complete` | queue, work or review to terminal (not from blocked) |
| `block` / `hold` | queue, work or review to blocked (saves previous role; not from blocked or terminal) |
| `resume` | blocked → previous role |
| `cancel` | Any → terminal (statusLabel: "cancelled") |
| `reopen` | terminal to queue |

See [Workflow Guide](../workflow-guide.md) Section 2 for the full trigger table.

### Checking Status

Use the `context` select with no `itemId` for a health check of all active work:

```json
{ "select": "context" }
```

The answer holds `active`, `blocked` and `stalled` items and a `claimSummary`. Add an `itemId` to inspect a specific item's gate status and expected notes:

```json
{ "select": "context", "itemId": "a1b2c3d4" }
```

### Finding What's Next

```json
{ "select": "next" }
```

Returns the top unblocked, unclaimed `queue` items ranked by priority then complexity (`limit` defaults to 1). Scope the recommendation to a subtree:

```json
{ "select": "next", "parentId": "5b21c0de" }
```

## Example Scenario: Multi-Step Feature

1. Create the feature with `items_write` `create_tree` (root + 3 children, linear dependencies)
2. Start the first child: an `advance` `transition` with `trigger: "start"` (queue to work)
3. Do the work, fill work-phase notes (`notes_write` `upsert`)
4. Advance: `trigger: "start"` again: moves to review (if schema has review-phase notes) or terminal (if not)
5. If in review: fill review notes, then `trigger: "start"` (review to terminal)
6. The next child auto-unblocks: the transition result lists it under `unblocked`, and `{"select": "blocked", "ancestorId": "<root>"}` shows what is still waiting
7. Use `{"select": "next", "parentId": "<root>"}` to find what's ready
8. When all children complete, a parent in work auto-cascades to terminal; a parent in review stays there (the child's result reports the cascade as `blocked: "gate"`, `reason: "review-pending"`) until its reviewer completes it

## Key Tool Patterns

| Pattern | Tool and call | Description |
|---------|---------------|-------------|
| Create hierarchy | `items_write` `create_tree` | Root + children + dependencies in one call |
| Check health | `items_read` `context` | Active, blocked, stalled items |
| Advance phase | `advance` `transition` | Trigger-based role transitions |
| Find next work | `items_read` `next` | Priority-ranked ready items |
| Batch complete | `items_write` `complete_tree` | Dependency-ordered completion of a subtree |

See [API Reference](../api-reference.md) for full tool documentation. Budgeted multi-agent passes (runs, seats and decisions) are explained in [Workflow Guide section 12](../workflow-guide.md#12-runs-seats-and-decisions), and aggregate figures in [reporting](../reporting.md).

## When to Level Up

**Signal:** You find yourself repeating the same workflow instructions to the agent every session: "use `advance`, not raw updates," "read the item's `context` before starting," "fill notes before advancing."

**Next:** [CLAUDE.md-Driven Workflow](claude-md-driven.md) — embed these conventions so the agent follows them automatically.
