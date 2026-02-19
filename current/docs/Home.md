# MCP Task Orchestrator

Stop losing context. Start building faster.

MCP Task Orchestrator is an open-source MCP server that gives AI agents persistent, structured task tracking across sessions. Built around a unified WorkItem graph with Note attachments and Dependency edges, it keeps context lean while making complex project work visible. v3 is a ground-up rewrite with 13 tools, role-based workflow, and note schema gating.

---

## What's New in v3

- Unified WorkItem model — one entity type at flexible depth (0-3), replacing separate Project/Feature/Task distinctions
- 13 tools with graph-aware queries (`includeAncestors`, `includeChildren`) that eliminate sequential parent-walk calls
- Role-based workflow: `queue` -> `work` -> `review` -> `terminal` with named triggers (`start`, `complete`, `block`, `hold`, `resume`)
- Note schemas — per-tag documentation requirements that gate phase transitions before an item can advance
- Dependency patterns: `linear`, `fan-out`, `fan-in` with `BLOCKS`, `IS_BLOCKED_BY`, and `RELATES_TO` edge types
- `create_work_tree` for atomic hierarchy creation; `complete_tree` for batch topological completion

---

## Quick Install

Run this once in your terminal to register the server with Claude Code:

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

After running, restart Claude Code and run `/mcp` to verify the connection. You should see `mcp-task-orchestrator` listed as connected with all 13 tools available.

---

## Documentation

| Guide | Description |
|-------|-------------|
| [Quick Start](quick-start) | Docker setup, first work item, note schemas, key concepts |
| [API Reference](api-reference) | All 13 MCP tools — parameters, response shapes, and examples |
| [Workflow Guide](workflow-guide) | Role lifecycle, triggers, note schemas, dependency patterns, cascade behavior |

---

## Links

- [GitHub Repository](https://github.com/jpicklyk/task-orchestrator)
- [Container Registry (ghcr.io)](https://github.com/jpicklyk/task-orchestrator/pkgs/container/task-orchestrator)
- [Changelog](https://github.com/jpicklyk/task-orchestrator/blob/main/CHANGELOG.md)
