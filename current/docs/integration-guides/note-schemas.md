# Tier 3: Note Schema Gating

## What You Get

- Phase gate enforcement — items cannot advance until required notes are filled
- Per-tag documentation requirements (different schemas for features, bug fixes, tasks)
- The `guidance` field — schema authors communicate authoring intent to agents via `guidancePointer`
- Universal fallback via the `default` schema for untagged items

## Prerequisites

- [Tier 1: Bare MCP](bare-mcp) setup complete
- Write access to your project root (to create `.taskorchestrator/config.yaml`)

---

## Schemas vs Notes: Two Independent Systems

This is the most important concept to understand before configuring schemas.

- **Schemas** = rules defined in `.taskorchestrator/config.yaml`. They specify which notes must exist at each phase. Never stored in the database.
- **Notes** = content that agents write via `manage_notes`. Stored in the MCP database. Notes carry no reference to schemas.
- **They meet only at gate-check time:** when `advance_item` fires, it fetches the item's notes from the database and checks them against schema requirements. If required notes are missing, the gate rejects the transition.

Items with no matching schema tag — and no `default` schema — advance freely with no gate enforcement.

---

## Your First Schema

Create `.taskorchestrator/config.yaml` in your project root:

```yaml
note_schemas:
  my-feature:
    - key: requirements
      role: queue
      required: true
      description: "What the feature must do."
    - key: implementation-plan
      role: work
      required: true
      description: "How you will build it."
```

This schema says: any item tagged `my-feature` must have a `requirements` note filled before it can advance past queue, and an `implementation-plan` note before it can advance past work.

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
        "-v", "${workspaceFolder}/.taskorchestrator:/project/.taskorchestrator:ro",
        "-e", "AGENT_CONFIG_DIR=/project",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

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

See [Quick Start](../quick-start) Step 8 for the full Docker mount example.

### Cache Invalidation

The server caches schemas on first access. After editing `config.yaml`, reconnect the MCP server by running `/mcp` in Claude Code and toggling the connection, or restart the container.

---

## Schema Field Reference

```yaml
note_schemas:
  <schema-key>:
    - key: <note-key>
      role: <queue|work|review>
      required: <true|false>
      description: "<Short description of expected content.>"
      guidance: "<Optional longer authoring instructions for agents.>"
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `key` | string | yes | Unique identifier for this note within the schema. Used as the `key` parameter in `manage_notes`. |
| `role` | string | yes | Phase this note belongs to: `queue`, `work`, or `review`. |
| `required` | boolean | yes | Whether this note must be filled before advancing past this phase. Optional notes never block transitions. |
| `description` | string | yes | Short description of expected content. Shown in `get_context` gate status. |
| `guidance` | string | no | Longer authoring hint. Surfaced as `guidancePointer` when this note is the next unfilled required note. |

---

## Multi-Phase Schema

A full lifecycle schema with gates at every transition:

```yaml
note_schemas:
  feature:
    - key: specification
      role: queue
      required: true
      description: "Problem statement, approach, and acceptance criteria."
      guidance: "Cover: what problem this solves, who benefits, acceptance criteria, and alternatives considered (minimum 2 real options plus 'do nothing')."
    - key: implementation-notes
      role: work
      required: true
      description: "What was built, deviations from the plan, decisions made."
      guidance: "Document decisions not captured in the spec. Focus on what downstream agents or reviewers need to know."
    - key: review-checklist
      role: review
      required: true
      description: "Quality gate — plan alignment and test coverage."
      guidance: "Verify: what was built matches the specification, tests cover the acceptance criteria."
```

With this schema:
- `advance_item(trigger="start")` from queue checks `specification` exists, then advances to work
- `advance_item(trigger="start")` from work checks `implementation-notes` exists, then advances to review
- `advance_item(trigger="start")` from review checks `review-checklist` exists, then advances to terminal
- `advance_item(trigger="complete")` checks all required notes across all phases before advancing to terminal

When an item has no review-phase required notes, `start` from work advances directly to terminal, skipping review.

---

## The `default` Schema

The `default` key matches any item whose tags do not match any other schema key:

```yaml
note_schemas:
  feature:
    # feature-specific notes
  default:
    - key: session-tracking
      role: work
      required: true
      description: "What happened during this work session."
