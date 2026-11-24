# 0013 No sibling rank until a need appears

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [architecture-breakdown.md](../architecture-breakdown.md) P8; Entry Skills Design server change table; [user-journey-map.html](../user-journey-map.html) gap "No backlog rank"

## Decision

No manual order among siblings (`work_items.rank`, `next` and `list` ordered by rank) is built now. Ordering stays priority and complexity, with dependency edges for real sequence.

## Context

The Entry Skills Design listed a sparse integer rank as a server change for the shape skill, while the journey map's gap list said to defer it until asked. Nobody has asked for it, and the owner has held further `next` ordering changes ([0004](0004-next-means-startable.md)).

## Alternatives rejected

- **Build rank with the other shape support (P8).** A new column, migration and ordering mode for a need no user has shown.

## Reopen when

A user's work needs an order that priority, complexity and dependency edges cannot express, or the owner reopens `next` ordering.
