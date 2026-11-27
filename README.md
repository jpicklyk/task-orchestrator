# MCP Task Orchestrator

**Server-enforced workflow discipline for AI agents.**

Prompt-based frameworks hope the LLM follows instructions. This one blocks the call if it doesn't.

[![Version](https://img.shields.io/github/v/tag/jpicklyk/task-orchestrator?sort=semver)](https://github.com/jpicklyk/task-orchestrator/releases)
[![CI](https://github.com/jpicklyk/task-orchestrator/actions/workflows/test.yml/badge.svg)](https://github.com/jpicklyk/task-orchestrator/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

Task Orchestrator is an [MCP server](https://modelcontextprotocol.io) that gives AI coding agents a persistent work item graph with quality gates enforced by the server, not the prompt. It is built for developers running multi-agent or multi-session coding workflows: an orchestrator dispatching sub-agents, a fresh session picking up yesterday's work, or an autonomous loop draining a backlog. It ships as a Docker image, works with any MCP client, and has an optional [Claude Code plugin](#claude-code-plugin) that adds skills and hooks on top.

> **New here? Start with the [illustrated field guide](https://jpicklyk.github.io/task-orchestrator/field-guide/).** It explains the ideas on this page in short visual pages, several of them interactive: fire triggers at a phase gate, click a work breakdown through its dependencies, and watch a schema resolve.

---

## The Problem

Multi-agent workflows need infrastructure the model doesn't provide. When an orchestrator dispatches sub-agents across sessions, there's no built-in way to enforce what documentation must exist before work starts, track which agent made which change, or guarantee dependency ordering across a work breakdown. These are structural concerns — they belong in the server, not in prompts.

Task Orchestrator puts them in the server. If a required design note isn't filled, `advance` returns an error naming the missing note. If an upstream dependency isn't complete, the transition is blocked. Every transition and note records who made it. A new session recovers the full state in one call instead of replaying a conversation. And the rules are YAML config, not hardcoded prompts - change them without changing code.

## What It Looks Like

```
Morning — new session, new agent, zero context:

Agent: items_read(select="context")
       → 2 items in work, 1 blocked, 1 stalled (missing implementation-notes)
Agent: items_read(select="history", since="2026-01-14T17:00:00Z")
       → Recent transitions show orchestrator-1 dispatched 3 sub-agents yesterday

Agent: advance(ops=[{ op: "transition", itemId: "a3f28c01", trigger: "start" }])
       -> gate_blocked: "Item a3f28c01-... is missing 1 required notes." (detail.missing: requirements)

Agent: notes_write(ops=[{ op: "upsert", itemId: "a3f28c01", key: "requirements", role: "queue",
         body: "Validate email format, enforce password complexity..." }])
       -> results[0]: { ok: true, id: "...", version: 1, created: true }

Agent: advance(ops=[{ op: "transition", itemId: "a3f28c01", trigger: "start" }])
       → queue → work. Caller recorded. No context rebuilding.
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

Create `.taskorchestrator/config.yaml` in your project (see [Note Schemas](current/docs/integration-guides/note-schemas.md) for an example, or use the plugin's `/task-orchestrator:manage-schemas` skill) and mount that folder into the container:

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

At every start the server imports the mounted file into its database (the `server-config-import` boot step) and then serves the stored copy; editing the file takes effect on the next restart. If several containers share one data volume but mount different files, the last one to boot wins; use step 3 for multi-project setups.

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

And, optionally, export the client-side variable that tells `config-sync` where the server is (config-sync also reads the origin of a loopback http `mcp-task-orchestrator` registration in `.mcp.json`; with neither, it silently does nothing):

```bash
export TASK_ORCHESTRATOR_API_URL=http://localhost:3001
```

Alternatively, a `client.json` (`apiUrl` only, no token) supplies the URL. Project-mode `/task-orchestrator:init` writes it beside the project config, only for a loopback server and only as a bare origin with no path, query or fragment (add `.taskorchestrator/client.json` to `.gitignore`, the URL is machine-specific); `/task-orchestrator:init --user` writes the user-level file, which works for any host. The order is the environment variable, then the project-level file, then the origin of the orchestrator's http(s) MCP registration (local, then project, then user scope; a project `.mcp.json` origin must be loopback), then the user-level file. `/task-orchestrator:init` sets up a project root for the current directory, and `/task-orchestrator:init --user` creates a personal root that serves every directory without its own config.

> **SECURITY:** unauthenticated mode (REST and `/mcp` alike) means anyone who can reach the port has full
> read/write/delete access, as the single `local` principal. This is only safe because the port is published **loopback-only** (`-p 127.0.0.1:3001:3001`).
> Never publish it on `0.0.0.0` or a wider interface. For shared or networked deployments use bearer
> tokens or JWKS auth — see [Fleet Deployment](https://github.com/jpicklyk/task-orchestrator/wiki/fleet-deployment).

Prefer to run without Docker? [CONTRIBUTING.md](CONTRIBUTING.md) covers building the fat JAR from source.

---

## Design Philosophy

Task Orchestrator enforces workflow structure without imposing methodology. The server owns the guardrails: role transitions, dependency ordering, gate enforcement, and attribution. Agents own everything else. There are no mandatory planning ceremonies and no opinion on how agents approach implementation. Schemas, traits, and actor authentication are opt-in layers configured in `.taskorchestrator/config.yaml`. As models gain capabilities, the harness stays out of the way.

---

## Claude Code Plugin

The plugin adds workflow automation on top of the MCP server: skills, hooks, and an orchestration context.

**Install:**

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

The MCP server works without the plugin. The plugin makes it seamless with Claude Code.

**Optional mod (early access):** [orchestration-mode](claude-plugins/orchestration-mode/README.md) shows on each session which work it drives and in which mode, so several open sessions stay easy to tell apart.

It is a standalone plugin, installed on its own. It needs the `task-orchestrator` plugin and a 4.x server: the band reads the server's REST API (the `apiUrl` setting), or the director MCP connection when `apiUrl` is empty. It is early access because it runs on Claude Code's early-access function-hooks API.

```
/plugin install orchestration-mode@task-orchestrator-marketplace
```

Illustrated: [Runs and Decisions](https://jpicklyk.github.io/task-orchestrator/field-guide/runs-and-decisions.html) steps through a server run, its seat tokens, run limits and decisions. [Orchestrating a Run](https://jpicklyk.github.io/task-orchestrator/field-guide/orchestrating-a-run.html) follows one feature from request to finished items. [Improvement Loop](https://jpicklyk.github.io/task-orchestrator/field-guide/improvement-loop.html) shows how friction noticed during work becomes a reviewed config change.

---

## Documentation

| Resource | What's there |
|----------|-------------|
| **[Field Guide](https://jpicklyk.github.io/task-orchestrator/field-guide/)** | Illustrated, partly interactive walkthrough of the core ideas. The best first read. |
| **[Quick Start Guide](https://github.com/jpicklyk/task-orchestrator/wiki/quick-start)** | Full setup walkthrough with first work item |
| **[API Reference](https://github.com/jpicklyk/task-orchestrator/wiki/api-reference)** | Every MCP tool: parameters, response shapes, actor attribution |
| **[Workflow Guide](https://github.com/jpicklyk/task-orchestrator/wiki/workflow-guide)** | Phase gates, dependencies |
| **[Fleet Deployment](https://github.com/jpicklyk/task-orchestrator/wiki/fleet-deployment)** | Multi-agent operators: REST and `/mcp` authentication, caller identity, health checks, SQLite tuning, capacity planning |
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

A layered architecture (Domain → Application → Infrastructure → Interfaces, enforced by Konsist layering and package-cycle tests, both with empty baselines) with an extensive JUnit 5 test suite.

---

## License

[MIT License](LICENSE) — Free for personal and commercial use.
