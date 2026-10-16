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

- **Schemas** = rules defined in `.taskorchestrator/config.yaml`. They specify which notes must exist at each phase. Never stored in the database.
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

See [Quick Start](../quick-start.md) Step 8 for the full Docker mount example. If you work across
multiple projects, `/task-orchestrator:configure-server` sets up `config-sync` instead, which
hot-reloads each project's config without a read-only mount or a restart.

### Cache Invalidation

The server caches schemas on first access. After editing `config.yaml`, reconnect the MCP server by running `/mcp` in Claude Code and toggling the connection, or restart the container.

---

## Schema Field Reference

```yaml
work_item_schemas:
  <type-key>:
    notes:
      - key: <note-key>
        role: <queue|work|review>
        required: <true|false>
        description: "<Short description of expected content.>"
        guidance: "<Optional longer authoring instructions for agents.>"
```

(The legacy `note_schemas:` flat-list form is still accepted; it cannot appear in the same document as `work_item_schemas`. The full key list is in the [config reference](../config-reference.md).)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `key` | string | yes | Unique identifier for this note within the schema. Used as the `key` field in `notes_write`. |
| `role` | string | yes | Phase this note belongs to: `queue`, `work`, or `review`. |
| `required` | boolean | no | Whether this note must be filled before advancing past this phase. Optional notes never block transitions. |
| `description` | string | no | Short description of expected content. Read via `items_read` `select: "schema"` - the keys-only `schema` list of `context` does not carry it. |
| `guidance` | string | no | Longer authoring hint. Full text via `items_read` `select: "schema"` with `fields: ["guidance"]`; `context` and `advance` return only `guidanceKey` (a reference). |

---

## Multi-Phase Schema

A full lifecycle schema with gates at every transition:

```yaml
work_item_schemas:
  feature:
    notes:
      - key: feature-summary
        role: queue
        required: true
        description: "Problem statement, approach, and acceptance criteria."
        guidance: "Cover: what problem this solves, who benefits, acceptance criteria, and alternatives considered (minimum 2 real options plus 'do nothing')."
      - key: implementation-notes
        role: work
        required: true
        description: "What was built, deviations from the plan, decisions made."
        guidance: "Document decisions not captured in the feature-summary. Focus on what downstream agents or reviewers need to know."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate - plan alignment and test coverage."
        guidance: "Verify: what was built matches the feature-summary, tests cover the acceptance criteria."
```

With this schema:
- an `advance` `transition` with `trigger: "start"` from queue checks `feature-summary` exists, then advances to work
- `start` from work checks `implementation-notes` exists, then advances to review
- `start` from review checks `review-checklist` exists, then advances to terminal
- `trigger: "complete"` checks all required notes across all phases before advancing to terminal

When an item has no review-phase required notes, `start` from work advances directly to terminal, skipping review.

---

## The `default` Schema

The `default` key matches any item whose `type` has no schema of its own:

```yaml
work_item_schemas:
  feature:
    notes:
      # feature-specific notes
  default:
    notes:
      - key: session-tracking
        role: work
        required: true
        description: "What happened during this work session."
```

This means every item gets at least `session-tracking` enforced, even items whose type has no schema. Keep the `default` schema minimal - it applies to everything that falls through the type-matching rules.

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

## Type Matching Rules

- An item's `type` selects its schema: the root's declared type, then the server's, then the `default` schema of the root and of the server
- Each item matches at most one schema, and tags never select one
- Items with no matching schema and no default advance freely with no gates

See [Workflow Guide](../workflow-guide.md) Section 9 for the full matching specification.

---

## Example Scenario: Bug Fix with a Diagnosis Gate

Schema:

```yaml
work_item_schemas:
  bug-fix:
    notes:
      - key: diagnosis
        role: queue
        required: true
        description: "Reproduction steps, root cause, and fix approach."
        guidance: "Include: exact reproduction steps, root cause (file + function + condition), fix approach, alternatives considered, and regression test plan."
```

Workflow:

**1. Create the item:**

```json
{
  "ops": [
    { "op": "create", "title": "Fix login timeout", "type": "bug-fix" }
  ]
}
```

The answer is `data.results[0]` with the new `id` (a full UUID) and `version`. A create returns no schema decoration; read the notes the item expects with `items_read` `{"select": "schema", "itemId": "3d7e91ab"}` (the 8-hex short id of the new item).

**2. Check gate status:**

```json
{ "select": "context", "itemId": "3d7e91ab" }
```

Response shows `gateStatus.canAdvance: false`, `gateStatus.missing: ["diagnosis"]`, and `guidanceKey: "diagnosis"` (resolve the authoring instructions via `{"select": "schema", "itemId": "3d7e91ab", "fields": ["guidance"]}`).

**3. Try to advance (rejected):**

```json
{ "ops": [ { "op": "transition", "itemId": "3d7e91ab", "trigger": "start" } ] }
```

```json
{
  "ok": false,
  "data": { "results": [ { "ok": false, "error": { "code": "gate_blocked", "kind": "permanent", "message": "...", "detail": { "itemId": "3d7e91ab-5c0e-4f1a-9b2d-0a6e8c4d1f37", "role": "queue", "missing": [ { "key": "diagnosis", "role": "queue" } ] } } } ], "summary": { "ok": 0, "failed": 1 } },
  "error": { "code": "partial_failure", "kind": "permanent", "message": "..." },
  "meta": { "reqId": "k7f3q9ab" }
}
```

The element is rejected with `gate_blocked`, and `detail.missing` names `diagnosis`. (A write call answers `partial_failure` next to the per-element results when an element fails; `message` fields are abridged here.)

**4. Fill the note:**

```json
{
  "ops": [
    {
      "op": "upsert",
      "itemId": "3d7e91ab",
      "key": "diagnosis",
      "role": "queue",
      "body": "## Reproduction\n1. Log in with a valid account\n2. Leave session idle for 28 minutes\n3. Submit any form - request fails with 401\n\n## Root cause\nSessionManager.refreshToken() does not extend expiry when called within the last 5 minutes of the token window.\n\n## Fix approach\nExtend refresh window to 10 minutes. Considered: (1) always refresh on request - rejected, too many DB writes. (2) do nothing - rejected, breaks UX.\n\n## Regression test\nNew unit test for token refresh boundary conditions."
    }
  ]
}
```

**5. Advance (succeeds):**

```json
{ "ops": [ { "op": "transition", "itemId": "3d7e91ab", "trigger": "start" } ] }
```

The result element reports `previousRole: "queue"` and `newRole: "work"`.

---

## Building Schemas Interactively

If you have the plugin installed, the `/task-orchestrator:manage-schemas` skill walks you through creating, viewing, editing, and validating schemas without editing YAML directly. See [Plugin: Skills and Hooks](plugin-skills-hooks.md).

---

## When to Level Up

**Signal:** You want automated workflows — plan-mode integration, subagent protocols, skill-based commands — not just schema-enforced gates.

**Next:** [Plugin: Skills and Hooks](plugin-skills-hooks.md) — install the Claude Code plugin for automated orchestration.
