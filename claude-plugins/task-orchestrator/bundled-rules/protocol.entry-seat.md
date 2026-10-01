# protocol.entry-seat

You are the entry seat for this item's current phase: the seat that starts the phase, or the sole
agent assigned when no seat is named. This rule governs when you may transition the item and what
you owe once you do.

1. **Follow the dispatch first.** If your dispatch names a seat (for example "implementer",
   "reviewer", or another named seat), that seat's own transition rule governs and takes
   precedence over the generic rule below.
2. **Enter the phase once.** As the entry seat, request the phase transition exactly once (the
   orchestration platform's "start" action for this item), then do the work and fill the notes
   this phase requires. Never repeat the transition request and never request the "complete"
   transition yourself -- the orchestrator owns every later transition on this item.
3. **Read the response.** Every transition failure carries a distinct error code. Branch on which
   code you received; never assume success just because no error text was echoed back.
4. **Already in phase.** If the transition fails because the item is already in your phase, do not
   retry it -- ask for the item's current context/guidance instead and proceed with the work using
   that.
5. **A contended shared resource** (a transient "unavailable" failure) is not the same as
   already-in-phase and must not be retried. Stop immediately, report which resource is contended,
   and wait -- do not proceed as though you had entered the phase.
6. **Any other failure** (item not found, invalid trigger, invalid actor, dependency blocked,
   validation failed, invalid transition, apply failed, not the claim holder, rejected by policy)
   means you did not enter the phase. Stop immediately and report the code and message -- do not
   proceed and do not retry the same request.
7. **If you stop with required notes unfilled**, you may be sent back with the missing keys named.
   Fill them, or state plainly what blocks you -- do not fabricate content to satisfy the check.

## claude:

- The transition request is `advance_item(transitions=[{itemId: "<your-item-UUID>", trigger:
  "start"}])`. `errorCode` values map directly to the numbered cases above:
  `gate_blocked`/`previousRole` = already-in-phase (case 4) -> call `get_context(itemId=...)`
  instead of retrying; `resource_unavailable` with `errorKind: "transient"` = case 5, report
  `contendedResources`; `item_not_found`, `invalid_trigger`, `invalid_actor`,
  `dependency_blocked`, `validation_failed`, `invalid_transition`, `apply_failed`,
  `not_claim_holder`, `rejected_by_policy` = case 6.
- Fill notes via `manage_notes(operation="upsert", ...)` -- each response returns the next note's
  guidance.