```

This means every item gets at least `session-tracking` enforced, even items with no matching tag. Keep the `default` schema minimal — it applies to everything that falls through the tag-matching rules.

---

## The `guidance` Field

The `guidance` field is a communication channel from the schema author to the agent. When an agent calls `advance_item` or `get_context`, the response includes a `guidancePointer` containing the guidance text for the first unfilled required note in the current phase.

```yaml
- key: specification
  role: queue
  required: true
  description: "Problem statement and approach."
  guidance: "Cover: problem statement, who benefits, acceptance criteria, alternatives (min 2 + 'do nothing'), blast radius, and test strategy."
```

The agent sees this guidance and knows exactly what to write. Guidance surfaces in four places:

| Tool | Field |
|------|-------|
| `get_context(itemId=...)` | `guidancePointer` (top-level) — first unfilled required note for current phase |
| `advance_item` response | `guidancePointer` — first unfilled required note in the new phase |
| `manage_notes(upsert)` response | `itemContext[itemId].guidancePointer` — updated after upsert |
| `manage_items(create)` response | `expectedNotes[].guidance` — per-note, for all phases |

`guidancePointer` is null when all required notes for the current phase are filled, or when no schema entry for the current phase defines a `guidance` value.

See [Workflow Guide](../workflow-guide) Section 5.5 for full `guidancePointer` semantics.

---

## Tag Matching Rules

- Items can have multiple tags (comma-separated string)
- The **first tag** that matches a schema key wins — each item matches at most one schema
- If no tag matches, the `default` schema applies (if defined)
- Items with no matching schema and no default advance freely with no gates

See [Workflow Guide](../workflow-guide) Section 9 for the full matching specification.

---

## Example Scenario: Bug Fix with a Diagnosis Gate

Schema:

```yaml
note_schemas:
  bug-fix:
    - key: diagnosis
      role: queue
      required: true
      description: "Reproduction steps, root cause, and fix approach."
      guidance: "Include: exact reproduction steps, root cause (file + function + condition), fix approach, alternatives considered, and regression test plan."
```

Workflow:

**1. Create the item:**

```json
manage_items(operation="create", items=[{
  "title": "Fix login timeout",
  "tags": "bug-fix"
}])
```

Response includes `expectedNotes` listing the `diagnosis` note with its guidance.

**2. Check gate status:**

```json
get_context(itemId="<uuid>")
```

Response shows `gateStatus.canAdvance: false`, `missing: ["diagnosis"]`, and `guidancePointer` with the authoring instructions.

**3. Try to advance (rejected):**

```json
advance_item(transitions=[{ "itemId": "<uuid>", "trigger": "start" }])
// Error: required notes not filled for queue phase: diagnosis
```

**4. Fill the note:**

```json
manage_notes(operation="upsert", notes=[{
  "itemId": "<uuid>",
  "key": "diagnosis",
  "role": "queue",
  "body": "## Reproduction\n1. Log in with a valid account\n2. Leave session idle for 28 minutes\n3. Submit any form — request fails with 401\n\n## Root cause\nSessionManager.refreshToken() does not extend expiry when called within the last 5 minutes of the token window.\n\n## Fix approach\nExtend refresh window to 10 minutes. Considered: (1) always refresh on request — rejected, too many DB writes. (2) do nothing — rejected, breaks UX.\n\n## Regression test\nNew unit test for token refresh boundary conditions."
}])
```

**5. Advance (succeeds):**

```json
advance_item(transitions=[{ "itemId": "<uuid>", "trigger": "start" }])
// Item moves queue → work
```

---

## Building Schemas Interactively

If you have the plugin installed, the `/task-orchestrator:manage-schemas` skill walks you through creating, viewing, editing, and validating schemas without editing YAML directly. See [Plugin: Skills and Hooks](plugin-skills-hooks).

---

## When to Level Up

**Signal:** You want automated workflows — plan-mode integration, subagent protocols, skill-based commands — not just schema-enforced gates.

**Next:** [Plugin: Skills and Hooks](plugin-skills-hooks) — install the Claude Code plugin for automated orchestration.
