# 0004 `next` returns an item a build seat can start now

**Status:** Accepted | **Date:** 2026-11-23 | **Source:** Entry Skills Design, call 7; [GLOSSARY.md](../../GLOSSARY.md) "Building item", "Refined"

## Decision

By default `items_read next` returns only items a build seat can start now: a leaf, a building item, refined, in queue, unblocked and unclaimed. The opt-outs are `includeParents`, `builds: false` and `refined: false | any`. `claim` by selector shares the same eligibility; claim by item id is unchanged. It ships in 4.x as a fix, phased: the leaf rule and `builds` first, then refined once its storage lands.

## Context

`next` returned containers, parents and items with unfilled queue notes, so ralph, the run-wave frontier and the post-plan route each had to filter after the fact, and each did it differently. The fix belongs in the one service every caller already uses, not in each caller.

## Alternatives rejected

- **Filter in each caller (ralph, run-wave, post-plan).** That is what leaked: three copies of one rule.
- **Several kinds of `next` (graph order, rank, breadth or depth).** Raised later and held by the owner until evidence asks for it. This record covers only eligibility.

## Consequences

- Fixes ralph, the run-wave frontier and post-plan route 3 together.
- An item that builds nothing (a question to be ruled, a container) is never handed to a build seat.

## Reopen when

The owner reopens ordering and traversal for `next`.
