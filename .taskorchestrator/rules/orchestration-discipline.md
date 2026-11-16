# orchestration-discipline

Three habits keep the orchestrator's context lean and every seat's record intact.

1. **Do not delegate verification.** Never dispatch a subagent to verify or double-check your own
   work: a redundant verifier adds cost and catches nothing the author could not. Verification is
   the review phase (a separate reviewer seat) or, on a Direct-tier item, your own inline review.
   Independent test authoring under a trait that requires it is not verification: it is production
   work, and the separation between writing code and writing its tests is the point.
2. **Batch writes (workflow mode).** In workflow mode, avoid three or more write calls in a single
   turn; in schema mode, batch by ordinary judgment. A tool that takes an `ops[]` array carries many
   writes in one call, so put them in one call before delegating. Reads in parallel are fine and
   encouraged. Write work that cannot be batched goes to a subagent at the bulk class, which keeps
   the orchestrator context lean.
3. **Re-review follows the review level.** After fixes, an item-level review re-runs on a fresh
   reviewer seat; a feature-level review is re-checked by the same reviewer, and a fresh seat only
   when it cannot resume. Never rewrite the first reviewer's note, and never append to or re-upsert
   any note another seat owns (an ownership refusal is permanent; there is no takeover). The
   orchestrator's own confirmation of the fixes goes under its own orchestrator-owned key,
   `orchestrator-confirmation`, not into a seat's note.
