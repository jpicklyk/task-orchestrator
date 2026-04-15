# MCP Task Orchestrator

**Server-enforced workflow discipline for AI agents.**

Prompt-based frameworks hope the LLM follows instructions. This one blocks the call if it doesn't.

[![Version](https://img.shields.io/github/v/tag/jpicklyk/task-orchestrator?sort=semver)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

---

## The Problem

Multi-agent workflows need infrastructure the model doesn't provide. When an orchestrator dispatches sub-agents across sessions, there's no built-in way to enforce what documentation must exist before work starts, track which agent made which change, or guarantee dependency ordering across a work breakdown. These are structural concerns — they belong in the server, not in prompts.

## A Different Approach

Task Orchestrator is an [MCP server](https://modelcontextprotocol.io) — not a prompt layer. It provides 13 tools that give any MCP-compatible AI agent a persistent work item graph with **server-enforced quality gates**. The enforcement happens at the tool level: if a required design note isn't filled, `advance_item` returns an error. If a dependency isn't satisfied, the transition is blocked. If auditing is enabled and an agent doesn't identify itself, the call is rejected before it reaches the server.

The rules live in the server, not the conversation.

**What this means in practice:**

- An agent can't start implementation without filling the required specification note
- A sub-agent can't advance a blocked task until its upstream dependency is complete
- Every transition and note records *who* made the change (actor attribution)
- Auditing mode blocks any write operation where the agent doesn't identify itself
- A new session picks up exactly where the last one left off — persistent state, not conversation replay
- Workflow schemas are YAML config, not hardcoded prompts — change the rules without changing code

---

## How It's Different

|  | Prompt-Based Frameworks | Task Orchestrator |
|--|------------------------|-------------------|
| **Enforcement** | Instructions that agents should follow | Server blocks the call if rules aren't met |
| **Persistence** | File-based state | SQLite database with structured queries |
| **Accountability** | No concept of which agent did what | Actor attribution with pluggable verification (JWKS) |
| **Dependency ordering** | Sequenced by prompt convention | Server validates dependency graphs before allowing transitions |
| **Session continuity** | Conversation history or file reconstruction | `get_context()` returns full state in one call |
| **Portability** | Tied to one AI client | Works with any MCP-compatible client |

---

## Core Capabilities

### Workflow Enforcement

Schemas define what agents must produce at each phase — and the server blocks progression until it's done. But schemas do more than gate transitions. They set a **planning floor**: when an agent enters plan mode, the schema tells it what documentation must exist before implementation can start, shaping the plan structure itself.

```yaml
# .taskorchestrator/config.yaml
work_item_schemas:
  feature-task:
    notes:
      - key: requirements
        role: queue
        required: true
        description: "Acceptance criteria before starting"
        guidance: "Cover: problem statement, acceptance criteria, alternatives considered, test strategy."
        skill: "spec-quality"
      - key: implementation-notes
        role: work
        required: true
        description: "What was built and why"
```

`advance_item(trigger="start")` from queue requires `requirements` to be filled. No exceptions, no prompt-dependent compliance — the server returns an error with exactly which notes are missing.

The `guidance` field provides authoring instructions surfaced at the right moment — when the agent is about to fill that note, `get_context` returns the guidance as a `guidancePointer`. The `skill` field takes this further: it references a specific skill that the agent must invoke before filling the note, providing a deterministic evaluation framework rather than freeform prose. Together, they create structured agent behavior that's configured in YAML, not hardcoded in prompts.

### Composable Traits

Traits add cross-cutting note requirements to any schema without duplicating definitions. Define a trait once, apply it to any item type:

```yaml
traits:
  needs-security-review:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review of auth, data handling, and access control"
        skill: "security-review"

work_item_schemas:
  feature-task:
    default_traits:
      - needs-security-review
    notes:
      # ... base notes
```

Every `feature-task` item automatically inherits the `security-assessment` note requirement. Traits can also be applied per-item via the `traits` parameter on `manage_items` — a task touching authentication gets `needs-security-review` while a CSS cleanup doesn't.

### Persistent Work Item Graph

Everything is a **WorkItem** in a hierarchical graph. Items nest up to 4 levels deep, connected by typed dependency edges. Create an entire work breakdown atomically:

```
create_work_tree(
  root={ "title": "User Authentication" },
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

When `schema` reaches terminal, `api` is automatically unblocked. When all children complete, the parent cascades to terminal. Dependency ordering is enforced by the server — structurally, not by convention.

### Actor Attribution & Auditing

Every `advance_item` transition and `manage_notes` upsert accepts an optional actor claim:

```json
{
  "actor": {
    "id": "impl-agent-42",
    "kind": "subagent",
    "parent": "orchestrator-1"
  }
}
```

Enable auditing in config to require it:

```yaml
auditing:
  enabled: true
```

When enabled, calls without actor claims are blocked before reaching the server. Query responses include the full delegation chain — which orchestrator dispatched which sub-agent, who wrote which note, who made which transition. Post-mortem debugging becomes a data query, not a conversation archaeology exercise.

### Session Continuity

No context rebuilding. One call recovers the full picture:

```
get_context(since="2025-01-15T09:00:00Z", includeAncestors=true)
```

Returns active items, recent transitions (with actor attribution), blocked items, stalled items with missing notes, and full ancestor chains. A new session has complete state in a single response.

### Notes as Structured Context

Notes provide targeted, phase-specific documentation attached to work items. An implementation agent reads a concise requirements note scoped to its task rather than scanning broader project context.

Notes are keyed, role-scoped, and queryable:

```
query_notes(itemId="<uuid>", role="work", includeBody=false)
```

Metadata-only queries (`includeBody=false`) let agents check what exists without paying the token cost of reading every note body.

---

## Quick Start

**Prerequisite**: [Docker](https://www.docker.com/products/docker-desktop/) installed and running.

### 1. Pull the image

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

### 2. Register with your MCP client

**Claude Code (recommended):**

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

**Any MCP client** — add to `.mcp.json` in your project root:

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

Restart your client. The server auto-initializes on first run — no setup required.

### 3. Enable workflow schemas (optional)

Mount your project's config to activate gate enforcement:

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

Without schemas, all 13 tools work in schema-free mode — no gates, no required notes. Add schemas when you want enforcement.

---

## Claude Code Plugin

The plugin adds workflow automation on top of the MCP server — skills, hooks, and an orchestrator output style.

**Install:**

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

**What it adds:**

| Layer | What it does |
|-------|-------------|
| **Skills** | Slash commands for common workflows — `/task-orchestrator:create-item`, `/task-orchestrator:manage-schemas`, `/task-orchestrator:quick-start` |
| **Hooks** | Automatic context injection at session start, plan mode integration, sub-agent context handoff, auditing enforcement |
| **Output style** | Workflow Orchestrator mode — Claude plans, delegates to sub-agents, and tracks progress without writing code directly |

The MCP server works without the plugin. The plugin makes it seamless with Claude Code.

---

## 13 MCP Tools

| Category | Tools | Purpose |
|----------|-------|---------|
| **Graph** | `manage_items`, `query_items`, `create_work_tree`, `complete_tree` | Build and query the work item hierarchy |
| **Notes** | `manage_notes`, `query_notes` | Persistent phase-scoped documentation |
| **Dependencies** | `manage_dependencies`, `query_dependencies` | Typed edges with pattern shortcuts (linear, fan-out, fan-in) |
| **Workflow** | `advance_item`, `get_next_status`, `get_context`, `get_next_item`, `get_blocked_items` | Trigger-based transitions with gate enforcement and dependency validation |

Every tool supports short hex ID prefixes — `advance_item(itemId="a3f2")` instead of full UUIDs.

---

## What It Looks Like in Practice

```
Morning — new session, new agent, zero context:

Agent: get_context(since="2025-01-14T17:00:00Z")
       → 2 items in work, 1 blocked, 1 stalled (missing implementation-notes)
       → Recent transitions show orchestrator-1 dispatched 3 sub-agents yesterday
       → Full ancestor chains: "Auth Feature > Login API > Input validation"

Agent: advance_item(trigger="start", itemId="a3f2",
         actor={ id: "morning-agent", kind: "subagent", parent: "orchestrator-1" })
       → Error: "Gate check failed: required notes not filled for queue phase: requirements"

Agent: manage_notes(upsert, itemId="a3f2", key="requirements",
         body="Validate email format, enforce password complexity...",
         actor={ id: "morning-agent", kind: "subagent" })
       → Upserted. guidancePointer: null, noteProgress: { filled: 1, remaining: 0, total: 1 }

Agent: advance_item(trigger="start", itemId="a3f2",
         actor={ id: "morning-agent", kind: "subagent" })
       → queue → work. No context rebuilding. No conversation replay.
       → Actor recorded. Traceable. Accountable.
```

---

## Documentation

| Resource | What's there |
|----------|-------------|
| **[Quick Start Guide](current/docs/quick-start.md)** | Full setup walkthrough with first work item |
| **[API Reference](current/docs/api-reference.md)** | All 13 tools — parameters, response shapes, actor attribution |
| **[Workflow Guide](current/docs/workflow-guide.md)** | Schemas, phase gates, dependencies, lifecycle modes |
| **[Integration Guides](current/docs/integration-guides/index.md)** | 6 tiers from bare MCP to self-improving orchestration |
| **[Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)** | Full documentation hub |
| **[Changelog](CHANGELOG.md)** | Release history |
| **[Contributing](CONTRIBUTING.md)** | Developer setup and contribution process |

---

## Technical Stack

- **Kotlin 2.2.0** with Coroutines
- **SQLite + Exposed ORM** — zero-config persistent storage
- **Flyway Migrations** — versioned schema management
- **MCP SDK 0.9.0** — STDIO and HTTP transport
- **Docker** — one-command deployment

Clean Architecture (Domain > Application > Infrastructure > Interface) with comprehensive test coverage.

---

## License

[MIT License](LICENSE) — Free for personal and commercial use.
