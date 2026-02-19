# Clockwork (v2) — Archived Module

> **Status: DEPRECATED** — This module is the legacy v2 MCP Task Orchestrator.
> Active development is in the [`:current`](../current/) module (v3).

---

## What Is Clockwork?

Clockwork (v2) is the original MCP Task Orchestrator built around a hierarchical
**Project → Feature → Task** model with Sections, Templates, and a locking system.

Key characteristics:
- Entities: `Project`, `Feature`, `Task`, `Section`, `Template`, `Dependency`
- Sections attached to any entity (content blocks with role-based filtering)
- Template system for applying predefined section structures
- Session-based locking to prevent concurrent modification
- ~20 MCP tools (CRUD + workflow + template management)
- 14 Flyway migrations (SQLite schema, `data/tasks.db`)
- ~127 Kotlin source files, 1240+ tests

## Why Superseded?

v3 (Current) replaces Clockwork with a simpler, more powerful model:

| Clockwork (v2) | Current (v3) |
|----------------|--------------|
| Project/Feature/Task hierarchy | Unified `WorkItem` with depth (0–3) |
| Sections (free-form content blocks) | Notes (key-value, schema-validated) |
| Templates | Note schemas in `.taskorchestrator/config.yaml` |
| ~20 tools with inconsistent surface | 13 tools with consistent surface |
| Manual status strings | Role-based workflow (queue/work/review/terminal) |
| No gate enforcement | Gate-enforced phase transitions via note schemas |

---

## Build Instructions

**Build the Clockwork JAR:**
```bash
./gradlew :clockwork:jar
```
Output: `clockwork/build/libs/mcp-task-orchestrator-<version>.jar`

**Run locally:**
```bash
DATABASE_PATH=data/clockwork-tasks.db java -jar clockwork/build/libs/mcp-task-orchestrator-*.jar
```

**Build the Docker image (v2):**
```bash
# First build the clockwork JAR (not done by default CI build)
./gradlew :clockwork:jar

# Then build the Docker target (runtime-v2)
docker build --target runtime-v2 -t task-orchestrator:v2 .

# Run it
docker run --rm -i -v mcp-task-data-v2:/app/data task-orchestrator:v2
```

**Run Clockwork tests:**
```bash
./gradlew :clockwork:test
```

---

## Migration to v3

See the [Current (v3) documentation](../current/) for setup and migration guidance.

The v3 module is at `current/` and is the default build target in CI.
