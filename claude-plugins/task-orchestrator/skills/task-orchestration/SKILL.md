---
name: task-orchestration
description: Manage tasks using MCP Task Orchestrator tools. Use when creating, updating, completing, or tracking tasks through the task lifecycle.
---

# Task Orchestration

Tasks are the primary work items, organized under Features and Projects. Every task must have at least a `projectId` so it remains queryable — tasks without a project or feature become orphaned and invisible to project-scoped queries.

## Project Initialization

If no project exists yet, create one before any other work:
1. `manage_container(operation="create", containerType="project", name="...", summary="...")`
2. Store the project UUID in the repository's `CLAUDE.md` (e.g., `## Project: <name> (<uuid>)`) so future sessions can reference it without querying.
3. All subsequent features and standalone tasks must reference this `projectId`.

## Core Workflow

1. **Discover templates** with `query_templates(targetEntityType="TASK")` before creating tasks
2. **Create tasks** with `manage_container(operation="create", containerType="task", containers=[...])` — pass `templateIds` to auto-create documentation sections, `featureId` to group under a feature, and `tags` for workflow selection and filtering. Use the `containers` array for batch creation (even for single items).
3. **Progress status** with `request_transition(trigger="start")`. See `status-progression` skill for flows and triggers.
4. **Complete** with `request_transition(trigger="complete")` — check sections are filled and dependencies resolved first.

## Tagging

Tags are comma-separated strings passed via the `tags` parameter on create/update. Apply tags consistently — they drive workflow selection and enable filtering.

### Workflow Tags

These tags select which status flow a task follows (see `status-progression` skill for full flows):

| Tags | Effect |
|------|--------|
| `bug`, `bugfix`, `fix` | Bug fix flow (pending → in-progress → testing → completed) |
| `documentation`, `docs` | Documentation flow (pending → in-progress → in-review → completed) |
| `hotfix`, `emergency` | Hotfix flow — skips pending (in-progress → testing → completed) |

Without workflow tags, the default task flow applies: backlog → pending → in-progress → testing → completed.

### Organizational Tags

Use for filtering and categorization:
- **Functional area:** `authentication`, `api`, `database`, `ui`, etc.
- **Work type:** `action-item` (standalone cross-session items), `tech-debt`, `research`, `spike`
- **Priority signals:** `blocking`, `deferred`

Tags are searchable via `query_container(operation="search", tags="authentication")` and filterable in `query_sections(tags="requirements")`.

## Verification Gates

Templates with a Verification section (Task Implementation, Bug Investigation) auto-enable `requiresVerification: true` when applied. The completion workflow becomes:

1. Agent defines acceptance criteria in the Verification section as JSON: `[{"criteria": "...", "pass": false}, ...]`
2. Agent performs the work
3. Agent verifies each criterion and flips `pass` to `true`
4. `request_transition(trigger="complete")` checks all criteria pass

Set `requiresVerification: true` manually when creating tasks without templates that warrant verification (code changes, infrastructure, tests). Skip verification for planning, documentation, research, or configuration tasks.

## Delegation Patterns

### Implementation Work
1. Create task in MCP (`manage_container`)
2. Spawn subagent with Task tool — provide MCP task ID, scope, acceptance criteria, dependency context
3. On completion: transition MCP task via `request_transition`

### MCP Bulk Operations

Never make 3+ MCP calls in a single orchestrator turn. Delegate bulk work to a subagent:
- Creating multiple tasks, dependencies, or sections
- Applying templates + creating dependencies in one flow
- Bulk status updates or deletions

Handle directly (1-2 calls): single `query_container`, `get_next_task`, `request_transition`, or `manage_container`.

### Handoff Prompt Requirements

Every delegation prompt must include:
1. **Entity IDs** — all UUIDs the subagent needs
2. **Exact operations** — tool names and parameters, not vague instructions
3. **Return format** — what summary to bring back
4. **No ambient context** — subagents start fresh; include everything they need

### Decision Framework

- Persistent tracking needed → MCP task first, then delegate
- Quick one-off research → delegate directly, no MCP task
- Bulk MCP operations (3+ calls) → delegate to subagent
- Normal status progression → `request_transition` (handle directly)
- Planning decisions, status checks → handle directly (1-2 MCP calls)
- Code, tests, file changes → always delegate to subagent

## Partial Updates

**Never fetch an entity just to update it.** Use `manage_container(operation="update", containers=[{id, ...changedFields}])` with only the changed fields in the containers array. Token savings: 90-95% vs sending the full entity.

## Section Management

Sections hold detailed content (technical approach, test plan, notes). Templates auto-create them.

- **Read:** `query_sections` with `entityType` and `entityId`
- **Surgical edit:** `manage_sections(operation="updateText")` with `oldText`/`newText` — avoids rewriting entire content
- **Add/bulk create:** `manage_sections` operations `add` and `bulkCreate`

**Section tags** enable targeted retrieval: `query_sections(tags="requirements,api")` returns only sections matching those tags. Templates automatically set tags on created sections. Add custom tags via `manage_sections(operation="updateMetadata", tags="...")` for cross-entity filtering.

## Finding Work

- **Smart recommendation:** `get_next_task` — respects dependencies, sorts by priority then complexity (quick wins first)
- **Filtered search:** `query_container(operation="search")` — supports multi-value filters (`status="pending,in-progress"`) and negation (`status="!completed,!cancelled"`)
- **Export:** `query_container(operation="export")` — full markdown representation with all sections

### Query Operation Selection

| Operation | Returns | Use When |
|-----------|---------|----------|
| `overview` | Metadata + child counts, no sections | Status checks, dashboards — default choice for most queries |
| `get` | Full entity, optional sections via `includeSections` | Need to read or update section content |
| `search` | Minimal result set (id, title, status, priority) | Finding entities by criteria, filtering across containers |
| `export` | Complete markdown with all sections and children | Snapshot before feature completion, archival, sharing outside MCP |

Default to `overview` for token efficiency (85-90% reduction vs `get` with sections). Use `export` specifically before feature completion to preserve task details that cleanup will delete.

## Standalone Tasks vs Feature Tasks

| Scenario | Container Pattern | Tags |
|----------|-------------------|------|
| Implementation work for a feature | Feature task (`featureId` set) | Functional tags |
| Cross-session action item (deferred topic, follow-up) | Standalone task (`projectId` only, no `featureId`) | `action-item` |
| Tech debt or refactoring | Standalone task | `tech-debt`, `action-item` |
| Bug discovered during feature work | Feature task | `bug` (survives feature completion cleanup) |
| Standalone bug not tied to a feature | Standalone task | `bug` |

Standalone tasks are not affected by feature completion cleanup. Use them for items that should persist independently of any feature lifecycle.

## MCP Resources

For detailed reference beyond this skill, the MCP server provides on-demand guideline resources:
- `task-orchestrator://guidelines/usage-overview` — decision framework for when to use Task Orchestrator
- `task-orchestrator://guidelines/template-strategy` — template discovery patterns and selection trees
- `task-orchestrator://guidelines/task-management` — intent recognition and 6 executable workflow patterns
- `task-orchestrator://guidelines/workflow-integration` — status flows, verification gates, update efficiency
- `task-orchestrator://docs/tools/{tool-name}` — per-tool documentation (13 tools)

Reference these via @-mention in Claude Code or `ReadResource` in other MCP clients when you need deeper guidance on a specific tool or pattern.
