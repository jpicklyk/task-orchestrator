# 0013 No sibling rank until a need appears

**Status:** Accepted | **Date:** 2026-11-24, amended 2026-11-26 | **Source:** [architecture-breakdown.md](../architecture-breakdown.md) P8; Entry Skills Design server change table; [user-journey-map.html](../user-journey-map.html) gap "No backlog rank". Amendment: Boundary analysis 2026-11-26, pass 10 decisions (plans/boundaries/README.md, not tracked), decision G11.

**Amended 2026-11-26:** "Reopen when" is reworded to a trigger anyone can observe. Why: "a need appears" and "the owner reopens" gave no one a sign to look for.

## Decision

No manual order among siblings (`work_items.rank`, `next` and `list` ordered by rank) is built now. Ordering stays priority and complexity, with dependency edges for real sequence.

## Context

The Entry Skills Design listed a sparse integer rank as a server change for the shape skill, while the journey map's gap list said to defer it until asked. Nobody has asked for it, and the owner has held further `next` ordering changes ([0004](0004-next-means-startable.md)).

## Alternatives rejected

- **Build rank with the other shape support (P8).** A new column, migration and ordering mode for a need no user has shown.

## Reopen when

An `agent-observation` or an improvement proposal records that the user had to name the next item because priority, complexity and dependency edges could not express the order, or shape is seen creating `blocks` edges that carry no real dependency, only order (the same trigger as [0004](0004-next-means-startable.md)).
