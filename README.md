# MCP Task Orchestrator

**Server-enforced workflow discipline for AI agents.**

Prompt-based frameworks hope the LLM follows instructions. This one blocks the call if it doesn't.

[![Version](https://img.shields.io/github/v/tag/jpicklyk/task-orchestrator?sort=semver)](https://github.com/jpicklyk/task-orchestrator/releases)
[![CI](https://github.com/jpicklyk/task-orchestrator/actions/workflows/test.yml/badge.svg)](https://github.com/jpicklyk/task-orchestrator/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

Task Orchestrator is an [MCP server](https://modelcontextprotocol.io) that gives AI coding agents a persistent work item graph with quality gates enforced by the server, not the prompt. It is built for developers running multi-agent or multi-session coding workflows: an orchestrator dispatching sub-agents, a fresh session picking up yesterday's work, or an autonomous loop draining a backlog. It ships as a Docker image, works with any MCP client, and has an optional [Claude Code plugin](#claude-code-plugin) that adds skills and hooks on top.

---

## The Problem

Multi-agent workflows need infrastructure the model doesn't provide. When an orchestrator dispatches sub-agents across sessions, there's no built-in way to enforce what documentation must exist before work starts, track which agent made which change, or guarantee dependency ordering across a work breakdown. These are structural concerns — they belong in the server, not in prompts.

Task Orchestrator puts them in the server. If a required design note isn't filled, `advance_item` returns an error naming the missing note. If an upstream dependency isn't complete, the transition is blocked. Every transition and note records who made it. A new session recovers the full state in one call instead of replaying a conversation. And the rules are YAML config, not hardcoded prompts — change them without changing code.

## What It Looks Like

```
Morning — new session, new agent, zero context:

Agent: get_context(since="2025-01-14T17:00:00Z")
       → 2 items in work, 1 blocked, 1 stalled (missing implementation-notes)
       → Recent transitions show orchestrator-1 dispatched 3 sub-agents yesterday
       → Full ancestor chains: "Auth Feature > Login API > Input validation"

Agent: advance_item(transitions=[{ itemId: "a3f2", trigger: "start",
         actor: { id: "morning-agent", kind: "subagent", parent: "orchestrator-1" } }])
       → Error: "Gate check failed: required notes not filled for queue phase: requirements"

Agent: manage_notes(operation="upsert", notes=[{ itemId: "a3f2", key: "requirements",
         body: "Validate email format, enforce password complexity...",
         actor: { id: "morning-agent", kind: "subagent" } }])
       → Upserted. noteProgress: { filled: 1, remaining: 0, total: 1 }

Agent: advance_item(transitions=[{ itemId: "a3f2", trigger: "start",
         actor: { id: "morning-agent", kind: "subagent" } }])
       → queue → work. Actor recorded. No context rebuilding.
```

---

## Quick Start

**Prerequisite**: [Docker](https://www.docker.com/products/docker-desktop/) installed and running.

### 1. Register the server

The simplest setup is a per-session STDIO container: no port, no daemon, no REST API. Add it to your project's `.mcp.json`:

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

Claude Code users can register the same shape from the CLI instead:

```bash
claude mcp add-json mcp-task-orchestrator '{
  "command": "docker",
  "args": ["run", "--rm", "-i", "-v", "mcp-task-data:/app/data", "ghcr.io/jpicklyk/task-orchestrator:latest"]
}'
```

Restart your client. The server creates its SQLite database on first run. Without a config file every tool works in schema-free mode: no gates, no required notes. Add schemas when you want enforcement.

### 2. Enable workflow schemas

Create `.taskorchestrator/config.yaml` in your project (see [Workflow Enforcement](#workflow-enforcement) for an example, or use the plugin's `/task-orchestrator:manage-schemas` skill) and mount that folder into the container:

```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "mcp-task-data:/app/data",
        "-v", "/absolute/path/to/your/project/.taskorchestrator:/project/.taskorchestrator:ro",
        "-e", "AGENT_CONFIG_DIR=/project",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

Use an absolute host path. Claude Code's `.mcp.json` expands only environment variables (`${VAR}` and `${VAR:-default}`), so editor-style placeholders such as `${workspaceFolder}` are not substituted. Only the `.taskorchestrator/` folder is exposed; the server has no access to the rest of your project.

### 3. Multi-project setup (HTTP + config-sync)

If you work across several repositories, run one persistent server with the REST API enabled. Each project's `.taskorchestrator/config.yaml` then syncs into it automatically through the plugin's `config-sync` hook: no per-project container, no manual mount, and config changes hot-reload without a restart. The plugin's `/task-orchestrator:configure-server` skill renders this setup interactively; the manual equivalent is:

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest

docker run -d --name mcp-task-orchestrator-http --restart unless-stopped \
  -v mcp-task-data:/app/data \
  -e MCP_TRANSPORT=http -e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true \
  -p 127.0.0.1:3001:3001 \
  ghcr.io/jpicklyk/task-orchestrator:latest
```

Register it in `.mcp.json` using the HTTP shape:

```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "type": "http",
      "url": "http://localhost:3001/mcp"
    }
  }
}
```

And export the client-side variable that tells `config-sync` where the server is (without it, config-sync silently does nothing):

```bash
export TASK_ORCHESTRATOR_API_URL=http://localhost:3001
```

> **SECURITY:** unauthenticated REST means anyone who can reach the port has full read/write/delete
> access. This is only safe because the port is published **loopback-only** (`-p 127.0.0.1:3001:3001`).
> Never publish it on `0.0.0.0` or a wider interface. For shared or networked deployments use bearer
> tokens or JWKS auth — see [Fleet Deployment](https://github.com/jpicklyk/task-orchestrator/wiki/fleet-deployment).

Prefer to run without Docker? [CONTRIBUTING.md](CONTRIBUTING.md) covers building the fat JAR from source.

---

## Core Capabilities

### The Phase Model

Every work item has a **role**: `queue` (not started), `work` (in progress), `review` (optional, opt-in per schema), `blocked` (an upstream dependency is unmet), and `terminal` (done or cancelled). Each role maps to one or more configurable statuses. Agents move items with `advance_item(trigger=...)` rather than editing status directly, and that call is where every gate is checked. Schemas attach note requirements to roles: a note declared with `role: queue` must exist before the item can leave the queue phase.

### Workflow Enforcement

Schemas define what agents must produce at each phase, and the server blocks progression until it's done. They also set a **planning floor**: when an agent enters plan mode, the schema tells it what documentation must exist before implementation can start, shaping the plan itself.

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

With this schema, `advance_item(trigger="start")` from queue requires `requirements` to be filled. The server returns an error listing exactly which notes are missing.

The `guidance` field provides authoring instructions surfaced at the right moment: when an agent is about to fill that note, the response carries the guidance as a `guidancePointer`. The `skill` field goes further, naming a skill the agent should invoke before filling the note so the evaluation follows a defined framework rather than freeform prose.

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

Every `feature-task` item automatically inherits the `security-assessment` requirement. Traits can also be applied per item via the `traits` parameter on `manage_items`, so a task touching authentication gets `needs-security-review` while a CSS cleanup doesn't.

### Persistent Work Item Graph

Everything is a **WorkItem** in a hierarchical graph. Items nest to any depth and are connected by typed dependency edges. Create an entire work breakdown atomically:

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

When `schema` reaches terminal, `api` is automatically unblocked. When all children complete, the parent cascades to terminal. Dependency ordering is enforced by the server, structurally, not by convention.

### Actor Attribution

Every `advance_item` transition and `manage_notes` upsert accepts an optional actor claim, and the server records it on the transition or note:

```json
{
  "actor": {
    "id": "impl-agent-42",
    "kind": "subagent",
    "parent": "orchestrator-1"
  }
}
```

Query responses include the full delegation chain: which orchestrator dispatched which sub-agent, who wrote which note, who made which transition. Post-mortem debugging becomes a data query rather than conversation archaeology.

To make the claim mandatory, enable actor authentication in config:

```yaml
actor_authentication:
  enabled: true
```

With the [Claude Code plugin](#claude-code-plugin) installed, a hook then rejects any write that lacks an actor before the call leaves the client. Other MCP clients need to supply the claim by convention. Optional JWKS verification checks that the claim is genuine; see [Fleet Deployment](https://github.com/jpicklyk/task-orchestrator/wiki/fleet-deployment).

### Session Continuity

No context rebuilding. One call recovers the full picture:

```
get_context(since="2025-01-15T09:00:00Z", includeAncestors=true)
```

Returns active items, recent transitions with actor attribution, blocked items, stalled items with their missing notes, and full ancestor chains. A new session has complete state in a single response.

### Notes as Structured Context

Notes are phase-specific documentation attached to work items. An implementation agent reads a concise requirements note scoped to its task instead of scanning broader project context.

Notes are keyed, role-scoped, and queryable:

```
query_notes(operation="list", itemId="<uuid>", role="work", includeBody=false)
```

Metadata-only queries (`includeBody=false`) let agents check what exists without paying the token cost of every note body.

### Full-Text Search

Search work items and notes by keyword. Results are relevance-ranked, so an agent looking for related work or picking up after a long gap gets the best matches first.

```
query_items(operation="search", query="authentication login")
query_notes(operation="search", query="password validation")
```

Search can be scoped to a subtree and filtered by role, tag, or priority, or run across the whole workspace.

### REST API

An optional HTTP layer (`API_ENABLED=true`) exposes items, notes, dependencies, transitions, config, and real-time SSE events to dashboards, CI systems, and operators. It supports static bearer tokens, JWKS JWT auth, and the unauthenticated loopback mode shown in the Quick Start. It also powers `config-sync`, so one server can serve many projects with per-project schemas. See the [REST API Reference](current/docs/api-rest.md).

### Design Philosophy

Task Orchestrator enforces workflow structure without imposing methodology. The server owns the guardrails: role transitions, dependency ordering, gate enforcement, and attribution. Agents own everything else. There are no mandatory planning ceremonies and no opinion on how agents approach implementation. Schemas, traits, and actor authentication are opt-in layers configured in `.taskorchestrator/config.yaml`. As models gain capabilities, the harness stays out of the way.

---

## Claude Code Plugin

The plugin adds workflow automation on top of the MCP server: skills, hooks, and an orchestrator output style.

**Install:**

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

**What it adds:**

| Layer | What it does |
|-------|-------------|
| **Skills** | Slash commands for common workflows, including `/task-orchestrator:quick-start`, `/task-orchestrator:configure-server`, `/task-orchestrator:manage-schemas`, and `/task-orchestrator:create-item` |
| **Hooks** | Context injection at session start, plan-mode integration, sub-agent context handoff, per-project config sync, and actor-attribution enforcement |
| **Output style** | Workflow Orchestrator mode: Claude plans, delegates to sub-agents, and tracks progress without writing code directly |

The MCP server works without the plugin. The plugin makes it seamless with Claude Code.

---

## MCP Tools

| Category | Tools | Purpose |
|----------|-------|---------|
| **Graph** | `manage_items`, `query_items`, `create_work_tree`, `complete_tree` | Build and query the work item hierarchy |
| **Notes** | `manage_notes`, `query_notes` | Persistent phase-scoped documentation |
| **Dependencies** | `manage_dependencies`, `query_dependencies` | Typed edges with pattern shortcuts (linear, fan-out, fan-in) and backlinks |
| **Workflow** | `advance_item`, `get_next_status`, `get_context`, `get_next_item`, `get_blocked_items`, `claim_item` | Trigger-based transitions with gate enforcement, dependency validation, and atomic find-and-claim for multi-agent fleets |
| **Project config** | `manage_project_config`, `manage_plan_documents` | Push per-project schema config and stash planning documents ahead of work item creation |

Item IDs accept short hex prefixes: `itemId="a3f2"` works in place of a full UUID once the prefix is unambiguous. See the [API Reference](https://github.com/jpicklyk/task-orchestrator/wiki/api-reference) for every tool's parameters and response shapes.

---

## Documentation

| Resource | What's there |
|----------|-------------|
| **[Quick Start Guide](https://github.com/jpicklyk/task-orchestrator/wiki/quick-start)** | Full setup walkthrough with first work item |
| **[API Reference](https://github.com/jpicklyk/task-orchestrator/wiki/api-reference)** | Every MCP tool: parameters, response shapes, actor attribution |
| **[REST API Reference](current/docs/api-rest.md)** | HTTP REST endpoints, DTOs, SSE, auth, merge-patch, ETag |
| **[Workflow Guide](https://github.com/jpicklyk/task-orchestrator/wiki/workflow-guide)** | Schemas, phase gates, dependencies, lifecycle modes |
| **[Fleet Deployment](https://github.com/jpicklyk/task-orchestrator/wiki/fleet-deployment)** | Multi-agent operators: REST API auth, actor identity, health checks, SQLite tuning, capacity planning |
| **[Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)** | Full documentation hub |
| **[Changelog](CHANGELOG.md)** | Release history |
| **[Contributing](CONTRIBUTING.md)** | Developer setup and contribution process |

---

## Technical Stack

- **Kotlin** with Coroutines
- **SQLite + Exposed ORM** with FTS5 full-text search, zero configuration
- **Flyway** versioned schema migrations
- **MCP Kotlin SDK** with STDIO and Streamable HTTP transports
- **Ktor** for the REST API and SSE
- **Docker** for one-command deployment

Clean Architecture (Domain > Application > Infrastructure > Interface) with an extensive JUnit 5 test suite.

---

## License

[MIT License](LICENSE) — Free for personal and commercial use.
