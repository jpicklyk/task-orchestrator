# Tier 3: Note Schema Gating

## What You Get

- Phase gate enforcement — items cannot advance until required notes are filled
- Per-type documentation requirements (different schemas for features, bug fixes, tasks)
- The `guidance` field - schema authors communicate authoring intent; agents resolve it via `guidanceKey` (a reference) and `items_read` `select: "schema"` (full text)
- Universal fallback via the `default` schema for types with no schema of their own

## Prerequisites

- [Tier 1: Bare MCP](bare-mcp.md) setup complete
- Write access to your project root (to create `.taskorchestrator/config.yaml`)

The examples are 4.0 tool-call request bodies; the profile table, `describe: true` and the response envelope are explained in [Calling the tools](../workflow-guide.md#calling-the-tools).

---

## Schemas vs Notes: Two Independent Systems

This is the most important concept to understand before configuring schemas.

- **Schemas** = rules defined in `.taskorchestrator/config.yaml` (imported into the server's config store at boot, or pushed per project root). They specify which notes must exist at each phase. They are not attached to items or notes.
- **Notes** = content that agents write via `notes_write`. Stored in the MCP database. Notes carry no reference to schemas.
- **They meet only at gate-check time:** when an `advance` `transition` fires, it fetches the item's notes from the database and checks them against schema requirements. If required notes are missing, the gate rejects the transition.

Items whose `type` matches no schema - and no `default` schema - advance freely with no gate enforcement.

---

## Your First Schema

Create `.taskorchestrator/config.yaml` in your project root:

```yaml
work_item_schemas:
  my-feature:
    notes:
      - key: task-scope
        role: queue
        required: true
        description: "What the feature must do."
      - key: implementation-notes
        role: work
        required: true
        description: "How you will build it."
```

This schema says: any item of `type: my-feature` must have a `task-scope` note filled before it can advance past queue, and an `implementation-notes` note before it can advance past work.

### Docker Mount

The MCP server reads config from the project directory. Mount it read-only alongside the data volume.

For a project-level `.mcp.json` (shareable with teammates):

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

Use an absolute host path. Claude Code's `.mcp.json` expands only environment variables (`${VAR}` and `${VAR:-default}`), so editor-style placeholders such as `${workspaceFolder}` are not substituted.

For a global CLI registration (user-level, not per-project):

```bash
claude mcp add-json mcp-task-orchestrator '{
  "command": "docker",
  "args": [
    "run", "--rm", "-i",
    "-v", "mcp-task-data:/app/data",
    "-v", "<absolute-path-to-project>/.taskorchestrator:/project/.taskorchestrator:ro",
    "-e", "AGENT_CONFIG_DIR=/project",
    "ghcr.io/jpicklyk/task-orchestrator:latest"
  ]
}'
```

Only the `.taskorchestrator/` folder is exposed — the server has no access to the rest of your project.

If you work across
multiple projects, `/task-orchestrator:configure-server` sets up `config-sync` instead, which
hot-reloads each project's config without a read-only mount or a restart.

### Cache Invalidation

A config file the server reads from disk (a mounted `.taskorchestrator/config.yaml`, or the file under `AGENT_CONFIG_DIR`) is imported at every boot, appending a config version only when it differs from the stored one, so an edit applies when the server process restarts: restart the container, or for a stdio server reconnect with `/mcp` in Claude Code (that starts a new process). When a REST API URL resolves and the file carries `project.rootId`, `config-sync` pushes the file to the server when you save it, and no reconnect or restart is needed; without either, it pushes nothing.

---

## The `guidance` Field

The `guidance` field is a communication channel from the schema author to the agent. `items_read` `context` and `advance` `transition` return only a `guidanceKey` - the key of the first unfilled required note with guidance - not the guidance text itself.

```yaml
- key: feature-summary
  role: queue
  required: true
  description: "Problem statement and approach."
  guidance: "Cover: problem statement, who benefits, acceptance criteria, alternatives (min 2 + 'do nothing'), blast radius, and test strategy."
```

Full guidance text comes from one place; everywhere else agents get only a reference:

| Tool | Field |
|------|-------|
| `items_read` `{"select": "context", "itemId": "..."}` | `guidanceKey` (top-level, reference only) - first unfilled required note for current phase |
| `advance` `transition` result | `guidanceKey` (reference only) - first unfilled required note in the new phase |
| `items_read` `{"select": "schema", "itemId": "...", "fields": ["guidance"]}` | `notes[].guidance` - **full text**, for all phases (also the resolution target for any `guidanceKey`) |
| Gate-failure error (`gate_blocked`) | `detail.missing[]` - the `key` and `role` of each missing note (no guidance text) |

`guidanceKey` is omitted when all required notes for the current phase are filled, or when no schema entry for the current phase defines a `guidance` value.

See [Workflow Guide](../workflow-guide.md) Section 5.5 for full guidance-resolution semantics.

---

## Building Schemas Interactively

If you have the plugin installed, the `/task-orchestrator:manage-schemas` skill walks you through creating, viewing, editing, validating, and auditing schemas without editing YAML directly. See [Plugin: Skills and Hooks](plugin-skills-hooks.md).

---

## When to Level Up

**Signal:** You want automated workflows — plan-mode integration, subagent protocols, skill-based commands — not just schema-enforced gates.

**Next:** [Plugin: Skills and Hooks](plugin-skills-hooks.md) — install the Claude Code plugin for automated orchestration.
