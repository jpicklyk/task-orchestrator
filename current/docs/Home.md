# MCP Task Orchestrator

Stop losing context. Start building faster.

MCP Task Orchestrator is an open-source MCP server that gives AI agents persistent, structured task tracking across sessions. Built around a unified WorkItem graph with Note attachments and Dependency edges, it keeps context lean while making complex project work visible. Version 4.0 is built around a compact, self-describing tool surface, role-based workflow, and note schema gating.

---

## Quick Install

If you work across multiple projects, the recommended setup is a persistent local server with the
REST API enabled (unauthenticated, loopback-only) — every project's `.taskorchestrator/config.yaml`
then syncs into it automatically via `config-sync`, with no per-project container or manual config
mounting:

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest

docker run -d --name mcp-task-orchestrator-http --restart unless-stopped \
  -v mcp-task-data:/app/data \
  -e MCP_TRANSPORT=http -e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true \
  -p 127.0.0.1:3001:3001 \
  ghcr.io/jpicklyk/task-orchestrator:latest
```

Register it in `.mcp.json` (HTTP shape — not an args array):

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

And export the client-side env so `config-sync` can find the server (optional: config-sync also reads the origin of a loopback http `mcp-task-orchestrator` registration in `.mcp.json`, and `/task-orchestrator:init` or `/task-orchestrator:init --user` may have written the URL to a project-level or user-level `client.json`; with none of these it silently no-ops):

```bash
export TASK_ORCHESTRATOR_API_URL=http://localhost:3001
```

> **SECURITY:** unauthenticated REST means anyone who can reach the port has full read/write/delete
> access. This is only safe because the port is published **loopback-only** (`-p 127.0.0.1:3001:3001`).
> Never publish it on `0.0.0.0` or a wider interface.

Once the plugin is installed, run `/task-orchestrator:configure-server` to walk through this (and
bearer-token or STDIO alternatives) interactively.

**Simpler alternative — STDIO, no config-sync.** If you don't want a persistent daemon, run this once
in your terminal to register a per-session container instead:

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

After running, restart Claude Code and run `/mcp` to verify the connection. You should see `mcp-task-orchestrator` listed as connected with its tools available.

---

## Documentation

| Guide | Description |
|-------|-------------|
| [Field Guide](https://jpicklyk.github.io/task-orchestrator/field-guide/) | Illustrated, partly interactive walkthrough of the core ideas. The best first read. |
| [Quick Start](quick-start.md) | Docker setup, first work item, note schemas, key concepts |
| [API Reference](api-reference.md) | Every MCP tool — parameters, response shapes, and examples |
| [Workflow Guide](workflow-guide.md) | Role lifecycle, dependency patterns, cascade behavior, runs, seats and decisions |
| [Fleet Deployment](fleet-deployment.md) | Multi-agent fleet operators: identity policy, SQLite tuning, capacity planning, claim disclosure, observability |
| [Upgrading from 3.x](fleet-deployment.md#upgrading-from-3x) | The procedure for moving a 3.x deployment and its data volume to 4.x |
| [Reporting](reporting.md) | The `report` tool: which select answers which question, how to read its bases, and what retention changes in an answer |
| [Integration Guides](integration-guides/index.md) | Progressive tiers from bare MCP tools to self-improving orchestration |

---

## Links

- [GitHub Repository](https://github.com/jpicklyk/task-orchestrator)
- [Container Registry (ghcr.io)](https://github.com/jpicklyk/task-orchestrator/pkgs/container/task-orchestrator)
- [Changelog](https://github.com/jpicklyk/task-orchestrator/blob/main/CHANGELOG.md)
