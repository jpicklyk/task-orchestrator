# 0013 Sibling rank orders `next` among items of equal priority

**Status:** Accepted | **Date:** 2026-11-24, amended 2026-11-26 | **Source:** [architecture-breakdown.md](../architecture-breakdown.md) P8; Entry Skills Design server change table; [user-journey-map.html](../user-journey-map.html) gap "No backlog rank" and stage 7.4. Amendment: Boundary analysis 2026-11-26, pass 10 decisions (plans/boundaries/README.md, not tracked), decision G11, and pass 11 (plans/boundaries/11-next-ordering.md, not tracked), owner decision options A and E.

**Amended 2026-11-26:** "Reopen when" is reworded to a trigger anyone can observe. Why: "a need appears" and "the owner reopens" gave no one a sign to look for. Also amended 2026-11-26 (pass 11, owner decision): the record is reversed and retitled, from "No sibling rank until a need appears" to building a sibling rank, and its file is renamed from `0013-no-sibling-rank-yet.md`. Why: the trigger is visible in shipped plugin text. The dependency-manager skill mapped "A sequence A then B then C" to a `linear` chain of `blocks` edges, and it called `unblockAt: queue` "ordering only".

## Decision

Each item gets an optional integer `rank`, and `next`'s default order becomes: priority, then rank ascending with unranked items last, then complexity, then `createdAt`, then `id`. Rank only orders the items `next` may return. It never makes an item ineligible, never appears in `blocked`, and adds no edge and no gate. `oldest` and `newest` stay plain creation-time orders a caller chooses with `orderBy`.

## Context

Ids are random UUIDs, so siblings that tie on priority and complexity come back in an order unrelated to the plan, and creation time cannot repair it: one `create_tree` builds its items within a few milliseconds, so they can tie on `createdAt` as well. Journey 2 stage 7.4 ("Keep going") needs `next` to follow an order the user or the approved plan stated among items that do not depend on each other; value-first among items of equal priority is the same need. The only way to state such an order today is a `blocks` edge, which is the confusion this record removes. Ordering across a whole tree (finish one parent before the next) is traversal and stays held by [0004](0004-next-means-startable.md).

The variation is the per-item value, which is data, not config: the orchestrator or shape writes it from the user's words or the approved plan. A stated order among independent items is recorded as rank, and a real dependency as a `blocks` edge. A smaller rank comes first, ties are allowed, and sparse values (10, 20, 30) let an item be inserted without renumbering. `orderBy` per call stays the input for a caller that wants another order, so no config key is added.

## Alternatives rejected

- **An ordering-only edge type (`precedes`).** It puts order into the dependency vocabulary, forces a rebuild of the `dependencies` table for its CHECK constraint, makes every edge reader learn to ignore it, and needs a transitive sort to order a chain.
- **Reuse `blocks` with `unblockAt: queue`.** No migration, but the same transitive sort, and the edge is still read as a dependency by graph `level` and by run-wave's topological order.
- **The parent declares the order** (a list of child ids, or a `sequential` mode). The list is a second copy of the child set beside `parent_id`, two owners of one fact; the mode makes only the first open child startable, which is blocking under another name and breaks [0004](0004-next-means-startable.md).
- **A project-declared default order in config.** It picks among orders that already exist and cannot say "these five in this order". A project default order stays held: reopen when a project's callers pass the same non-default `orderBy` on every call.
- **The `createdAt` tie-break alone.** Kept as the final tie-break before `id`, but it does not close stage 7.4: items made by one `create_tree` can tie on it.
- **A hint on the `next` call** (`after:` or `prefer:` ids). The order lives only in conversation, so a disposable orchestrator loses it; persisting the hint is this record.

## Consequences

- Package P8 in [architecture-breakdown.md](../architecture-breakdown.md) carries it: on the server a migration adding a nullable `work_items.rank`, `WorkItem.rank`, a `rank` field on create, update and `create_tree` children with its `@Doc`, the sort key in `next`, and regenerated goldens and API reference; in the plugin post-plan writes rank from the plan, dependency-manager stops turning a stated sequence into a `blocks` chain and stops calling `unblockAt: queue` "ordering only", and run-wave's frontier comparator and snapshot carry rank.
- `claim` by selector shares the eligibility query, so it inherits the same order.
- Run-wave admits the same items as before; the comparator with rank orders its size cap and dispatch so it matches `next`. Items in one wave still run in parallel: rank is a preference, and strict one-after-another is a real dependency.
- Ralph drains with `orderBy: "oldest"`, so it ignores rank by its own documented choice.
- "Rank" earns a glossary entry when its code lands.

## Reopen when

A project's callers pass the same non-default `orderBy` on every call (the held project default order), or an `agent-observation` or improvement proposal records that rank could not express a stated order among items of equal priority. Ordering across parents reopens under [0004](0004-next-means-startable.md).
