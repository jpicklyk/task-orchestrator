# 0011 No synchronisation with outside tools

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** journey discussion on incremental delivery; [user-journey-map.html](../user-journey-map.html) "A node's content: internal or referenced"

## Decision

Task Orchestrator never mirrors items into, or reads changes back from, outside tools such as markdown files, GitHub, Jira or Linear. When a schema keeps content outside, its items stay sparse: a short note holds a reference to the outside source, and a skill bound in the schema (a note's `skill:` or a seat's `skills:`) writes the outside copy. With no such schema, everything lives in the graph. Nodes show current state; their content lists the decisions that shaped them.

## Context

Agile teams keep stories in other tools, and decision records may live in files or trackers. Keeping two systems in step is a large, error-prone problem the tool does not need to own. The schema already defines the journey and can bind the skill that does the writing.

## Alternatives rejected

- **Two-way sync adapters per tool.** High cost, endless edge cases, and two sources of truth.
- **Note history inside Task Orchestrator to track mutation.** Not needed: nodes are current state, and the server's decision records keep their own trail.

## Consequences

- A gate checks only that the reference note is filled, not what the outside content says.
- An edit made directly in the outside tool is invisible to Task Orchestrator, by design.
- Exporting decision records later is an adapter off the server's `decision.*` events, not sync; it is not built now.
