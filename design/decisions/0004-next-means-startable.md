# 0004 `next` returns an item a build seat can start now

**Status:** Accepted | **Date:** 2026-11-23, amended 2026-11-26 | **Source:** Entry Skills Design, call 7; [GLOSSARY.md](../../GLOSSARY.md) "Building item", "Refined". Amendment: Boundary analysis 2026-11-26, pass 10 decisions (plans/boundaries/README.md, not tracked), decision G11, and pass 11 (plans/boundaries/11-next-ordering.md, not tracked), owner decision options A and E.

**Amended 2026-11-26:** "Reopen when" is reworded to a trigger anyone can observe; the hold on ordering and traversal stays. Why: "the owner reopens" gave no one a sign to look for. Also amended 2026-11-26 (pass 11, owner decision): the hold narrows to traversal. An item's `rank` is a sort key within the default order, not a new kind of `next` ([0013](0013-sibling-rank.md)), so eligibility and the one meaning of `next` are unchanged. Why: a stated order among items of equal priority had no home but a `blocks` edge, and rank orders the startable set without holding anything back.

## Decision

By default `items_read next` returns only items a build seat can start now: a leaf, a building item, refined, in queue, unblocked and unclaimed. The opt-outs are `includeParents`, `builds: false` and `refined: false | any`. `claim` by selector shares the same eligibility; claim by item id is unchanged. It ships in 4.x as a fix, phased: the leaf rule and `builds` first, then refined once its storage lands.

## Context

`next` returned containers, parents and items with unfilled queue notes, so ralph, the run-wave frontier and the post-plan route each had to filter after the fact, and each did it differently. The fix belongs in the one service every caller already uses, not in each caller.

## Alternatives rejected

- **Filter in each caller (ralph, run-wave, post-plan).** That is what leaked: three copies of one rule.
- **Several kinds of `next` (graph order, breadth or depth).** Raised later and held by the owner until evidence asks for it. This record covers only eligibility. Rank is not one of these kinds: it orders the items `next` may return and never changes which ones it may return ([0013](0013-sibling-rank.md)).

## Consequences

- Fixes ralph, the run-wave frontier and post-plan route 3 together.
- An item that builds nothing (a question to be ruled, a container) is never handed to a build seat.

## Reopen when

For traversal: an `agent-observation` or an improvement proposal records that the user had to name the next item across parents, because priority, rank and dependency edges could not say "finish this parent first".
