# 0017 The orchestrator binding sits at config root and trait level

**Status:** Accepted | **Date:** 2026-11-26 | **Source:** Boundary analysis 2026-11-26, owner answers (plans/boundaries/README.md, not tracked), decision 1 as revised after the simplicity review; companion to [0016](0016-seat-capability-and-orchestrator-notes.md)

**Amended 2026-11-26:** the block is advertised by its own feature string instead of the seat-model change set's one flag. Why: [0016](0016-seat-capability-and-orchestrator-notes.md) now advertises one string per facet, so the binding gets its own when it lands.

## Decision

The rules and skills a project binds to the orchestrator are declared in an `orchestrator:` block (`rules`, `skills`) at config root and on a trait, outside `seats`. The root list replaces the plugin's orchestrator defaults; a trait's list adds to it for an item that carries the trait; a schema reaches a trait's binding through `default_traits`; `protocol.orchestrator` is always given first and no list removes it. The block is not a seat and never makes a type seat-aware. Whether it also binds a knowledge session's agent is held until there is data.

## Context

The orchestrator's own conduct (orchestration discipline, closure notes, feature closure, and this repository's merge gate, changelog and review fan-out) reaches it today only as prose spread over the SessionStart hook, the background orchestrator's brief and the `implement` and run-wave skills. No config binds it, and the plugin's role function returns nothing for `orchestrator` (`claude-plugins/task-orchestrator/hooks/lib/seat-rules.mjs:61`). Binding is a schema concept ([0018](0018-content-placement.md)), so a project's process for the orchestrator belongs in config it can override, like a seat's `rules:` and `skills:`.

The boundary analysis's journey overlay found the block load-bearing: it is the only seam that reaches the main session before an item exists, where the planning method and the shape classification run (six journey steps depend on it). A seat binding cannot reach that point, since seats live on a type.

A schema level was dropped after the simplicity review: a type that needs extra orchestrator conduct lists a trait in `default_traits`, which already exists, so a third merge level buys nothing.

## Alternatives rejected

- **The reserved `orchestrator` seat entry carries the rules and skills** (a phase-less seat). It makes conduct for the actor a seat entry, switches seat enforcement on for a seat-less type, and binds nothing before an item exists. Rejected with the orchestrator seat itself ([0016](0016-seat-capability-and-orchestrator-notes.md)).
- **Root, schema and trait levels** (the first answer to decision 1). The schema level duplicates what `default_traits` already reaches.
- **Plugin settings only** (`extensions.claude-code.orchestrator`). Binding is a schema concept, and the server could not serve the keys to another client.
- **A matching `director:` block.** The director's conduct stays in the director skill and the guard hook ([0002](0002-director-on-orchestrator-tools.md), [0014](0014-director-reads-skills-runs-plugin-scripts.md)); nothing asks for a binding.

## Consequences

- The server parses and serves the block (JSON Schema, key catalog, `config` `effective`, `items_read` `schema`) behind its own advertised feature string, when it lands (0016 advertises its facets one string each, P9); it never enforces it.
- The plugin's rule table gains an `orchestrator` row with `protocol.orchestrator` and the orchestrator's defaults. The plugin delivers one resolved rules and skills line at SessionStart, in the background orchestrator's brief and in the run plan.
- The dogfood config moves this repository's orchestrator rules from skill prose to the root block, or to the trait that needs them (for example `arbitration` on `needs-test-author`). A root list repeats any plugin default it wants to keep, since it replaces them.
- The design is in [seat-model.md](../seat-model.md); where the block sits among the config layers is in [config-layering.md](../config-layering.md).

## Reopen when

A knowledge-base user needs bound conduct for the agent that keeps the knowledge base (the held question: "orchestrator" does not name that agent today); or a project needs orchestrator conduct per type that a trait in `default_traits` cannot express.
