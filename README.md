# MCP Task Orchestrator

**Stop losing context. Start building faster.**

An orchestration framework for AI coding assistants that solves context pollution and token exhaustion — enabling your AI to work on complex projects without running out of memory.

[![Version](https://img.shields.io/github/v/tag/jpicklyk/task-orchestrator?sort=semver)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

---

## Why This Exists

AI agents [exhaust their effective context windows](https://www.anthropic.com/news/context-management) on long-running tasks — conversation history, tool outputs, and code examples accumulate until the model loses focus and fails. Task Orchestrator implements [industry-recommended patterns](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) to prevent this: persistent external memory, summary-based phase handoffs, and sub-agent isolation with clean contexts. Work scales to 50+ tasks with up to 90% token reduction and zero time rebuilding context after session restarts.

---

## Key Features

- ✅ **Persistent Memory** — AI remembers project state, completed work, and decisions across sessions
- ✅ **Token Efficiency** — Up to 90% reduction via just-in-time context loading and note-based handoffs
- ✅ **Hierarchical WorkItems** — Flexible depth hierarchy (up to 4 levels) with any nesting structure you need
- ✅ **Note Schemas** — Per-item documentation requirements that gate phase transitions; enforced by the server
- ✅ **Role-Based Workflow** — `queue → work → review → terminal` with named triggers and automatic dependency enforcement
- ✅ **Dependency Graph** — Typed BLOCKS edges with pattern shortcuts (linear chains, fan-out, fan-in) and BFS traversal
- ✅ **Sub-Agent Orchestration** — Delegated execution for complex work (Claude Code)
- ✅ **Skills & Hooks** — Workflow coordination skills and event-driven automation (Claude Code plugin)
- ✅ **MCP Protocol Support** — Core persistence and task management work with any MCP client

---

## Quick Start

**Prerequisite**: [Docker](https://www.docker.com/products/docker-desktop/) must be installed and running.

### Step 1: Pull the image

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

This is a one-time step — Docker caches the image locally. Pulling first ensures your MCP client connects instantly rather than waiting silently on first launch.

### Step 2: Register with your MCP client

Choose the option that matches your setup:

#### Option A: Claude Code (CLI — recommended)

Register the server once from your terminal:

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

Restart Claude Code, then run `/mcp` to confirm `mcp-task-orchestrator` is connected.

#### Option B: Project `.mcp.json`

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

The `mcp-task-data` Docker volume persists the SQLite database across container restarts. The server auto-initializes its schema on first run — no additional setup required.

#### Option C: Other MCP Clients

Configure your client with the same JSON as Option A above. STDIO transport works with any MCP-compatible client.

### Advanced: Per-Project Note Schemas

By default the server runs in schema-free mode — all 13 tools work with no additional configuration. If you want to define custom note schemas that gate role transitions (e.g., require an acceptance-criteria note before a work item can advance), you can point the server at your project's `.taskorchestrator/config.yaml`.

Add the config mount to your **Option B** `.mcp.json` only (not the global Option A registration — a globally-registered server should not have its schema config vary per project):

```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "mcp-task-data:/app/data",
        "-v", "${workspaceFolder}/.taskorchestrator:/project/.taskorchestrator:ro",
        "-e", "AGENT_CONFIG_DIR=/project",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Security note:** Only the `.taskorchestrator/` folder is mounted — the server has no access to the rest of your project. The container's `/project` path contains nothing else.

See [Workflow Guide](current/docs/workflow-guide.md) for the `.taskorchestrator/config.yaml` schema format and examples.

### Advanced: Per-Project Data Isolation

By default, all projects share the `mcp-task-data` Docker volume — a single SQLite database for everything. To give a project its own isolated task store, change the volume name in the `-v` flag to something project-specific:

```json
"-v", "my-project-data:/app/data",
```

Docker creates the volume automatically on first run. Each named volume is a completely separate database — work items, notes, and dependencies from one project never appear in another. Combine this with the per-project `.mcp.json` (Option B) so the scoped volume and config schema travel together with the project.

### Step 3: Claude Code Plugin (optional)

The plugin adds workflow skills, automation hooks, and an orchestrator output style to Claude Code. The MCP server (Step 2) must be connected first.

**Install:**

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

After installing, restart Claude Code and verify with `/plugin list` — you should see `task-orchestrator` enabled.

**Skills** — invoke as slash commands in any Claude Code session:

| Command | Description |
|---------|-------------|
| `/task-orchestrator:work-summary` | Insight-driven dashboard: active work, blockers, and next actions |
| `/task-orchestrator:create-item` | Create a tracked work item from the current conversation context |
| `/task-orchestrator:status-progression` | Navigate role transitions; shows gate status and the correct trigger |
| `/task-orchestrator:schema-builder` | Interactively design a note schema for a new work item type |

**Hooks** — automatic, no invocation needed:

- **Session start** — loads current work context at the beginning of each Claude Code session
- **Plan mode** — after plan approval, prompts Claude to create MCP items so persistent tracking stays in sync
- **Subagent start** — injects task context into spawned subagents so they start with full awareness

**Output style** — The plugin includes a **Workflow Analyst** output style that turns Claude Code into a project management orchestrator: it plans, delegates to subagents, and tracks progress without writing code directly. Select it from the output style menu (`/output-style`) or enable it in your Claude Code settings.

> **Contributing?** See [Contributing Guidelines](CONTRIBUTING.md) for developer setup.

---

## How It Works

### 1. Unified WorkItem Graph

Everything is a **WorkItem** — there are no separate "Project", "Feature", or "Task" types. Items nest by `parentId` up to 4 levels deep. The hierarchy is whatever your workflow needs:

```
WorkItem (depth 0): "E-Commerce Platform"
  └── WorkItem (depth 1): "User Authentication"
      ├── WorkItem (depth 2): "Database schema" [terminal]
      ├── WorkItem (depth 2): "Login API" [work]
      └── WorkItem (depth 2): "Integration tests" [blocked by: Login API]
```

Create an entire subtree atomically with `create_work_tree`:

```
create_work_tree(
  root={ "title": "User Authentication", "priority": "high" },
  children=[
    { "ref": "schema", "title": "Database schema" },
    { "ref": "api",    "title": "Login API" },
    { "ref": "tests",  "title": "Integration tests" }
  ],
  deps=[
    { "from": "schema", "to": "api" },
    { "from": "api",    "to": "tests" }
  ]
)
```

### 2. Notes as Persistent Documentation

Notes are keyed text documents attached to WorkItems. They serve as the persistent memory layer — capturing requirements, decisions, test results, and handoff context that survives session restarts.

Instead of re-reading conversation history, each item carries structured phase-specific notes:

```
manage_notes(operation="upsert", notes=[{
  "itemId": "<uuid>",
  "key": "done-criteria",
  "role": "work",
  "body": "All 42 tests passing. Schema migration verified on staging. No regressions."
}])
```

Reading a 200-token note instead of 5k+ tokens of conversation history implements Anthropic's "compaction" pattern — preserving critical information while discarding redundant details.

### 3. Role-Based Workflow

Every WorkItem moves through lifecycle phases called **roles**:

```
queue  →  work  →  review  →  terminal
              ↘                    ↗
               ← skip review if no review-phase notes defined ←

Any non-terminal role can transition to:
  blocked  (hold/block trigger)  →  resume  →  previous role
```

Transitions use named triggers — no raw status assignments:

| Trigger   | Effect |
|-----------|--------|
| `start`   | queue→work, work→review (or terminal if no review notes), review→terminal |
| `complete`| Force-close to terminal, bypassing phase gates |
| `block`   | Pause to blocked, saving previous role for resume |
| `resume`  | Restore blocked item to its previous role |
| `cancel`  | Close to terminal with cancelled status label |

**Dependency enforcement**: `advance_item(trigger="start")` checks that all blocking items have reached their `unblockAt` threshold before allowing a transition. Blocked items appear in `get_blocked_items()` and `get_context()`.

---

## Note Schemas

Note schemas are the key feature that makes phase transitions meaningful. When an item's `tags` match a configured schema, required notes must be filled before `advance_item` allows progression to the next phase.

Define schemas in `.taskorchestrator/config.yaml` in your project root:

```yaml
note_schemas:
  task-implementation:
    - key: requirements
      role: queue
      required: true
      description: "Testable acceptance criteria before starting"
    - key: done-criteria
      role: work
      required: true
      description: "What does done look like? How was it verified?"
```

Items tagged `task-implementation` are now gated:

- `advance_item(trigger="start")` from **queue** requires `requirements` to be filled
- `advance_item(trigger="start")` from **work** requires `done-criteria` to be filled

Use `get_context(itemId=...)` to inspect gate status before attempting a transition — it returns `canAdvance`, `missing`, and `guidancePointer` for the first unfilled required note.

After adding or editing `config.yaml`, reconnect the MCP server:

```
/mcp  (disconnect and reconnect mcp-task-orchestrator)
```

> **Full schema reference**: [Workflow Guide](current/docs/workflow-guide.md)

---

## MCP Tools

v3 exposes **13 tools** organized around the WorkItem graph:

### Hierarchy & CRUD

| Tool | Description |
|------|-------------|
| `manage_items` | Create, update, or delete WorkItems (batch operations) |
| `query_items` | Get by ID, search with filters and pagination, or hierarchical overview |
| `create_work_tree` | Atomically create root + children + dependency edges + notes in one call |
| `complete_tree` | Batch-complete all descendants in topological order with gate checking |

### Notes

| Tool | Description |
|------|-------------|
| `manage_notes` | Upsert or delete notes on WorkItems |
| `query_notes` | Get a single note or list notes for an item; use `includeBody=false` for token-efficient metadata checks |

### Dependencies

| Tool | Description |
|------|-------------|
| `manage_dependencies` | Create or delete edges; supports `linear`, `fan-out`, `fan-in` pattern shortcuts |
| `query_dependencies` | Query edges with direction filtering; `neighborsOnly=false` for full BFS graph traversal |

### Workflow

| Tool | Description |
|------|-------------|
| `advance_item` | Trigger-based role transitions with gate enforcement, dependency checking, and unblock reporting |
| `get_next_status` | Read-only progression recommendation before transitioning |
| `get_context` | Context snapshot: item gate check, session resume, or health check |
| `get_next_item` | Priority-ranked recommendation of next actionable, unblocked item |
| `get_blocked_items` | All items blocked by unsatisfied dependencies or explicit block trigger |

> **Full reference**: [API Reference](current/docs/api-reference.md)

### Token-Efficient Query Patterns

`get_context(includeAncestors=true)` + `query_items(operation="overview")` gives a complete work-summary dashboard in **2 calls** — active items with full ancestor chains, blocked items, and container-level counts. No sequential parent-walk needed.

---

## Documentation

- **[Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)** — Full documentation hub
- **[Quick Start Guide](current/docs/quick-start.md)** — Setup walkthrough and first work item
- **[API Reference](current/docs/api-reference.md)** — All 13 MCP tools, parameters, and response shapes
- **[Workflow Guide](current/docs/workflow-guide.md)** — Note schemas, phase gates, dependency patterns, and lifecycle examples
- **[Changelog](CHANGELOG.md)** — Release history
- **[Contributing Guidelines](CONTRIBUTING.md)** — Development setup and contribution process

---

## Example: From Session Start to Feature Complete

```
You: "I want to build user authentication"
AI: → create_work_tree("User Auth", children=[schema, login-api, tests], deps=[schema→api→tests])
   → 3 WorkItems created with linear dependency chain
   → Note schema gates applied to items tagged task-implementation

You: "What's next?"
AI: → get_next_item() → "Database schema" [queue, no blockers, high priority]
   → advance_item(trigger="start") → schema moves to work
   → [implements schema]
   → manage_notes(key="done-criteria"): "Migration V5 applied. Users table with email index."
   → advance_item(trigger="start") → schema moves to terminal
   → Response: unblockedItems = ["Login API"] ← dependency satisfied

You: "What's next?"
AI: → get_next_item() → "Login API" [queue, schema is terminal]
   → Reads 200-token done-criteria note (not 5k conversation history)
   → Implements API, fills notes, advances to terminal

[Next morning - new session]
You: "What's next?"
AI: → get_context(includeAncestors=true) → sees active/blocked items with full context instantly
   → "Integration tests" was blocked; Login API is now terminal → tests unblocked
   → advance_item(trigger="start") → tests move to work
```

**No context rebuilding.** Persistent WorkItem graph + notes = instant session resume with full state.

---

## Troubleshooting

**Quick Fixes**:
- **AI can't find tools**: Restart your AI client or run `/mcp reconnect mcp-task-orchestrator`
- **Docker not running**: Start Docker Desktop, verify with `docker version`
- **Server shows failed**: Enable `LOG_LEVEL=DEBUG` in your Docker config to inspect startup logs
- **Note gates blocking unexpectedly**: Run `get_context(itemId=...)` to see exactly which notes are missing
- **Skills not available**: Install via plugin marketplace (requires Claude Code)

**Get Help**:
- [Discussions](../../discussions) — Ask questions and share ideas
- [Issues](../../issues) — Bug reports and feature requests

---

## Technical Stack

- **Kotlin 2.2.0** with Coroutines for concurrent operations
- **SQLite + Exposed ORM** for fast, zero-config database (persistent memory system)
- **Flyway Migrations** for versioned schema management
- **MCP SDK 0.8.4** for standards-compliant protocol (STDIO and HTTP transport)
- **Docker** for one-command deployment

Clean Architecture (Domain → Application → Infrastructure → Interface) with 1,600+ tests.

---

## License

[MIT License](LICENSE) — Free for personal and commercial use

