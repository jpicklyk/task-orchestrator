---
name: setup-instructions
description: Generate a CLAUDE.md instruction block for any project using MCP Task Orchestrator. Use when setting up Task Orchestrator in a new project, or when a user asks how to configure their AI agent for MCP workflow integration.
user-invocable: true
---

# Setup Instructions

One-liner: Generate and install a CLAUDE.md instruction block that teaches AI agents how to use MCP Task Orchestrator effectively.

## Prerequisites

- MCP Task Orchestrator server must be configured in `.mcp.json` or Claude Code MCP settings
- A project CLAUDE.md file must exist (or will be created)

## Steps

1. **Check for existing project** — Read the project's CLAUDE.md and look for a `## Project:` or `## MCP Task Orchestrator` header with a UUID. If found, offer to update the existing block rather than creating a duplicate.

2. **Resolve or create the MCP project** — If no project UUID exists in CLAUDE.md:
   - Ask the user for a project name using AskUserQuestion
   - Create the project: `manage_container(operation="create", containerType="project", name="...", summary="...")`
   - Capture the returned UUID

3. **Generate the CLAUDE.md block** — Output the block below with `{name}` and `{uuid}` replaced with actual values. Present it to the user as a markdown code fence they can paste into their CLAUDE.md.

4. **Offer to write** — Ask if the user wants you to append the block to their CLAUDE.md directly, or if they prefer to paste it manually.

## CLAUDE.md Block Template

The following block is the output. Replace `{name}` with the project name and `{uuid}` with the project UUID.

---

```markdown
<!-- mcp-task-orchestrator-setup: v1 -->
## MCP Task Orchestrator — Project: {name} (`{uuid}`)

All features and tasks belong to this project. Always pass `projectId` when creating features or standalone tasks so they remain queryable.

### Workflow Rules

1. **Status changes** — Use `request_transition(trigger=start|complete|cancel|block|hold)`. For batch changes, use the `transitions` array parameter. The `setStatus` operation was removed in v2 — use `request_transition` exclusively for all status changes.

2. **Template discovery** — Before creating any task or feature, run `query_templates(operation="list", targetEntityType="TASK"|"FEATURE", isEnabled=true)` and include `templateIds` in the create call.

3. **Post-transition handling** — After every `request_transition`, check the response for:
   - `cascadeEvents` — parent entities that should advance (act on them)
   - `unblockedTasks` — downstream tasks now available to start
   - Flow context (`activeFlow`, `flowSequence`, `flowPosition`)

4. **Token-efficient queries** — Default to `query_container(operation="overview")` for status checks and dashboards. Use `get` only when you need section content. Use `export` for full markdown snapshots before completion or archival.

5. **Work selection** — Use `get_next_task(projectId="{uuid}")` for dependency-aware, priority-sorted recommendations instead of manual searching. Use `get_blocked_tasks` to identify bottlenecks.

6. **Completion requirements** — Tasks: summary populated, dependencies resolved, required sections filled. Features: all child tasks in terminal status (completed or cancelled). Projects: all features completed.

### Status Flows (Tag-Driven)

Tags applied at creation time select which status flow an entity follows:

| Entity | Tags | Flow |
|--------|------|------|
| Task | _(default)_ | backlog → pending → in-progress → testing → completed |
| Task | `bug`, `bugfix`, `fix` | pending → in-progress → testing → completed |
| Task | `documentation`, `docs` | pending → in-progress → in-review → completed |
| Task | `hotfix`, `emergency` | in-progress → testing → completed |
| Feature | _(default)_ | draft → planning → in-development → testing → validating → completed |
| Feature | `prototype`, `poc`, `spike` | draft → in-development → completed |
| Feature | `experiment`, `research` | draft → in-development → archived |

### Completion Cleanup

When a feature reaches terminal status (completed/archived), its child tasks are **automatically deleted** — including their sections and dependencies. Tasks tagged `bug`, `bugfix`, `fix`, `hotfix`, or `critical` are retained. Use `query_container(operation="export")` on a feature BEFORE completing it to preserve a full markdown snapshot of all task content.

### Dependency Batch Creation

Use `manage_dependencies` with a `dependencies` array or pattern shortcuts (`linear`, `fan-out`, `fan-in`) for creating multiple dependencies at once. Avoid creating them one at a time.

### Session Start

Begin each session by checking project state:
```
query_container(operation="overview", containerType="project", id="{uuid}")
```

### MCP Resources (Deep Reference)

For detailed guidance beyond these rules, read these MCP resources:
- `task-orchestrator://guidelines/usage-overview` — decision framework for when to use Task Orchestrator
- `task-orchestrator://guidelines/template-strategy` — template discovery patterns and selection trees
- `task-orchestrator://guidelines/task-management` — intent recognition and 6 executable workflow patterns
- `task-orchestrator://guidelines/workflow-integration` — status flows, verification gates, update efficiency
- `task-orchestrator://docs/tools/{tool-name}` — per-tool documentation (13 tools)
```

---

## Notes

- The block is **self-contained** — it works for any MCP client, not just Claude Code
- The `<!-- mcp-task-orchestrator-setup: v1 -->` comment is a version marker — the MCP server's `instructions` field tells agents to check for it and update when a newer version ships
- If the user also installs the Task Orchestrator plugin, the plugin's skills and hooks provide additional automation (template enforcement, planning workflow, subagent context injection) on top of this base block
- The block should be placed near the top of CLAUDE.md so it's always in context
- For projects with multiple MCP servers, this block can coexist with other integration instructions
