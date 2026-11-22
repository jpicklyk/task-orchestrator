# Ranking candidates

Rank by what acting buys, not by how bad the code looks. Two measures decide: how often the place changes (from history) and how much each change costs there today (from reading). A candidate in a hot spot with a concrete, repeated cost outranks a worse-looking one that nobody touches.

## The ratings

**Act now.** Worth a work item immediately. It earns this only when all three hold: the file is in the hot set, which is measured against the whole repository even in a scoped audit ([hotspots](hotspots.md)); the cost is shown, not just asserted, with evidence such as repeated co-changes, duplicate sites or a bug that had to be fixed twice; and the move is small enough to state in a sentence and to finish in one item.

**Plan it.** Worth doing, but it needs a decision or sequencing first. Typical reasons: the move touches a widely used surface and has to be staged; two reasonable designs compete; a documented decision covers the code, its cost is shown, and its owner has to reopen it; the work spans several items.

**Watch.** Do not act yet. The code is a plausible problem but cold, the cost is a guess, or the evidence is thin. Say what would promote it: entering the hot set, a second duplicate appearing, a planned feature landing in it.

## Order within a rating

1. More evidence beats less. A candidate whose cost is counted outranks one whose cost is argued.
2. Recent churn beats lifetime churn.
3. A smaller move beats a larger one when evidence is equal.
4. A candidate that unblocks another (a leaked decision two others depend on) goes before the ones it unblocks.

## Cases that need care

- **Ugly and stable.** Large, old, confusing and unchanged for a year: Watch at most, usually left out of the list. Say in a line that it was seen and skipped.
- **A cold area.** A scoped area whose busiest files are not in the repository's hot set has no Act now candidate. Rate a candidate Plan it when its cost is shown by evidence other than churn (duplicate sites, a bug fixed in two copies), otherwise Watch.
- **Covered by a decision record.** Not a finding unless the cost is shown. When it is, rate it Plan it and put the record on the line. When it is not, leave it out or mention it as skipped on that ground.
- **Vocabulary only.** A rejected word or a missing term is cheap to fix: rate it Act now only when its sites are few and in the hot set, or the file is already in an Act now candidate; otherwise Watch.
- **No history.** A ranking built on size alone is capped at Plan it, and says so.

## Writing a line

A candidate that cannot be written as one proposed work item (a place, a cost, a move) is not ready to rank. Split a broad one or drop it. Never rate everything Act now; if every line gets the top rating the ranking carries no information.
