# Tier 2: CLAUDE.md-Driven Workflow

## What You Get

- Consistent agent behavior without installing a plugin
- Workflow conventions embedded in project instructions
- Team-shareable via checked-in `CLAUDE.md`
- Personal overrides via gitignored `CLAUDE.local.md`

## Prerequisites

- [Tier 1: Bare MCP](bare-mcp.md) setup complete
- [Claude Code](https://claude.ai/code) CLI installed

## How CLAUDE.md Works

Claude Code loads `CLAUDE.md` from your project root at the start of every session. Instructions in this file act as persistent context — the agent reads them before responding to any prompt.

- **`CLAUDE.md`** — checked into source control. Shared with all team members who clone the repo.
- **`CLAUDE.local.md`** — gitignored. Personal conventions layered on top of the shared file.

This means you can embed MCP workflow conventions once and have them apply to every session, for every team member.

The snippets below name 4.0 tools, selects and ops. How a call is shaped (`select` for reads, `ops[]` for writes, the profile a client connects with, `describe: true`) is explained once in [Calling the tools](../workflow-guide.md#calling-the-tools); runs, seats and decisions are in [Workflow Guide section 12](../workflow-guide.md#12-runs-seats-and-decisions).

## Example CLAUDE.md Snippets

### Workflow Protocol

Add this block to your project's `CLAUDE.md`:

```markdown
## MCP Task Orchestrator Conventions

- Use `items_write` with the `create_tree` op to create features with child items - never create items one at a time. Give every item a `type` declared in the project config
- Use the `advance` tool's `transition` op for all role transitions - never change a role through `items_write` `update`
- Read `items_read` with `select: "context"` and the item's `itemId` before starting work on it, to check gate status and expected notes
- Read `items_read` with `select: "context"` and no `itemId` at session start to see active and stalled work
- Use `items_read` with `select: "next"` when uncertain what to work on next
- Fill required notes with `notes_write` (`op: "upsert"`) before attempting to advance phases
- Use `items_read` with `select: "blocked"` to check for dependency blockers before starting an item
- Send `{"describe": true}` to any tool when unsure of a field name
```

### Session Resume

```markdown
## Session Start

At the beginning of each session, read `items_read` with `select: "context"` (no `itemId`) to see what's active, blocked, or stalled.
If resuming from a previous session, read `items_read` with `select: "history"` and `since: "<last-session-timestamp>"` to see
recent transitions.
```

### Dependency Awareness

```markdown
## Dependencies

Before starting any item, read `items_read` with `select: "blocked"` to confirm no upstream blockers.
When completing an item, check the `unblocked` field in the `advance` `transition` result
to see what's now ready to start.
```

### Role Semantics

```markdown
## Work Item Roles

- **queue** — planned but not started. Fill required queue-phase notes here.
- **work** — actively being implemented. Fill implementation notes.
- **review** — implementation complete, under review. Fill review notes.
- **terminal** — done. Items here are complete, cancelled, or archived.
- **blocked** — paused due to external dependency or manual hold.
```

## CLAUDE.local.md for Personal Conventions

Personal preferences that shouldn't be shared with the team:

```markdown
## My Preferences

- Default priority for new items: high
- When I say "TO", I mean Task Orchestrator
- Preferred complexity range: 3-7 (skip trivial and overly complex items)
- When creating features, always add a "test-strategy" child item
```

## Team Scenario

A typical team setup:

1. **`CLAUDE.md`** (checked in): MCP workflow conventions, project-specific tool patterns, shared terminology
2. **`CLAUDE.local.md`** (gitignored): Personal shortcuts, preferred priorities, individual work style
3. A new team member clones the repo and gets all MCP conventions automatically

The agent reads both files, with `CLAUDE.local.md` layered on top. No plugin installation required — the conventions travel with the repo.

## Combining with Note Schemas

CLAUDE.md can instruct agents to fill specific note keys even without schema gating:

```markdown
## Note Conventions

When creating a feature item, always fill these notes:
- `feature-summary` (role: queue) — what the feature must do
- `implementation-notes` (role: work) — how you built it
- `review-checklist` (role: review) — test output and coverage
```

This is convention-based — the agent follows the instructions but nothing enforces them. For enforcement, add [Note Schemas](note-schemas.md) (Tier 3).

## When to Level Up

**Signal:** You want automated quality gates — required documentation before items can advance, not just conventions the agent might forget.

**Next:** [Note Schema Gating](note-schemas.md) — add `.taskorchestrator/config.yaml` to enforce required notes at each phase.

**Alternative signal:** You want skill-based automation (plan-mode pipeline, subagent protocols) without intermediate schema setup.

**Next:** [Plugin: Skills and Hooks](plugin-skills-hooks.md) — install the Claude Code plugin for automated workflows.
