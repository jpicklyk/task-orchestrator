# MCP Tool API — Canonical Format Reference

This scoped CLAUDE.md governs all tool descriptions, parameter schemas, MCP Resource content, and code examples under this directory tree (`application/tools/`, `interfaces/mcp/`).

When editing tool descriptions, resource text, or examples, **always use these v2 formats**.

## manage_container

All operations use arrays. Even single-item operations wrap in an array.

```
# Create — containers array, shared defaults at top level
manage_container(
  operation="create",
  containerType="task",
  projectId="...",           # shared default
  featureId="...",           # shared default
  templateIds=["..."],       # shared default
  tags="tag1,tag2",          # shared default
  containers=[{title: "...", summary: "...", priority: "high"}]
)

# Update — containers array, each item has id
manage_container(
  operation="update",
  containerType="task",
  containers=[{id: "...", summary: "updated"}]
)

# Delete — ids array
manage_container(
  operation="delete",
  containerType="task",
  ids=["uuid-1", "uuid-2"]
)
```

**Never use:** inline entity fields at top level for create (e.g., `name="..."` outside `containers`).

## request_transition

Always use the `transitions` array. Even for a single transition.

```
request_transition(
  transitions=[{containerId: "...", containerType: "task", trigger: "start"}]
)

# Batch
request_transition(
  transitions=[
    {containerId: "...", containerType: "task", trigger: "complete", summary: "Done"},
    {containerId: "...", containerType: "feature", trigger: "complete"}
  ]
)
```

**Never use:** top-level `containerId`, `containerType`, `trigger` params (legacy, not in schema).

## manage_dependencies

Create uses `dependencies` array or `pattern` shortcuts. Never legacy single `fromTaskId`/`toTaskId` for create.

```
# Explicit edges
manage_dependencies(
  operation="create",
  dependencies=[
    {fromTaskId: "A", toTaskId: "B", type: "BLOCKS"},
    {fromTaskId: "B", toTaskId: "C"}
  ]
)

# Pattern shortcuts
manage_dependencies(operation="create", pattern="linear", taskIds=["A","B","C","D"])
manage_dependencies(operation="create", pattern="fan-out", source="A", targets=["B","C","D"])
manage_dependencies(operation="create", pattern="fan-in", sources=["B","C","D"], target="E")

# Delete — fromTaskId/toTaskId are for delete only
manage_dependencies(operation="delete", fromTaskId="A", toTaskId="B")
manage_dependencies(operation="delete", id="dep-uuid")
manage_dependencies(operation="delete", fromTaskId="A", deleteAll=true)
```

## Authoritative References

These two documents are the **external-facing API contracts**. Any change to tool descriptions, schemas, or examples in this directory tree MUST be reflected in both:

1. **Setup Instructions** — `interfaces/mcp/TaskOrchestratorResources.kt` → `addSetupInstructionsResource()`
   - Contains the agent instructions template that non-plugin agents install into their projects
   - Versioned via `SETUP_INSTRUCTIONS_VERSION` — bump when the template changes materially
   - Parallel copy in plugin skill: `claude-plugins/task-orchestrator/skills/setup-instructions/SKILL.md`
   - This is the **first thing new users see** — it must match the actual tool API exactly

2. **API Reference** — `docs/api-reference.md`
   - Complete MCP tools documentation with parameter tables, examples, and response shapes
   - This is the **comprehensive reference** for all 14 tools — tool descriptions are summaries of this

When modifying a tool's `description`, `parameterSchema`, or `outputSchema`, update both of these documents to stay in sync. If you're unsure whether a change is material enough to bump the setup instructions version, err on the side of bumping it — agents with stale instructions will auto-detect the mismatch.

## Cross-Reference Locations

The full set of locations that contain tool API examples. Check ALL of these when making format changes:

### Source of Truth (this directory tree)
| Location | What to check |
|----------|--------------|
| `application/tools/*/` | Tool `description` strings and `parameterSchema` |
| `interfaces/mcp/TaskOrchestratorResources.kt` | MCP Resource text content (4 guideline resources + setup instructions) |
| `interfaces/mcp/McpServer.kt` | Server instructions text |

### Downstream Consumers (must mirror source of truth)
| Location | What to check |
|----------|--------------|
| `docs/api-reference.md` | Complete tool documentation — parameter tables, examples, response shapes |
| `docs/tools/` | Per-tool deep-dive documentation |
| `docs/ai-guidelines.md` | AI usage guide with workflow examples |
| `docs/workflow-patterns.md` | End-to-end workflow examples |
| `docs/developer-guides/subagent-template.md` | Subagent delegation examples |
| `claude-plugins/task-orchestrator/skills/` | Plugin skill SKILL.md files |
| `CLAUDE.md` (project root) | Tool summary descriptions |
